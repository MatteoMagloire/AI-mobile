package com.matteomagloire.aimobile.brain

import java.time.LocalDate

enum class NoteOutcome {
    /** Des faits ajoutés au Cerveau. */
    NOTED,

    /** Le modèle n'a rien trouvé de durable. */
    NOTHING,

    /** Rien de neuf : faits déjà notés, ou oubliés à la demande de l'utilisateur. */
    KNOWN,

    /** Message trop court ou qui ne parle pas de lui : pas lu par le modèle. */
    IGNORED,

    /** « Oublie… ». */
    FORGOTTEN,

    FAILED,
}

/** Une ligne du journal du Cerveau : ce qu'Axo a fait d'un message, et en combien de temps. */
data class JournalEntry(
    val time: Long,
    /** Heure du message de l'utilisateur. */
    val messageTime: Long,
    val message: String,
    val outcome: NoteOutcome,
    val noted: List<NotedFact> = emptyList(),
    /** Faits trouvés mais déjà connus (ou oubliés). */
    val known: List<String> = emptyList(),
    /** Faits retirés par « oublie… ». */
    val forgotten: List<String> = emptyList(),
    val ms: Long = 0,
    val undone: Boolean = false,
)

/**
 * Le journal en texte simple : une ligne par entrée (E), suivie de ses faits (N, avec R et S pour ce
 * qu'ils ont remplacé), des faits connus (K) et oubliés (F). Champs séparés par des tabulations.
 */
object JournalFormat {
    fun write(entries: List<JournalEntry>): String = buildString {
        for (e in entries) {
            line("E", e.time.toString(), e.messageTime.toString(), e.outcome.name, e.ms.toString(), if (e.undone) "1" else "0", e.message)
            for (n in e.noted) {
                line("N", n.noteId, n.noteTitle, n.text, if (n.newNote) "1" else "0")
                n.replaced?.let { line("R", it.date?.toString() ?: "", it.text) }
                n.superseded.forEach { line("S", it.date?.toString() ?: "", it.text) }
            }
            e.known.forEach { line("K", it) }
            e.forgotten.forEach { line("F", it) }
        }
    }

    private fun StringBuilder.line(vararg fields: String) {
        append(fields.joinToString("\t") { escape(it) }).append('\n')
    }

    fun parse(text: String): List<JournalEntry> {
        val out = ArrayList<JournalEntry>()
        var entry: JournalEntry? = null
        var noted: NotedFact? = null
        fun flushNoted() {
            val e = entry ?: return
            noted?.let { entry = e.copy(noted = e.noted + it) }
            noted = null
        }
        fun flushEntry() {
            flushNoted()
            entry?.let { out += it }
            entry = null
        }
        for (raw in text.split('\n')) {
            if (raw.isEmpty()) continue
            val f = raw.split('\t').map(::unescape)
            try {
                when (f[0]) {
                    "E" -> {
                        flushEntry()
                        entry = JournalEntry(f[1].toLong(), f[2].toLong(), f[6], NoteOutcome.valueOf(f[3]), ms = f[4].toLong(), undone = f[5] == "1")
                    }
                    "N" -> {
                        flushNoted()
                        if (entry != null) noted = NotedFact(f[1], f[2], f[3], newNote = f[4] == "1")
                    }
                    "R" -> noted = noted?.copy(replaced = Fact(f[2], date(f[1])))
                    "S" -> noted = noted?.let { it.copy(superseded = it.superseded + Fact(f[2], date(f[1]))) }
                    "K" -> {
                        flushNoted()
                        entry = entry?.let { it.copy(known = it.known + f[1]) }
                    }
                    "F" -> {
                        flushNoted()
                        entry = entry?.let { it.copy(forgotten = it.forgotten + f[1]) }
                    }
                }
            } catch (e: Exception) {
                // Ligne abîmée : ignorée.
            }
        }
        flushEntry()
        return out
    }

    private fun date(s: String): LocalDate? = if (s.isEmpty()) null else try {
        LocalDate.parse(s)
    } catch (e: Exception) {
        null
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    private fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    't' -> sb.append('\t')
                    'n' -> sb.append('\n')
                    else -> sb.append(s[i + 1])
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
