package com.matteomagloire.aimobile.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkParserTest {
    @Test
    fun plainAnswer() {
        val p = ThinkParser.parse("Bonjour !")
        assertEquals("Bonjour !", p.answer)
        assertEquals("", p.thinking)
        assertFalse(p.isThinking)
    }

    @Test
    fun thinkingInProgress() {
        val p = ThinkParser.parse("[THINK]Je calcule 2+2")
        assertEquals("Je calcule 2+2", p.thinking)
        assertEquals("", p.answer)
        assertTrue(p.isThinking)
    }

    @Test
    fun thinkingThenAnswer() {
        val p = ThinkParser.parse("[THINK]2+2=4[/THINK]La réponse est 4.")
        assertEquals("2+2=4", p.thinking)
        assertEquals("La réponse est 4.", p.answer)
        assertFalse(p.isThinking)
    }
}
