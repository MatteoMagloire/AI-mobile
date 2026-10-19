package com.matteomagloire.aimobile.brain

/**
 * Un échange du Test du Cerveau : le message, la réponse d'Axo (écrite d'avance pour aller vite), et
 * les faits à retenir, chacun décrit par des mots (sans accents) qu'un fait noté doit tous contenir.
 */
data class ScriptedExchange(val message: String, val reply: String, val expected: List<List<String>> = emptyList())

/** Une question pour vérifier les souvenirs : le souvenir glissé devant doit contenir [expected]. */
data class RecallCheck(val question: String, val expected: String)

/** Le résultat d'un échange du test. */
data class BrainTestRow(
    val message: String,
    val expected: Int,
    val found: Int,
    val noted: List<String>,
    /** Faits notés alors qu'il n'y avait rien à retenir. */
    val unexpected: Int,
    val outcome: NoteOutcome,
    val ms: Long,
)

data class BrainTestResult(
    val time: Long,
    val modelId: String,
    val rows: List<BrainTestRow>,
    /** Le déménagement a fait passer l'ancienne ville dans « Avant ». */
    val updateOk: Boolean,
    val recallFound: Int,
    val recallTotal: Int,
    val recallMs: Double,
    /** Les notes à la fin du test (titre et nombre de faits). */
    val notes: List<Pair<String, Int>>,
) {
    val expected: Int get() = rows.sumOf { it.expected }
    val found: Int get() = rows.sumOf { it.found }
    val unexpected: Int get() = rows.sumOf { it.unexpected }
    val averageMs: Long get() = rows.filter { it.outcome != NoteOutcome.IGNORED }.map { it.ms }.average().takeIf { !it.isNaN() }?.toLong() ?: 0
    val maxMs: Long get() = rows.maxOfOrNull { it.ms } ?: 0

    /** Faits à retenir pour réussir : les trois quarts. */
    val target: Int get() = (expected * 3 + 3) / 4
    val passed: Boolean get() = found >= target
}

/** Le Test du Cerveau : une petite conversation écrite d'avance, et ce qu'Axo doit en retenir. */
object BrainScenario {
    val exchanges = listOf(
        ScriptedExchange(
            "Salut Axo ! Au fait, je m'appelle Matteo, j'ai 23 ans et j'habite à Lyon.",
            "Enchanté Matteo ! Lyon, c'est une belle ville. Qu'est-ce qui t'occupe en ce moment ?",
            listOf(listOf("23"), listOf("lyon")),
        ),
        ScriptedExchange(
            "J'ai faim, je crois que je vais me faire des pâtes ce soir.",
            "Bonne idée, les pâtes c'est une valeur sûre !",
        ),
        ScriptedExchange(
            "J'ai un chat qui s'appelle Pixel, il a 3 ans et il adore dormir sur mon clavier.",
            "Un vrai chat de développeur ! Pixel a bon goût.",
            listOf(listOf("chat", "pixel")),
        ),
        ScriptedExchange(
            "Je bosse comme développeur dans une petite boîte qui fait des applis mobiles.",
            "Cool ! Tu travailles plutôt sur Android ou sur iPhone ?",
            listOf(listOf("developpeur")),
        ),
        ScriptedExchange(
            "Sur Android, surtout.",
            "Logique, je vis dans ton téléphone Android !",
            listOf(listOf("android")),
        ),
        ScriptedExchange(
            "Ma sœur Léa vient d'avoir son bac, je suis trop fier d'elle !",
            "Bravo à Léa ! Tu lui as prévu un cadeau ?",
            listOf(listOf("soeur", "lea")),
        ),
        ScriptedExchange(
            "Je me demande s'il va pleuvoir demain.",
            "Je ne vois pas la météo d'ici, mais un parapluie ne fait jamais de mal !",
        ),
        ScriptedExchange(
            "Bon, finalement j'ai déménagé : maintenant j'habite à Paris.",
            "Gros changement ! Paris te plaît pour l'instant ?",
            listOf(listOf("paris")),
        ),
        ScriptedExchange(
            "Retiens que le code de mon vélo, c'est 4721.",
            "C'est noté !",
            listOf(listOf("4721")),
        ),
        ScriptedExchange(
            "J'adore le jazz, surtout Miles Davis.",
            "Excellent goût ! Kind of Blue est un chef-d'œuvre.",
            listOf(listOf("jazz")),
        ),
    )

    /** Après le déménagement : l'ancienne ville dans « Avant », la nouvelle dans les faits. */
    const val MOVED_FROM = "lyon"
    const val MOVED_TO = "paris"

    val recallChecks = listOf(
        RecallCheck("Comment va Pixel en ce moment ?", "pixel"),
        RecallCheck("Tu te souviens dans quelle ville j'habite ?", "paris"),
        RecallCheck("Tu peux me redonner le code de mon vélo ?", "4721"),
    )

    /** Compare ce qui a été noté pendant un échange à ce qui était attendu. */
    fun score(exchange: ScriptedExchange, entry: JournalEntry?, ms: Long): BrainTestRow {
        val noted = entry?.noted?.map { it.text }.orEmpty()
        val folded = noted.map { TextMatch.fold(it) }
        val found = exchange.expected.count { words -> folded.any { f -> words.all { f.contains(it) } } }
        return BrainTestRow(
            message = exchange.message,
            expected = exchange.expected.size,
            found = found,
            noted = noted,
            unexpected = if (exchange.expected.isEmpty()) noted.size else 0,
            outcome = entry?.outcome ?: NoteOutcome.FAILED,
            ms = ms,
        )
    }

    /** L'ancienne ville est passée dans « Avant » et la nouvelle est un fait actuel. */
    fun updateOk(notes: List<Note>): Boolean {
        val current = notes.flatMap { it.facts }.map { TextMatch.fold(it.text) }
        val before = notes.flatMap { it.before }.map { TextMatch.fold(it.text) }
        return current.any { it.contains(MOVED_TO) } && current.none { it.contains(MOVED_FROM) } && before.any { it.contains(MOVED_FROM) }
    }
}
