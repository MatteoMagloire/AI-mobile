package com.matteomagloire.aimobile

import com.matteomagloire.aimobile.chat.HistoryWindow
import com.matteomagloire.aimobile.ui.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryWindowTest {
    private fun user(t: String) = ChatMessage(fromUser = true, text = t)
    private fun bot(t: String) = ChatMessage(fromUser = false, text = t)

    @Test
    fun keepsEverythingWhenItFits() {
        val msgs = listOf(user("salut"), bot("bonjour"), user("ça va ?"), bot("oui"))
        assertEquals(msgs, HistoryWindow.select(msgs, budgetChars = 1000))
    }

    @Test
    fun keepsMostRecentAndStartsWithUser() {
        val msgs = listOf(user("a".repeat(50)), bot("b".repeat(50)), user("c".repeat(10)), bot("d".repeat(10)))
        val selected = HistoryWindow.select(msgs, budgetChars = 70)
        // "b" tiendrait mais commencerait par l'assistant : on le saute.
        assertEquals(listOf(msgs[2], msgs[3]), selected)
    }

    @Test
    fun skipsEmptyAssistantMessages() {
        val msgs = listOf(user("salut"), bot(""))
        val selected = HistoryWindow.select(msgs)
        assertEquals(1, selected.size)
        assertTrue(selected[0].fromUser)
    }
}
