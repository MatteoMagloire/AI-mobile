package com.matteomagloire.aimobile.brain

import java.time.LocalDate

/** Prénom de l'utilisateur, tel qu'Axo l'écrit dans ses notes (« Matteo a un chat »). */
const val USER_NAME = "Matteo"

/** Un fait du Cerveau, daté du jour où Axo l'a appris ; [until] : jour où il a cessé d'être vrai. */
data class Fact(val text: String, val date: LocalDate? = null, val until: LocalDate? = null)

/** Une note du Cerveau : un fichier Markdown sur le téléphone. */
data class Note(
    /** Nom du fichier, sans « .md ». */
    val id: String,
    val title: String,
    val tags: List<String> = emptyList(),
    val created: LocalDate,
    val modified: LocalDate,
    val opened: LocalDate? = null,
    /** Dernier jour où Axo s'en est servi pour répondre. */
    val used: LocalDate? = null,
    val facts: List<Fact> = emptyList(),
    /** Ce qui était vrai avant : faits remplacés par un plus récent. */
    val before: List<Fact> = emptyList(),
    /** Notes reliées à la main (rubrique « ## Liens »), par leur titre. */
    val links: List<String> = emptyList(),
    /** Texte écrit à la main hors des faits, gardé tel quel. */
    val extra: String = "",
) {
    val isMe: Boolean get() = id == ME_ID

    /** Dernier jour où quelqu'un s'en est occupé : créée, modifiée, ouverte ou utilisée par Axo. */
    val lastActive: LocalDate get() = listOfNotNull(created, modified, opened, used).max()

    /**
     * En veille (mémoire froide) : ni toi ni Axo ne l'avez touchée depuis plus de [days] jours. Rien
     * n'est effacé, Axo puise d'abord dans les autres. « Moi » ne s'endort jamais.
     */
    fun isDormant(today: LocalDate, days: Int): Boolean = !isMe && lastActive.plusDays(days.toLong()) < today

    companion object {
        /** La note « Moi » : ce qu'Axo sait de l'utilisateur lui-même. */
        const val ME_ID = "moi"
        const val ME_TITLE = "Moi"
        const val ME_TAG = "moi"

        fun me(today: LocalDate) = Note(ME_ID, ME_TITLE, listOf(ME_TAG), today, today)
    }
}

/**
 * Format des fichiers : un en-tête (étiquettes, dates), le titre, puis les faits datés.
 *
 * ```
 * ---
 * etiquettes: animal
 * creee: 2026-10-19
 * modifiee: 2026-10-19
 * ---
 * # Pixel
 *
 * ## Faits
 * - 2026-10-19 · Matteo a un chat qui s'appelle Pixel.
 *
 * ## Avant
 * - 2026-10-12 → 2026-10-19 · Pixel dormait dans le salon.
 *
 * ## Liens
 * - [[Léa]]
 * ```
 *
 * Une note retouchée à la main se relit aussi : une ligne sans date est un fait, le reste est gardé.
 * « utilisee » dans l'en-tête : dernier jour où Axo s'en est servi. « ## Liens » : les notes reliées à
 * la main, en plus de celles que le texte nomme.
 */
object NoteFormat {
    private const val FACTS = "## Faits"
    private const val BEFORE = "## Avant"
    private const val LINKS = "## Liens"
    private val BULLET = Regex("""^[-*](\s+|$)""")
    private val DATED = Regex("""^(\d{4}-\d{2}-\d{2})?\s*(?:(?:→|->)\s*(\d{4}-\d{2}-\d{2}))?\s*·\s*(.*)$""")

    fun write(note: Note): String = buildString {
        append("---\n")
        if (note.tags.isNotEmpty()) append("etiquettes: ${note.tags.joinToString(", ")}\n")
        append("creee: ${note.created}\n")
        append("modifiee: ${note.modified}\n")
        note.opened?.let { append("ouverte: $it\n") }
        note.used?.let { append("utilisee: $it\n") }
        append("---\n")
        append("# ${note.title}\n\n")
        // Le texte libre juste sous le titre : relu tel quel, sans être pris pour des faits.
        if (note.extra.isNotBlank()) append("${note.extra.trim()}\n\n")
        append("$FACTS\n")
        note.facts.forEach { append("- ${line(it)}\n") }
        if (note.before.isNotEmpty()) {
            append("\n$BEFORE\n")
            note.before.forEach { append("- ${line(it)}\n") }
        }
        if (note.links.isNotEmpty()) {
            append("\n$LINKS\n")
            note.links.forEach { append("- [[$it]]\n") }
        }
    }

