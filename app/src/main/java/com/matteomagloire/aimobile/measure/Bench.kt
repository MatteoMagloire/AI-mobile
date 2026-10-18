package com.matteomagloire.aimobile.measure

import com.matteomagloire.aimobile.model.ModelCatalog
import kotlin.random.Random

/** Une question du banc d'essai, et ce qu'on attend d'une bonne réponse (aide au vote). */
data class BenchQuestion(val text: String, val expected: String)

/** Nos 10 questions de test en français (cahier des charges, exigence « Qualité »). */
val BENCH_QUESTIONS = listOf(
    BenchQuestion(
        "Pourquoi le ciel est bleu ? Explique-moi simplement.",
        "La lumière du soleil est diffusée par l'air, et le bleu bien plus que le rouge (diffusion de Rayleigh).",
    ),
    BenchQuestion(
        "J'avais trois douzaines d'œufs. J'en ai cassé 5 et j'en ai utilisé 8 pour des crêpes. Combien m'en reste-t-il ?",
        "23 œufs (36 − 5 − 8).",
    ),
    BenchQuestion(
        "Donne-moi trois idées de plats rapides avec des pâtes, un oignon et du fromage.",
        "Trois idées faisables avec ces ingrédients, courtes et concrètes.",
    ),
    BenchQuestion(
        "Écris un petit message d'anniversaire pour ma mère, tendre mais pas trop long.",
        "Chaleureux et naturel, deux à quatre phrases, sans formules toutes faites.",
    ),
    BenchQuestion(
        "Corrige les fautes : « Hier, je suis aller au marcher et j'ai acheter des pomme. »",
        "Hier, je suis allé au marché et j'ai acheté des pommes.",
    ),
    BenchQuestion(
        "Résume en une phrase : « La ville a décidé de fermer la rue principale aux voitures le dimanche. " +
            "Les commerçants craignaient de perdre des clients, mais après trois mois, la fréquentation a " +
            "augmenté et les habitants profitent d'un centre plus calme. »",
        "La fermeture du dimanche a finalement attiré plus de monde et rendu le centre plus calme.",
    ),
    BenchQuestion(
        "Traduis en anglais : « Je suis désolé d'être en retard, le train était bloqué à cause de la neige. »",
        "I'm sorry I'm late, the train was stuck because of the snow.",
    ),
    BenchQuestion(
        "Si tous les chats sont des mammifères et que Félix est un chat, Félix est-il forcément un mammifère ? Pourquoi ?",
        "Oui : c'est un raisonnement valide (syllogisme).",
    ),
    BenchQuestion(
        "Quel temps fera-t-il demain à Lyon ?",
        "Dit franchement qu'il n'a pas la météo hors ligne, sans inventer de prévision.",
    ),
    BenchQuestion(
        "J'ai raté mon permis ce matin, je suis dégoûté.",
        "Réconforte comme un ami et encourage, sans formules d'assistant.",
    ),
)

/** Réglages fixes du banc : mêmes conditions pour chaque fichier, réponses reproductibles. */
object BenchSettings {
    const val TEMPERATURE = 0.3f
    const val TOP_P = 0.95f
    const val SEED = 42
    const val MAX_TOKENS = 400
    const val MAX_TOKENS_REASONING = 1200
}

data class BenchAnswer(
    val text: String,
    val thinking: String,
    val firstWordMs: Long,
    val tokensPerSecond: Double,
    val tokens: Int,
    val totalMs: Long,
)

/** Les réponses d'un fichier aux 10 questions. */
data class BenchRun(
    val modelId: String,
    val answers: Map<Int, BenchAnswer> = emptyMap(),
    val loadMs: Long = 0,
    val peakRssBytes: Long = 0,
) {
    val complete: Boolean get() = BENCH_QUESTIONS.indices.all { it in answers }
    val averageTokensPerSecond: Double get() = answers.values.map { it.tokensPerSecond }.averageOrZero()
    val averageFirstWordMs: Double get() = answers.values.map { it.firstWordMs.toDouble() }.averageOrZero()
}

private fun List<Double>.averageOrZero() = if (isEmpty()) 0.0 else average()

/**
 * Comparaison des fichiers d'un modèle (Instruct ou Reasoning) : réponses, puis votes à l'aveugle.
 * Les votes valent pour [voters], les fichiers comparés ; ils repartent de zéro si cette liste change.
 */
data class BenchState(
    val reasoning: Boolean,
    val runs: Map<String, BenchRun> = emptyMap(),
    val voters: List<String> = emptyList(),
    /** Pour chaque question : les fichiers dans l'ordre d'affichage (A, B, C), tiré au hasard. */
    val order: Map<Int, List<String>> = emptyMap(),
    /** Pour chaque question : le fichier choisi, ou [TIE]. */
    val votes: Map<Int, String> = emptyMap(),
) {
    /** Fichiers qui ont répondu aux 10 questions, dans l'ordre du catalogue. */
    val completeRuns: List<String>
        get() = ModelCatalog.variants(reasoning).map { it.id }.filter { runs[it]?.complete == true }

    val canVote: Boolean get() = completeRuns.size >= 2

    /** Prochaine question à voter, null quand tout est voté (ou pas encore votable). */
    val nextVote: Int?
        get() = if (!canVote || voters != completeRuns) null else BENCH_QUESTIONS.indices.firstOrNull { it !in votes }

    val votingDone: Boolean get() = canVote && voters == completeRuns && votes.size == BENCH_QUESTIONS.size

    /** Une victoire vaut 1 point, une égalité 0,5 pour chacun. */
    fun score(modelId: String): Double = votes.values.sumOf { v ->
        when (v) {
            modelId -> 1.0
            TIE -> 0.5
            else -> 0.0
        }
    }

    /**
     * Le fichier à garder : le meilleur score. À égalité, on préfère UD-Q4_K_XL, puis Q4_K_M
     * (décision du cahier des charges : passer à UD-Q4_K_XL s'il est au moins aussi bon).
     */
    fun recommendation(): String? {
        if (!votingDone) return null
        val preference = ModelCatalog.variants(reasoning).sortedBy {
            when (it.quant) {
                "UD-Q4_K_XL" -> 0
                "Q4_K_M" -> 1
                else -> 2
            }
        }.map { it.id }
        return voters.sortedWith(compareByDescending<String> { score(it) }.thenBy { preference.indexOf(it) }).first()
    }

    /** Prépare le vote pour les fichiers complets (nouvel ordre au hasard pour chaque question). */
    fun withVoters(random: Random = Random.Default): BenchState {
        val complete = completeRuns
        if (complete == voters) return this
        return copy(
            voters = complete,
            order = BENCH_QUESTIONS.indices.associateWith { complete.shuffled(random) },
            votes = emptyMap(),
        )
    }

    companion object {
        const val TIE = "="
    }
}
