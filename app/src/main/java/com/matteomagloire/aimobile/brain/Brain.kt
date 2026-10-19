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
import kotlinx.coroutines.channels.BufferOverflow
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

    private val _events = MutableSharedFlow<BrainEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Ce qu'Axo fait dans sa mémoire, geste par geste, pour le montrer en direct. */
    val events: SharedFlow<BrainEvent> = _events.asSharedFlow()

    /** La conversation signale où en est la réponse ([BrainEvent.Answering], [BrainEvent.End] de la réponse). */
    fun signal(event: BrainEvent) {
        _events.tryEmit(event)
    }

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
            when (val reading = taker.read(next, store, model) { _events.tryEmit(it) }) {
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
        // Les faits proches du message, puis ceux des notes qu'ils nomment : « mon frère » mène à Hugo.
        val found = Recall.search(notes, message, limit = CANDIDATES, exclude = given, dormant = dormant)
        val top = found.take(MAX_HITS)
        // Axo lit les faits sans les crochets des liens écrits à la main.
        val hits = top.map { NoteLinks.plain(it.fact.text) }
        val hops = Recall.follow(notes, top, message, exclude = given + hits)
        val aboutMe = Recall.aboutMe(message, notes, always = startOfSession, limit = 4, exclude = given + hits)
        // Peu de lignes : chaque mot lu retarde le premier mot de la réponse. Les liens suivis passent en dernier.
        var chars = 0
        val facts = (aboutMe + hits + hops.map { NoteLinks.plain(it.fact.text) }).distinct()
            .takeWhile { chars += it.length; chars <= MAX_MEMORY_CHARS }
        val kept = top.filter { NoteLinks.plain(it.fact.text) in facts }.map { it.note.id }.distinct()
        val followed = hops.filter { NoteLinks.plain(it.fact.text) in facts }
        show(notes, found, kept, followed, readsMe = aboutMe.any { it in facts }, dormant)
        // Les notes dont Axo se sert restent actives, ou se réveillent ; sans retarder la réponse.
        val used = (kept + followed.map { it.note.id }).filter { id -> notes.none { it.id == id && it.isMe } }.distinct()
        if (used.isNotEmpty()) scope.launch { store.markUsed(used) }
        return Prepared(Recall.block(facts, remember = command is BrainCommand.Remember))
    }

    /** Montre à l'écran ce qu'Axo vient de faire avant de répondre : chercher, lire, suivre un lien, réveiller. */
    private fun show(
        notes: List<Note>,
        found: List<Recall.Hit>,
        kept: List<String>,
        followed: List<Recall.Hop>,
        readsMe: Boolean,
        dormant: Set<String>,
    ) {
        _events.tryEmit(BrainEvent.Begin(BrainEvent.Work.ANSWER))
        if (found.isNotEmpty()) _events.tryEmit(BrainEvent.Searched(found.map { it.note.id }.distinct(), kept))
        fun read(id: String) {
            _events.tryEmit(BrainEvent.Read(id))
            if (id in dormant) _events.tryEmit(BrainEvent.Woke(id))
        }
        notes.firstOrNull { it.isMe }?.takeIf { readsMe && it.id !in kept }?.let { read(it.id) }
        kept.forEach(::read)
        for (hop in followed) {
            _events.tryEmit(BrainEvent.Followed(hop.from.note.id, hop.note.id))
            read(hop.note.id)
        }
    }

    companion object {
        private const val TAG = "Brain"
        const val MAX_MEMORY_CHARS = 420

        /** Faits glissés devant un message, au plus (sans les liens suivis ni « Moi »). */
        const val MAX_HITS = 3

        /** Faits trouvés montrés à l'écran, parmi lesquels Axo garde les meilleurs. */
        const val CANDIDATES = 8

        /** Au-delà de ce silence, le prochain message commence une nouvelle séance. */
        const val SESSION_GAP_MS = 3 * 60 * 60 * 1000L

        /** Une note ni ouverte ni utilisée depuis ce nombre de jours passe en veille (réglable). */
        const val DEFAULT_DORMANT_DAYS = 30

        /** Les délais proposés dans les réglages. */
        val DORMANT_CHOICES = listOf(1, 7, 30, 90)
    }
}
