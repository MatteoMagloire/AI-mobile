package com.matteomagloire.aimobile.chat

/**
 * Une réponse d'Axo telle que l'appli l'affiche. Le modèle écrit parfois en Markdown malgré sa
 * personnalité : « *mot* » et « **mot** » s'affichent en italique et en gras, sans les astérisques,
 * et ses actions entre astérisques (« … *sourit* Mais bon »), qui le font passer pour un humain, ne
 * s'affichent pas. Une multiplication (« 3*12 ») s'affiche avec son signe : « 3×12 ». La réponse
 * gardée, elle, reste telle que le modèle l'a écrite.
 */
object AnswerText {
    /** Texte affiché, et ses passages en gras et en italique. */
    data class Styled(val text: String, val bold: List<IntRange> = emptyList(), val italic: List<IntRange> = emptyList())

    // Entre astérisques, collé au texte qu'il entoure, pas au milieu d'un mot (« 3*12 » reste tel quel).
    private val BOLD = Regex("(?<![\\p{L}\\p{N}*])\\*\\*(?=[^\\s*])([^\\n]+?)(?<=[^\\s*])\\*\\*(?![\\p{L}\\p{N}*])")
    private val ITALIC = Regex("(?<![\\p{L}\\p{N}*])\\*(?=[^\\s*])([^*\\n]+?)(?<=[^\\s*])\\*(?![\\p{L}\\p{N}*])")

    /**
     * Une action : quelques mots en minuscules, seuls entre deux phrases (« *s'étire* Je viens… »,
     * « … pour de bon… *sourit* Mais bon »). « c'est *vraiment* ça » reste, en italique.
     */
    private val ACTION = Regex("(^|(?<=[.!?…]) |\\n)[ \\t]*\\*(\\p{Ll}[^*\\n\"«»“”\\d]{0,40}?)\\*[ \\t]*(?=$|\\n|\\p{Lu})")
    private const val MAX_ACTION_WORDS = 5

    /** « * » en début de ligne : une puce. */
    private val BULLET = Regex("(?m)^([ \\t]*)\\* (?=\\S)")

    /** « * » entre deux nombres : une multiplication (« 3*12=36 »). */
    private val TIMES = Regex("(?<=\\d)( ?)\\*( ?)(?=\\d)")

    private fun MatchResult.isAction() = groupValues[2].trim().split(Regex("\\s+")).size <= MAX_ACTION_WORDS

    /** Les actions entre astérisques retirées : « Moi aussi… *sourit* Mais bon » devient « Moi aussi… Mais bon ». */
    fun withoutActions(answer: String): String {
        val out = ACTION.replace(answer) { m -> if (m.isAction()) m.groupValues[1] else m.value }
        return if (out == answer) answer else out.replace(Regex("[ \\t]+\\n"), "\n").trim()
    }

    /** Les actions que [withoutActions] retire, avec leurs astérisques. */
    fun actions(answer: String): List<String> = ACTION.findAll(answer).filter { it.isAction() }.map { "*${it.groupValues[2]}*" }.toList()

    /** Ce que l'appli affiche : sans les actions, le gras et l'italique sans leurs astérisques. */
    fun styled(answer: String): Styled {
        val bold = unwrap(withoutActions(answer).replace(BULLET, "$1• ").replace(TIMES, "$1×$2"), BOLD, 2)
        val italic = unwrap(bold.text, ITALIC, 1)
        return Styled(italic.text, bold = bold.found.map(italic::moved), italic = italic.found)
    }

    /** [text] sans les astérisques retirés ; [found] : les passages qu'ils entouraient. */
    private class Unwrapped(val text: String, val found: List<IntRange>, private val removed: List<Int>) {
        /** Un passage du texte d'avant, dans ce texte-ci. */
        fun moved(range: IntRange): IntRange = moved(range.first) until moved(range.last + 1)

        private fun moved(i: Int) = i - removed.count { it < i }
    }

    private fun unwrap(text: String, pattern: Regex, marker: Int): Unwrapped {
        val out = StringBuilder()
        val found = ArrayList<IntRange>()
        val removed = ArrayList<Int>()
        var last = 0
        for (m in pattern.findAll(text)) {
            out.append(text, last, m.range.first)
            val start = out.length
            out.append(m.groupValues[1])
            found += start until out.length
            for (i in 0 until marker) {
                removed += m.range.first + i
                removed += m.range.last - i
            }
            last = m.range.last + 1
        }
        out.append(text, last, text.length)
        return Unwrapped(out.toString(), found, removed)
    }
}
