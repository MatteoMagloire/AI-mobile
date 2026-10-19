package com.matteomagloire.aimobile.brain

import com.matteomagloire.aimobile.chat.ChatMessage
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Le modèle, vu par la prise de notes : un travail de fond en plusieurs étapes, retiré à la fin. */
interface TaskRunner {
    /** Commence un travail avec ses [instructions] (gardées d'un travail à l'autre). False s'il ne peut pas commencer. */
    suspend fun begin(instructions: TaskInstructions): Boolean

    /** Réponse à [prompt], au format de la grammaire GBNF [grammar]. Null si l'étape a échoué ou a été interrompue. */
    suspend fun step(prompt: String, grammar: String, maxTokens: Int): String?

    /** Retire tout ce que le travail a ajouté après ses consignes. */
    suspend fun end()
}

/**
 * Consignes d'un travail de fond : un prompt système et des exemples (message, réponse attendue).
 * Le moteur les garde en mémoire : elles ne sont lues qu'une fois par chargement du modèle.
 */
data class TaskInstructions(val system: String, val examples: List<Pair<String, String>>) {
    /** Pour le moteur : le prompt système, puis chaque message d'exemple et sa réponse. */
    val messages: List<String> get() = listOf(system) + examples.flatMap { listOf(it.first, it.second) }
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
 * Prise de notes : après un échange, le modèle Instruct relève ce qui vaut d'être retenu. Il travaille
 * à part de la conversation et ne voit que le dernier message (avec la question d'Axo juste avant,
 * pour une réponse courte) : il ne peut pas reprendre ce qui a été dit plus tôt. Ses consignes et des
 * exemples restent en mémoire d'une prise de notes à l'autre. Le format est imposé (grammaire GBNF)
 * pour qu'un petit modèle s'y tienne, et le code écarte ce que le message ne dit pas, ce qui ne dure
 * pas et les doublons ; le modèle ne tranche que les cas douteux (un fait devenu faux).
 *
 * 1. Les faits : « aucun », ou une à trois lignes « Note : fait. », la note étant « Moi » ou celle
 *    d'un proche, d'un animal ou d'un sujet à part, avec son type.
 * 2. Seulement si la note contient un fait ressemblant : lequel n'est plus vrai maintenant ?
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
        val thought = model.withModel { runner ->
            think(runner, text, forced, store.notes.value, store.forgotten(), exchange.previousReply)
        } ?: return Reading.Later
        // Même interrompu à la fin, ce qui a été compris est noté.
        val entry = withContext(NonCancellable) {
            store.record(exchange, excerpt(exchange.message), thought, (System.nanoTime() - start) / 1_000_000)
        }
        return Reading.Done(entry)
    }

    /** Lit les consignes sans rien noter (Test du Cerveau : pour mesurer la suite à part). */
    suspend fun prepare(runner: TaskRunner): Boolean {
        val ok = runner.begin(instructions)
        if (ok) withContext(NonCancellable) { runner.end() }
        return ok
    }

    /**
     * Les étapes du modèle. [previousReply] : la réponse d'Axo avant le message. Interrompu :
     * CancellationException, rien n'est noté.
     */
    suspend fun think(
        runner: TaskRunner,
        message: String,
        forced: Boolean,
        notes: List<Note>,
        forgotten: List<String>,
        previousReply: String = "",
    ): Thought {
        if (!runner.begin(instructions)) return failed("début")
        try {
            val question = if (forced) null else questionBefore(message, previousReply)
            val raw = runner.step(readPrompt(message, forced, question), readGrammar(forced), READ_TOKENS)
                ?: return failed("lecture")
            val source = TextMatch.words(message) + question?.let { TextMatch.words(it) }.orEmpty()
            val all = dedupe(parseLines(raw).filter { keep(it.fact, source, forced) })
            if (all.isEmpty()) return Thought.Empty
            val known = all.filter { isKnown(it.fact, notes, forgotten) }
            val fresh = all - known.toSet()
            if (fresh.isEmpty()) return Thought.Known(known.map { it.fact })

            val planned = ArrayList<PlannedFact>(fresh.size)
            for (line in fresh) {
                val target = target(line, notes)
                val note = target.id?.let { id -> notes.firstOrNull { it.id == id } }
                val words = TextMatch.words(line.fact)
                val current = note?.facts.orEmpty()
                val supersedes = current.filter { coverage(TextMatch.words(it.text), words) >= SUPERSEDE }.map { it.text }
                // Le nom de la note (« Léa ») est dans tous ses faits : il ne les rend pas ressemblants.
                val title = note?.let { TextMatch.words(it.title) }.orEmpty()
                fun likeness(f: Fact) = TextMatch.similarity(words - title, TextMatch.words(f.text) - title)
                val similar = current
                    .filter { it.text !in supersedes && likeness(it) >= SIMILAR }
                    .sortedByDescending { likeness(it) }
                    .take(MAX_SIMILAR)
                var replaces: String? = null
                if (similar.isNotEmpty()) {
                    val answer = runner.step(replacePrompt(message, line.fact, note!!.title, similar), replaceGrammar(similar.size), 4)
                    if (answer == null) currentCoroutineContext().ensureActive()
                    replaces = answer?.trim()?.toIntOrNull()?.let { similar.getOrNull(it - 1)?.text }
                }
                planned += PlannedFact(line.fact, target.id, target.title, target.tag, replaces, supersedes)
            }
            return Thought.Facts(planned, known.map { it.fact })
        } finally {
            withContext(NonCancellable) { runner.end() }
        }
    }

    private suspend fun failed(step: String): Thought {
        currentCoroutineContext().ensureActive()
        return Thought.Failed(step)
    }

    // --- Étape 1 : les faits ---

    /** Les consignes, et des exemples des deux étapes qui montrent le format (sans reprendre le Test du Cerveau). */
    val instructions = TaskInstructions(
        system = "Tu tiens le carnet de notes d'Axo sur $name. Pour chaque message de $name, relève ce qu'il y dit " +
            "de durable sur lui et sa vie : identité, âge, ville, travail, études, proches, animaux, goûts, loisirs, " +
            "projets, santé, habitudes.\n" +
            "Écris un fait par ligne : le nom de la note, « : », puis le fait.\n" +
            "- La note est « ${Note.ME_TITLE} » pour ce qui concerne $name lui-même. Un proche, un animal ou un sujet à part " +
            "a sa propre note : son nom, puis son type entre parenthèses (${TAGS.joinToString(", ")}).\n" +
            "- Le fait est une phrase courte et complète, à la troisième personne, fidèle à ses mots, sans commentaire.\n" +
            "- Note seulement ce que dit ce message : n'invente rien, ne déduis rien.\n" +
            "- Ignore ce qui ne dure pas (humeur, fatigue, faim, repas, météo, programme du jour), ses questions, " +
            "et ce que dit Axo.\n" +
            "- S'il n'y a rien de durable, écris : $NOTHING",
        examples = listOf(
            readPrompt("Je suis infirmier de nuit à Rennes, du coup je dors souvent le matin.", false, null) to
                "${Note.ME_TITLE} : $name est infirmier de nuit à Rennes.\n${Note.ME_TITLE} : $name dort souvent le matin.",
            // Étape 2 : un fait qui ressemble au nouveau, mais toujours vrai.
            replacePrompt(
                "Je suis infirmier de nuit à Rennes, du coup je dors souvent le matin.",
                "$name est infirmier de nuit à Rennes.",
                Note.ME_TITLE,
                listOf(Fact("$name a grandi à Rennes.")),
            ) to NOTHING,
            readPrompt("Je suis crevé, je crois que je vais me commander une pizza ce soir.", false, null) to NOTHING,
            readPrompt("Mon chien Rocky a 5 ans et il a peur de l'aspirateur.", false, null) to
                "Rocky (animal) : Rocky est le chien de $name, il a 5 ans.\nRocky (animal) : Rocky a peur de l'aspirateur.",
            readPrompt("La montagne, sans hésiter.", false, "Tu préfères la mer ou la montagne ?") to
                "${Note.ME_TITLE} : $name préfère la montagne à la mer.",
            readPrompt("Mon meilleur ami Karim part vivre au Canada en septembre.", false, null) to
                "Karim (personne) : Karim est le meilleur ami de $name.\nKarim (personne) : Karim part vivre au Canada en septembre.",
            readPrompt("Tu crois qu'il va faire beau ce week-end ?", false, null) to NOTHING,
            readPrompt("J'ai arrêté le foot, maintenant je fais de la boxe deux fois par semaine.", false, null) to
                "${Note.ME_TITLE} : $name fait de la boxe deux fois par semaine.\n${Note.ME_TITLE} : $name a arrêté le foot.",
            // Il ne joue plus au foot, mais il peut toujours le regarder.
            replacePrompt(
                "J'ai arrêté le foot, maintenant je fais de la boxe deux fois par semaine.",
                "$name a arrêté le foot.",
                Note.ME_TITLE,
                listOf(Fact("$name regarde le foot à la télé."), Fact("$name joue au foot le samedi.")),
            ) to "2",
            readPrompt("J'ai changé de boulot : maintenant je travaille dans un restaurant.", false, null) to
                "${Note.ME_TITLE} : $name travaille dans un restaurant.",
            // La situation a changé : l'ancien fait n'est plus vrai.
            replacePrompt(
                "J'ai changé de boulot : maintenant je travaille dans un restaurant.",
                "$name travaille dans un restaurant.",
                Note.ME_TITLE,
                listOf(Fact("$name travaille dans un supermarché.")),
            ) to "1",
            readPrompt("ma carte de bus expire en mars", true, null) to "${Note.ME_TITLE} : La carte de bus de $name expire en mars.",
        ),
    )

    /** Le message à lire, tel que le modèle le voit. [question] : celle d'Axo, pour une réponse courte. */
    fun readPrompt(message: String, forced: Boolean, question: String?): String = when {
        forced -> "$name te demande de retenir : « $message »"
        question != null -> "Axo lui a demandé : « $question »\nMessage de $name : « $message »"
        else -> "Message de $name : « $message »"
    }

    fun readGrammar(forced: Boolean): String {
        val lines = "line (\"\\n\" line)? (\"\\n\" line)?"
        return "root ::= " + (if (forced) lines else "$NOTHING_RULE | $lines") + "\n" +
            "line ::= note \" : \" fact\n" +
            "note ::= \"${gbnf(Note.ME_TITLE)}\" | name \" (\" tag \")\"\n" +
            "name ::= [$UPPER] [^\\n():«»\"*#_\\[\\]]{0,$MAX_TITLE_CHARS}\n" +
            "tag ::= ${TAGS.joinToString(" | ") { "\"${gbnf(it)}\"" }}\n" +
            // Pas de commentaire : ni « : », ni parenthèses, ni tirets longs, ni mise en forme.
            "fact ::= [${UPPER}0-9] [^\\n:()«»\"*#_\\[\\]–—]{3,$MAX_FACT_CHARS} \".\"\n"
    }

    /** Une ligne lue : la note (null pour « Moi »), son type, et le fait. */
    data class Line(val note: String?, val tag: String?, val fact: String)

    /** Les lignes complètes « Note : fait. », remises au propre. */
    fun parseLines(raw: String): List<Line> = raw.split('\n').mapNotNull { l ->
        val m = LINE.matchEntire(l.trim()) ?: return@mapNotNull null
        val fact = m.groupValues[4].trim()
        // Une ligne coupée par la limite de tokens n'a pas son point final.
        if (!fact.endsWith('.')) return@mapNotNull null
        val tidy = tidy(fact)
        if (TextMatch.words(tidy).isEmpty()) return@mapNotNull null
        if (m.groupValues[1].isNotEmpty()) Line(null, null, tidy) else Line(m.groupValues[2].trim(), m.groupValues[3].trim(), tidy)
    }

    private fun tidy(fact: String): String {
        var t = fact.replace(Regex("\\s+"), " ").trim().trim('"', '«', '»', ' ')
        if (t.length > MAX_FACT_CHARS) t = t.take(MAX_FACT_CHARS).substringBeforeLast(' ').trimEnd(',', ';', ' ') + "…"
        if (t.last() !in ".!?…") t += "."
        return t.replaceFirstChar { it.uppercaseChar() }
    }

    /**
     * Un fait à garder : il reprend au moins un mot du message ([source], avec la question d'Axo), ne
     * parle pas d'Axo, n'est pas une supposition, et ne décrit pas un moment qui passe (sauf « retiens »).
     */
    fun keep(fact: String, source: Set<String>, forced: Boolean): Boolean {
        val words = TextMatch.words(fact)
        if (words.none { it in source } || words.all { it in GENERIC }) return false
        val plain = " " + TextMatch.allWords(fact).joinToString(" ") + " "
        if (HEDGES.any { plain.contains(" $it ") } || plain.contains(" axo ")) return false
        return forced || PASSING.none { plain.contains(" $it ") }
    }

    /** La question d'Axo juste avant, quand le message y répond en peu de mots. */
    fun questionBefore(message: String, previousReply: String): String? {
        if (TextMatch.allWords(message).size > SHORT_ANSWER_WORDS) return null
        val reply = previousReply.trim()
        val end = reply.lastIndexOf('?')
        if (end < 0) return null
        val start = reply.substring(0, end).indexOfLast { it in ".!?…\n" } + 1
        return reply.substring(start, end + 1).trim().takeLast(MAX_QUESTION_CHARS).trim().takeIf { it.length > 1 }
    }

    /** Deux faits presque pareils dans la même réponse : un seul. */
    private fun dedupe(lines: List<Line>): List<Line> {
        val out = ArrayList<Line>()
        for (l in lines) {
            val w = TextMatch.words(l.fact)
            if (out.none { coverage(w, TextMatch.words(it.fact)) >= KNOWN }) out += l
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

    // --- La note de chaque fait ---

    /** Où ranger un fait : une note existante ([id]), ou une nouvelle note (id null) avec son étiquette. */
    data class Target(val id: String?, val title: String, val tag: String?)

    fun target(line: Line, notes: List<Note>): Target {
        val title = line.note ?: return Target(Note.ME_ID, Note.ME_TITLE, null)
        return newTarget(title, line.tag.orEmpty(), notes)
    }

    private fun newTarget(rawTitle: String, tag: String, notes: List<Note>): Target {
        val title = cleanTitle(rawTitle)
        val folded = TextMatch.fold(title)
        val words = TextMatch.words(title)
        if (words.isEmpty() || folded == TextMatch.fold(Note.ME_TITLE) || folded == TextMatch.fold(name)) {
            return Target(Note.ME_ID, Note.ME_TITLE, null)
        }
        // Une note de ce nom existe déjà (« Léa » pour « Ma sœur Léa ») : on la complète.
        notes.firstOrNull { TextMatch.fold(it.title) == folded }?.let { return Target(it.id, it.title, null) }
        notes.firstOrNull { n -> !n.isMe && TextMatch.words(n.title).let { it.isNotEmpty() && words.containsAll(it) } }
            ?.let { return Target(it.id, it.title, null) }
        return Target(null, title, tag.takeIf { it in TAGS })
    }

    private fun cleanTitle(raw: String): String {
        var t = raw.replace(Regex("\\s+"), " ").trim().trim('"', '«', '»', '.', ',', ':', ';', ' ')
        if (t.length > MAX_TITLE_CHARS) t = t.take(MAX_TITLE_CHARS).substringBeforeLast(' ')
        return t.replaceFirstChar { it.titlecase() }
    }

    // --- Étape 2 : un fait devenu faux ---

    /** [message] : ce que l'utilisateur a dit, d'où vient [fact] (« j'ai déménagé », « maintenant »…). */
    fun replacePrompt(message: String, fact: String, noteTitle: String, similar: List<Fact>): String =
        "$name a dit : « ${message.take(MAX_SAID_CHARS)} »\n" +
            "Nouveau fait : $fact\n" +
            "Déjà noté dans « $noteTitle » :\n" +
            similar.withIndex().joinToString("\n") { (i, f) -> "${i + 1}. ${NoteLinks.plain(f.text)}" } + "\n" +
            "Lequel de ces faits n'est plus vrai maintenant ? Réponds par son numéro, ou « $NOTHING » s'ils sont tous encore vrais."

    fun replaceGrammar(count: Int): String = "root ::= $NOTHING_RULE" + (1..count).joinToString("") { " | \"$it\"" } + "\n"

    companion object {
        /** Tokens écrits au plus pour les faits d'un message (trois lignes). */
        const val READ_TOKENS = 120
        const val MAX_MESSAGE_CHARS = 600
        const val MAX_FACT_CHARS = 150
        const val MAX_TITLE_CHARS = 30
        const val MAX_QUESTION_CHARS = 160

        /** Ce qu'il a dit, repris dans la question « plus vrai maintenant ? ». */
        const val MAX_SAID_CHARS = 300
        const val MAX_SIMILAR = 3

        /** Jusqu'à ce nombre de mots, le message peut être la réponse à une question d'Axo. */
        const val SHORT_ANSWER_WORDS = 12
        const val NOTHING = "aucun"

        /** « aucun », ou « Aucun » si le modèle met une majuscule en début de réponse. */
        private const val NOTHING_RULE = "\"aucun\" | \"Aucun\""
        private const val UPPER = "A-ZÀÂÄÇÉÈÊËÎÏÔÖÙÛÜŸŒÆ"
        private val LINE = Regex("^(?:(Moi)|(.+?) \\(([^()]+)\\)) : (.+)$")

        /** Mots qui ne disent rien à eux seuls (« Matteo s'appelle Matteo. »). */
        private val GENERIC = setOf("appelle", "nom", "prenom", "dit", "parle", "raconte", "explique", "message")

        /** Une supposition ou un commentaire, pas un fait. */
        private val HEDGES = listOf(
            "semble", "semblent", "sembler", "probablement", "peut etre", "sans doute", "apparemment", "visiblement",
            "mentionne", "mentionnee", "mentionnes", "mentionnees", "mention", "precise pas", "non precise", "inconnu", "inconnue",
        )

        /** Ce qui ne dure pas. */
        private val PASSING = listOf(
            "ce soir", "ce matin", "cet apres midi", "cette nuit", "aujourd hui", "demain", "tout a l heure", "a faim", "se demande",
        )

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
