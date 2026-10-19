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

    /** Faits glissés devant un message, au plus (sans les liens suivis ni « Moi »). */
    const val MAX_HITS = 3

    /** Faits trouvés montrés à l'écran, parmi lesquels Axo garde les meilleurs. */
    const val CANDIDATES = 8

    /** Longueur des faits glissés devant un message, au plus. */
    const val MAX_MEMORY_CHARS = 420

    /** Ce qu'Axo relit avant un message ([facts], dans l'ordre), et ce qu'il a touché pour le trouver. */
    data class Recollection(
        val facts: List<String>,
        val found: List<Hit>,
        val kept: List<String>,
        val followed: List<Hop>,
        val readsMe: Boolean,
    )

    /**
     * Les souvenirs à glisser devant [message] (rapide, sans le modèle) : son prénom et « Moi » s'il
     * parle de lui ou si [startOfSession], les faits proches, puis ceux des notes qu'ils nomment.
     * [given] : faits déjà glissés dans les derniers messages ; [dormant] : notes en veille.
     */
    fun recollect(notes: List<Note>, message: String, given: Set<String>, startOfSession: Boolean, dormant: Set<String>): Recollection {
        // Les faits proches du message, puis ceux des notes qu'ils nomment : « mon frère » mène à Hugo.
        val found = search(notes, message, limit = CANDIDATES, exclude = given, dormant = dormant)
        val top = found.take(MAX_HITS)
        // Axo lit les faits sans les crochets des liens écrits à la main.
        val hits = top.map { NoteLinks.plain(it.fact.text) }
        val hops = follow(notes, top, message, exclude = given + hits)
        val me = aboutMe(message, notes, always = startOfSession, limit = 4, exclude = given + hits)
        // Peu de lignes : chaque mot lu retarde le premier mot de la réponse. Les liens suivis passent en dernier.
        var chars = 0
        val facts = (me + hits + hops.map { NoteLinks.plain(it.fact.text) }).distinct()
            .takeWhile { chars += it.length; chars <= MAX_MEMORY_CHARS }
        return Recollection(
            facts = facts,
            found = found,
            kept = top.filter { NoteLinks.plain(it.fact.text) in facts }.map { it.note.id }.distinct(),
            followed = hops.filter { NoteLinks.plain(it.fact.text) in facts },
            readsMe = me.any { it in facts },
        )
    }

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
        val q = TextMatch.words(query) - ASKING
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

    /**
     * Mots d'une question qui ne disent pas de quoi elle parle : « comment je m'appelle ? » ne doit pas
     * ramener tous les faits où quelqu'un « s'appelle » (son prénom, lui, Axo le sait toujours).
     */
    private val ASKING = setOf("comment", "appelle", "appeler", "appele", "appelee", "nom", "prenom")

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

    // Écrit vite, « tu sais » devient « tu sai », « tu ses », « tu c'est » ou « tu c ».
    private val ABOUT_ME = Regex(
        "\\b(tu te (souvien|rap+el)|te souvien|te rap+el+es|rap+el+e?[- ]toi|" +
            "tu (sais|sait|sai|ses|c.est|c) (quoi|ce que|des choses|qui je|comment je|ou j)|" +
            "que sais[- ]tu|qu.est[- ]ce que tu sais|tu connais (de|sur) moi|tu me connais|ce que tu sais (de|sur) moi)",
    )
    private val NAME = Regex("\\b(comment j(e )?m.ap+el|j(e )?m.ap+el+e? comment|comment tu m.ap+el|mon (pre)?nom\\b)")

    /** Le message demande ce qu'Axo sait de lui, son prénom compris. */
    fun asksAboutMe(message: String): Boolean = ABOUT_ME.containsMatchIn(TextMatch.fold(message)) || asksName(message)

    /** Le message demande son prénom (« tu sais comment je m'appelle ? »). */
    fun asksName(message: String): Boolean = NAME.containsMatchIn(TextMatch.fold(message))

    // « tu c'est comment je m'appelle » se lisait « c'est comment ? » : « Matteo, c'est un peu comme un ami virtuel ».
    private val TU_CEST = Regex("\\btu c'?est\\b")

    /** Le message écrit « tu sais » comme « tu c'est ». */
    fun writesTuCest(message: String): Boolean = TU_CEST.containsMatchIn(TextMatch.fold(message).replace('’', '\''))

    /**
     * Ce qu'Axo relit sur lui avant [message] : s'il parle de lui, son prénom (aucune note ne le dit,
     * Axo le sait toujours) puis les derniers faits de « Moi », aussi relus si [always]. S'il demande
     * son prénom, le prénom seul : avec le reste, Axo récitait tout ce qu'il sait de lui. Lui dire en
     * plus qu'il demande son prénom l'a fait parler du sien, au banc : « Moi c'est Axo. Et toi ? ».
     */
    fun aboutMe(message: String, notes: List<Note>, always: Boolean, limit: Int, exclude: Set<String>, name: String = USER_NAME): List<String> {
        val askName = asksName(message)
        val asksMe = asksAboutMe(message)
        val facts = if (always || (asksMe && !askName)) aboutMe(notes, limit, exclude).map { NoteLinks.plain(it.text) } else emptyList()
        val first = "Il s'appelle $name.".takeIf { asksMe && it !in exclude }
        return listOfNotNull(first) + facts
    }

    /** Les faits déjà glissés dans [memories] (les messages récents, sans les [[…]]) : inutile de les redonner. */
    fun alreadyGiven(memories: List<String>): Set<String> =
        memories.flatMap { block -> block.lines().filter { it.startsWith("- ") }.map { it.removePrefix("- ").trim() } }.toSet()

    /**
     * Ce que le modèle lit avant le message. Vide s'il n'y a rien à dire : le message est lu tel quel.
     * [forgotten] : faits qu'il vient d'oublier à la demande de l'utilisateur ; [nothingToForget] : la
     * demande d'oubli ne correspondait à rien ; [remember] : il a demandé de retenir son message ;
     * [cues] : ce qu'Axo lit juste avant ce message ([Cue.of]) ; [tuCest] : il a écrit « tu c'est »
     * pour « tu sais » ([writesTuCest]).
     */
    fun block(
        facts: List<String>,
        forgotten: List<String> = emptyList(),
        nothingToForget: Boolean = false,
        remember: Boolean = false,
        cues: Set<Cue> = emptySet(),
        tuCest: Boolean = false,
        name: String = USER_NAME,
    ): String {
        if (facts.isEmpty() && forgotten.isEmpty() && !nothingToForget && !remember && cues.isEmpty() && !tuCest) return ""
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
            if (remember) append("[Mémoire d'Axo : tu notes dans ton Cerveau ce qu'il te demande de retenir, dis-lui simplement que c'est noté]\n")
            for (cue in Cue.entries) if (cue in cues) append("[Pour Axo : ").append(cue.text).append("]\n")
            append(if (tuCest) "[Message de $name, où « tu c'est » veut dire « tu sais »]\n" else "[Message de $name]\n")
        }
    }
}
