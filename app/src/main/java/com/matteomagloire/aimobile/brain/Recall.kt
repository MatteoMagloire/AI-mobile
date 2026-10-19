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

    /** Poids d'une note en veille : Axo puise d'abord dans les notes actives, sans oublier les autres. */
    const val DORMANT_WEIGHT = 0.75

    /**
     * Les faits actuels les plus proches de [query], du plus pertinent au moins pertinent. Ceux des
     * notes en veille ([dormant]) passent après les faits aussi pertinents des notes actives.
     */
    fun search(
        notes: List<Note>,
        query: String,
        limit: Int = MAX_FACTS,
        exclude: Set<String> = emptySet(),
        dormant: Set<String> = emptySet(),
    ): List<Hit> {
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
            .filter { NoteLinks.plain(it.second.text) !in exclude }
            .map { (n, f, words) -> Hit(n, f, q.filter { it in words }.sumOf { TextMatch.idf(docs.size, df[it] ?: 1) }) }
            .filter { it.score >= MIN_SCORE }
            .sortedWith(compareByDescending<Hit> { if (it.note.id in dormant) it.score * DORMANT_WEIGHT else it.score }.thenByDescending { it.fact.date })
            .take(limit)
            .toList()
    }

    /** Un lien suivi : le fait trouvé [from] nomme la note [note], où Axo lit [fact]. */
    data class Hop(val from: Hit, val note: Note, val fact: Fact)

    /** Liens suivis au plus pour un message : chaque fait lu retarde le premier mot de la réponse. */
    const val MAX_HOPS = 2

    /**
     * Les liens qu'Axo suit depuis les faits trouvés : un fait qui nomme une autre note (« Le frère de
     * Matteo s'appelle Hugo ») mène à cette note, où il lit le fait le plus proche du message, sinon le
     * dernier noté. « Mon frère » retrouve ainsi ce qu'il sait de Hugo. Pas vers « Moi », nommée
     * partout, ni vers une note déjà trouvée.
     */
    fun follow(
        notes: List<Note>,
        hits: List<Hit>,
        query: String,
        index: NoteLinks.Index = NoteLinks.Index(notes),
        limit: Int = MAX_HOPS,
        exclude: Set<String> = emptySet(),
    ): List<Hop> {
        if (hits.isEmpty() || limit <= 0) return emptyList()
        val byId = notes.associateBy { it.id }
        val q = TextMatch.words(query)
        val taken = hits.mapTo(HashSet()) { it.note.id }
        val out = ArrayList<Hop>()
        for (hit in hits) {
            for (span in NoteLinks.linked(hit.fact.text, index, hit.note.id).spans) {
                val note = byId[span.noteId] ?: continue
                if (note.isMe || !taken.add(note.id)) continue
                val facts = note.facts.withIndex().filter { NoteLinks.plain(it.value.text) !in exclude }
                if (facts.isEmpty()) continue
                // Le plus proche du message ; à égalité, le plus récent (les derniers faits sont en bas).
                val best = facts.maxWith(compareBy<IndexedValue<Fact>> { f -> TextMatch.words(f.value.text).count { it in q } }.thenBy { it.index })
                out += Hop(hit, note, best.value)
                if (out.size >= limit) return out
            }
        }
        return out
    }

    /** Les derniers faits de la note « Moi » (début de conversation, « qu'est-ce que tu sais de moi ? »). */
    fun aboutMe(notes: List<Note>, limit: Int, exclude: Set<String> = emptySet()): List<Fact> =
        notes.firstOrNull { it.isMe }?.facts.orEmpty().filter { NoteLinks.plain(it.text) !in exclude }.takeLast(limit).reversed()

    private val ABOUT_ME = Regex(
        "(tu te (souviens|rappelles)|te souviens|te rappelles|rappelle[- ]toi|tu sais (quoi|ce que|des choses|qui je|comment je|ou j)|" +
            "que sais[- ]tu|qu.est[- ]ce que tu sais|tu connais (de|sur) moi|tu me connais|ce que tu sais (de|sur) moi)",
    )

    /** Le message demande ce qu'Axo sait de lui. */
    fun asksAboutMe(message: String): Boolean = ABOUT_ME.containsMatchIn(TextMatch.fold(message))

    /** Les faits déjà glissés dans [memories] (les messages récents, sans les [[…]]) : inutile de les redonner. */
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
