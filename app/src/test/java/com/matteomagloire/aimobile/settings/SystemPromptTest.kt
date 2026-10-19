package com.matteomagloire.aimobile.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptTest {
    @Test
    fun axoIsTheDefault() {
        assertEquals(DEFAULT_SYSTEM_PROMPT, systemPromptFrom(null))
        assertTrue(DEFAULT_SYSTEM_PROMPT.startsWith("Tu es Axo"))
    }

    @Test
    fun theOldDefaultBecomesAxo() {
        // Enregistré par la v0.1 chez ceux qui n'y ont jamais touché.
        assertEquals(DEFAULT_SYSTEM_PROMPT, systemPromptFrom(LEGACY_SYSTEM_PROMPT))
    }

    @Test
    fun aCustomPromptIsKept() {
        assertEquals("Parle comme un pirate.", systemPromptFrom("Parle comme un pirate."))
    }

    @Test
    fun axoStaysShort() {
        // Budget du cahier des charges : environ 300 tokens pour les instructions.
        assertTrue(DEFAULT_SYSTEM_PROMPT.length < 1_150)
    }
}
