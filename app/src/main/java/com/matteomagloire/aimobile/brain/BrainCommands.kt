package com.matteomagloire.aimobile.brain

/** Ce que l'utilisateur demande au Cerveau dans un message. */
sealed interface BrainCommand {
    /** « Retiens que… » : [what] est noté même si Axo ne l'aurait pas jugé utile. */
    data class Remember(val what: String) : BrainCommand

    /** « Oublie… » : [what] vide = ce qui vient d'être noté (« oublie ça »). */
    data class Forget(val what: String) : BrainCommand
}

object BrainCommands {
    // « Axo, », « dis, », « stp »… devant la demande.
    private const val LEAD = """^\s*(?:(?:axo|dis|hey|eh|stp|s'il te pla[iî]t)[\s,!:.]+)*"""

    private val REMEMBER = Regex(
        LEAD + """(?:retiens(?:\s+bien)?|(?:n['’]?\s*)?oublie\s+pas|m[ée]morise|garde\s+en\s+(?:t[êe]te|m[ée]moire)|""" +
            """(?:note|souviens[-\s]toi|rappelle[-\s]toi)(?:\s+bien)?(?=\s+(?:que|qu['’])))""" +
            """(?:\s+(?:que|qu['’]|ceci|ça|cela|de|d['’]))?\s*[:,]?\s*(.*)$""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private val FORGET = Regex(
        LEAD + """(?:oublie|efface|supprime)(?:[-\s](?:le|la|les))?\b(?!\s+pas)(?:\s+tout)?""" +
            """(?:\s+(?:ce\s+que|le\s+fait\s+que|que|qu['’]))?""" +
            """(?:\s+(?:je\s+t['’]ai\s+(?:dit|racont[ée]|parl[ée])|tu\s+sais))?""" +
            """(?:\s+(?:sur|à\s+propos\s+d(?:e|u|es)|au\s+sujet\s+d(?:e|u|es)))?\s*[:,]?\s*(.*)$""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    // Fin de phrase sans intérêt pour retrouver le fait.
    private val TAIL = Regex(
        """[\s,]*(?:de\s+ta\s+m[ée]moire|de\s+ton\s+cerveau|du\s+cerveau|stp|s'il\s+te\s+pla[iî]t|merci)?[\s.!?,]*$""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(message: String): BrainCommand? {
        REMEMBER.matchEntire(message)?.let { m ->
            val what = clean(m.groupValues[1])
            return if (what.isEmpty()) null else BrainCommand.Remember(what)
        }
        FORGET.matchEntire(message)?.let { m ->
            val what = clean(m.groupValues[1])
            // « Oublie ça », « efface-le » : ce qui vient d'être noté.
            return BrainCommand.Forget(if (TextMatch.words(what).isEmpty()) "" else what)
        }
        return null
    }

    private fun clean(s: String): String = s.trim().replace(TAIL, "").trim()

    private val FIRST_PERSON = setOf("je", "j", "moi", "mon", "ma", "mes", "me", "m", "nous", "notre", "nos", "on")
    private val SECOND_PERSON = setOf("tu", "t", "ton", "ta", "tes", "toi", "te")

    /**
     * Le message peut-il apprendre quelque chose sur l'utilisateur ? Les petits messages (« ok merci »)
     * et ceux qui ne parlent pas de lui ne sont pas lus par le modèle. Une courte réponse à une question
     * d'Axo sur lui (« Tu as des animaux ? » « Oui, un chat. ») est lue.
     */
    fun worthReading(message: String, previousReply: String): Boolean {
        val words = TextMatch.allWords(message)
        if (words.size < 3) return false
        if (words.any { it in FIRST_PERSON }) return true
        return previousReply.takeLast(120).contains('?') && TextMatch.allWords(previousReply.takeLast(200)).any { it in SECOND_PERSON }
    }
}