    private fun line(f: Fact): String {
        val date = when {
            f.date != null && f.until != null -> "${f.date} → ${f.until} · "
            f.date != null -> "${f.date} · "
            f.until != null -> "→ ${f.until} · "
            else -> ""
        }
        return date + f.text
    }

    /** Relit une note ; [today] date ce qui manque (fichier écrit à la main). */
    fun parse(id: String, text: String, today: LocalDate): Note {
        val lines = text.replace("\r\n", "\n").split('\n')
        val meta = mutableMapOf<String, String>()
        var start = 0
        if (lines.firstOrNull()?.trim() == "---") {
            val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
            if (end >= 0) {
                for (l in lines.subList(1, end + 1)) {
                    val colon = l.indexOf(':')
                    if (colon > 0) meta[TextMatch.fold(l.substring(0, colon).trim())] = l.substring(colon + 1).trim()
                }
                start = end + 2
            }
        }
        var title: String? = null
        val facts = mutableListOf<Fact>()
        val before = mutableListOf<Fact>()
        val links = mutableListOf<String>()
        val extra = StringBuilder()
        // 0 : hors section, 1 : faits, 2 : avant, 3 : autre section (gardée telle quelle), 4 : liens.
        var section = 0
        for (l in lines.drop(start)) {
            val t = l.trim()
            when {
                title == null && section == 0 && t.startsWith("# ") -> title = t.removePrefix("# ").trim()
                t.startsWith("## ") -> {
                    section = when (TextMatch.fold(t.removePrefix("## ").trim())) {
                        "faits" -> 1
                        "avant" -> 2
                        "liens" -> 4
                        else -> 3
                    }
                    if (section == 3) extra.append(l).append('\n')
                }
                section == 1 || section == 2 -> parseFact(t)?.let { if (section == 1) facts += it else before += it }
                section == 4 -> parseLink(t)?.let { if (links.none { l -> TextMatch.fold(l) == TextMatch.fold(it) }) links += it }
                t.isNotEmpty() || extra.isNotEmpty() -> extra.append(l).append('\n')
            }
        }
        return Note(
            id = id,
            title = title?.takeIf { it.isNotEmpty() } ?: meta["titre"] ?: id,
            tags = meta["etiquettes"]?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() } ?: emptyList(),
            created = date(meta["creee"]) ?: today,
            modified = date(meta["modifiee"]) ?: today,
            opened = date(meta["ouverte"]),
            used = date(meta["utilisee"]),
            facts = facts,
            before = before,
            links = links,
            extra = extra.toString().trim(),
        )
    }

    /** Une ligne de « ## Liens » : « - [[Léa]] », ou simplement « - Léa ». */
    private fun parseLink(line: String): String? {
        val t = line.replace(BULLET, "").trim()
        val inner = NoteLinks.WIKI.find(t)?.groupValues?.get(1) ?: t
        return NoteLinks.target(inner).takeIf { it.isNotEmpty() }
    }

    /** Un fait, avec ou sans date (« - 2026-10-19 · texte »). */
    fun parseFact(line: String): Fact? {
        val t = line.trim().replace(BULLET, "").trim()
        if (t.isEmpty()) return null
        val m = DATED.matchEntire(t)
        if (m != null && (m.groupValues[1].isNotEmpty() || m.groupValues[2].isNotEmpty())) {
            val text = m.groupValues[3].trim()
            return if (text.isEmpty()) null else Fact(text, date(m.groupValues[1]), date(m.groupValues[2]))
        }
        return Fact(t)
    }

    private fun date(s: String?): LocalDate? =
        if (s.isNullOrBlank()) null else try {
            LocalDate.parse(s.trim())
        } catch (e: Exception) {
            null
        }

    /** Nom de fichier tiré du titre (« Sœur de Matteo » -> « soeur-de-matteo »). */
    fun slug(title: String): String =
        TextMatch.fold(title).replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).trim('-').ifEmpty { "note" }
}
