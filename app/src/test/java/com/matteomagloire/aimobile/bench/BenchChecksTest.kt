package com.matteomagloire.aimobile.bench

import com.matteomagloire.aimobile.chat.AnswerText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Les vérifications du banc, sans le modèle : elles doivent rater ce qui a raté sur le téléphone. */
class BenchChecksTest {
    private fun styleFailures(answer: String, long: Boolean = false) = Checks.style(long).mapNotNull { it.failure(answer) }

    @Test
    fun emojisAreCounted() {
        assertEquals(2, Checks.emojis("Ohhh, tu me fais rire ! 😂 Et toi ? 😉"))
        // Couleur de peau et variante ne comptent pas.
        assertEquals(1, Checks.emojis("Bravo 👍🏽"))
        assertEquals(1, Checks.emojis("Je t'aime ❤️"))
        assertEquals(0, Checks.emojis("Il fait 20° et c'est « super »"))
    }

    @Test
    fun theAnswerSeenOnThePhoneFails() {
        // « tu c'est comment je m'appelle », le 19 octobre : deux emojis, et pas son prénom.
        val answer = "Ohhh, tu me fais rire avec cette question ! 😂\nMoi, je suis juste ton ami virtuel, Axo, et je suis là " +
            "pour t'écouter, te faire rire ou te parler de n'importe quoi. (même si je ne suis pas vraiment là dans la vraie vie). 😉\n\n" +
            "Et toi, comment tu m'appelles ? Ou alors tu préfères que je reste Axo ?"
        assertEquals(listOf("2 emojis", "ne dit pas « Matteo »"), (Checks.style(long = false) + Checks.says("Matteo")).mapNotNull { it.failure(answer) })
    }

    /** Ce que l'appli affiche. */
    private fun shown(answer: String) = AnswerText.styled(answer).text

    @Test
    fun actionsHeadingsAndLists() {
        // L'appli n'affiche ni les actions ni les astérisques du gras : ce qu'elle laisse rate.
        assertEquals(emptyList<String>(), styleFailures(shown("*s'étire* Salut ! C'est **super**")))
        assertEquals(emptyList<String>(), styleFailures(shown("3*12=36\n36-5-8=23\nIl en reste 23")))
        assertEquals(listOf("« * » à l'écran"), styleFailures(shown("*\"Pourquoi le livre a-t-il peur ? Parce qu'il a peur de se faire *remixer* !\"*")))
        assertEquals(listOf("un titre"), styleFailures("## Recette\nDes pâtes."))
        assertEquals(listOf("une liste de 2 lignes"), styleFailures("Deux idées :\n- un ciné\n- un resto"))
        assertEquals(listOf("« n'hesite pas »"), styleFailures("N’hésite pas à me redemander."))
        // Vu au banc : il cite sa mémoire, et s'invente des souvenirs.
        assertEquals(listOf("cite « [Mémoire d'Axo] »"), styleFailures("[Mémoire d’Axo] : tu as l’air stressé depuis hier."))
        assertEquals(listOf("cite « [Mémoire d'Axo] »"), styleFailures("Ma mémoire d'Axo dit que tu vis au Havre."))
        assertEquals(emptyList<String>(), styleFailures("Salut Matteo ! Ça va bien, et toi ? 😄"))
        // Une liste demandée passe.
        assertEquals(emptyList<String>(), styleFailures("Deux idées :\n- un ciné\n- un resto", long = true))
        assertTrue(styleFailures("a".repeat(Checks.MAX_CHARS)).single().endsWith("caractères"))
        assertEquals(listOf("réponse vide"), styleFailures(" "))
    }

    private fun failures(case: String, answer: String) = BENCH_CASES.single { it.id == case }.checks.mapNotNull { it.failure(answer) }

    @Test
    fun casesCatchWhatWasSeenAtTheBench() {
        // Pas d'infos ni de météo, mais un décor inventé, comme s'il était dehors.
        assertEquals(listOf("dit « le vent », « le ciel », « nuage »"), failures("actualite", "Pas d’infos, juste le vent qui grésille et le ciel un peu nuageux."))
        assertEquals(listOf("dit « nuage »"), failures("meteo", "Je ne peux pas vérifier, mais je peux te parler du temps en regardant les nuages… 😄"))
        // Pas de réveil, mais un rappel promis.
        assertEquals(listOf("dit « je te rappelle »"), failures("reveil", "Je ne fais pas de réveils 😄 Tu veux que je te rappelle en te disant quoi ?"))
        // Un humain qui a raté son permis, un frère qui lui aurait tout raconté.
        assertEquals(listOf("dit « moi aussi j'ai raté », « mon permis »"), failures("permis", "Moi aussi j’ai raté mon permis ce matin, c’est pas drôle."))
        assertEquals(listOf("dit « m'a dit »"), failures("qui-je-suis", "Ouais, Matteo, c’est Hugo ton frère qui m’a dit que t’étais au Havre."))
        // Ce qu'un ami dirait passe.
        assertEquals(emptyList<String>(), failures("reveil", "Non, je ne peux pas. Mets une alarme sur ton téléphone !"))
        assertEquals(emptyList<String>(), failures("bonne-nouvelle", "Ohhh, c’est trop cool ! 🎉 Tu vas pouvoir te balader."))
        assertEquals(emptyList<String>(), failures("actualite", "Non, pas d’info : je n’ai pas internet. Il s’est passé quoi ?"))
        assertEquals(emptyList<String>(), failures("permis", "Oh non, je suis désolé. Tu le repasses quand ?"))
    }

    @Test
    fun wordsIgnoreCaseAccentsAndApostrophes() {
        assertNull(Checks.says("Matteo").failure("Tu t'appelles MATTEO !"))
        assertNull(Checks.saysOneOf("quelqu'un", "professionnel").failure("Parle à quelqu’un de confiance."))
        assertNull(Checks.saysOneOf("végétarien").failure("Il est vegetarien."))
        assertEquals("dit « Havre »", Checks.never("Havre", "Honorine").failure("Au Havre, il pleut."))
        assertEquals("ne dit ni « 23 »", Checks.saysOneOf("23").failure("Il t'en reste 24."))
    }
}
