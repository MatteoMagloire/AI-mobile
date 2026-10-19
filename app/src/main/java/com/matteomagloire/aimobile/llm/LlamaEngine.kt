package com.matteomagloire.aimobile.llm

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Moteur LLM local (llama.cpp). Le modèle reste chargé entre deux questions ;
 * un seul modèle à la fois pour tenir dans le budget RAM du S21 (~3 Go).
 */
class LlamaEngine {

    sealed interface State {
        data object Empty : State
        data class Loading(val modelId: String) : State
        data class Ready(val modelId: String, val loadMs: Long) : State
        data class Failed(val message: String) : State
    }

    /** Mesures d'une réponse : lecture du message puis écriture de la réponse. */
    data class Stats(val promptTokens: Int, val promptMs: Double, val genTokens: Int, val genMs: Double) {
        val promptTokensPerSecond: Double get() = if (promptMs > 0) promptTokens * 1000.0 / promptMs else 0.0
        val genTokensPerSecond: Double get() = if (genMs > 0) genTokens * 1000.0 / genMs else 0.0
    }

    /** Contexte occupé : par le prompt système, puis au total (sur [DEFAULT_CONTEXT]). */
    data class Usage(val systemPromptTokens: Int, val usedTokens: Int)

    data class SavedSession(val bytes: Long, val systemPromptTokens: Int)

    /**
     * Mesures d'un travail de fond : consignes lues (seulement la première fois), messages lus,
     * réponses écrites, et nombre d'étapes.
     */
    data class TaskStats(
        val prefixTokens: Int,
        val prefixMs: Double,
        val promptTokens: Int,
        val promptMs: Double,
        val genTokens: Int,
        val genMs: Double,
        val steps: Int,
    )

    // llama.cpp n'est pas thread-safe : les appels natifs passent un par un, sur un seul thread,
    // et le verrou empêche une opération de s'intercaler au milieu d'une réponse.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val llamaThread: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val mutex = Mutex()

    private val _state = MutableStateFlow<State>(State.Empty)
    val state: StateFlow<State> = _state.asStateFlow()

    // Lu sans le verrou par [abortTask].
    @Volatile
    private var initialized = false

    /** Cœurs utilisés pour les calculs : moins de cœurs, moins de chauffe. */
    @Volatile
    var threads: Int = DEFAULT_THREADS
        private set

    /** Prompt système dont le KV est en tête du contexte (cache en mémoire), null sinon. */
    private var systemPromptInMemory: String? = null

    private suspend fun <T> locked(block: () -> T): T = mutex.withLock { withContext(llamaThread) { block() } }

    private fun checkReady() = check(_state.value is State.Ready) { "Aucun modèle chargé" }

    fun loadedModelId(): String? = (_state.value as? State.Ready)?.modelId

    suspend fun load(
        modelId: String,
        file: File,
        contextSize: Int = DEFAULT_CONTEXT,
    ) = locked {
        val current = _state.value
        if (current is State.Ready && current.modelId == modelId) return@locked
        _state.value = State.Loading(modelId)
        if (!initialized) {
            LlamaNative.init()
            initialized = true
        }
        if (current is State.Ready) LlamaNative.unload()
        systemPromptInMemory = null
        val start = System.nanoTime()
        val code = LlamaNative.load(file.absolutePath, contextSize, threads)
        _state.value = if (code == 0) State.Ready(modelId, (System.nanoTime() - start) / 1_000_000)
        else State.Failed("Impossible de charger le modèle (code $code)")
    }

    /** Change le nombre de cœurs, pour ce modèle et les suivants. */
    suspend fun setThreads(count: Int) = locked {
        threads = count.coerceIn(MIN_THREADS, MAX_THREADS)
        if (_state.value is State.Ready) LlamaNative.setThreads(threads)
    }

    /** [seed] négatif : graine aléatoire ; sinon réponses reproductibles. */
    suspend fun configureSampler(temperature: Float, topP: Float, seed: Int = -1) = locked {
        if (_state.value is State.Ready) LlamaNative.configureSampler(temperature, topP, seed)
    }

    /** Nouvelle conversation. Si ce prompt système est déjà en tête du contexte, il n'est pas relu. */
    suspend fun startConversation(systemPrompt: String) = locked {
        checkReady()
        systemPromptInMemory = null
        val code = LlamaNative.startConversation(systemPrompt)
        check(code == 0) { "Échec du prompt système (code $code)" }
        systemPromptInMemory = systemPrompt
    }

    fun hasSystemPromptInMemory(systemPrompt: String) = systemPromptInMemory == systemPrompt

    /** Relit des messages déjà échangés (du plus ancien au plus récent, `true` = utilisateur). */
    suspend fun replay(history: List<Pair<Boolean, String>>) = locked {
        checkReady()
        for ((fromUser, text) in history) {
            val c = LlamaNative.addHistoryMessage(fromUser, text)
            check(c == 0) { "Échec de la reprise de la conversation (code $c)" }
        }
    }

    /** Écrit le KV de la conversation dans [file]. Null si rien n'a pu être écrit. */
    suspend fun saveSession(file: File): SavedSession? = locked {
        if (_state.value !is State.Ready) return@locked null
        val bytes = LlamaNative.saveSession(file.absolutePath)
        if (bytes <= 0) null else SavedSession(bytes, LlamaNative.contextUsage()[0])
    }

