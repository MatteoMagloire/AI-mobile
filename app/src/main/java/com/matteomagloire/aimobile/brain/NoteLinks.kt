package com.matteomagloire.aimobile.brain

import java.util.Locale

/**
 * Les liens entre notes, trouvés par le code, sans le modèle. Une note est reliée à celles que son
 * texte nomme : « Pixel est le chat de Matteo » relie « Pixel » à « Moi », « Matteo a une sœur, Léa »
 * relie « Moi » à « Léa ». S'y ajoutent les liens écrits à la main : [[Titre]] dans le texte, ou la
 * rubrique « ## Liens ». Rien n'est ajouté aux faits : Axo les relit tels qu'il les a écrits.
 */
object NoteLinks {
    /** Un lien écrit à la main : [[Titre]], ou [[Titre|texte affiché]] comme dans Obsidian. */
    val WIKI = Regex("""\[\[([^\[\]\n]+)]]""")

    private val WORD = Regex("[\\p{L}\\p{N}\\p{M}]+")

    // Un titre comme « La Fnac » se reconnaît aussi dans « chez Fnac ».
    private val ARTICLES = setOf("le", "la", "les", "l", "un", "une", "des", "du", "de", "d")

    /** Un passage du texte qui mène à une note ([start] inclus, [end] exclu). */
    data class Span(val start: Int, val end: Int, val noteId: String)

    /** Un texte prêt à afficher : les [[…]] sans leurs crochets, et les passages qui mènent à une note. */
    data class Linked(val text: String, val spans: List<Span>)

    /** La note visée par l'intérieur d'un [[…]] : « Titre » dans « Titre|texte » ou « Titre#rubrique ». */
    fun target(inner: String): String = inner.substringBefore('|').substringBefore('#').trim()

    private class Token(val start: Int, val end: Int, val word: String)

    /** Les mots du texte, repliés et au singulier, avec leur place dans le texte. */
    private fun tokens(text: String): List<Token> = WORD.findAll(text).map { m ->
        val raw = m.value
        // Le plus souvent sans accent : inutile de passer par la normalisation Unicode.
        val folded = if (raw.all { it < '\u0080' }) raw.lowercase(Locale.ROOT) else TextMatch.fold(raw)
        Token(m.range.first, m.range.last + 1, TextMatch.stem(folded))
    }.filter { it.word.isNotEmpty() }.toList()

    /** Clé d'un titre ou d'un nom de fichier : « Course à pied » et « course-a-pied » se valent. */
    private fun key(text: String): String = TextMatch.allWords(text).joinToString(" ")

    private class Name(val words: List<String>, val noteId: String)

    /** Les noms qui désignent chaque note (son titre ; le prénom pour « Moi »), rangés par premier mot. */
    class Index(notes: List<Note>) {
        private val byFirst: Map<String, List<Name>>
        private val byKey: Map<String, String>

        init {
            val names = ArrayList<Name>()
            val keys = HashMap<String, String>()
            for (n in notes) {
                if (n.isMe) {
                    nameOf(USER_NAME, anyWord = true)?.let { names += Name(it, n.id) }
                    keys.putIfAbsent(key(USER_NAME), n.id)
                }
                nameOf(n.title, anyWord = false)?.let { names += Name(it, n.id) }
                keys.putIfAbsent(key(n.title), n.id)
                keys.putIfAbsent(key(n.id), n.id)
            }
            byFirst = names.groupBy { it.words.first() }.mapValues { (_, v) -> v.sortedByDescending { it.words.size } }
            byKey = keys
        }

        /** La note d'un lien écrit à la main : son titre, son nom de fichier, ou le prénom pour « Moi ». */
        fun resolve(title: String): String? = byKey[key(target(title))]

        /** Les notes nommées dans [text], de gauche à droite ; le nom le plus long l'emporte. */
        fun mentions(text: String): List<Span> {
            val toks = tokens(text)
            val out = ArrayList<Span>()
            var i = 0
            while (i < toks.size) {
                val hit = byFirst[toks[i].word]?.firstOrNull { n ->
                    n.words.size <= toks.size - i && n.words.indices.all { k -> toks[i + k].word == n.words[k] }
                }
                if (hit == null) {
                    i++
                } else {
                    out += Span(toks[i].start, toks[i + hit.words.size - 1].end, hit.noteId)
                    i += hit.words.size
                }
            }
            return out
        }
    }

