package com.matteomagloire.aimobile.llm

/**
 * Sépare la réflexion du modèle Reasoning (entre [THINK] et [/THINK]) de la réponse finale.
 * Fonctionne sur un texte partiel pendant le streaming.
 */
object ThinkParser {
    private const val OPEN = "[THINK]"
    private const val CLOSE = "[/THINK]"

    data class Parsed(val thinking: String, val answer: String, val isThinking: Boolean)

    fun parse(raw: String): Parsed {
        val start = raw.indexOf(OPEN)
        if (start < 0) return Parsed(thinking = "", answer = raw.trim(), isThinking = false)
        val afterOpen = start + OPEN.length
        val end = raw.indexOf(CLOSE, afterOpen)
        val before = raw.substring(0, start)
        return if (end < 0) {
            Parsed(thinking = raw.substring(afterOpen).trim(), answer = before.trim(), isThinking = true)
        } else {
            val answer = (before + raw.substring(end + CLOSE.length)).trim()
            Parsed(thinking = raw.substring(afterOpen, end).trim(), answer = answer, isThinking = false)
        }
    }
}
