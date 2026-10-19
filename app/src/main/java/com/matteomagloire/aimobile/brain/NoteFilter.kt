package com.matteomagloire.aimobile.brain

import java.util.Locale

/**
 * La recherche de l'écran du Cerveau : sans accents ni majuscules, chaque mot cherché doit se trouver
 * dans la note (titre, étiquettes, faits, « Avant », texte libre), même en début de mot (« pix »).
 */
object NoteFilter {
    private fun text(note: Note) = TextMatch.fold(
        (listOf(note.title, note.extra) + note.tags + note.facts.map { it.text } + note.before.map { it.text }).joinToString("\n"),
    )

    /** Les notes qui contiennent tous les mots de [query] et portent l'étiquette [tag] (null : toutes). */
    fun filter(notes: List<Note>, query: String, tag: String? = null): List<Note> {
        val words = TextMatch.allWords(query)
        return notes.filter { n -> (tag == null || tag in n.tags) && text(n).let { t -> words.all { it in t } } }
    }

    /** Les faits à montrer sous le titre : ceux qui contiennent un mot cherché, sinon les derniers notés. */
    fun preview(note: Note, query: String, max: Int = 2): List<Fact> {
        val words = TextMatch.allWords(query)
        val matching = if (words.isEmpty()) emptyList() else note.facts.filter { f -> TextMatch.fold(f.text).let { t -> words.any { it in t } } }
        return matching.ifEmpty { note.facts.asReversed() }.take(max)
    }

    /** Les étiquettes des notes, les plus courantes d'abord (sans celle de « Moi »). */
    fun tags(notes: List<Note>): List<String> =
        notes.flatMap { it.tags }.filter { it != Note.ME_TAG }.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { TextMatch.fold(it.key) })
            .map { it.key }

    /** Un fait remis au propre pour la lecture : majuscule au début, point à la fin. Le fichier ne change pas. */
    fun pretty(text: String): String {
        val t = text.trim()
        if (t.isEmpty()) return t
        val capital = t.replaceFirstChar { it.titlecase(Locale.FRENCH) }
        return if (capital.last() in ".!?…") capital else "$capital."
    }

    /** Comme [pretty], pour un texte avec des liens : les liens restent à leur place. */
    fun pretty(linked: NoteLinks.Linked): NoteLinks.Linked {
        val t = linked.text
        if (t.isBlank() || t.first().isWhitespace() || t.last().isWhitespace()) return linked
        // Une majuscule qui s'écrirait en deux lettres décalerait les liens : on la laisse.
        val first = t.first().titlecase(Locale.FRENCH).takeIf { it.length == 1 } ?: t.first().toString()
        val capital = first + t.substring(1)
        return linked.copy(text = if (capital.last() in ".!?…") capital else "$capital.")
    }
}
