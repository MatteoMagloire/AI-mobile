package com.matteomagloire.aimobile.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class NoteLinksTest {
    private val day = LocalDate.of(2026, 10, 19)

    private fun note(id: String, title: String, vararg facts: String, links: List<String> = emptyList(), before: List<String> = emptyList()) =
        Note(id, title, emptyList(), day, day, facts = facts.map { Fact(it, day) }, before = before.map { Fact(it) }, links = links)

    // Ce que le Test du Cerveau laisse d'habitude dans les notes.
    private val me = note(
        Note.ME_ID, Note.ME_TITLE,
        "Matteo a 23 ans.", "Matteo a un chat qui s'appelle Pixel.", "Matteo a une sœur, Léa.",
        before = listOf("Matteo habite à Lyon."),
    )
    private val pixel = note("pixel", "Pixel", "Pixel est le chat de Matteo.", "Pixel a 3 ans.", "Pixel adore dormir sur le clavier de Matteo.")
    private val lea = note("lea", "Léa", "Léa est la sœur de Matteo.", "Léa vient d'obtenir son bac.")
    private val velo = note("velo", "Vélo", "Le code du cadenas est 4721.")
    private val notes = listOf(me, pixel, lea, velo)

    @Test
    fun notesThatNameEachOtherAreLinked() {
        val links = NoteLinks.links(notes)
        assertEquals(listOf("moi"), links.linksOf("pixel"))
        assertEquals(listOf("moi"), links.linksOf("lea"))
        assertEquals(listOf("pixel", "lea"), links.linksOf("moi"))
        // Rétroliens : les notes qui la nomment, dans l'ordre des notes.
        assertEquals(listOf("pixel", "lea"), links.backlinksOf("moi"))
        assertEquals(listOf("moi"), links.backlinksOf("pixel"))
        // Une note qui ne nomme personne et que personne ne nomme reste seule.
        assertEquals(emptyList<String>(), links.linksOf("velo"))
        assertEquals(emptyList<String>(), links.backlinksOf("velo"))
        assertEquals(setOf("pixel", "lea"), links.neighbors("moi"))
        assertEquals(listOf("lea" to "moi", "moi" to "pixel"), links.edges)
    }

    @Test
    fun aNoteDoesNotLinkToItself() {
        val links = NoteLinks.links(notes)
        assertFalse("moi" in links.linksOf("moi"))
        assertFalse("pixel" in links.linksOf("pixel"))
    }

    @Test
    fun pastFactsAndFreeTextCountToo() {
        val lyon = note("lyon", "Lyon", "Lyon est une grande ville.")
        val paris = note("paris", "Paris", "Paris est la capitale.").copy(extra = "Voir aussi Lyon.")
        val links = NoteLinks.links(listOf(me, lyon, paris))
        assertEquals(listOf("lyon"), links.linksOf("moi"))
        assertEquals(listOf("lyon"), links.linksOf("paris"))
        assertEquals(listOf("moi", "paris"), links.backlinksOf("lyon"))
    }

    @Test
    fun namesAreFoundWithoutAccentsCaseOrArticles() {
        val index = NoteLinks.Index(
            listOf(
                note("fnac", "La Fnac"),
                note("jeux", "Jeux vidéo"),
                lea,
                note("lui", "Lui"),
                note("a", "A"),
            ),
        )
        assertEquals(listOf("lea", "fnac"), index.mentions("Il a vu LEA chez Fnac.").map { it.noteId })
        assertEquals(listOf("jeux"), index.mentions("Matteo adore le jeu vidéo.").map { it.noteId })
        // « Lui » et « A » ne nomment rien de précis : pas de lien à chaque « lui » ou « a ».
        assertEquals(emptyList<String>(), index.mentions("Il lui a dit oui.").map { it.noteId })
    }

    @Test
    fun theLongestNameWins() {
        val sister = note("soeur", "Sœur de Matteo")
        val index = NoteLinks.Index(listOf(me, sister, lea))
        val text = "La sœur de Matteo s'appelle Léa."
        val spans = index.mentions(text)
        assertEquals(listOf("soeur", "lea"), spans.map { it.noteId })
        assertEquals("sœur de Matteo", text.substring(spans[0].start, spans[0].end))
        assertEquals("Léa", text.substring(spans[1].start, spans[1].end))
    }

    @Test
    fun linkedTextHidesBracketsAndKeepsPlaces() {
        val index = NoteLinks.Index(notes)
        val linked = NoteLinks.linked("Matteo a offert un livre à [[Léa]] et à [[Pixel|son chat]] chez [[Inconnu]].", index, self = "moi")
        assertEquals("Matteo a offert un livre à Léa et à son chat chez Inconnu.", linked.text)
        assertEquals(listOf("lea", "pixel"), linked.spans.map { it.noteId })
        assertEquals(listOf("Léa", "son chat"), linked.spans.map { linked.text.substring(it.start, it.end) })
        assertEquals("Matteo a offert un livre à Léa.", NoteLinks.plain("Matteo a offert un livre à [[Léa]]."))
        assertEquals("Pas de lien ici.", NoteLinks.plain("Pas de lien ici."))
    }

    @Test
    fun aNameIsUnderlinedOnlyTheFirstTimeInANote() {
        val index = NoteLinks.Index(notes)
        val seen = HashSet<String>()
        val first = NoteLinks.linked("Pixel est le chat de Matteo.", index, "pixel", seen)
        val second = NoteLinks.linked("Pixel adore dormir sur le clavier de Matteo.", index, "pixel", seen)
        val written = NoteLinks.linked("Il dort avec [[Matteo]].", index, "pixel", seen)
        assertEquals(listOf("moi"), first.spans.map { it.noteId })
        assertTrue(second.spans.isEmpty())
        // Un lien écrit à la main reste souligné.
        assertEquals(listOf("moi"), written.spans.map { it.noteId })
    }

    @Test
    fun handMadeLinksAreResolvedByTitleFileNameOrFirstName() {
        val course = note("course-a-pied", "Course à pied", "Il court le mardi.", links = listOf("Léa", "pixel", "Matteo", "Personne"))
        val links = NoteLinks.links(notes + course)
        assertEquals(listOf("lea", "pixel", "moi"), links.linksOf("course-a-pied"))
        assertEquals(listOf("moi", "course-a-pied"), links.backlinksOf("lea"))
        assertEquals("course-a-pied", links.index.resolve("course à pied|la course"))
    }

    @Test
    fun linksSurviveWriteAndRead() {
        val n = pixel.copy(links = listOf("Léa", "Course à pied"), used = day, opened = day.minusDays(2))
        val text = NoteFormat.write(n)
        assertTrue(text, text.contains("utilisee: 2026-10-19\n"))
        assertTrue(text, text.contains("## Liens\n- [[Léa]]\n- [[Course à pied]]\n"))
        assertEquals(n, NoteFormat.parse("pixel", text, LocalDate.of(2030, 1, 1)))
        // Écrits à la main : avec ou sans crochets, sans doublon.
        val hand = NoteFormat.parse("x", "# X\n\n## Faits\n- Un fait.\n\n## Liens\n- Léa\n* [[Pixel|le chat]]\n- [[léa]]\n\n", day)
        assertEquals(listOf("Léa", "Pixel"), hand.links)
        assertEquals(listOf(Fact("Un fait.")), hand.facts)
        assertEquals("", hand.extra)
    }

    @Test
    fun aNoteFallsAsleepAfterTheDelayAndWakesUpWhenTouched() {
        val old = note("vieux", "Vieux").copy(created = day.minusDays(40), modified = day.minusDays(31))
        assertTrue(old.isDormant(day, 30))
        assertFalse(old.isDormant(day, 90))
        // 30 jours tout juste : encore active.
        assertFalse(old.copy(modified = day.minusDays(30)).isDormant(day, 30))
        assertFalse(old.copy(opened = day.minusDays(2)).isDormant(day, 30))
        assertFalse(old.copy(used = day).isDormant(day, 30))
        assertEquals(day, old.copy(used = day).lastActive)
        // « Moi » ne s'endort jamais.
        assertFalse(me.copy(created = day.minusDays(400), modified = day.minusDays(400)).isDormant(day, 1))
    }

    @Test
    fun recallPrefersActiveNotesButStillFindsSleepingOnes() {
        val bike = note("velo", "Vélo", "Le vélo de Matteo est bleu.")
        val car = note("voiture", "Voiture", "La voiture de Matteo est bleue.")
        val both = listOf(me, bike, car)
        val awake = Recall.search(both, "Mon vélo et ma voiture, ils sont de quelle couleur ?")
        assertEquals(listOf("velo", "voiture"), awake.map { it.note.id }.sorted())
        val sleepy = Recall.search(both, "Mon vélo et ma voiture, ils sont de quelle couleur ?", dormant = setOf("velo"))
        assertEquals("voiture", sleepy.first().note.id)
        assertEquals("velo", Recall.search(both, "Et mon vélo ?", dormant = setOf("velo")).single().note.id)
    }
}
