package com.matteomagloire.aimobile.brain

import com.matteomagloire.aimobile.chat.ChatMessage
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Le modèle, vu par la prise de notes : un travail de fond en plusieurs étapes, retiré à la fin. */
interface TaskRunner {
    /** False si le travail ne peut pas commencer. */
    suspend fun begin(reserve: Int): Boolean

    /** Réponse à [prompt], au format de la grammaire GBNF [grammar]. Null si l'étape a échoué ou a été interrompue. */
    suspend fun step(prompt: String, grammar: String, maxTokens: Int): String?

    /** Retire tout ce que le travail a ajouté à la conversation. */
    suspend fun end()
}

/** Accès au modèle pour un travail de fond. */
interface ModelAccess {
    /** Lance [block] avec le modèle ; null s'il n'est pas disponible maintenant (à refaire plus tard). */
    suspend fun <T> withModel(block: suspend (TaskRunner) -> T): T?
}

/** Un échange à lire : le message de l'utilisateur, et la réponse d'Axo juste avant (sa question éventuelle). */
data class Exchange(val time: Long, val message: String, val previousReply: String = "")

/** Un fait à noter, la note où le ranger, et ce qu'il change dans cette note. */
data class PlannedFact(
    val text: String,
    /** Note existante (ou « Moi »), null pour une nouvelle note. */
    val noteId: String?,
    val noteTitle: String,
    /** Étiquette d'une nouvelle note. */
    val tag: String? = null,
    /** Fait devenu faux (la situation a changé) : il passe dans « Avant ». */
    val replaces: String? = null,
    /** Faits que le nouveau dit déjà, en plus précis : retirés. */
    val supersedes: List<String> = emptyList(),
)

/** Ce que le modèle a tiré d'un échange. */
sealed interface Thought {
    /** Rien de durable. */
    data object Empty : Thought

    /** Des faits, mais déjà connus, ou oubliés à la demande de l'utilisateur. */
    data class Known(val facts: List<String>) : Thought

    data class Facts(val facts: List<PlannedFact>, val known: List<String>) : Thought

    data class Failed(val step: String) : Thought
}

sealed interface Reading {
    /** Échange lu (ou volontairement ignoré). */
    data class Done(val entry: JournalEntry?) : Reading

    /** Le modèle n'était pas disponible : l'échange sera lu plus tard. */
    data object Later : Reading
}

/**
 * Prise de notes : après un échange, le modèle Instruct relève ce qui vaut d'être retenu, en petites
 * étapes au format imposé (grammaire GBNF) pour qu'un petit modèle s'y tienne. Le code décide des
 * doublons ; le modèle ne tranche que les cas douteux (un fait devenu faux).
 *
 * 1. Les faits : « aucun », ou une à trois lignes « - Matteo … ».
 * 2. La note de chacun : « Moi », une note existante proche, ou une nouvelle note.
 * 3. Seulement si la note contient un fait ressemblant : le nouveau le rend-il faux ?
 */
class NoteTaker(private val name: String = USER_NAME) {

    /** Lit un échange et met le Cerveau à jour. */
    suspend fun read(exchange: Exchange, store: NoteStore, model: ModelAccess): Reading {
        val start = System.nanoTime()
        val command = BrainCommands.parse(exchange.message)
        // « Oublie… » : fait au moment de l'envoi, il n'y a rien à noter.
        if (command is BrainCommand.Forget) {
            store.markRead(exchange, null)
            return Reading.Done(null)
        }
        val forced = command is BrainCommand.Remember
        if (!forced && !BrainCommands.worthReading(exchange.message, exchange.previousReply)) {
            val entry = JournalEntry(System.currentTimeMillis(), exchange.time, excerpt(exchange.message), NoteOutcome.IGNORED)
            store.markRead(exchange, entry)
            return Reading.Done(entry)
        }
        val text = ((command as? BrainCommand.Remember)?.what ?: exchange.message).take(MAX_MESSAGE_CHARS)
        val thought = model.withModel { runner -> think(runner, text, forced, store.notes.value, store.forgotten()) }
            ?: return Reading.Later
        // Même interrompu à la fin, ce qui a été compris est noté.
        val entry = withContext(NonCancellable) {
            store.record(exchange, excerpt(exchange.message), thought, (System.nanoTime() - start) / 1_000_000)
        }
        return Reading.Done(entry)
    }

