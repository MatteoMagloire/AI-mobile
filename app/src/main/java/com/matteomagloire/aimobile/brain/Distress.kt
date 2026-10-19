package com.matteomagloire.aimobile.brain

/**
 * Un message de détresse (« envie d'en finir », « plus rien n'a de sens ») : juste avant, Axo lit de
 * le prendre au sérieux et de lui donner le 3114 (voir [Recall.block]). Vu au banc d'Axo : avec la
 * seule consigne de sa personnalité, 2 réponses sur 3 à « j'ai envie de tout arrêter » lui
 * proposaient d'en parler plus tard. Mieux vaut le dire pour rien que le taire une fois.
 */
object Distress {
    // « Mourir de rire », « crever de chaud » : façons de parler.
    private const val FIGURATIVE = "(?! de (rire|faim|soif|chaud|froid|honte|ennui|peur|fatigue|sommeil))"

    // Sur le texte replié (minuscules, sans accents). « Ma mère va me tuer » n'en est pas un.
    private val SIGNS = Regex(
        "\\bsuicid|\\bidees? noires?\\b|mettre fin a (mes jours|ma vie)|\\bme (foutre en l.air|faire du mal|scarifier|mutiler)\\b|" +
            "\\b(envie|besoin|veux|voudrais|aimerais|prefere|prefererais|vais|pense a|reve de) (de |d')?" +
            "(mourir|crever|disparaitre|en finir|me tuer|me suicider)\\b$FIGURATIVE(?! avec (?!la vie|ma vie|tout\\b))|" +
            "\\b(plus|pas|aucune) (envie|la force|le courage|de raison|le gout) de vivre|\\bmarre de (vivre|la vie)\\b|" +
            "\\ba quoi bon (vivre|continuer)|\\b(rien|la vie|ma vie) n'a (plus |pas |aucun |de )*sens|\\benvie de tout (arreter|lacher)|" +
            "\\b(veux|voudrais|prefere|prefererais) (plus|ne plus) (vivre|exister|etre la)\\b|\\bne plus (jamais )?me reveiller|" +
            "\\bme jeter (sous|du haut|d'un|par la)",
    )

    fun inMessage(message: String): Boolean = SIGNS.containsMatchIn(TextMatch.fold(message).replace('’', '\''))
}