    /**
     * Recharge un KV écrit par [saveSession]. [history] : messages couverts par ce KV, donnés au
     * template de chat sans être relus. En cas d'échec le contexte est vide.
     */
    suspend fun restoreSession(
        file: File,
        expectedBytes: Long,
        systemPrompt: String,
        systemPromptTokens: Int,
        history: List<Pair<Boolean, String>>,
    ): Boolean = locked {
        if (_state.value !is State.Ready) return@locked false
        val code = LlamaNative.restoreSession(
            file.absolutePath,
            expectedBytes,
            systemPrompt,
            systemPromptTokens,
            BooleanArray(history.size) { history[it].first },
            Array(history.size) { history[it].second },
        )
        systemPromptInMemory = if (code == 0) systemPrompt else null
        code == 0
    }

    suspend fun usage(): Usage = locked {
        val u = LlamaNative.contextUsage()
        Usage(u[0], u[1])
    }

    /** Mesures de la dernière réponse générée. */
    suspend fun lastStats(): Stats = locked {
        val s = LlamaNative.lastStats()
        Stats(s[0].toInt(), s[1] / 1000.0, s[2].toInt(), s[3] / 1000.0)
    }

    suspend fun modelSizeBytes(): Long = locked { if (_state.value is State.Ready) LlamaNative.modelSize() else 0L }

    /** Envoie le message et émet la réponse morceau par morceau (streaming). */
    fun generate(message: String, maxTokens: Int): Flow<String> = flow {
        mutex.withLock {
            checkReady()
            val code = LlamaNative.sendUserMessage(message, maxTokens)
            check(code == 0) { "Échec de l'envoi du message (code $code)" }
            try {
                while (currentCoroutineContext().isActive) {
                    val piece = LlamaNative.nextToken() ?: break
                    if (piece.isNotEmpty()) emit(piece)
                }
            } finally {
                // Si l'utilisateur arrête la génération, on garde ce qui a été écrit dans l'historique.
                LlamaNative.stopGeneration()
            }
        }
    }.flowOn(llamaThread)

    /**
     * Travail de fond (prise de notes), dans un contexte à part : la conversation n'est jamais
     * touchée. [beginTask] avec ses consignes, des étapes ([taskStep]), puis [endTask], qui retire tout
     * ce que le travail a ajouté après les consignes. Les consignes restent en mémoire : un travail
     * suivant avec les mêmes ne les relit pas.
     *
     * [instructions] : le prompt système, puis des exemples, message et réponse tour à tour.
     * [epoch] : [taskEpoch] lu avant de décider de commencer ; si [abortTask] a été appelé depuis, le
     * travail ne commence pas. False s'il ne peut pas commencer (ou a été interrompu en lisant ses
     * consignes : la lecture reprendra là où elle s'est arrêtée).
     */
    suspend fun beginTask(instructions: List<String>, epoch: Int): Boolean = locked {
        if (_state.value !is State.Ready) return@locked false
        val code = LlamaNative.beginTask(instructions.toTypedArray(), epoch)
        if (code != 0) Log.w(TAG, "travail de fond pas commencé (code $code)")
        code == 0
    }

    /** Réponse la plus probable à [prompt], au format imposé par la grammaire GBNF [grammar]. Null si l'étape échoue. */
    suspend fun taskStep(prompt: String, grammar: String, maxTokens: Int): String? = locked {
        if (_state.value !is State.Ready) return@locked null
        val text = LlamaNative.taskStep(prompt, grammar, maxTokens)
        if (text == null) Log.w(TAG, "étape du travail de fond arrêtée (code ${LlamaNative.taskError()})")
        text
    }

    /** False si le contexte de travail a dû être vidé : les consignes seront relues au prochain travail. */
    suspend fun endTask(): Boolean = locked { _state.value !is State.Ready || LlamaNative.endTask() == 0 }

    /** Mesures du dernier travail de fond. */
    suspend fun taskStats(): TaskStats = locked {
        if (_state.value !is State.Ready) return@locked TaskStats(0, 0.0, 0, 0.0, 0, 0.0, 0)
        val s = LlamaNative.taskStats()
        TaskStats(s[0].toInt(), s[1] / 1000.0, s[2].toInt(), s[3] / 1000.0, s[4].toInt(), s[5] / 1000.0, s[6].toInt())
    }

    /** Époque des interruptions, à lire avant de commencer un travail de fond (voir [beginTask]). */
    fun taskEpoch(): Int = if (initialized) LlamaNative.abortEpoch() else 0

    /** Arrête au plus vite le travail de fond en cours, ou celui qui allait commencer : appelable à tout moment. */
    fun abortTask() {
        if (initialized) LlamaNative.abortTask()
    }

    suspend fun unload() = locked {
        if (_state.value is State.Ready) LlamaNative.unload()
        systemPromptInMemory = null
        _state.value = State.Empty
    }

    companion object {
        private const val TAG = "LlamaEngine"

        /** Contexte limité à 4096 tokens pour économiser la RAM. */
        const val DEFAULT_CONTEXT = 4096

        /** 3 threads : presque aussi rapide que 4 sur le S21, sans chauffer (Test de vitesse). */
        const val DEFAULT_THREADS = 3
        const val MIN_THREADS = 2
        const val MAX_THREADS = 4

        /** Version de llama.cpp compilée dans l'app. */
        val buildRef: String by lazy { LlamaNative.buildRef() }
    }
}
