package com.matteomagloire.aimobile.brain

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

class NoteStoreTest {
    private val day = LocalDate.of(2026, 10, 19)
    private val dir: File = Files.createTempDirectory("cerveau").toFile()
    private var now = 1_000L
    private fun store() = NoteStore(dir, today = { day }, clock = { now })

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private val noted = Thought.Facts(
        listOf(
            PlannedFact("Matteo a 23 ans.", Note.ME_ID, Note.ME_TITLE),
            PlannedFact("Matteo a un chat qui s'appelle Pixel.", null, "Pixel", "animal"),
        ),
        known = listOf("Matteo aime le jazz."),
    )

    /** Un modèle qui répond toujours la même chose. */
    private class Model(private val thought: Thought?) : ModelAccess {
        var calls = 0
        override suspend fun <T> withModel(block: suspend (TaskRunner) -> T): T? {
            calls++
            if (thought == null) return null
            @Suppress("UNCHECKED_CAST")
            return thought as T
        }
    }

    @Test
    fun theFirstOpeningSkipsThePastConversation() = runBlocking {
        val s = store()
        s.load()
        assertEquals(1_000L, s.processedUntil)
        now = 5_000
        assertEquals(1_000L, store().also { it.load() }.processedUntil)
    }

    @Test
    fun recordedFactsAreWrittenAndReadBack() = runBlocking {
        val s = store()
        val entry = s.record(Exchange(2_000, "J'ai 23 ans et un chat, Pixel"), "J'ai 23 ans et un chat, Pixel", noted, 1234)
        assertEquals(NoteOutcome.NOTED, entry.outcome)
        assertEquals(listOf("moi", "pixel"), entry.noted.map { it.noteId })
        assertTrue(entry.noted[1].newNote)
        assertEquals(2_000L, s.processedUntil)
        assertTrue(File(dir, "notes/pixel.md").readText().contains("# Pixel"))

        val again = store()
        again.load()
        assertEquals(s.notes.value, again.notes.value)
        assertEquals(s.journal.value, again.journal.value)
        assertEquals(2_000L, again.processedUntil)
        assertEquals(listOf("Moi", "Pixel"), again.notes.value.map { it.title })
    }

    @Test
    fun undoRemovesTheFactsAndRemembersNotToNoteThemAgain() = runBlocking {
        val s = store()
        val entry = s.record(Exchange(2_000, "m"), "m", noted, 0)
        s.undo(entry)
        assertEquals(listOf(Note.ME_ID), s.notes.value.map { it.id })
        assertEquals(emptyList<Fact>(), s.notes.value.single().facts)
        assertTrue(s.journal.value.first().undone)
        assertEquals(listOf("Matteo a 23 ans.", "Matteo a un chat qui s'appelle Pixel."), s.forgotten())
    }

    @Test
    fun forgetThatUndoesTheLastNotes() = runBlocking {
        val s = store()
        s.record(Exchange(2_000, "m"), "m", noted, 0)
        val forgetting = s.forget("", 3_000, "oublie ça")
        assertEquals(listOf("Matteo a un chat qui s'appelle Pixel.", "Matteo a 23 ans."), forgetting.texts)
        assertFalse(s.notes.value.any { it.id == "pixel" })
        assertEquals(NoteOutcome.FORGOTTEN, s.journal.value.first().outcome)
        // « Annuler » : tout revient.
        s.restore(forgetting)
        assertEquals(setOf("moi", "pixel"), s.notes.value.map { it.id }.toSet())
        assertEquals(1, s.notes.value.first { it.isMe }.facts.size)
        assertEquals(emptyList<String>(), s.forgotten())
        // Plus rien à oublier : la prise de notes est marquée annulée.
        assertTrue(s.forget("", 4_000, "oublie ça").isEmpty)
    }

    @Test
    fun forgetByWords() = runBlocking {
        val s = store()
        s.record(Exchange(2_000, "m"), "m", noted, 0)
        val forgetting = s.forget("Pixel", 3_000, "oublie Pixel")
        assertEquals(listOf("pixel"), forgetting.notes.map { it.id })
        assertEquals(listOf("Matteo a un chat qui s'appelle Pixel."), s.forgotten())
        assertTrue(s.forget("mon vélo", 3_500, "oublie mon vélo").isEmpty)
    }

    @Test
    fun handEditsAreRead() = runBlocking {
        val s = store()
        s.record(Exchange(2_000, "m"), "m", noted, 0)
        val saved = s.rewrite("pixel", "# Pixel le chat\n\n## Faits\n- Pixel est roux.\n")
        assertEquals("Pixel le chat", saved?.title)
        assertEquals(listOf(Fact("Pixel est roux.")), s.notes.value.first { it.id == "pixel" }.facts)
        // Sans titre, la note garde le sien.
        assertEquals("Pixel le chat", s.rewrite("pixel", "## Faits\n- Pixel est roux.\n")?.title)
        assertNull(s.rewrite("absente", "# X"))
        // « Moi » garde son titre.
        assertEquals("Moi", s.rewrite(Note.ME_ID, "# Autre\n\n## Faits\n- Matteo a 24 ans.\n")?.title)
    }

