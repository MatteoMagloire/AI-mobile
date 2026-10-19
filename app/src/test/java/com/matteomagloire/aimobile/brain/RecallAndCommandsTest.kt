package com.matteomagloire.aimobile.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RecallAndCommandsTest {
    private val day = LocalDate.of(2026, 10, 19)

    private val notes = listOf(
        Note(
            Note.ME_ID, Note.ME_TITLE, listOf(Note.ME_TAG), day, day,
            facts = listOf(Fact("Matteo a 23 ans.", day), Fact("Matteo habite à Paris.", day), Fact("Matteo aime le jazz.", day)),
            before = listOf(Fact("Matteo habite à Lyon.", day, day)),
        ),
        Note("pixel", "Pixel", listOf("animal"), day, day, facts = listOf(Fact("Matteo a un chat qui s'appelle Pixel.", day), Fact("Il adore dormir sur le clavier.", day))),
        Note("lea", "Léa", listOf("personne"), day, day, facts = listOf(Fact("Matteo a une sœur, Léa, qui a eu son bac.", day))),
    )

    @Test
    fun recallFindsTheNoteByItsTitle() {
        val hits = Recall.search(notes, "Comment va Pixel en ce moment ?").map { it.fact.text }
        // « Il adore dormir… » ne dit pas « Pixel », mais il est dans sa note.
        assertEquals(setOf("Matteo a un chat qui s'appelle Pixel.", "Il adore dormir sur le clavier."), hits.toSet())
    }

    @Test
    fun recallIgnoresWhatIsNoLongerTrueAndUnrelatedMessages() {
        assertEquals(listOf("Matteo habite à Paris."), Recall.search(notes, "Tu te souviens où j'habite ?").map { it.fact.text })
        assertEquals(emptyList<Recall.Hit>(), Recall.search(notes, "Il fait beau aujourd'hui"))
        assertEquals(emptyList<Recall.Hit>(), Recall.search(notes, "ok"))
    }

    @Test
    fun recallSkipsWhatWasJustGiven() {
        val given = setOf("Matteo a un chat qui s'appelle Pixel.")
        assertEquals(listOf("Il adore dormir sur le clavier."), Recall.search(notes, "Et Pixel ?", exclude = given).map { it.fact.text })
    }

    @Test
    fun questionsAboutMe() {
        assertTrue(Recall.asksAboutMe("Tu te souviens de mon âge ?"))
        assertTrue(Recall.asksAboutMe("Qu'est-ce que tu sais sur moi ?"))
        assertFalse(Recall.asksAboutMe("Tu sais faire des crêpes ?"))
        assertEquals(listOf("Matteo aime le jazz.", "Matteo habite à Paris."), Recall.aboutMe(notes, 2).map { it.text })
    }

    @Test
    fun memoryBlock() {
        assertEquals("", Recall.block(emptyList()))
        val block = Recall.block(listOf("Matteo a 23 ans."))
        assertEquals("[Mémoire d'Axo : ce que tu sais déjà sur Matteo]\n- Matteo a 23 ans.\n[Message de Matteo]\n", block)
        assertEquals(setOf("Matteo a 23 ans."), Recall.alreadyGiven(listOf(block, "")))
        assertTrue(Recall.block(emptyList(), nothingToForget = true).contains("rien noté"))
        assertTrue(Recall.block(emptyList(), remember = true).endsWith("[Message de Matteo]\n"))
    }

    @Test
    fun rememberRequests() {
        assertEquals(BrainCommand.Remember("le code de mon vélo, c'est 4721"), BrainCommands.parse("Retiens que le code de mon vélo, c'est 4721."))
        assertEquals(BrainCommand.Remember("j'ai rendez-vous mardi"), BrainCommands.parse("Axo, n'oublie pas que j'ai rendez-vous mardi stp"))
        assertEquals(BrainCommand.Remember("je suis allergique aux noix"), BrainCommands.parse("note bien que je suis allergique aux noix"))
        assertEquals(BrainCommand.Remember("je pars en Italie"), BrainCommands.parse("oublie pas que je pars en Italie"))
        assertEquals(BrainCommand.Remember("mon numéro de casier : 12"), BrainCommands.parse("Mémorise mon numéro de casier : 12"))
    }

    @Test
    fun forgetRequests() {
        assertEquals(BrainCommand.Forget("j'ai un chat"), BrainCommands.parse("Oublie que j'ai un chat."))
        assertEquals(BrainCommand.Forget("Pixel"), BrainCommands.parse("oublie Pixel"))
        assertEquals(BrainCommand.Forget(""), BrainCommands.parse("oublie ça"))
        assertEquals(BrainCommand.Forget(""), BrainCommands.parse("Efface-le de ta mémoire !"))
        assertEquals(BrainCommand.Forget("ma sœur"), BrainCommands.parse("Axo, efface ce que je t'ai dit sur ma sœur de ta mémoire"))
    }

    @Test
    fun ordinaryMessagesAreNotRequests() {
        assertNull(BrainCommands.parse("J'ai oublié mes clés ce matin"))
        assertNull(BrainCommands.parse("Tu te souviens de mon chat ?"))
        assertNull(BrainCommands.parse("Je note tout dans un carnet"))
        assertNull(BrainCommands.parse("Retiens"))
    }

    @Test
    fun onlyMessagesAboutTheUserAreRead() {
        assertFalse(BrainCommands.worthReading("ok merci", ""))
        assertFalse(BrainCommands.worthReading("C'est quoi la capitale du Pérou ?", ""))
        assertTrue(BrainCommands.worthReading("J'ai un chat qui s'appelle Pixel", ""))
        assertTrue(BrainCommands.worthReading("Sur Android, surtout.", "Cool ! Tu travailles plutôt sur Android ou sur iPhone ?"))
        assertFalse(BrainCommands.worthReading("Sur Android, surtout.", "Android est un système de Google."))
    }
}
