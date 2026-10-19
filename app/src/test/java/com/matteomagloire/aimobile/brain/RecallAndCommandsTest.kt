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
    fun recallIgnoresHowTheQuestionIsAsked() {
        // « s'appelle », « comment » : rien sur le sujet ; « chat », si.
        assertEquals(emptyList<Recall.Hit>(), Recall.search(notes, "tu sais comment je m'appelle ?"))
        assertEquals(listOf("Matteo a un chat qui s'appelle Pixel."), Recall.search(notes, "Comment s'appelle mon chat ?").map { it.fact.text })
    }

    @Test
    fun recallSkipsWhatWasJustGiven() {
        val given = setOf("Matteo a un chat qui s'appelle Pixel.")
        assertEquals(listOf("Il adore dormir sur le clavier."), Recall.search(notes, "Et Pixel ?", exclude = given).map { it.fact.text })
    }

    @Test
    fun recallFollowsLinksFromWhatItFound() {
        val family = listOf(
            Note(
                Note.ME_ID, Note.ME_TITLE, listOf(Note.ME_TAG), day, day,
                facts = listOf(Fact("Le frère de Matteo s'appelle Hugo.", day), Fact("Matteo aime le jazz.", day)),
            ),
            Note("hugo", "Hugo", listOf("personne"), day, day, facts = listOf(Fact("Hugo est végétarien.", day), Fact("Hugo travaille chez Airbus.", day))),
            Note("pixel", "Pixel", listOf("animal"), day, day, facts = listOf(Fact("Pixel est le chat de Matteo.", day))),
        )
        val message = "Mon frère vient manger ce soir"
        val hits = Recall.search(family, message)
        assertEquals(listOf("Le frère de Matteo s'appelle Hugo."), hits.map { it.fact.text })
        // « Mon frère » ne nomme pas Hugo : le fait trouvé y mène. Rien en commun : le dernier noté.
        val hop = Recall.follow(family, hits, message).single()
        assertEquals(Note.ME_ID to "hugo", hop.from.note.id to hop.note.id)
        assertEquals("Hugo travaille chez Airbus.", hop.fact.text)
        // Un mot du message : ce fait-là.
        assertEquals("Hugo est végétarien.", Recall.follow(family, hits, "Mon frère mange végétarien").single().fact.text)
        // Déjà donné : rien de neuf à lire là-bas.
        val given = setOf("Hugo est végétarien.", "Hugo travaille chez Airbus.")
        assertEquals(emptyList<Recall.Hop>(), Recall.follow(family, hits, message, exclude = given))
        // Jamais vers « Moi », nommée partout, ni vers une note déjà trouvée.
        assertEquals(emptyList<Recall.Hop>(), Recall.follow(family, Recall.search(family, "Et Pixel ?"), "Et Pixel ?"))
        val both = Recall.search(family, "Mon frère est-il végétarien ?")
        assertEquals(setOf(Note.ME_ID, "hugo"), both.map { it.note.id }.toSet())
        assertEquals(emptyList<Recall.Hop>(), Recall.follow(family, both, "Mon frère est-il végétarien ?"))
        assertEquals(emptyList<Recall.Hop>(), Recall.follow(family, hits, message, limit = 0))
    }

    @Test
    fun questionsAboutMe() {
        assertTrue(Recall.asksAboutMe("Tu te souviens de mon âge ?"))
        assertTrue(Recall.asksAboutMe("Qu'est-ce que tu sais sur moi ?"))
        assertFalse(Recall.asksAboutMe("Tu sais faire des crêpes ?"))
        assertEquals(listOf("Matteo aime le jazz.", "Matteo habite à Paris."), Recall.aboutMe(notes, 2).map { it.text })
    }

    @Test
    fun questionsAboutMeWrittenFast() {
        // « tu c'est » pour « tu sais » : écrit vite, c'est toujours une question sur lui.
        assertTrue(Recall.asksAboutMe("tu c'est comment je m'appelle"))
        assertTrue(Recall.asksAboutMe("tu sai ce que j'aime ?"))
        assertTrue(Recall.asksAboutMe("tu te rapelle de mon chat"))
        assertFalse(Recall.asksAboutMe("Je te rappelle demain"))
        assertFalse(Recall.asksAboutMe("tu cherches quoi ?"))
    }

    @Test
    fun askingMyNameGivesIt() {
        assertTrue(Recall.asksName("tu c'est comment je m'appelle"))
        assertTrue(Recall.asksName("C'est quoi mon prénom ?"))
        assertTrue(Recall.asksName("je m'appelle comment déjà"))
        assertFalse(Recall.asksName("Comment tu t'appelles ?"))
        assertFalse(Recall.asksName("Mon nombre préféré c'est 7"))
        // Aucune note ne dit son prénom : Axo le relit, seul (avec le reste, il récitait tout).
        assertEquals(listOf("Il s'appelle Matteo."), Recall.aboutMe("tu c'est comment je m'appelle", notes, always = false, limit = 2, exclude = emptySet()))
        // En début de conversation, ce qu'il sait de lui vient après.
        assertEquals(
            listOf("Il s'appelle Matteo.", "Matteo aime le jazz.", "Matteo habite à Paris."),
            Recall.aboutMe("tu c'est comment je m'appelle", notes, always = true, limit = 2, exclude = emptySet()),
        )
        assertEquals(listOf("Il s'appelle Matteo."), Recall.aboutMe("Tu connais mon prénom ?", emptyList(), always = false, limit = 4, exclude = emptySet()))
        // Déjà donné il y a peu : inutile de le redire.
        assertEquals(emptyList<String>(), Recall.aboutMe("Et mon nom ?", emptyList(), always = false, limit = 4, exclude = setOf("Il s'appelle Matteo.")))
        // Un autre message : rien, sauf en début de conversation.
        assertEquals(emptyList<String>(), Recall.aboutMe("Il fait beau", notes, always = false, limit = 2, exclude = emptySet()))
        assertEquals(listOf("Matteo aime le jazz.", "Matteo habite à Paris."), Recall.aboutMe("Il fait beau", notes, always = true, limit = 2, exclude = emptySet()))
    }

    @Test
    fun recollectGathersWhatAxoRereads() {
        // Son prénom, rien d'autre : « s'appelle » ne ramène plus le chat.
        val name = Recall.recollect(notes, "tu c'est comment je m'appelle", emptySet(), startOfSession = false, dormant = emptySet())
        assertEquals(listOf("Il s'appelle Matteo."), name.facts)
        assertTrue(name.readsMe)
        assertEquals(emptyList<String>(), name.kept)
        // « Qui je suis » : son prénom, puis ce qu'il sait de lui.
        val me = Recall.recollect(notes, "tu sais qui je suis ?", emptySet(), startOfSession = false, dormant = emptySet())
        assertEquals(listOf("Il s'appelle Matteo.", "Matteo aime le jazz.", "Matteo habite à Paris.", "Matteo a 23 ans."), me.facts)
        // Un message ordinaire : seulement ce qui s'en rapproche, sans « Moi ».
        val pixel = Recall.recollect(notes, "Comment va Pixel ?", emptySet(), startOfSession = false, dormant = emptySet())
        assertEquals(setOf("Matteo a un chat qui s'appelle Pixel.", "Il adore dormir sur le clavier."), pixel.facts.toSet())
        assertFalse(pixel.readsMe)
        // Début de séance : « Moi » est relu, et ce qui vient d'être donné ne l'est pas deux fois.
        val start = Recall.recollect(notes, "Il fait beau", setOf("Matteo aime le jazz."), startOfSession = true, dormant = emptySet())
        assertEquals(listOf("Matteo habite à Paris.", "Matteo a 23 ans."), start.facts)
    }

    @Test
    fun memoryBlock() {
        assertEquals("", Recall.block(emptyList()))
        val block = Recall.block(listOf("Matteo a 23 ans."))
        assertEquals("[Mémoire d'Axo : ce que tu sais déjà sur Matteo]\n- Matteo a 23 ans.\n[Message de Matteo]\n", block)
        assertEquals(setOf("Matteo a 23 ans."), Recall.alreadyGiven(listOf(block, "")))
        assertTrue(Recall.block(emptyList(), nothingToForget = true).contains("rien noté"))
        val remember = Recall.block(emptyList(), remember = true)
        assertTrue(remember.contains("c'est noté") && remember.endsWith("[Message de Matteo]\n"))
        // « tu c'est comment je m'appelle » se lisait « c'est comment ? ».
        assertTrue(Recall.writesTuCest("tu c’est comment je m'appelle") && !Recall.writesTuCest("tu sais, c'est pas grave"))
        assertEquals(
            "[Mémoire d'Axo : ce que tu sais déjà sur Matteo]\n- Il s'appelle Matteo.\n[Message de Matteo, où « tu c'est » veut dire « tu sais »]\n",
            Recall.block(listOf("Il s'appelle Matteo."), tuCest = true),
        )
        val distress = Recall.block(emptyList(), cues = setOf(Cue.DISTRESS))
        assertTrue(distress.contains("3114") && distress.endsWith("[Message de Matteo]\n"))
        // Pas un souvenir : rien à montrer sous son message, rien à ne pas redonner.
        assertEquals(emptySet<String>(), Recall.alreadyGiven(listOf(distress)))
        // Après les souvenirs, dans le même ordre quel que soit le message.
        assertEquals(
            "[Mémoire d'Axo : ce que tu sais déjà sur Matteo]\n- Matteo a un chat qui s'appelle Pixel.\n" +
                "[Pour Axo : ${Cue.OFFLINE.text}]\n[Pour Axo : ${Cue.HEALTH.text}]\n[Message de Matteo]\n",
            Recall.block(listOf("Matteo a un chat qui s'appelle Pixel."), cues = linkedSetOf(Cue.HEALTH, Cue.OFFLINE)),
        )
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
