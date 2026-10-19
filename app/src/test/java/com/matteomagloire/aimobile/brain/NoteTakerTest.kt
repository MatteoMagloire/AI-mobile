package com.matteomagloire.aimobile.brain

import com.matteomagloire.aimobile.chat.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

/** Le modèle remplacé par des réponses écrites d'avance, étape par étape. */
class FakeRunner(vararg answers: String?) : TaskRunner {
    private val queue = ArrayDeque(answers.toList())
    val prompts = mutableListOf<String>()
    val grammars = mutableListOf<String>()
    var begun = 0
    var ended = 0
    var job: Job? = null

    override suspend fun begin(reserve: Int): Boolean {
        begun++
        return true
    }

    override suspend fun step(prompt: String, grammar: String, maxTokens: Int): String? {
        prompts += prompt
        grammars += grammar
        // Une interruption : l'étape s'arrête et renvoie null, comme le moteur.
        if (queue.firstOrNull() == INTERRUPT) {
            job?.cancel()
            return null
        }
        return if (queue.isEmpty()) error("étape imprévue : $prompt") else queue.removeFirst()
    }

    override suspend fun end() {
        ended++
    }

    companion object {
        const val INTERRUPT = "<interruption>"
    }
}

class NoteTakerTest {
    private val day = LocalDate.of(2026, 10, 19)
    private val taker = NoteTaker()

    private fun me(vararg facts: String) = Note(Note.ME_ID, Note.ME_TITLE, listOf(Note.ME_TAG), day, day, facts = facts.map { Fact(it, day) })

    private fun think(runner: FakeRunner, notes: List<Note> = emptyList(), forgotten: List<String> = emptyList(), message: String = "message") =
        runBlocking { taker.think(runner, message, forced = false, notes = notes, forgotten = forgotten) }

    @Test
    fun nothingToRemember() {
        val runner = FakeRunner("aucun")
        assertEquals(Thought.Empty, think(runner))
        assertEquals(1, runner.prompts.size)
        assertEquals(1 to 1, runner.begun to runner.ended)
    }

    @Test
    fun factsArePlacedInNotes() {
        val runner = FakeRunner(
            "- Matteo a 23 ans\n- Matteo a un chat qui s'appelle Pixel.\n",
            "1. Moi\n2. nouvelle note : pixel (animal)\n",
        )
        val thought = think(runner) as Thought.Facts
        assertEquals(
            listOf(
                PlannedFact("Matteo a 23 ans.", Note.ME_ID, Note.ME_TITLE),
                PlannedFact("Matteo a un chat qui s'appelle Pixel.", null, "Pixel", "animal"),
            ),
            thought.facts,
        )
        assertTrue(runner.prompts[1].contains("Pas encore d'autre note."))
        assertTrue(runner.grammars[1], runner.grammars[1].startsWith("root ::= \"1. \" choice \"\\n\" \"2. \" choice \"\\n\"\n"))
        assertEquals(1, runner.ended)
    }

    @Test
    fun aNewNoteNamedLikeAnExistingOneCompletesIt() {
        val pixel = Note("pixel", "Pixel", listOf("animal"), day, day, facts = listOf(Fact("Matteo a un chat qui s'appelle Pixel.", day)))
        val runner = FakeRunner("- Matteo dit que Pixel a 3 ans.\n", "1. nouvelle note : Pixel (animal)\n")
        val thought = think(runner, listOf(me(), pixel)) as Thought.Facts
        assertEquals("pixel", thought.facts.single().noteId)
        // Une nouvelle note « Matteo » est la note « Moi ».
        val runner2 = FakeRunner("- Matteo est gaucher.\n", "1. nouvelle note : Matteo (personne)\n")
        assertEquals(Note.ME_ID, (think(runner2) as Thought.Facts).facts.single().noteId)
    }

    @Test
    fun existingNotesAreOfferedFirst() {
        val pixel = Note("pixel", "Pixel", listOf("animal"), day, day, facts = listOf(Fact("Matteo a un chat qui s'appelle Pixel.", day)))
        val other = Note("velo", "Vélo", emptyList(), day, day, facts = listOf(Fact("Matteo fait du vélo le dimanche.", day)))
        val runner = FakeRunner("- Matteo a emmené son chat chez le vétérinaire.\n", "1. Pixel\n")
        val thought = think(runner, listOf(me(), pixel, other)) as Thought.Facts
        assertEquals("pixel", thought.facts.single().noteId)
        assertTrue(runner.prompts[1].contains("Autres notes : « Pixel »."))
        assertTrue(runner.grammars[1].contains("choice ::= \"Moi\" | \"Pixel\" | \"nouvelle note : \""))
    }

    @Test
    fun aChangedSituationGoesToBefore() {
        val runner = FakeRunner("- Matteo habite à Paris.\n", "1. Moi\n", "1")
        val thought = think(runner, listOf(me("Matteo habite à Lyon.", "Matteo a 23 ans."))) as Thought.Facts
        assertEquals("Matteo habite à Lyon.", thought.facts.single().replaces)
        assertTrue(runner.prompts[2].contains("1. Matteo habite à Lyon."))
        assertFalse(runner.prompts[2].contains("23 ans"))
        assertEquals("root ::= \"aucun\" | \"1\"\n", runner.grammars[2])
    }

