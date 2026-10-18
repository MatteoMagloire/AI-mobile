package com.matteomagloire.aimobile.measure

/** Cibles du cahier des charges (exigences non fonctionnelles). */
object Targets {
    const val FIRST_WORD_MS = 1_000L
    const val TOKENS_PER_SECOND = 10.0
    const val RAM_BYTES = 3_000_000_000L

    /** « 10 minutes de discussion sans ralentissement notable » : moins de 15 % de perte. */
    const val MAX_SLOWDOWN = 0.15
    const val ENDURANCE_MS = 10 * 60 * 1000L
}

/** Le test de vitesse : un long texte à résumer, puis une question courte pour le premier mot. */
object SpeedTestPrompts {
    val LONG_TEXT =
        "L'axolotl est une salamandre originaire des lacs de Xochimilco, près de Mexico. Contrairement aux " +
            "autres amphibiens, il garde toute sa vie l'apparence d'une larve : il respire avec des branchies " +
            "en forme de plumes, de chaque côté de la tête, et ne sort presque jamais de l'eau. Cette " +
            "particularité s'appelle la néoténie. Mais ce qui fascine le plus les chercheurs, c'est sa capacité " +
            "à se régénérer. Un axolotl peut reconstruire une patte entière, sa queue, une partie de son cœur " +
            "et même de son cerveau, sans laisser de cicatrice. Les scientifiques étudient ses cellules pour " +
            "comprendre comment il y parvient, dans l'espoir d'améliorer un jour la cicatrisation chez l'humain. " +
            "Pourtant, à l'état sauvage, l'axolotl est en danger critique d'extinction. Les canaux où il vit " +
            "sont pollués, l'urbanisation de Mexico a réduit son habitat, et des poissons introduits, comme le " +
            "tilapia, mangent ses œufs et ses petits. Des associations locales créent des refuges dans les " +
            "canaux, en filtrant l'eau avec des plantes, et travaillent avec les agriculteurs des chinampas, " +
            "les jardins flottants traditionnels, pour cultiver sans pesticides. Dans le monde, l'axolotl est " +
            "devenu très populaire : on le voit dans les jeux vidéo, les dessins animés et les aquariums, où " +
            "il vit facilement une dizaine d'années. Son sourire permanent et ses couleurs, du gris sauvage " +
            "au rose pâle, en ont fait une petite star. Certains espèrent que cette célébrité aidera à " +
            "protéger ceux qui vivent encore à l'état sauvage."

    val LONG_MESSAGE = "Résume ce texte en trois phrases :\n\n$LONG_TEXT"
    const val LONG_MAX_TOKENS = 160

    const val SHORT_MESSAGE = "Et en trois mots ?"
    const val SHORT_MAX_TOKENS = 48

    /** Endurance : la même réponse à chaque tour, pour comparer des vitesses comparables. */
    const val ENDURANCE_MESSAGE = "Raconte-moi une petite histoire d'axolotl qui découvre la mer."
    const val ENDURANCE_MAX_TOKENS = 192
}

data class SpeedResult(
    val time: Long,
    val modelId: String,
    val loadMs: Long,
    /** Premier mot après une question courte : ce qu'on ressent dans le chat. */
    val firstWordMs: Long,
    /** Premier mot après un message long (environ 400 tokens à lire). */
    val longFirstWordMs: Long,
    val promptTokens: Int,
    val promptTokensPerSecond: Double,
    val tokensPerSecond: Double,
    val rssBytes: Long,
    val peakRssBytes: Long,
    val batteryBefore: Float,
    val batteryAfter: Float,
    val thermalStatus: Int,
    val threads: Int,
)

data class EndurancePoint(val seconds: Int, val tokensPerSecond: Double, val batteryC: Float)

data class EnduranceResult(
    val time: Long,
    val modelId: String,
    val points: List<EndurancePoint>,
    val thermalStatus: Int,
) {
    val durationSeconds: Int get() = points.lastOrNull()?.seconds ?: 0

    /** Vitesse moyenne de la première minute et de la dernière minute. */
    val startSpeed: Double get() = window(fromStart = true)
    val endSpeed: Double get() = window(fromStart = false)

    /** Perte de vitesse entre le début et la fin (0,1 = 10 % plus lent). */
    val slowdown: Double get() = if (startSpeed > 0) ((startSpeed - endSpeed) / startSpeed).coerceAtLeast(0.0) else 0.0

    val complete: Boolean get() = durationSeconds * 1000L >= Targets.ENDURANCE_MS

    private fun window(fromStart: Boolean): Double {
        if (points.isEmpty()) return 0.0
        val selected = if (fromStart) points.filter { it.seconds <= points.first().seconds + 60 }
        else points.filter { it.seconds >= durationSeconds - 60 }
        return selected.map { it.tokensPerSecond }.average()
    }
}