    @Test
    fun factsCorrectedOrAddedByHand() = runBlocking {
        val s = store()
        s.record(Exchange(2_000, "m"), "m", noted, 0)
        val age = s.notes.value.first { it.isMe }.facts.single()
        s.editFact(Note.ME_ID, age, "  Matteo a   24 ans. ")
        assertEquals(listOf(Fact("Matteo a 24 ans.", null)), s.notes.value.first { it.isMe }.facts.map { it.copy(date = null) })
        // La version corrigée ne sera pas renotée.
        assertEquals(listOf("Matteo a 23 ans."), s.forgotten())
        s.editFact("pixel", null, "Pixel est roux.")
        assertEquals(Fact("Pixel est roux.", day), s.notes.value.first { it.id == "pixel" }.facts.last())
        // Rien de changé : rien d'écrit.
        val before = s.notes.value
        s.editFact("pixel", Fact("Pixel est roux.", day), "Pixel est roux.")
        s.editFact("pixel", null, "   ")
        assertEquals(before, s.notes.value)
        // Vidé : supprimé.
        s.editFact("pixel", Fact("Pixel est roux.", day), "")
        assertFalse(s.notes.value.first { it.id == "pixel" }.facts.any { it.text == "Pixel est roux." })
        assertEquals(s.notes.value, store().also { it.load() }.notes.value)
    }

    @Test
    fun clearingTheBrainDoesNotRereadThePast() = runBlocking {
        val s = store()
        s.record(Exchange(2_000, "m"), "m", noted, 0)
        now = 9_000
        s.clear()
        assertEquals(emptyList<Note>(), s.notes.value)
        assertEquals(emptyList<JournalEntry>(), s.journal.value)
        assertEquals(emptyList<String>(), s.forgotten())
        assertEquals(9_000L, s.processedUntil)
        assertEquals(9_000L, store().also { it.load() }.processedUntil)
    }

    @Test
    fun deletingAFactOrANote() = runBlocking {
        val s = store()
        s.record(Exchange(2_000, "m"), "m", noted, 0)
        val me = s.notes.value.first { it.isMe }
        s.deleteFact(me.id, me.facts.single(), wasBefore = false)
        assertEquals(emptyList<Fact>(), s.notes.value.first { it.isMe }.facts)
        s.deleteNote("pixel")
        s.deleteNote(Note.ME_ID)
        assertEquals(listOf(Note.ME_ID), s.notes.value.map { it.id })
        assertEquals(listOf("Matteo a 23 ans.", "Matteo a un chat qui s'appelle Pixel."), s.forgotten())
    }

    @Test
    fun readingAnExchange() = runBlocking {
        val s = store()
        s.load()
        val taker = NoteTaker()
        // Trop court : pas lu par le modèle, mais marqué lu.
        val model = Model(noted)
        val ignored = taker.read(Exchange(2_000, "ok merci"), s, model) as Reading.Done
        assertEquals(NoteOutcome.IGNORED, ignored.entry?.outcome)
        assertEquals(0, model.calls)
        assertEquals(2_000L, s.processedUntil)
        // « Oublie… » : déjà fait à l'envoi.
        assertEquals(Reading.Done(null), taker.read(Exchange(3_000, "oublie ça"), s, model))
        assertEquals(3_000L, s.processedUntil)
        // Modèle indisponible : à relire plus tard.
        assertEquals(Reading.Later, taker.read(Exchange(4_000, "J'ai 23 ans et un chat"), s, Model(null)))
        assertEquals(3_000L, s.processedUntil)
        val done = taker.read(Exchange(4_000, "J'ai 23 ans et un chat"), s, model) as Reading.Done
        assertEquals(NoteOutcome.NOTED, done.entry?.outcome)
        assertEquals(4_000L, s.processedUntil)
    }

    @Test
    fun journalSurvivesSpecialCharacters() {
        val entries = listOf(
            JournalEntry(
                5, 4, "un\tmessage\nsur deux lignes \\ fin", NoteOutcome.NOTED,
                noted = listOf(
                    NotedFact("moi", "Moi", "Matteo habite à Paris.", replaced = Fact("Matteo habite à Lyon.", day), superseded = listOf(Fact("x", null))),
                    NotedFact("pixel", "Pixel", "Pixel a 3 ans.", newNote = true),
                ),
                known = listOf("Matteo aime le jazz."),
                ms = 12_345,
                undone = true,
            ),
            JournalEntry(3, 2, "oublie ça", NoteOutcome.FORGOTTEN, forgotten = listOf("a", "b")),
            JournalEntry(1, 0, "ok", NoteOutcome.IGNORED),
        )
        assertEquals(entries, JournalFormat.parse(JournalFormat.write(entries)))
        assertEquals(entries.takeLast(1), JournalFormat.parse("Z\tligne inconnue\n" + JournalFormat.write(entries.takeLast(1))))
    }

    @Test
    fun scenarioScoring() {
        val exchange = BrainScenario.exchanges.first()
        val entry = JournalEntry(1, 1, "m", NoteOutcome.NOTED, noted = listOf(NotedFact("moi", "Moi", "Matteo a 23 ans."), NotedFact("moi", "Moi", "Matteo aime Paris.")))
        val row = BrainScenario.score(exchange, entry, 4_000)
        assertEquals(2 to 1, row.expected to row.found)
        assertEquals(0, row.unexpected)
        val nothing = BrainScenario.exchanges[1]
        assertEquals(2, BrainScenario.score(nothing, entry, 0).unexpected)

        val moved = listOf(
            Note(Note.ME_ID, "Moi", emptyList(), day, day, facts = listOf(Fact("Matteo habite à Paris.")), before = listOf(Fact("Matteo habite à Lyon."))),
        )
        assertTrue(BrainScenario.updateOk(moved))
        assertFalse(BrainScenario.updateOk(listOf(moved[0].copy(before = emptyList()))))

        // Réussi à partir des trois quarts des faits : 7 sur 9 dans le scénario.
        fun result(found: Int) = BrainTestResult(0, "m", listOf(row.copy(expected = 9, found = found)), true, 3, 3, 0.1, emptyList())
        assertEquals(9, BrainScenario.exchanges.sumOf { it.expected.size })
        assertEquals(7, result(7).target)
        assertTrue(result(7).passed)
        assertFalse(result(6).passed)
    }
}