    /** Les étapes du modèle. Interrompu : CancellationException, rien n'est noté. */
    suspend fun think(runner: TaskRunner, message: String, forced: Boolean, notes: List<Note>, forgotten: List<String>): Thought {
        if (!runner.begin(RESERVE_TOKENS)) return Thought.Failed("début")
        try {
            val raw = runner.step(extractPrompt(message, forced), extractGrammar(forced), EXTRACT_TOKENS)
                ?: return failed("lecture")
            val all = dedupe(parseFacts(raw))
            if (all.isEmpty()) return Thought.Empty
            val known = all.filter { isKnown(it, notes, forgotten) }
            val fresh = all - known.toSet()
            if (fresh.isEmpty()) return Thought.Known(known)

            val candidates = candidates(fresh, notes)
            val placementRaw = runner.step(
                placePrompt(candidates),
                placeGrammar(fresh.size, candidates),
                PLACE_TOKENS_PER_FACT * fresh.size + 8,
            )
            if (placementRaw == null) currentCoroutineContext().ensureActive()
            val placements = placementRaw?.let { parsePlacement(it, fresh.size, candidates, notes) }

            val planned = ArrayList<PlannedFact>(fresh.size)
            for ((i, fact) in fresh.withIndex()) {
                val target = placements?.getOrNull(i) ?: Target(Note.ME_ID, Note.ME_TITLE, null)
                val note = target.id?.let { id -> notes.firstOrNull { it.id == id } }
                val words = TextMatch.words(fact)
                val current = note?.facts.orEmpty()
                val supersedes = current.filter { coverage(TextMatch.words(it.text), words) >= SUPERSEDE }.map { it.text }
                val similar = current
                    .filter { it.text !in supersedes && TextMatch.similarity(words, TextMatch.words(it.text)) >= SIMILAR }
                    .sortedByDescending { TextMatch.similarity(words, TextMatch.words(it.text)) }
                    .take(MAX_SIMILAR)
                var replaces: String? = null
                if (similar.isNotEmpty()) {
                    val answer = runner.step(replacePrompt(fact, note!!.title, similar), replaceGrammar(similar.size), 4)
                    if (answer == null) currentCoroutineContext().ensureActive()
                    replaces = answer?.trim()?.toIntOrNull()?.let { similar.getOrNull(it - 1)?.text }
                }
                planned += PlannedFact(fact, target.id, target.title, target.tag, replaces, supersedes)
            }
            return Thought.Facts(planned, known)
        } finally {
            withContext(NonCancellable) { runner.end() }
        }
    }

    private suspend fun failed(step: String): Thought {
        currentCoroutineContext().ensureActive()
        return Thought.Failed(step)
    }

    // --- Étape 1 : les faits ---

    fun extractPrompt(message: String, forced: Boolean): String = if (forced) {
        "[Mémoire] Tâche invisible pour $name, ta réponse ne lui sera pas montrée.\n" +
            "Il t'a demandé de retenir ceci : « $message »\n" +
            "Écris-le en une à trois lignes courtes qui commencent par « - $name », fidèles à ses mots. N'invente rien."
    } else {
        "[Mémoire] Tâche invisible pour $name, ta réponse ne lui sera pas montrée.\n" +
            "Son dernier message : « $message »\n" +
            "Relève ce qu'il y révèle de durable sur lui et sa vie : identité, proches, animaux, travail, études, lieux, " +
            "goûts, projets, santé, habitudes.\n" +
            "Une ligne courte par fait, qui commence par « - $name », fidèle à ses mots. N'invente rien. " +
            "Ignore ce qui ne dure pas (humeur du moment, repas, programme du jour), les questions, et ce que toi, Axo, as dit.\n" +
            "S'il n'y a rien de durable : aucun"
    }

    fun extractGrammar(forced: Boolean): String {
        val fact = "fact ::= \"- ${gbnf(name)} \" [^\\n]+ \"\\n\"\n"
        return if (forced) "root ::= fact | fact fact | fact fact fact\n$fact"
        else "root ::= \"aucun\" | fact | fact fact | fact fact fact\n$fact"
    }

