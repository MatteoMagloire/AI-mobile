package com.matteomagloire.aimobile.brain

import java.time.LocalDate

/** Un fait ajouté par une prise de notes, avec ce qu'il a changé : de quoi l'afficher et l'annuler. */
data class NotedFact(
    val noteId: String,
    val noteTitle: String,
    val text: String,
    /** La note a été créée pour ce fait. */
    val newNote: Boolean = false,
    /** Fait passé dans « Avant ». */
    val replaced: Fact? = null,
    /** Faits retirés : le nouveau les dit déjà, en plus précis. */
    val superseded: List<Fact> = emptyList(),
)

/** Ce qu'un « oublie… » a retiré : des notes entières, ou des faits. */
data class Forgetting(
    val notes: List<Note> = emptyList(),
    val facts: List<Pair<String, Fact>> = emptyList(),
) {
    val texts: List<String> get() = notes.flatMap { n -> n.facts.map { it.text } } + facts.map { it.second.text }
    val isEmpty: Boolean get() = notes.isEmpty() && facts.isEmpty()
}

/** Modifications des notes, sans fichiers : faciles à tester. */
object NoteEdits {

    /** Ajoute les faits [planned] (créant les notes nécessaires). */
    fun apply(notes: List<Note>, planned: List<PlannedFact>, today: LocalDate): Pair<List<Note>, List<NotedFact>> {
        val out = notes.toMutableList()
        val noted = ArrayList<NotedFact>()
        for (p in planned) {
            var index = if (p.noteId != null) out.indexOfFirst { it.id == p.noteId }
            else out.indexOfFirst { TextMatch.fold(it.title) == TextMatch.fold(p.noteTitle) }
            val created = index < 0
            if (created) {
                out += if (p.noteId == Note.ME_ID) Note.me(today)
                else Note(uniqueId(p.noteTitle, out), p.noteTitle, listOfNotNull(p.tag), today, today)
                index = out.lastIndex
            }
            val note = out[index]
            val superseded = note.facts.filter { it.text in p.supersedes }
            val replaced = p.replaces?.let { r -> note.facts.firstOrNull { it.text == r && it !in superseded } }
            val facts = note.facts.filter { it !in superseded && it != replaced } + Fact(p.text, today)
            val before = if (replaced != null) note.before + replaced.copy(until = today) else note.before
            out[index] = note.copy(facts = facts, before = before, modified = today)
            noted += NotedFact(note.id, note.title, p.text, created, replaced, superseded)
        }
        return out to noted
    }

    /** Annule une prise de notes : les faits ajoutés partent, ceux qu'ils avaient remplacés reviennent. */
    fun undo(notes: List<Note>, noted: List<NotedFact>): List<Note> {
        val out = notes.toMutableList()
        for (n in noted.asReversed()) {
            val index = out.indexOfFirst { it.id == n.noteId }
            if (index < 0) continue
            val note = out[index]
            var facts = note.facts.filter { it.text != n.text }
            var before = note.before
            n.replaced?.let { r ->
                val old = before.lastOrNull { it.text == r.text }
                if (old != null) before = before - old
                facts = facts + r.copy(until = null)
            }
            facts = facts + n.superseded
            val undone = note.copy(facts = facts, before = before)
            if (n.newNote && undone.facts.isEmpty() && undone.before.isEmpty() && undone.extra.isBlank() && !undone.isMe) {
                out.removeAt(index)
            } else {
                out[index] = undone
            }
        }
        return out
    }

    /**
     * « Oublie [what] » : une note entière si [what] est son titre (sauf « Moi »), sinon les faits qui
     * en parlent.
     */
    fun forget(notes: List<Note>, what: String, today: LocalDate): Pair<List<Note>, Forgetting> {
        val target = TextMatch.words(what)
        if (target.isEmpty()) return notes to Forgetting()
        val out = ArrayList<Note>()
        val goneNotes = ArrayList<Note>()
        val goneFacts = ArrayList<Pair<String, Fact>>()
        for (note in notes) {
            val title = TextMatch.words(note.title)
            if (!note.isMe && title.isNotEmpty() && title == target) {
                goneNotes += note
                continue
            }
            val (drop, keep) = note.facts.partition { NoteTaker.coverage(target, TextMatch.words(it.text) + title) >= FORGET_MATCH }
            goneFacts += drop.map { note.id to it }
            out += if (drop.isEmpty()) note else note.copy(facts = keep, modified = today)
        }
        return out to Forgetting(goneNotes, goneFacts)
    }

    /** Remet ce qu'un « oublie… » avait retiré. */
    fun restore(notes: List<Note>, forgetting: Forgetting): List<Note> {
        val out = notes.toMutableList()
        for ((id, fact) in forgetting.facts) {
            val index = out.indexOfFirst { it.id == id }
            if (index >= 0 && fact !in out[index].facts) out[index] = out[index].copy(facts = out[index].facts + fact)
        }
        for (note in forgetting.notes) if (out.none { it.id == note.id }) out += note
        return out
    }

    /** Part des mots de la demande d'oubli qu'un fait doit contenir. */
    const val FORGET_MATCH = 0.6

    /** Nom de fichier libre pour une nouvelle note. */
    fun uniqueId(title: String, notes: List<Note>): String {
        val base = NoteFormat.slug(title).let { if (it == Note.ME_ID) "$it-2" else it }
        if (notes.none { it.id == base }) return base
        var i = 2
        while (notes.any { it.id == "$base-$i" }) i++
        return "$base-$i"
    }
}
