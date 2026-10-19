package com.matteomagloire.aimobile.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class NoteEditsTest {
    private val d1 = LocalDate.of(2026, 10, 12)
    private val d2 = LocalDate.of(2026, 10, 19)
    private val me = Note(
        Note.ME_ID, Note.ME_TITLE, listOf(Note.ME_TAG), d1, d1,
        facts = listOf(Fact("Matteo habite à Lyon.", d1), Fact("Matteo a une sœur, Léa.", d1)),
    )

    @Test
    fun applyCreatesNotesAndKeepsHistory() {
        val planned = listOf(
            PlannedFact("Matteo habite à Paris.", Note.ME_ID, Note.ME_TITLE, replaces = "Matteo habite à Lyon."),
            PlannedFact("Matteo a une sœur, Léa, qui a eu son bac.", Note.ME_ID, Note.ME_TITLE, supersedes = listOf("Matteo a une sœur, Léa.")),
            PlannedFact("Matteo a un chat qui s'appelle Pixel.", null, "Pixel", "animal"),
            PlannedFact("Matteo dit que Pixel a 3 ans.", null, "pixel", "animal"),
        )
        val (notes, noted) = NoteEdits.apply(listOf(me), planned, d2)
        val newMe = notes.first { it.isMe }
        assertEquals(listOf("Matteo habite à Paris.", "Matteo a une sœur, Léa, qui a eu son bac."), newMe.facts.map { it.text })
        assertEquals(listOf(Fact("Matteo habite à Lyon.", d1, d2)), newMe.before)
        assertEquals(d2, newMe.modified)
        val pixel = notes.first { it.id == "pixel" }
        assertEquals(listOf("animal"), pixel.tags)
        assertEquals(2, pixel.facts.size)
        assertEquals(listOf(false, false, true, false), noted.map { it.newNote })
        assertEquals(Fact("Matteo habite à Lyon.", d1), noted[0].replaced)

        // Annuler remet tout comme avant (à l'ordre des faits près).
        val undone = NoteEdits.undo(notes, noted)
        assertEquals(listOf(Note.ME_ID), undone.map { it.id })
        assertEquals(me.facts.toSet(), undone.single().facts.toSet())
        assertEquals(emptyList<Fact>(), undone.single().before)
    }

    @Test
    fun meIsCreatedWhenMissing() {
        val (notes, noted) = NoteEdits.apply(emptyList(), listOf(PlannedFact("Matteo a 23 ans.", Note.ME_ID, Note.ME_TITLE)), d2)
        assertEquals(Note.ME_ID, notes.single().id)
        assertTrue(noted.single().newNote)
        // « Moi » n'est jamais supprimée, même vidée.
        assertEquals(listOf(Note.ME_ID), NoteEdits.undo(notes, noted).map { it.id })
    }

    @Test
    fun newNotesGetFreeNames() {
        val taken = listOf(Note("pixel", "Pixel", emptyList(), d1, d1))
        assertEquals("pixel-2", NoteEdits.uniqueId("Pixel !", taken))
        assertEquals("moi-2", NoteEdits.uniqueId("Moi", emptyList()))
    }

    @Test
    fun forgetAndRestore() {
        val pixel = Note("pixel", "Pixel", emptyList(), d1, d1, facts = listOf(Fact("Matteo a un chat qui s'appelle Pixel.", d1)))
        val notes = listOf(me, pixel)
        val (afterTitle, byTitle) = NoteEdits.forget(notes, "Pixel", d2)
        assertEquals(listOf(Note.ME_ID), afterTitle.map { it.id })
        assertEquals(listOf(pixel), byTitle.notes)

        val (afterWords, byWords) = NoteEdits.forget(notes, "ma sœur Léa", d2)
        assertEquals(listOf("Matteo a une sœur, Léa."), byWords.texts)
        assertEquals(listOf("Matteo habite à Lyon."), afterWords.first { it.isMe }.facts.map { it.text })
        assertEquals(notes.map { it.facts.toSet() }, NoteEdits.restore(afterWords, byWords).map { it.facts.toSet() })

        assertTrue(NoteEdits.forget(notes, "ma voiture", d2).second.isEmpty)
        // « Moi » n'est jamais supprimée d'un coup.
        assertEquals(2, NoteEdits.forget(notes, "moi", d2).first.size)
    }
}
