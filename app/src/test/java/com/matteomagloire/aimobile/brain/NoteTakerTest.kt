package com.matteomagloire.aimobile.brain

import com.matteomagloire.aimobile.chat.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

/** Le modèle remplacé par des réponses écrites d'avance, étape par étape. */
class FakeRunner(vararg answers: String?) : TaskRunner {
    private val queue = ArrayDeque(answers.toList())
    val prompts = mutableListOf<String>()
    val grammars = mutableListOf<String>()
    var instructions: TaskInstructions? = null
    var canBegin = true
    var begun = 0
    var ended = 0
    var job: Job? = null

    override suspend fun begin(instructions: TaskInstructions): Boolean {
        begun++
        this.instructions = instructions
        return canBegin
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

    private fun think(
        runner: FakeRunner,
        message: String,
        notes: List<Note> = emptyList(),
        forgotten: List<String> = emptyList(),
        previousReply: String = "",
        forced: Boolean = false,
    ) = runBlocking { taker.think(runner, message, forced, notes, forgotten, previousReply) }

    @Test
    fun nothingToRemember() {
        val runner = FakeRunner("aucun")
        assertEquals(Thought.Empty, think(runner, "J'ai faim, je vais me faire des pâtes ce soir."))
        assertEquals(1, runner.prompts.size)
        assertEquals(1 to 1, runner.begun to runner.ended)
        // Le modèle lit les consignes et des exemples, puis seulement ce message.
        assertEquals(taker.instructions, runner.instructions)
        assertEquals("Message de Matteo : « J'ai faim, je vais me faire des pâtes ce soir. »", runner.prompts.single())
    }

    @Test
    fun factsGoToMeOrToTheirOwnNote() {
        val runner = FakeRunner("Moi : Matteo a 23 ans.\nPixel (animal) : Pixel est le chat de Matteo, il a 3 ans.")
        val thought = think(runner, "J'ai 23 ans et un chat, Pixel, qui a 3 ans.") as Thought.Facts
        assertEquals(
            listOf(
                PlannedFact("Matteo a 23 ans.", Note.ME_ID, Note.ME_TITLE),
                PlannedFact("Pixel est le chat de Matteo, il a 3 ans.", null, "Pixel", "animal"),
            ),
            thought.facts,
        )
        // Une seule étape : la note est choisie en même temps que le fait.
        assertEquals(1, runner.prompts.size)
    }

    @Test
    fun anExistingNoteIsCompleted() {
        val pixel = Note("pixel", "Pixel", listOf("animal"), day, day, facts = listOf(Fact("Pixel est le chat de Matteo.", day)))
        val lea = Note("lea", "Léa", listOf("personne"), day, day, facts = listOf(Fact("Léa est la sœur de Matteo.", day)))
        val notes = listOf(me(), pixel, lea)
        val runner = FakeRunner("Pixel (animal) : Pixel adore dormir sur le clavier.\nMa sœur Léa (personne) : Léa vient d'avoir son bac.")
        val facts = (think(runner, "Pixel adore dormir sur mon clavier, et Léa vient d'avoir son bac !", notes) as Thought.Facts).facts
        assertEquals(listOf("pixel", "lea"), facts.map { it.noteId })
        // Une note « Matteo » est la note « Moi ».
        val runner2 = FakeRunner("Matteo (personne) : Matteo est gaucher.")
        assertEquals(Note.ME_ID, (think(runner2, "Je suis gaucher.") as Thought.Facts).facts.single().noteId)
    }

    @Test
    fun aChangedSituationGoesToBefore() {
        val runner = FakeRunner("Moi : Matteo vit à Paris.", "1")
        val notes = listOf(me("Matteo habite à Lyon.", "Matteo a 23 ans."))
        val thought = think(runner, "Bon, finalement j'ai déménagé : maintenant je vis à Paris.", notes) as Thought.Facts
        assertEquals("Matteo habite à Lyon.", thought.facts.single().replaces)
        // « vit » et « habite » se ressemblent : la question est posée, sans les faits sans rapport.
        assertTrue(runner.prompts[1].contains("1. Matteo habite à Lyon."))
        assertFalse(runner.prompts[1].contains("23 ans"))
        assertEquals("root ::= \"aucun\" | \"Aucun\" | \"1\"\n", runner.grammars[1])
    }

    @Test
    fun unrelatedFactsDoNotAsk() {
        val runner = FakeRunner("Moi : Matteo aime le jazz.")
        val thought = think(runner, "J'aime le jazz.", listOf(me("Matteo a 23 ans."))) as Thought.Facts
        assertNull(thought.facts.single().replaces)
        assertEquals(1, runner.prompts.size)
    }

    @Test
    fun knownAndForgottenFactsAreNotNotedAgain() {
        val runner = FakeRunner("Moi : Matteo a 23 ans.\nMoi : Matteo a un chat qui s'appelle Pixel.")
        val thought = think(
            runner,
            "J'ai 23 ans et un chat qui s'appelle Pixel.",
            listOf(me("Matteo a 23 ans.")),
            forgotten = listOf("Matteo a un chat qui s'appelle Pixel."),
        )
        assertEquals(Thought.Known(listOf("Matteo a 23 ans.", "Matteo a un chat qui s'appelle Pixel.")), thought)
        // Le chat revient avec du neuf : ce n'est plus le fait oublié.
        val runner2 = FakeRunner("Moi : Matteo a un chat, Pixel, qui est malade.")
        assertTrue(think(runner2, "Mon chat Pixel est malade.", forgotten = listOf("Matteo a un chat qui s'appelle Pixel.")) is Thought.Facts)
    }

    @Test
    fun aMorePreciseFactReplacesTheOldOne() {
        val runner = FakeRunner("Moi : Matteo a une sœur, Léa, qui a eu son bac.")
        val fact = (think(runner, "Ma sœur Léa a eu son bac.", listOf(me("Matteo a une sœur, Léa."))) as Thought.Facts).facts.single()
        assertEquals(listOf("Matteo a une sœur, Léa."), fact.supersedes)
        assertNull(fact.replaces)
        assertEquals(1, runner.prompts.size)
    }

    @Test
    fun whatTheMessageDoesNotSayIsDropped() {
        // Le modèle reprend ce qu'il sait déjà, ou invente : rien de cela n'est dans le message.
        val runner = FakeRunner("Moi : Matteo a 23 ans.\nMoi : Matteo n'a pas d'animaux.\nMoi : Matteo travaille comme développeur.")
        assertEquals(Thought.Empty, think(runner, "J'ai faim, je crois que je vais me faire des pâtes ce soir."))
        assertEquals(1, runner.ended)
    }

    @Test
    fun passingThingsGuessesAndCommentsAreDropped() {
        fun kept(fact: String, message: String, forced: Boolean = false) = taker.keep(fact, TextMatch.words(message), forced)
        assertFalse(kept("Matteo a faim ce soir.", "J'ai faim ce soir"))
        assertFalse(kept("Matteo se demande s'il va pleuvoir demain.", "Je me demande s'il va pleuvoir demain."))
        assertFalse(kept("Matteo semble aimer les pâtes.", "Des pâtes ce soir"))
        assertFalse(kept("Matteo n'a pas de travail mentionné.", "Mon travail ?"))
        assertFalse(kept("Matteo parle souvent à Axo de son chat.", "Je te parle de mon chat"))
        assertFalse(kept("Matteo s'appelle Matteo.", "Je m'appelle Matteo"))
        assertTrue(kept("Matteo travaille comme développeur.", "Je bosse comme développeur."))
        // « Retiens que… » : il l'a demandé, même pour demain.
        assertTrue(kept("Matteo a rendez-vous chez le dentiste demain.", "j'ai rendez-vous chez le dentiste demain", forced = true))
    }

    @Test
    fun aShortAnswerIsReadWithAxosQuestion() {
        val reply = "Cool ! Tu travailles plutôt sur Android ou sur iPhone ?"
        val runner = FakeRunner("Moi : Matteo travaille surtout sur Android.")
        val thought = think(runner, "Sur Android, surtout.", previousReply = reply) as Thought.Facts
        assertEquals("Matteo travaille surtout sur Android.", thought.facts.single().text)
        assertEquals(
            "Axo lui a demandé : « Tu travailles plutôt sur Android ou sur iPhone ? »\nMessage de Matteo : « Sur Android, surtout. »",
            runner.prompts.single(),
        )
        // La question ne compte que pour une réponse courte.
        assertNull(taker.questionBefore("Je bosse comme développeur dans une petite boîte qui fait des applis mobiles depuis deux ans.", reply))
        assertNull(taker.questionBefore("Oui.", "Bonne idée, les pâtes c'est une valeur sûre !"))
        assertEquals("Tu lui as prévu un cadeau ?", taker.questionBefore("Pas encore.", "Bravo à Léa ! Tu lui as prévu un cadeau ?"))
    }

    @Test
    fun theRequestToRememberIsAlwaysNoted() {
        val runner = FakeRunner("Moi : Le code du vélo de Matteo est 4721.")
        val thought = think(runner, "le code de mon vélo, c'est 4721", forced = true, previousReply = "Tu fais du vélo ?") as Thought.Facts
        assertEquals("Le code du vélo de Matteo est 4721.", thought.facts.single().text)
        assertEquals("Matteo te demande de retenir : « le code de mon vélo, c'est 4721 »", runner.prompts.single())
        assertFalse(runner.grammars.single().contains("aucun", ignoreCase = true))
    }

    @Test
    fun linesAreParsedAndTidied() {
        assertEquals(
            listOf(
                NoteTaker.Line(null, null, "Matteo aime le jazz."),
                NoteTaker.Line("Rocky", "animal", "Rocky a peur de l'aspirateur."),
            ),
            taker.parseLines("Moi : Matteo   aime le jazz.\nRocky (animal) : rocky a peur de l'aspirateur.\nMoi : Matteo est en train d'écri"),
        )
        assertEquals(emptyList<NoteTaker.Line>(), taker.parseLines("aucun"))
        assertEquals(emptyList<NoteTaker.Line>(), taker.parseLines("Aucun"))
        assertEquals(emptyList<NoteTaker.Line>(), taker.parseLines("- Matteo : *23 ans* – Âge fixe."))
        // Un type inconnu : la note est créée sans étiquette.
        val runner = FakeRunner("Pixel (chat) : Pixel est roux.")
        assertNull((think(runner, "Pixel est roux.") as Thought.Facts).facts.single().tag)
    }

    @Test
    fun aFailedStepIsReported() {
        val runner = FakeRunner(null)
        assertEquals(Thought.Failed("lecture"), think(runner, "J'adore le jazz."))
        assertEquals(1, runner.ended)
        val refused = FakeRunner().apply { canBegin = false }
        assertEquals(Thought.Failed("début"), think(refused, "J'adore le jazz."))
        assertEquals(0, refused.prompts.size)
    }

    @Test
    fun anInterruptionNotesNothingAndCleansUp() {
        val runner = FakeRunner("Moi : Matteo aime le jazz.", FakeRunner.INTERRUPT)
        try {
            runBlocking {
                withContext(Job()) {
                    runner.job = coroutineContext[Job]
                    taker.think(runner, "J'adore le jazz", false, listOf(me("Matteo aime le rap.")), emptyList())
                }
            }
            fail("interruption attendue")
        } catch (e: CancellationException) {
            // Attendu.
        }
        assertEquals(2, runner.prompts.size)
        assertEquals(1, runner.ended)
    }

    @Test
    fun grammarsAreWellFormed() {
        val grammar = taker.readGrammar(forced = false)
        assertTrue(grammar, grammar.startsWith("root ::= \"aucun\" | \"Aucun\" | line (\"\\n\" line)? (\"\\n\" line)?\n"))
        assertTrue(grammar, grammar.contains("note ::= \"Moi\" | name \" (\" tag \")\"\n"))
        assertTrue(grammar, grammar.contains("tag ::= \"personne\" | \"animal\" | \"lieu\""))
        // Ni « : », ni parenthèses, ni mise en forme dans un fait, qui finit par un point.
        val fact = grammar.lines().first { it.startsWith("fact ::= ") }
        assertTrue(fact, fact.contains("[^\\n:()«»\"*#_\\[\\]–—]{3,150} \".\""))
        assertFalse(taker.readGrammar(forced = true).contains("aucun", ignoreCase = true))
    }

    @Test
    fun theExamplesFollowTheirOwnRules() {
        val messages = taker.instructions.messages
        assertEquals(1 + 2 * taker.instructions.examples.size, messages.size)
        assertTrue(messages.first().startsWith("Tu tiens le carnet de notes d'Axo sur Matteo."))
        for ((prompt, answer) in taker.instructions.examples) {
            if (prompt.startsWith("Nouveau fait")) {
                // Étape 2 : des faits que le code jugerait ressemblants, et un numéro de la liste ou « aucun ».
                val fact = TextMatch.words(prompt.substringAfter("« ").substringBefore(" »"))
                val listed = prompt.lines().filter { it.matches(Regex("\\d+\\. .+")) }.map { TextMatch.words(it.substringAfter(". ")) }
                assertTrue(prompt, listed.isNotEmpty() && listed.all { TextMatch.similarity(fact, it) >= NoteTaker.SIMILAR })
                assertTrue(answer, answer == NoteTaker.NOTHING || answer.toInt() in 1..listed.size)
                continue
            }
            if (answer == NoteTaker.NOTHING) continue
            val lines = taker.parseLines(answer)
            assertEquals(answer, answer.split('\n').size, lines.size)
            val message = prompt.substringAfterLast("« ").substringBefore(" »")
            val question = prompt.substringAfter("Axo lui a demandé : « ", "").substringBefore(" »", "")
            val forced = prompt.contains("te demande de retenir")
            for (l in lines) assertTrue(l.fact, taker.keep(l.fact, TextMatch.words(message) + TextMatch.words(question), forced))
            for (l in lines) assertTrue(l.note, l.note == null || l.tag in NoteTaker.TAGS)
        }
        // Les exemples ne reprennent pas le Test du Cerveau.
        val examples = taker.instructions.examples.joinToString(" ") { TextMatch.fold(it.first) }
        for (word in listOf("pixel", "lyon", "paris", "lea", "jazz", "4721", "android", "pates")) assertFalse(word, examples.contains(word))
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
