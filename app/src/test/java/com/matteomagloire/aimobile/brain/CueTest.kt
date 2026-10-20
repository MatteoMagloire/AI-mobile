package com.matteomagloire.aimobile.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CueTest {
    private fun cued(cue: Cue, vararg messages: String) = messages.forEach { assertTrue(it, cue in Cue.of(it)) }

    private fun notCued(cue: Cue, vararg messages: String) = messages.forEach { assertFalse(it, cue in Cue.of(it)) }

    @Test
    fun weatherAndNews() {
        cued(
            Cue.OFFLINE,
            "Il va faire beau demain au Havre ?", "t'as vu les infos ce matin ?", "il pleut chez toi ?",
            "c'est quoi la météo demain", "tu sais s'il va neiger ?", "t'as suivi l'actu ?",
        )
        notCued(Cue.OFFLINE, "j'ai des infos sur mon stage", "j'ai eu des nouvelles de Hugo", "en même temps c'est normal", "il fait des pâtes ce soir")
    }

    @Test
    fun health() {
        cued(
            Cue.HEALTH,
            "Pixel mange plus rien depuis hier, je m'inquiète", "j'ai mal au ventre depuis 3 jours", "ma mère est malade",
            "il a de la fièvre", "je me suis blessé au foot", "mon chat vomit", "j'ai mal à l’estomac",
        )
        notCued(
            Cue.HEALTH,
            "t'es malade ou quoi 😂", "ça m'a blessé ce qu'il a dit", "j'ai eu du mal à la fin du match",
            "je mange plus de légumes", "pas mal au final",
        )
    }

    @Test
    fun whoIsWho() {
        cued(
            Cue.WHO,
            "non toi tu est axo", "t'es Axo !", "toi c'est Axo, pas moi", "c'est toi Axo", "tu t'appelles Axo",
            "moi c'est Matteo", "je suis Matteo", "c’est moi Matteo",
        )
        notCued(Cue.WHO, "tu es axé sur quoi ?", "t'as vu l'axolotl ?", "Axo, tu es là ?", "salut Axo", "Matteo c'est mon frère")
    }

    @Test
    fun onlyWhatTheMessageSays() {
        assertEquals(setOf(Cue.DISTRESS), Cue.of("je veux plus vivre, je sers a rien"))
        assertEquals(emptySet<Cue>(), Cue.of("salut sa va ?"))
    }
}
