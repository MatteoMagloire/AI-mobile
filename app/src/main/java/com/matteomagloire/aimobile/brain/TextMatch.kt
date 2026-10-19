package com.matteomagloire.aimobile.brain

import java.text.Normalizer
import java.util.Locale
import kotlin.math.ln

/**
 * Comparaison de textes en français, sans modèle : on garde les mots qui portent le sens, sans
 * accents ni majuscules, au singulier. Sert à retrouver les souvenirs et à repérer les doublons.
 */
object TextMatch {
    private val MARKS = Regex("\\p{Mn}+")
    private val SPLIT = Regex("[^\\p{L}\\p{N}]+")

    /** Minuscules, sans accents : « Léa » et « lea » se ressemblent. */
    fun fold(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.FRENCH).replace("œ", "oe").replace("æ", "ae"), Normalizer.Form.NFD)
            .replace(MARKS, "")

    /** Tous les mots, repliés, petits mots compris. */
    fun allWords(text: String): List<String> = fold(text).split(SPLIT).filter { it.isNotEmpty() }

    /**
     * Les mots qui portent le sens : sans les petits mots ni le prénom, au singulier, et quelques
     * synonymes ramenés au même mot (« vit » et « habite », « bosse » et « travaille »).
     */
    fun words(text: String): Set<String> =
        allWords(text).filter { it !in STOPWORDS && (it.length > 1 || it[0].isDigit()) }.map { stem(it).let { w -> SAME[w] ?: w } }.toSet()

    /** Pluriel en -s ou -x retiré (« chats » -> « chat »). */
    fun stem(word: String): String {
        if (word.length <= 3 || word[0].isDigit()) return word
        val last = word.last()
        if ((last == 's' || last == 'x') && word[word.length - 2] != 's') return word.dropLast(1)
        return word
    }

    /** Part des mots en commun, de 0 (rien) à 1 (les mêmes). */
    fun similarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val common = a.count { it in b }
        return common.toDouble() / (a.size + b.size - common)
    }

    fun similarity(a: String, b: String): Double = similarity(words(a), words(b))

    /** Poids d'un mot présent dans [df] textes sur [n] : un mot rare compte plus. */
    fun idf(n: Int, df: Int): Double = ln((n + 1.0) / df.coerceAtLeast(1)) + 1.0

    // Mots qui disent la même chose dans ce qu'on raconte de soi (repliés, au singulier).
    private val SAME: Map<String, String> = buildMap {
        for (w in listOf("vit", "vis", "vivre", "habiter", "demenage", "demenager", "installe")) put(w, "habite")
        for (w in listOf("travaille", "travailler", "bosse", "bosser", "boulot", "job", "taf", "emploi")) put(w, "travail")
        for (w in listOf("developpeur", "developpeuse", "developper", "dev")) put(w, "developpe")
        for (w in listOf("adore", "adorer", "aimer", "kiffe")) put(w, "aime")
        for (w in listOf("etudiant", "etudiante", "etude", "etudier")) put(w, "etudie")
    }

    private val STOPWORDS: Set<String> = (
        "le la les l un une des du de d et ou a au aux en dans sur sous pour par avec sans chez vers entre " +
            "ce cet cette ces c ca cela ceci celle celui ceux son sa ses mon ma mes ton ta tes notre nos votre vos " +
            "leur leurs je j tu il elle on nous vous ils elles me m te t se s lui y moi toi soi " +
            "est es suis sont etre ete etait sera ai as avons avez ont avoir avait eu qui que qu quoi dont " +
            "ne n pas plus tres bien aussi mais donc car ni si comme tout tous toute toutes fait faire fais " +
            "rien jamais toujours encore deja alors quand puis oui non ok peu beaucoup trop vraiment juste meme " +
            "autre autres quelque quelques chaque aujourd hui depuis axo"
        ).split(' ').toSet() + fold(USER_NAME)
}