    /** Les lignes complètes « - Matteo … », remises au propre. */
    fun parseFacts(raw: String): List<String> =
        raw.split('\n').dropLast(1)
            .map { it.trim().removePrefix("-").trim() }
            .filter { it.startsWith("$name ") }
            .map(::tidy)
            .filter { TextMatch.words(it).isNotEmpty() }
            .distinct()

    private fun tidy(fact: String): String {
        var t = fact.replace(Regex("\\s+"), " ").trim().trim('"', '«', '»', ' ')
        if (t.length > MAX_FACT_CHARS) t = t.take(MAX_FACT_CHARS).substringBeforeLast(' ').trimEnd(',', ';', ' ') + "…"
        if (t.last() !in ".!?…") t += "."
        return t
    }

    /** Deux faits presque pareils dans la même réponse : un seul. */
    private fun dedupe(facts: List<String>): List<String> {
        val out = ArrayList<String>()
        for (f in facts) {
            val w = TextMatch.words(f)
            if (out.none { coverage(w, TextMatch.words(it)) >= KNOWN }) out += f
        }
        return out
    }

    /** Déjà noté quelque part (et pas en moins précis), ou oublié à la demande de l'utilisateur. */
    private fun isKnown(fact: String, notes: List<Note>, forgotten: List<String>): Boolean {
        val w = TextMatch.words(fact)
        return notes.any { n ->
            n.facts.any {
                val old = TextMatch.words(it.text)
                coverage(w, old) >= KNOWN && !refines(w, old)
            }
        } || forgotten.any { coverage(w, TextMatch.words(it)) >= FORGOTTEN }
    }

    /** Le nouveau fait dit tout ce que disait l'ancien, et plus. */
    private fun refines(new: Set<String>, old: Set<String>): Boolean = coverage(old, new) >= SUPERSEDE && new.any { it !in old }

    // --- Étape 2 : la note de chaque fait ---

    /** Où ranger un fait : une note existante ([id]), ou une nouvelle note (id null) avec son étiquette. */
    data class Target(val id: String?, val title: String, val tag: String?)

    /** « Moi », puis les notes qui ressemblent le plus aux faits. */
    fun candidates(facts: List<String>, notes: List<Note>): List<Target> {
        val words = facts.flatMap { TextMatch.words(it) }.toSet()
        val others = notes.filter { !it.isMe }
            .map { n -> n to TextMatch.similarity(words, TextMatch.words(n.title) + n.facts.flatMap { TextMatch.words(it.text) }) }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(MAX_CANDIDATES)
            .map { Target(it.first.id, it.first.title, null) }
        return listOf(Target(Note.ME_ID, Note.ME_TITLE, null)) + others
    }

    fun placePrompt(candidates: List<Target>): String {
        val others = candidates.filter { it.id != Note.ME_ID }
        return "Range chacun de ces faits dans une note de ton Cerveau, dans l'ordre.\n" +
            "« ${Note.ME_TITLE} » : $name lui-même (âge, ville, métier, études, caractère, goûts, santé).\n" +
            (if (others.isEmpty()) "Pas encore d'autre note.\n" else "Autres notes : ${others.joinToString(", ") { "« ${it.title} »" }}.\n") +
            "Un proche, un animal, un projet ou un sujet précis a sa propre note : reprends-la si elle existe, " +
            "sinon crée-la, avec un titre court (son nom, ou un à trois mots)."
    }

    fun placeGrammar(count: Int, candidates: List<Target>): String {
        val root = (1..count).joinToString(" ") { "\"$it. \" choice \"\\n\"" }
        val choices = candidates.joinToString(" | ") { "\"${gbnf(it.title)}\"" }
        val tags = TAGS.joinToString(" | ") { "\"${gbnf(it)}\"" }
        return "root ::= $root\n" +
            "choice ::= $choices | \"$NEW_NOTE\" title \" (\" tag \")\"\n" +
            "title ::= [^\\n()]+\n" +
            "tag ::= $tags\n"
    }

    /** Une cible par fait (null si la ligne est illisible). */
    fun parsePlacement(raw: String, count: Int, candidates: List<Target>, notes: List<Note>): List<Target?> {
        val lines = raw.split('\n')
        return (1..count).map { i ->
            val line = lines.firstOrNull { it.startsWith("$i. ") }?.removePrefix("$i. ")?.trim() ?: return@map null
            candidates.firstOrNull { it.title == line }
                ?: NEW_LINE.matchEntire(line)?.let { m -> newTarget(m.groupValues[1], m.groupValues[2], notes) }
        }
    }