    @Test
    fun unrelatedFactsDoNotAsk() {
        val runner = FakeRunner("- Matteo aime le jazz.\n", "1. Moi\n")
        val thought = think(runner, listOf(me("Matteo a 23 ans."))) as Thought.Facts
        assertEquals(null, thought.facts.single().replaces)
        assertEquals(2, runner.prompts.size)
    }

    @Test
    fun knownAndForgottenFactsAreNotNotedAgain() {
        val runner = FakeRunner("- Matteo a 23 ans.\n- Matteo a un chat qui s'appelle Pixel.\n")
        val thought = think(runner, listOf(me("Matteo a 23 ans.")), forgotten = listOf("Matteo a un chat qui s'appelle Pixel."))
        assertEquals(Thought.Known(listOf("Matteo a 23 ans.", "Matteo a un chat qui s'appelle Pixel.")), thought)
        // Le chat revient avec du neuf : ce n'est plus le fait oublié.
        val runner2 = FakeRunner("- Matteo a un chat, Pixel, qui est malade.\n", "1. Moi\n")
        assertTrue(think(runner2, forgotten = listOf("Matteo a un chat qui s'appelle Pixel.")) is Thought.Facts)
    }

    @Test
    fun aMorePreciseFactReplacesTheOldOne() {
        val runner = FakeRunner("- Matteo a une sœur, Léa, qui a eu son bac.\n", "1. Moi\n")
        val fact = (think(runner, listOf(me("Matteo a une sœur, Léa."))) as Thought.Facts).facts.single()
        assertEquals(listOf("Matteo a une sœur, Léa."), fact.supersedes)
        assertEquals(null, fact.replaces)
        assertEquals(2, runner.prompts.size)
    }

    @Test
    fun repeatedAndIncompleteLinesAreDropped() {
        assertEquals(
            listOf("Matteo aime le jazz."),
            taker.parseFacts("- Matteo aime le jazz.\n- Matteo aime le jazz\n- Matteo est en train d'écri"),
        )
        assertEquals(emptyList<String>(), taker.parseFacts("aucun"))
    }

    @Test
    fun anUnreadablePlacementGoesToMe() {
        val runner = FakeRunner("- Matteo aime le jazz.\n", null)
        assertEquals(Note.ME_ID, (think(runner) as Thought.Facts).facts.single().noteId)
    }

    @Test
    fun aFailedStepIsReported() {
        val runner = FakeRunner(null)
        assertEquals(Thought.Failed("lecture"), think(runner))
        assertEquals(1, runner.ended)
    }

    @Test
    fun anInterruptionNotesNothingAndCleansUp() {
        val runner = FakeRunner("- Matteo aime le jazz.\n", FakeRunner.INTERRUPT)
        try {
            runBlocking {
                withContext(Job()) {
                    runner.job = coroutineContext[Job]
                    taker.think(runner, "J'adore le jazz", false, emptyList(), emptyList())
                }
            }
            fail("interruption attendue")
        } catch (e: CancellationException) {
            // Attendu.
        }
        assertEquals(1, runner.ended)
    }

    @Test
    fun grammarsAreWellFormed() {
        assertEquals(
            "root ::= \"aucun\" | fact | fact fact | fact fact fact\nfact ::= \"- Matteo \" [^\\n]+ \"\\n\"\n",
            taker.extractGrammar(forced = false),
        )
        assertFalse(taker.extractGrammar(forced = true).contains("aucun"))
        val grammar = taker.placeGrammar(1, listOf(NoteTaker.Target(Note.ME_ID, "Moi", null), NoteTaker.Target("x", "Le \"club\"", null)))
        assertTrue(grammar, grammar.contains("\"Le \\\"club\\\"\""))
        assertTrue(grammar, grammar.contains("tag ::= \"personne\" | \"animal\""))
    }

    @Test
    fun theRequestToRememberIsQuoted() {
        val prompt = taker.extractPrompt("le code de mon vélo, c'est 4721", forced = true)
        assertTrue(prompt.contains("« le code de mon vélo, c'est 4721 »"))
        assertFalse(prompt.contains("aucun"))
    }

    @Test
    fun pendingExchangesAreThoseAnsweredAfterTheLastReading() {
        val messages = listOf(
            ChatMessage(true, "ancien", time = 10),
            ChatMessage(false, "réponse ?", time = 11),
            ChatMessage(true, "nouveau", time = 20),
            ChatMessage(false, "réponse 2", time = 21),
            ChatMessage(true, "sans réponse", time = 30),
        )
        assertEquals(listOf(Exchange(20, "nouveau", "réponse ?")), NoteTaker.pending(messages, processedUntil = 10))
        assertEquals(2, NoteTaker.pending(messages, processedUntil = 0).size)
        assertEquals(listOf("nouveau"), NoteTaker.pending(messages, processedUntil = 0, max = 1).map { it.message })
    }
}
