package com.matteomagloire.aimobile.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TextAndNoteFormatTest {
    private val d1 = LocalDate.of(2026, 10, 12)
    private val d2 = LocalDate.of(2026, 10, 19)

    @Test
    fun foldRemovesAccentsAndCase() {
        assertEquals("lea oeuvre a l'ecole", TextMatch.fold("Léa Œuvre à l'École"))
    }

    @Test
    fun wordsKeepWhatCarriesMeaning() {
        // Ni les petits mots ni le prénom : tous les faits commencent par « Matteo ».
        assertEquals(setOf("chat", "appelle", "pixel"), TextMatch.words("Matteo a un chat qui s'appelle Pixel."))
        assertEquals(setOf("23", "ans"), TextMatch.words("Matteo a 23 ans."))
        assertEquals(setOf("chat"), TextMatch.words("Mes chats"))
        assertEquals("boss", TextMatch.stem("boss"))
    }

    @Test
    fun similarityCountsSharedWords() {
        assertEquals(1.0 / 3, TextMatch.similarity("Matteo habite à Lyon.", "Matteo habite à Paris."), 1e-9)
        assertEquals(0.0, TextMatch.similarity("Matteo aime le jazz.", "Matteo a 23 ans."), 1e-9)
        assertEquals(1.0, TextMatch.similarity("Matteo a un chat.", "matteo a des chats"), 1e-9)
    }

    @Test
    fun noteSurvivesWriteAndRead() {
        val note = Note(
            id = "pixel",
            title = "Pixel",
            tags = listOf("animal", "maison"),
            created = d1,
            modified = d2,
            opened = d2,
            facts = listOf(Fact("Matteo a un chat qui s'appelle Pixel.", d1), Fact("Pixel a 3 ans.", d2)),
            before = listOf(Fact("Pixel dormait dans le salon.", d1, d2)),
            extra = "Une ligne écrite à la main.\n\n## Idées\n- un arbre à chat",
        )
        val text = NoteFormat.write(note)
        assertTrue(text, text.contains("- 2026-10-12 · Matteo a un chat qui s'appelle Pixel."))
        assertTrue(text, text.contains("- 2026-10-12 → 2026-10-19 · Pixel dormait dans le salon."))
        assertEquals(note, NoteFormat.parse("pixel", text, LocalDate.of(2030, 1, 1)))
    }

    @Test
    fun handWrittenNoteIsRead() {
        val text = "# Léa\n\nMa sœur.\n\n## Faits\n- Matteo a une sœur, Léa.\nElle a eu son bac\n\n## Avant\n- 2026-10-12 → 2026-10-19 · Léa était au lycée.\n"
        val note = NoteFormat.parse("lea", text, d2)
        assertEquals("Léa", note.title)
        assertEquals(listOf(Fact("Matteo a une sœur, Léa."), Fact("Elle a eu son bac")), note.facts)
        assertEquals(listOf(Fact("Léa était au lycée.", d1, d2)), note.before)
        assertEquals("Ma sœur.", note.extra)
        assertEquals(d2, note.created)
        assertEquals(emptyList<String>(), note.tags)
    }

    @Test
    fun aFileWithoutTitleKeepsItsName() {
        assertEquals("velo", NoteFormat.parse("velo", "## Faits\n- code 4721", d2).title)
    }

    @Test
    fun factDatesAreOptional() {
        assertEquals(Fact("texte", d2), NoteFormat.parseFact("- 2026-10-19 · texte"))
        assertEquals(Fact("Matteo · développeur"), NoteFormat.parseFact("- Matteo · développeur"))
        assertEquals(null, NoteFormat.parseFact("-  "))
    }

    @Test
    fun slugIsAFileName() {
        assertEquals("soeur-de-matteo", NoteFormat.slug("Sœur de Matteo !"))
        assertEquals("note", NoteFormat.slug("???"))
    }
}
