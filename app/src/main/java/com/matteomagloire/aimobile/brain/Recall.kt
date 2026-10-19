package com.matteomagloire.aimobile.brain

/**
 * Souvenirs : ce que le Cerveau sait de pertinent pour un message, glissé devant ce message pour le
 * modèle seulement (l'utilisateur voit son message tel qu'il l'a écrit). Peu de lignes : chaque mot
 * lu retarde le premier mot de la réponse.
 */
object Recall {
    data class Hit(val note: Note, val fact: Fact, val score: Double)

    /** Score minimal : au moins un mot assez rare en commun. */
    const val MIN_SCORE = 2.0
    const val MAX_FACTS = 5

    /** Les faits actuels les plus proches de [query], du plus pertinent au moins pertinent. */
    fun search(notes: List<Note>, query: String, limit: Int = MAX_FACTS, exclude: Set<String> = emptySet()): List<Hit> {
        val q = TextMatch.words(query)
        if (q.isEmpty()) return emptyList()
        // Les mots du titre comptent pour chaque fait de la note : « Pixel » rappelle tout ce qui le concerne.
        val docs = notes.flatMap { n ->
            val title = TextMatch.words(n.title)
            n.facts.map { f -> Triple(n, f, TextMatch.words(f.text) + title) }
        }
        val df = HashMap<String, Int>()
        for ((_, _, words) in docs) for (w in words) df[w] = (df[w] ?: 0) + 1
        return docs.asSequence()
            .filter { it.second.text !in exclude }
            .map { (n, f, words) -> Hit(n, f, q.filter { it in words }.sumOf { TextMatch.idf(docs.size, df[it] ?: 1) }) }
            .filter { it.score >= MIN_SCORE }
            .sortedWith(compareByDescending<Hit> { it.score }.thenByDescending { it.fact.date })
            .take(limit)
            .toList()
    }

    /** Les derniers faits de la note « Moi » (début de conversation, « qu'est-ce que tu sais de moi ? »). */
    fun aboutMe(notes: List<Note>, limit: Int, exclude: Set<String> = emptySet()): List<Fact> =
        notes.firstOrNull { it.isMe }?.facts.orEmpty().filter { it.text !in exclude }.takeLast(limit).reversed()

    private val ABOUT_ME = Regex(
        "(tu te (souviens|rappelles)|te souviens|te rappelles|rappelle[- ]toi|tu sais (quoi|ce que|des choses|qui je|comment je|ou j)|" +
            "que sais[- ]tu|qu.est[- ]ce que tu sais|tu connais (de|sur) moi|tu me connais|ce que tu sais (de|sur) moi)",
    )

    /** Le message demande ce qu'Axo sait de lui. */
    fun asksAboutMe(message: String): Boolean = ABOUT_ME.containsMatchIn(TextMatch.fold(message))

    /** Les faits déjà glissés dans [memories] (les messages récents) : inutile de les redonner. */
    fun alreadyGiven(memories: List<String>): Set<String> =
        memories.flatMap { block -> block.lines().filter { it.startsWith("- ") }.map { it.removePrefix("- ").trim() } }.toSet()

    /**
     * Ce que le modèle lit avant le message. Vide s'il n'y a rien à dire : le message est lu tel quel.
     * [forgotten] : faits qu'il vient d'oublier à la demande de l'utilisateur ; [nothingToForget] : la
     * demande d'oubli ne correspondait à rien ; [remember] : il a demandé de retenir son message.
     */
    fun block(
        facts: List<String>,
        forgotten: List<String> = emptyList(),
        nothingToForget: Boolean = false,
        remember: Boolean = false,
        name: String = USER_NAME,
    ): String {
        if (facts.isEmpty() && forgotten.isEmpty() && !nothingToForget && !remember) return ""
        return buildString {
            if (facts.isNotEmpty()) {
                append("[Mémoire d'Axo : ce que tu sais déjà sur $name]\n")
                facts.forEach { append("- ").append(it).append('\n') }
            }
            if (forgotten.isNotEmpty()) {
                append("[Mémoire d'Axo : à sa demande, tu viens d'oublier ceci, n'en parle plus]\n")
                forgotten.forEach { append("- ").append(it).append('\n') }
            }
            if (nothingToForget) append("[Mémoire d'Axo : tu n'avais rien noté qui corresponde à ce qu'il veut te faire oublier]\n")
            if (remember) append("[Mémoire d'Axo : ce qu'il te demande de retenir sera noté dans ton Cerveau]\n")
            append("[Message de $name]\n")
        }
    }
}
