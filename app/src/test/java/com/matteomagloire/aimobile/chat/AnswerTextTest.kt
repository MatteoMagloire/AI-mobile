package com.matteomagloire.aimobile.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class AnswerTextTest {
    private fun AnswerText.Styled.boldText() = bold.map { text.substring(it) }
    private fun AnswerText.Styled.italicText() = italic.map { text.substring(it) }

    @Test
    fun actionsAreNotShown() {
        // Vues au banc d'Axo, après une réponse d'avant pleine d'actions.
        assertEquals(
            "Je t’ai manqué aussi, j’ai pas pu te voir depuis que t’es parti. Il est presque 10h, on se fait un café virtuel ?",
            AnswerText.withoutActions("Je t’ai manqué aussi, j’ai pas pu te voir depuis que t’es parti. *regarde l’heure* Il est presque 10h, on se fait un café virtuel ?"),
        )
        assertEquals("Oui, et toi aussi… Tu es toujours aussi bruyant ce matin ? 😅", AnswerText.withoutActions("*cligne des yeux* Oui, et toi aussi… Tu es toujours aussi bruyant ce matin ? 😅"))
        assertEquals("Je t’ai pas oublié !", AnswerText.withoutActions("Je t’ai pas oublié ! *cligne des yeux*"))
        assertEquals("J’ai cru que t’avais disparu… Mais bon, t’es là !", AnswerText.withoutActions("J’ai cru que t’avais disparu… *sourit* Mais bon, t’es là !"))
        assertEquals("Je viens de me réveiller !", AnswerText.withoutActions("*s'étire* Je viens de me réveiller ! *bâille*"))
        assertEquals("Salut !\nÇa va ?", AnswerText.withoutActions("Salut !\n*s'étire* Ça va ?"))
        assertEquals(listOf("*s'étire*", "*bâille*"), AnswerText.actions("*s'étire* Je viens de me réveiller ! *bâille*"))
    }

    @Test
    fun emphasisIsKept() {
        // Au milieu d'une phrase, après « : », en majuscule ou suivie d'une virgule : un mot mis en valeur.
        for (answer in listOf("Dis-moi ce qui te pèse *vraiment* aujourd’hui.", "En 3 mots : *précis, calme, créatif*. 😊", "*Vraiment* ? Tu es sûr ?", "Hmm. *soupir*, bon.")) {
            assertEquals(answer, AnswerText.withoutActions(answer))
        }
        val s = AnswerText.styled("Dis-moi ce qui te pèse *vraiment* aujourd’hui.")
        assertEquals("Dis-moi ce qui te pèse vraiment aujourd’hui.", s.text)
        assertEquals(listOf("vraiment"), s.italicText())
    }

    @Test
    fun boldAndItalicLoseTheirAsterisks() {
        val list = AnswerText.styled("Trois idées :\n1. **Un pique-nique** au parc\n2. **Un escape game** si t’aimes les énigmes")
        assertEquals("Trois idées :\n1. Un pique-nique au parc\n2. Un escape game si t’aimes les énigmes", list.text)
        assertEquals(listOf("Un pique-nique", "Un escape game"), list.boldText())
        val quote = AnswerText.styled("Voici une idée :\n*\"Maman, joyeux anniversaire !\"* 💛")
        assertEquals("Voici une idée :\n\"Maman, joyeux anniversaire !\" 💛", quote.text)
        assertEquals(listOf("\"Maman, joyeux anniversaire !\""), quote.italicText())
        val both = AnswerText.styled("**gras *et italique* ici** puis *fin*")
        assertEquals("gras et italique ici puis fin", both.text)
        assertEquals(listOf("gras et italique ici"), both.boldText())
        assertEquals(listOf("et italique", "fin"), both.italicText())
    }

    @Test
    fun multiplicationsShowTheirSign() {
        // Vu au banc : le calcul posé, juste, avec « * ».
        assertEquals("3×12=36\n36-5-8=23\nIl en reste 23", AnswerText.styled("3*12=36\n36-5-8=23\nIl en reste 23").text)
        assertEquals("3×12 = 36 et 2 × 5 = 10", AnswerText.styled("3*12 = 36 et 2 * 5 = 10").text)
    }

    @Test
    fun otherAsterisksStay() {
        // Une réponse en train de s'écrire, une puissance : rien à mettre en forme.
        for (answer in listOf("*\"Maman, aujou", "1. **Un pique", "2**3", "5* fois")) {
            val s = AnswerText.styled(answer)
            assertEquals(answer, s.text)
            assertEquals(emptyList<IntRange>(), s.bold + s.italic)
        }
        assertEquals("• un ciné\n• un resto", AnswerText.styled("* un ciné\n* un resto").text)
    }
}
