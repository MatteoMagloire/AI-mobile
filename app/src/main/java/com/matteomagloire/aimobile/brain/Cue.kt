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
     * vois pas dehors », il restait « juste le vent qui grésille dans la rue » ; avec « dis-lui que tu
     * ne sais pas », « Non je ne sais pas, mais ça peut être nuageux ».
     */
    OFFLINE("tu n'as pas internet et tu ne vois pas dehors : tu ne connais ni la météo ni les infos, n'en invente rien"),

    /**
     * Sa santé, celle d'un proche ou d'un animal : Pixel ne mangeait plus, « c'est bizarre, mais pas
     * grave ». Sans « ne dis pas que ce n'est rien », il le disait encore, avant le vétérinaire.
     */
    HEALTH(
        "c'est peut-être une question de santé : ne dis pas que ce n'est rien, " +
            "et conseille-lui un médecin, ou un vétérinaire pour un animal",
    ),

    /**
     * Il lui dit qui est qui. Sur le téléphone, après « Moi aussi je suis Matteo, mais en version IA »,
     * « non toi tu est axo » donnait « Ahhhhhhhhhhhh ! 😂 Tu me prends pour un humain ». Au banc, sans
     * ce rappel, il lui donnait raison 4 ou 5 fois sur 12 ; avec, 11 ou 12 fois.
     */
    WHO("il te rappelle qui est qui : toi, c'est Axo, et lui, c'est $USER_NAME. S'il te corrige, donne-lui raison"),

    /**
     * Un salut, sans rien d'autre : Axo ne sait rien de sa journée. Sur le téléphone, « salut comment
     * tu va? » donnait « tu préfères rester en mode "jeu vidéo" ? », qu'il a repris ensuite (« ton
     * jeu »). Au banc, sans ce rappel, il supposait quelque chose 7 fois sur 12 (« Toujours en train de
     * bosser sur ce projet de code bizarre ? ») ; avec, jamais : « Tu as déjà commencé ta journée ? ».
     */
    HELLO("n'invente rien de sa journée : demande-lui comment elle se passe"),
    ;

    companion object {
        // Sur le texte replié (minuscules, sans accents). Aussi pour « il fait beau » : rien à inventer non plus.
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

        // « non toi tu est axo », « t'es Axo », « moi c'est Matteo » ; pas « tu es axé », ni « l'axolotl ».
        private val WHO_SIGNS = Regex(
            "\\b(tu ?(es|est|e|et)|t'?es|toi,? c'?est|c'?est toi|tu t'?appel+es?) axo\\b|\\baxo,? c'?est toi\\b|" +
                "\\b(moi,? c'?est|je suis|je m'?appel+e|c'?est moi) ${TextMatch.fold(USER_NAME)}\\b",
        )

        // Un salut sans rien d'autre : « salut comment tu va? », « ça va toi ? », « coucou Axo » ; pas
        // « salut, j'ai raté mon permis », ni « ça va pas ».
        private val HELLO_WORDS = setOf("salut", "slt", "coucou", "cc", "bonjour", "bjr", "bonsoir", "hello", "hey", "yo", "wesh", "re", "va", "vas", "neuf", "forme", "roule")
        private val SMALL_TALK = HELLO_WORDS + setOf("ca", "sa", "comment", "tu", "toi", "et", "bien", "quoi", "de", "la", "axo")

        private fun saysHello(folded: String): Boolean {
            val words = folded.split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
            return words.isNotEmpty() && words.all { it in SMALL_TALK } && words.any { it in HELLO_WORDS }
        }

        /** Ce qu'Axo lit avant [message]. */
        fun of(message: String): Set<Cue> {
            val folded = TextMatch.fold(message).replace('’', '\'')
            return buildSet {
                if (Distress.inMessage(message)) add(DISTRESS)
                if (OFFLINE_SIGNS.containsMatchIn(folded)) add(OFFLINE)
                if (HEALTH_SIGNS.containsMatchIn(folded)) add(HEALTH)
                if (WHO_SIGNS.containsMatchIn(folded)) add(WHO)
                if (saysHello(folded)) add(HELLO)
            }
        }
    }
}
