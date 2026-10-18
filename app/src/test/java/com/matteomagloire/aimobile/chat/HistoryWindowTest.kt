package com.matteomagloire.aimobile.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryWindowTest {
    private fun user(t: String) = ChatMessage(fromUser = true, text = t)
    private fun bot(t: String) = ChatMessage(fromUser = false, text = t)

    @Test
    fun turnsKeepAConversationAsIs() {
        val msgs = listOf(user("salut"), bot("bonjour"), user("ça va ?"), bot("oui"))
        assertEquals(
            listOf(true to "salut", false to "bonjour", true to "ça va ?", false to "oui"),
            HistoryWindow.turns(msgs),
        )
    }

    @Test
    fun unansweredMessagesGetAnEmptyAnswer() {
        // Réponse arrêtée avant le premier mot (enregistrée vide, donc absente du fichier).
        val msgs = listOf(user("a"), bot(""), user("b"), bot("réponse"))
        assertEquals(listOf(true to "a", false to "", true to "b", false to "réponse"), HistoryWindow.turns(msgs))
        // Dernier message sans réponse (app fermée pendant l'écriture).
        assertEquals(listOf(true to "a", false to ""), HistoryWindow.turns(listOf(user("a"))))
    }

    @Test
    fun strayAnswersAreDropped() {
        val msgs = listOf(bot("orpheline"), user("a"), bot("b"), bot("doublon"))
        assertEquals(listOf(true to "a", false to "b"), HistoryWindow.turns(msgs))
    }

    @Test
    fun turnsAreStableAcrossASaveAndReload() {
        // Le fichier ne garde pas les réponses vides : la conversation vue par le modèle ne change pas.
        val live = listOf(user("a"), bot(""), user("b"), bot("c"))
        assertEquals(3, live.toSave().size)
        assertEquals(HistoryWindow.turns(live), HistoryWindow.turns(live.toSave()))
    }

    @Test
    fun aStoppedThinkingIsKeptButNotSentBack() {
        // Arrêtée pendant la réflexion : la réflexion reste affichée, le modèle voit une réponse vide.
        val live = listOf(user("a"), ChatMessage(fromUser = false, text = "", thinking = "hmm"), user("b"), bot("c"))
        assertEquals(4, live.toSave().size)
        assertEquals(listOf(true to "a", false to "", true to "b", false to "c"), HistoryWindow.turns(live.toSave()))
    }

    @Test
    fun selectKeepsEverythingWhenItFits() {
        val turns = listOf(true to "salut", false to "bonjour", true to "ça va ?", false to "oui")
        assertEquals(turns, HistoryWindow.select(turns, budgetChars = 1000))
    }

    @Test
    fun selectKeepsMostRecentAndStartsWithUser() {
        val turns = listOf(true to "a".repeat(50), false to "b".repeat(50), true to "c".repeat(10), false to "d".repeat(10))
        // "b" tiendrait mais commencerait par Axo : on le saute.
        assertEquals(turns.subList(2, 4), HistoryWindow.select(turns, budgetChars = 70))
    }

    @Test
    fun charsCountsAllTexts() {
        assertEquals(7, HistoryWindow.chars(listOf(true to "abc", false to "defg")))
    }
}
