package com.matteomagloire.aimobile.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCacheTest {
    private val conversation = listOf(true to "salut", false to "bonjour", true to "ça va ?", false to "oui")

    @Test
    fun sameConversationSameHash() {
        assertEquals(SessionCache.hash(conversation), SessionCache.hash(conversation.toList()))
        assertEquals(64, SessionCache.hash(conversation).length)
    }

    @Test
    fun anyChangeChangesTheHash() {
        val base = SessionCache.hash(conversation)
        assertNotEquals(base, SessionCache.hash(conversation.dropLast(1)))
        assertNotEquals(base, SessionCache.hash(conversation.mapIndexed { i, t -> if (i == 1) false to "Bonjour" else t }))
        // Qui parle compte aussi, et les messages ne se collent pas entre eux.
        assertNotEquals(SessionCache.hash(listOf(true to "a")), SessionCache.hash(listOf(false to "a")))
        assertNotEquals(SessionCache.hash(listOf(true to "ab", false to "")), SessionCache.hash(listOf(true to "a", false to "b")))
    }

    @Test
    fun emptyConversationHasAHash() {
        assertEquals(SessionCache.hash(emptyList()), SessionCache.hash(emptyList()))
    }

    @Test
    fun startsWithChecksThePrefix() {
        assertTrue(SessionCache.startsWith(conversation, conversation.subList(0, 2)))
        assertTrue(SessionCache.startsWith(conversation, emptyList()))
        assertTrue(SessionCache.startsWith(conversation, conversation))
        assertFalse(SessionCache.startsWith(conversation.subList(0, 2), conversation))
        assertFalse(SessionCache.startsWith(conversation, listOf(true to "autre chose")))
    }
}
