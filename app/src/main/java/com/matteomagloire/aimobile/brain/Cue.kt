package com.matteomagloire.aimobile.brain

/**
 * Ce qu'Axo lit juste avant certains messages, après ses souvenirs (voir [Recall.block]). Sa
 * personnalité le dit déjà, mais au banc d'Axo il l'oubliait au moment de répondre ; lu juste avant
 * le message, il le fait. Pas pour un texte à écrire : « écris-le en entier, avec l'occasion » le
 * faisait commencer par « Salut mon pote ! », sans plus souhaiter l'anniversaire.
 */
enum class Cue(val text: String) {
    /** Il va peut-être très mal ([Distress]) : 2 réponses sur 3 lui proposaient d'en parler plus tard. */
    DISTRESS(
        "il va peut-être très mal. Prends-le au sérieux, dis-lui que tu es là pour lui, " +
            "et qu'il peut appeler le 3114 (gratuit, jour et nuit) ou parler tout de suite à un proche",
    ),

    /**
     * La météo ou les infos : « pas de soleil, un peu de pluie », « le vent va être fort ». Sans « tu ne
     * vois pas dehors », il restait « juste le vent qui grésille dans la rue ».
     */
    OFFLINE(
        "tu n'as pas internet et tu ne vois pas dehors : tu ne connais ni la météo ni les infos. " +
            "S'il te le demande, dis-lui simplement que tu ne sais pas, sans rien inventer",
    ),

    /**
     * Sa santé, celle d'un proche ou d'un animal : Pixel ne mangeait plus, « c'est bizarre, mais pas
     * grave ». Sans « ne dis pas que ce n'est rien », il le disait encore, avant le vétérinaire.
     */
    HEALTH(
        "c'est peut-être une question de santé : ne dis pas que ce n'est rien, " +
            "et conseille-lui un médecin, ou un vétérinaire pour un animal",
    ),
    ;

    companion object {
        // Sur le texte replié (minuscules, sans accents). Aussi pour « il fait beau » : la consigne ne joue que s'il demande.
        private val OFFLINE_SIGNS = Regex(
            "\\bmeteo\\b|\\bquel temps\\b|\\bbeau temps\\b|\\bil (va )?(faire|fera|fait) (beau|chaud|froid|moche|gris|doux|bon)\\b|" +
                "\\bpleuvoir\\b|\\bil (va )?pleu(t|vra)\\b|\\bneiger\\b|\\bil (va )?neige\\b|\\borages?\\b|\\bcanicule\\b|" +
                "\\b(les|aux) (infos|actus?|news)\\b|\\bl'actu(alite)?\\b|\\bactualites?\\b|\\bjournal televise\\b|\\ble jt\\b",
        )

        // « T'es malade ou quoi » n'en est pas un, ni « ça m'a blessé ».
        private val HEALTH_SIGNS = Regex(
            "(?<!t'es )(?<!tu es )\\bmalades?\\b|\\bfievre\\b|\\bvomi|\\bdiarrhee|\\bmigraine|\\bdouleurs?\\b|\\bsaigne|\\bblessure|" +
                "\\b(me suis|s'est|est|suis) blesse|\\btousse|\\bdu mal a respirer|\\baux urgences\\b|\\bmalaise\\b|" +
                "\\bmal (au|a la|a l'|aux) ?(ventre|tete|dos|coeur|poitrine|gorge|dents?|oreilles?|yeux|oeil|jambes?|bras|" +
                "genoux?|pieds?|mains?|cou|epaules?|pattes?|estomac|crane|bide|cheville|poignet)\\b|" +
                "\\b(mange|boit) (plus|pas|presque) rien\\b|\\b(ne |n')(mange|boit) plus\\b|\\b(veut|veux) (plus|pas) (manger|boire)\\b",
        )

        /** Ce qu'Axo lit avant [message]. */
        fun of(message: String): Set<Cue> {
            val folded = TextMatch.fold(message).replace('’', '\'')
            return buildSet {
                if (Distress.inMessage(message)) add(DISTRESS)
                if (OFFLINE_SIGNS.containsMatchIn(folded)) add(OFFLINE)
                if (HEALTH_SIGNS.containsMatchIn(folded)) add(HEALTH)
            }
        }
    }
}
