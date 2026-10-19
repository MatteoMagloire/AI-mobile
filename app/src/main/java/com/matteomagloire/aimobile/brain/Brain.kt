package com.matteomagloire.aimobile.brain

import android.content.Context
import android.util.Log
import com.matteomagloire.aimobile.chat.ChatMessage
import com.matteomagloire.aimobile.llm.LlamaEngine
import com.matteomagloire.aimobile.llm.SessionCache
import com.matteomagloire.aimobile.model.ModelCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

/**
 * Le Cerveau d'Axo : ses notes, les souvenirs glissés devant les messages, et la prise de notes en
 * tâche de fond après chaque réponse. Elle passe après tout le reste : un message envoyé, une mesure
 * ou l'app mise en arrière-plan l'interrompent, et l'échange est relu plus tard.
 */
class Brain(
    context: Context,
    private val engine: LlamaEngine,
    private val sessions: SessionCache,
    private val scope: CoroutineScope,
) {
    val store = NoteStore(File(context.filesDir, "brain"))
    private val taker = NoteTaker()

    private val _noting = MutableStateFlow(false)

    /** Le modèle prend des notes en ce moment. */
    val noting: StateFlow<Boolean> = _noting.asStateFlow()

    private val _noted = MutableSharedFlow<JournalEntry>(extraBufferCapacity = 8)

    /** Chaque prise de notes qui a ajouté quelque chose (« Noté dans le Cerveau »). */
    val noted: SharedFlow<JournalEntry> = _noted.asSharedFlow()

    // La conversation la plus récente, et un numéro qui change à chaque interruption : une lecture
    // demandée avant une interruption ne démarre pas après elle.
    @Volatile
    private var latest: List<ChatMessage> = emptyList()

    @Volatile
    private var interruptions = 0

    @Volatile
    private var current: Job? = null
    private val wake = Channel<Int>(Channel.CONFLATED)

    // Le modèle de la conversation, s'il est chargé et que c'est le modèle Instruct (pas Réflexion).
    // Pas pendant une mesure, qu'il fausserait. Déclaré avant init : la boucle de lecture peut
    // démarrer aussitôt.
    private val model = object : ModelAccess {
        override suspend fun <T> withModel(block: suspend (TaskRunner) -> T): T? = sessions.use {
            val id = modelId
            if (id == null || id != engine.loadedModelId() || ModelCatalog.byId(id)?.reasoning != false) return@use null
            // Lue avant de vérifier l'annulation : une interruption après cette ligne arrête le moteur.
            val epoch = engine.taskEpoch()
            currentCoroutineContext().ensureActive()
            val runner = object : TaskRunner {
                override suspend fun begin(instructions: TaskInstructions) = engine.beginTask(instructions.messages, epoch)
                override suspend fun step(prompt: String, grammar: String, maxTokens: Int) = engine.taskStep(prompt, grammar, maxTokens)
                override suspend fun end() {
                    engine.endTask()
                }
            }
            _noting.value = true
            try {
                block(runner)
            } finally {
                _noting.value = false
            }
        }
    }

    init {
        scope.launch {
            store.load()
            for (ticket in wake) {
                if (ticket != interruptions) continue
                val round = launch {
                    try {
                        readPending(ticket)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Une erreur ne doit pas arrêter les prises de notes suivantes.
                        Log.w(TAG, "prise de notes en échec", e)
                    }
                }
                current = round
                round.join()
            }
        }
    }

    /** Lit, en tâche de fond, les échanges de [messages] pas encore lus. */
    fun takeNotes(messages: List<ChatMessage>) {
        latest = messages
        wake.trySend(interruptions)
    }

    /** Reprend la lecture laissée en plan (retour sur l'app). */
    fun resume() = wake.trySend(interruptions)

    /** Arrête la prise de notes en cours, au plus vite : elle reprendra après la prochaine réponse. */
    fun interrupt() {
        interruptions++
        current?.cancel()
        engine.abortTask()
    }

    /** Efface tout le Cerveau (Réglages) : notes, journal et faits oubliés. */
    suspend fun clear() {
        interrupt()
        // Une prise de notes qui finit d'écrire ne doit pas revenir après l'effacement.
        current?.join()
        store.clear()
    }

    private suspend fun readPending(ticket: Int) {
        while (ticket == interruptions) {
            val next = NoteTaker.pending(latest, store.processedUntil).firstOrNull() ?: return
            when (val reading = taker.read(next, store, model)) {
                Reading.Later -> return
                is Reading.Done -> reading.entry?.takeIf { it.outcome == NoteOutcome.NOTED }?.let { _noted.tryEmit(it) }
            }
        }
    }

    /** Ce que le modèle lit avant le message, et ce qu'un « oublie… » a retiré (pour « Annuler »). */
    data class Prepared(val memory: String, val forgetting: Forgetting? = null)

    /**
     * Prépare un message avant qu'il parte (rapide, sans le modèle) : fait un « oublie… », et choisit
     * les souvenirs à glisser devant. [recentMemories] : souvenirs déjà glissés dans les derniers
     * messages ; [startOfSession] : premier message depuis longtemps, Axo relit ce qu'il sait de lui ;
     * [dormantDays] : délai de mise en veille, Axo puise d'abord dans les notes actives.
     */
    suspend fun prepare(
        message: String,
        time: Long,
        recentMemories: List<String>,
        startOfSession: Boolean,
        dormantDays: Int = DEFAULT_DORMANT_DAYS,
    ): Prepared {
        store.load()
        val command = BrainCommands.parse(message)
        if (command is BrainCommand.Forget) {
            val forgetting = store.forget(command.what, time, NoteTaker.excerpt(message))
            return Prepared(Recall.block(emptyList(), forgotten = forgetting.texts, nothingToForget = forgetting.isEmpty), forgetting)
        }
        val given = Recall.alreadyGiven(recentMemories)
        val notes = store.notes.value
        val today = LocalDate.now()
        val dormant = notes.filter { it.isDormant(today, dormantDays) }.map { it.id }.toSet()
        val found = Recall.search(notes, message, limit = 3, exclude = given, dormant = dormant)
        // Axo lit les faits sans les crochets des liens écrits à la main.
        val hits = found.map { NoteLinks.plain(it.fact.text) }
        val aboutMe = if (startOfSession || Recall.asksAboutMe(message)) {
            Recall.aboutMe(notes, 4, given + hits).map { NoteLinks.plain(it.text) }
        } else {
            emptyList()
        }
        // Peu de lignes : chaque mot lu retarde le premier mot de la réponse.
        var chars = 0
        val facts = (aboutMe + hits).distinct().takeWhile { chars += it.length; chars <= MAX_MEMORY_CHARS }
        // Les notes dont Axo se sert restent actives, ou se réveillent ; sans retarder la réponse.
        val used = found.filter { !it.note.isMe && NoteLinks.plain(it.fact.text) in facts }.map { it.note.id }.distinct()
        if (used.isNotEmpty()) scope.launch { store.markUsed(used) }
        return Prepared(Recall.block(facts, remember = command is BrainCommand.Remember))
    }

    companion object {
        private const val TAG = "Brain"
        const val MAX_MEMORY_CHARS = 420

        /** Au-delà de ce silence, le prochain message commence une nouvelle séance. */
        const val SESSION_GAP_MS = 3 * 60 * 60 * 1000L

        /** Une note ni ouverte ni utilisée depuis ce nombre de jours passe en veille (réglable). */
        const val DEFAULT_DORMANT_DAYS = 30

        /** Les délais proposés dans les réglages. */
        val DORMANT_CHOICES = listOf(1, 7, 30, 90)
    }
}