    /** Les mots d'un nom ; null s'il ne nomme rien de précis (« Lui », « Été », « A »). */
    private fun nameOf(text: String, anyWord: Boolean): List<String>? {
        val words = tokens(text).map { it.word }.dropWhile { it in ARTICLES }
        if (words.isEmpty() || (words.size == 1 && words[0].length < 2)) return null
        if (!anyWord && TextMatch.words(text).isEmpty()) return null
        return words
    }

    /**
     * [text] prêt à afficher dans la note [self] : sans les crochets des [[…]], avec ses liens. Avec
     * [seen], une note déjà nommée plus haut dans la note n'est plus soulignée (les [[…]] le restent).
     */
    fun linked(text: String, index: Index, self: String? = null, seen: MutableSet<String>? = null): Linked {
        val plain = StringBuilder()
        val spans = ArrayList<Span>()
        val written = ArrayList<IntRange>()
        var last = 0
        for (m in WIKI.findAll(text)) {
            plain.append(text, last, m.range.first)
            val inner = m.groupValues[1]
            val shown = if ('|' in inner) inner.substringAfter('|').trim() else target(inner)
            val start = plain.length
            plain.append(shown)
            written += start until plain.length
            val id = index.resolve(inner)
            if (id != null && id != self) {
                spans += Span(start, plain.length, id)
                seen?.add(id)
            }
            last = m.range.last + 1
        }
        plain.append(text, last, text.length)
        val shown = plain.toString()
        for (m in index.mentions(shown)) {
            if (m.noteId == self || written.any { m.start <= it.last && it.first < m.end }) continue
            if (seen != null && !seen.add(m.noteId)) continue
            spans += m
        }
        return Linked(shown, spans.sortedBy { it.start })
    }

    /** Le texte sans les crochets des [[…]] (pour Axo, et pour la recherche). */
    fun plain(text: String): String =
        if ("[[" !in text) text else WIKI.replace(text) { m -> m.groupValues[1].let { if ('|' in it) it.substringAfter('|').trim() else target(it) } }

    /** Les textes d'une note, dans l'ordre de lecture : le texte libre, les faits, puis « Avant ». */
    fun texts(note: Note): List<String> = listOf(note.extra) + note.facts.map { it.text } + note.before.map { it.text }

    /** Les notes que [note] nomme ou relie à la main, dans l'ordre du texte, sans elle-même. */
    fun outgoing(note: Note, index: Index): List<String> {
        val out = LinkedHashSet<String>()
        for (text in texts(note)) {
            if (text.isNotBlank()) linked(text, index, note.id).spans.forEach { out += it.noteId }
        }
        for (title in note.links) index.resolve(title)?.takeIf { it != note.id }?.let { out += it }
        return out.toList()
    }

    /** Tous les liens du Cerveau : ceux que chaque note fait, et ses rétroliens (les notes qui la nomment). */
    class Links(val index: Index, private val out: Map<String, List<String>>) {
        private val back: Map<String, List<String>> = HashMap<String, MutableList<String>>().also { m ->
            for ((from, targets) in out) for (to in targets) m.getOrPut(to) { ArrayList() } += from
        }

        /** Les notes que [id] nomme. */
        fun linksOf(id: String): List<String> = out[id].orEmpty()

        /** Rétroliens : les notes qui nomment [id]. */
        fun backlinksOf(id: String): List<String> = back[id].orEmpty()

        /** Les notes reliées à [id], dans un sens ou dans l'autre. */
        fun neighbors(id: String): Set<String> = LinkedHashSet(linksOf(id)).also { it.addAll(backlinksOf(id)) }

        /** Chaque paire de notes reliées, une seule fois, quel que soit le sens. */
        val edges: List<Pair<String, String>> by lazy {
            val seen = HashSet<Pair<String, String>>()
            for ((from, targets) in out) for (to in targets) seen += if (from < to) from to to else to to from
            seen.sortedWith(compareBy({ it.first }, { it.second }))
        }

        companion object {
            val EMPTY = Links(Index(emptyList()), emptyMap())
        }
    }

    fun links(notes: List<Note>): Links {
        val index = Index(notes)
        return Links(index, notes.associate { it.id to outgoing(it, index) })
    }
}
