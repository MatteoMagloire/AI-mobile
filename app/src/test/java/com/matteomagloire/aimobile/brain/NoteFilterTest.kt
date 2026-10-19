package com.matteomagloire.aimobile.brain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class NoteFilterTest {
    private val day = LocalDate.of(2026, 10, 19)
    private val me = Note(
        Note.ME_ID, Note.ME_TITLE, listOf(Note.ME_TAG), day, day,
        facts = listOf(Fact("Matteo a 23 ans."), Fact("Matteo habite à Paris.")),
        before = listOf(Fact("Matteo habitait à Lyon.")),
    )
    private val pixel = Note("pixel", "Pixel", listOf("animal"), day, day, facts = listOf(Fact("Matteo a un chat qui s'appelle Pixel."), Fact("Pixel a 3 ans.")))
    private val lea = Note("lea", "Léa", listOf("personne"), day, day, facts = listOf(Fact("Léa est la sœur de Matteo.")))
    private val velo = Note("velo", "Vélo", listOf("autre"), day, day, extra = "Le code du cadenas.", facts = listOf(Fact("Le code du vélo de Matteo est 4721.")))
    private val notes = listOf(me, pixel, lea, velo)

    @Test
    fun searchIgnoresAccentsAndCaseAndFindsTheStartOfAWord() {
        assertEquals(listOf("lea"), NoteFilter.filter(notes, "LEA").map { it.id })
        assertEquals(listOf("pixel"), NoteFilter.filter(notes, "pix").map { it.id })
        assertEquals(listOf("lea"), NoteFilter.filter(notes, "soeur").map { it.id })
        // Tous les mots doivent y être.
        assertEquals(listOf("pixel"), NoteFilter.filter(notes, "chat 3 ans").map { it.id })
        // Le texte libre, « Avant » et les étiquettes comptent aussi.
        assertEquals(listOf("velo"), NoteFilter.filter(notes, "cadenas").map { it.id })
        assertEquals(listOf(Note.ME_ID), NoteFilter.filter(notes, "lyon").map { it.id })
        assertEquals(listOf("pixel"), NoteFilter.filter(notes, "animal").map { it.id })
        assertEquals(notes, NoteFilter.filter(notes, "  "))
        assertEquals(emptyList<Note>(), NoteFilter.filter(notes, "guitare"))
    }

    @Test
    fun filterByTag() {
        assertEquals(listOf("lea"), NoteFilter.filter(notes, "", "personne").map { it.id })
        assertEquals(emptyList<Note>(), NoteFilter.filter(notes, "pixel", "personne"))
    }

    @Test
    fun previewShowsTheMatchingFactsOrTheLatest() {
        assertEquals(listOf(Fact("Pixel a 3 ans."), Fact("Matteo a un chat qui s'appelle Pixel.")), NoteFilter.preview(pixel, ""))
        assertEquals(listOf(Fact("Matteo a un chat qui s'appelle Pixel.")), NoteFilter.preview(pixel, "chat"))
        assertEquals(listOf(Fact("Pixel a 3 ans.")), NoteFilter.preview(pixel, "", max = 1))
    }

    @Test
    fun tagsMostCommonFirstWithoutMe() {
        val more = notes + Note("rex", "Rex", listOf("animal"), day, day)
        assertEquals(listOf("animal", "autre", "personne"), NoteFilter.tags(more))
    }

    @Test
    fun prettyFact() {
        assertEquals("Pixel est roux.", NoteFilter.pretty("  pixel est roux "))
        assertEquals("Élodie vient samedi !", NoteFilter.pretty("élodie vient samedi !"))
        assertEquals("", NoteFilter.pretty(" "))
    }
}
