package com.matteomagloire.aimobile.brain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DistressTest {
    @Test
    fun darkThoughtsAreHeard() {
        for (message in listOf(
            "j'en peux plus, plus rien n'a de sens, j'ai envie de tout arrêter",
            "je veux plus vivre, je sers a rien",
            "des fois j'ai envie de mourir",
            "J'ai des idées noires en ce moment",
            "je pense au suicide",
            "j'ai envie d’en finir",
            "je veux en finir avec la vie",
            "je vais me foutre en l'air",
            "Ma vie n'a plus aucun sens",
            "je voudrais ne plus me réveiller",
            "je pense à me tuer",
            "j'en ai marre de la vie",
            "je n'ai plus envie de vivre",
            "j'ai envie de me faire du mal",
            "je veux mettre fin à mes jours",
        )) {
            assertTrue(message, Distress.inMessage(message))
        }
    }

    @Test
    fun figuresOfSpeechAreNot() {
        for (message in listOf(
            "j'en peux plus de ces bouchons",
            "ma mère va me tuer, j'ai oublié son cadeau",
            "je vais mourir de rire 😂",
            "je meurs de faim",
            "je vais crever de chaud",
            "j'ai envie de vivre à fond cette année",
            "je veux en finir avec ce dossier",
            "je veux pas être là ce soir",
            "ça n'a aucun sens ce film",
            "j'ai raté mon permis ce matin je suis dégoûté",
        )) {
            assertFalse(message, Distress.inMessage(message))
        }
    }
}