    private fun newTarget(rawTitle: String, tag: String, notes: List<Note>): Target {
        val title = cleanTitle(rawTitle)
        val folded = TextMatch.fold(title)
        if (TextMatch.words(title).isEmpty() || folded == TextMatch.fold(Note.ME_TITLE) || folded == TextMatch.fold(name)) {
            return Target(Note.ME_ID, Note.ME_TITLE, null)
        }
        // Une note de ce nom existe déjà : on la complète.
        notes.firstOrNull { TextMatch.fold(it.title) == folded }?.let { return Target(it.id, it.title, null) }
        return Target(null, title, tag.takeIf { it in TAGS })
    }

    private fun cleanTitle(raw: String): String {
        var t = raw.replace(Regex("\\s+"), " ").trim().trim('"', '«', '»', '.', ',', ':', ';', ' ')
        if (t.length > MAX_TITLE_CHARS) t = t.take(MAX_TITLE_CHARS).substringBeforeLast(' ')
        return t.replaceFirstChar { it.titlecase() }
    }

    // --- Étape 3 : un fait devenu faux ---

    fun replacePrompt(fact: String, noteTitle: String, similar: List<Fact>): String =
        "Nouveau fait : « $fact »\n" +
            "Déjà noté dans « $noteTitle » :\n" +
            similar.withIndex().joinToString("\n") { (i, f) -> "${i + 1}. ${f.text}" } + "\n" +
            "Le nouveau fait rend-il l'un d'eux faux, parce que la situation a changé ? Réponds par son numéro, sinon : aucun"

    fun replaceGrammar(count: Int): String = "root ::= \"aucun\"" + (1..count).joinToString("") { " | \"$it\"" } + "\n"

    companion object {
        /** Tokens réservés pour toutes les étapes d'un échange (consignes et réponses). */
        const val RESERVE_TOKENS = 1024
        const val EXTRACT_TOKENS = 120
        const val PLACE_TOKENS_PER_FACT = 16
        const val MAX_MESSAGE_CHARS = 600
        const val MAX_FACT_CHARS = 180
        const val MAX_TITLE_CHARS = 40
        const val MAX_CANDIDATES = 5
        const val MAX_SIMILAR = 3
        private const val NEW_NOTE = "nouvelle note : "
        private val NEW_LINE = Regex("^nouvelle note : (.+) \\(([^()]+)\\)$")

        /** Part d'un nouveau fait déjà dite par un fait noté : au-delà, rien de neuf. */
        const val KNOWN = 0.65

        /** Un fait oublié n'est bloqué que s'il revient presque tel quel. */
        const val FORGOTTEN = 0.8

        /** Un fait noté entièrement contenu dans le nouveau : le nouveau le remplace. */
        const val SUPERSEDE = 0.8

        /** Assez proches pour demander au modèle si le nouveau rend l'ancien faux. */
        const val SIMILAR = 0.25

        val TAGS = listOf("personne", "animal", "lieu", "travail", "études", "projet", "loisir", "santé", "autre")

        /** Part des mots de [a] présents dans [b]. */
        fun coverage(a: Set<String>, b: Set<String>): Double = if (a.isEmpty()) 0.0 else a.count { it in b }.toDouble() / a.size

        /** Texte sûr dans une chaîne GBNF. */
        fun gbnf(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

        fun excerpt(message: String): String = message.replace(Regex("\\s+"), " ").trim().let { if (it.length > 160) it.take(160) + "…" else it }

        /**
         * Les échanges pas encore lus : messages de l'utilisateur envoyés après [processedUntil] et déjà
         * suivis d'une réponse. Les plus anciens sont laissés s'il y en a trop.
         */
        fun pending(messages: List<ChatMessage>, processedUntil: Long, max: Int = 5): List<Exchange> {
            val out = ArrayList<Exchange>()
            var previousReply = ""
            messages.forEachIndexed { i, m ->
                if (!m.fromUser) {
                    previousReply = m.text
                } else if (m.time > processedUntil && i + 1 < messages.size && !messages[i + 1].fromUser) {
                    out += Exchange(m.time, m.text, previousReply)
                }
            }
            return out.takeLast(max)
        }
    }
}
