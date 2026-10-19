package com.matteomagloire.aimobile.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/**
 * Le Cerveau sur le téléphone : une note Markdown par fichier dans `notes/`, le journal des prises de
 * notes, les faits oubliés à la demande de l'utilisateur (pour ne pas les renoter), et l'heure du
 * dernier message lu. Toutes les écritures passent une par une.
 */
class NoteStore(
    private val dir: File,
    private val today: () -> LocalDate = { LocalDate.now() },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val notesDir = File(dir, "notes")
    private val journalFile = File(dir, "journal.txt")
    private val forgottenFile = File(dir, "oublis.txt")
    private val stateFile = File(dir, "etat.txt")
    private val lock = Mutex()
    private var loaded = false

    private val _notes = MutableStateFlow<List<Note>>(emptyList())

    /** « Moi » d'abord, puis les notes les plus récemment modifiées. */
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    private val _journal = MutableStateFlow<List<JournalEntry>>(emptyList())

    /** Du plus récent au plus ancien. */
    val journal: StateFlow<List<JournalEntry>> = _journal.asStateFlow()

    @Volatile
    private var forgottenTexts: List<String> = emptyList()

    /** Heure du dernier message lu : les suivants sont à lire. */
    @Volatile
    var processedUntil: Long = 0
        private set

    fun forgotten(): List<String> = forgottenTexts

    suspend fun load() = locked { }

    private suspend fun <T> locked(block: () -> T): T = lock.withLock {
        withContext(Dispatchers.IO) {
            if (!loaded) loadFiles()
            block()
        }
    }

    private fun loadFiles() {
        notesDir.mkdirs()
        _notes.value = sorted(
            notesDir.listFiles { f -> f.name.endsWith(".md") }.orEmpty().mapNotNull { f ->
                try {
                    NoteFormat.parse(f.name.removeSuffix(".md"), f.readText(), today())
                } catch (e: Exception) {
                    null
                }
            },
        )
        _journal.value = if (journalFile.exists()) JournalFormat.parse(journalFile.readText()) else emptyList()
        forgottenTexts = if (forgottenFile.exists()) forgottenFile.readLines().filter { it.isNotBlank() } else emptyList()
        // Première ouverture : la conversation passée n'est pas relue, seulement les messages à venir.
        processedUntil = stateFile.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()
            ?: clock().also { writeAtomic(stateFile, it.toString()) }
        loaded = true
    }

    private fun sorted(notes: List<Note>): List<Note> =
        notes.sortedWith(compareByDescending<Note> { it.isMe }.thenByDescending { it.modified }.thenBy { TextMatch.fold(it.title) })

    private fun writeAtomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun noteFile(id: String) = File(notesDir, "$id.md")

    /** Remplace toutes les notes par celles de [transform] : seuls les fichiers changés sont écrits. */
    private fun replaceNotes(transform: (List<Note>) -> List<Note>) {
        val old = _notes.value
        val new = transform(old)
        val oldById = old.associateBy { it.id }
        for (n in new) if (oldById[n.id] != n) writeAtomic(noteFile(n.id), NoteFormat.write(n))
        val ids = new.map { it.id }.toSet()
        for (o in old) if (o.id !in ids) noteFile(o.id).delete()
        _notes.value = sorted(new)
    }

    suspend fun update(transform: (List<Note>) -> List<Note>) = locked { replaceNotes(transform) }

    /** Une note modifiée à la main (null : supprimée). */
    suspend fun edit(id: String, transform: (Note) -> Note?) = locked {
        replaceNotes { notes -> notes.mapNotNull { if (it.id == id) transform(it) else it } }
    }

    /** Note ouverte aujourd'hui (date « ouverte » de l'en-tête). */
    suspend fun markOpened(id: String) = locked {
        val day = today()
        if (_notes.value.any { it.id == id && it.opened != day }) {
            replaceNotes { notes -> notes.map { if (it.id == id) it.copy(opened = day) else it } }
        }
    }

    /**
     * Notes dont Axo s'est servi pour répondre (date « utilisee ») : une note en veille redevient
     * active. Écrit au plus une fois par jour et par note.
     */
    suspend fun markUsed(ids: Collection<String>) = locked {
        val day = today()
        val used = ids.toSet()
        if (_notes.value.any { it.id in used && it.used != day }) {
            replaceNotes { notes -> notes.map { if (it.id in used && it.used != day) it.copy(used = day) else it } }
        }
    }

    /** Les notes reliées à la main à [id] (rubrique « ## Liens »), par leur titre. */
    suspend fun setLinks(id: String, titles: List<String>) = locked {
        val clean = titles.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { TextMatch.fold(it) }
        replaceNotes { notes -> notes.map { if (it.id == id && it.links != clean) it.copy(links = clean, modified = today()) else it } }
    }

    /**
     * Une note réécrite à la main (vue « Brut ») : relue telle quelle. Si son titre change, le fichier
     * garde son nom, et les liens écrits à la main vers elle suivent ; sans titre, elle garde l'ancien.
     * Null si la note n'existe plus.
     */
    suspend fun rewrite(id: String, markdown: String): Note? = locked {
        val old = _notes.value.firstOrNull { it.id == id } ?: return@locked null
        val parsed = NoteFormat.parse(id, markdown, today())
        val hasTitle = markdown.lines().any { it.trim().startsWith("# ") }
        val title = if (old.isMe || !hasTitle) old.title else parsed.title
        val saved = parsed.copy(title = title, created = old.created, modified = today())
        val renamed = TextMatch.fold(title) != TextMatch.fold(old.title)
        replaceNotes { notes ->
            notes.map { n ->
                when {
                    n.id == id -> saved
                    renamed && n.links.any { TextMatch.fold(it) == TextMatch.fold(old.title) } ->
                        n.copy(links = n.links.map { if (TextMatch.fold(it) == TextMatch.fold(old.title)) title else it })
                    else -> n
                }
            }
        }
        saved
    }

    /** Applique ce qu'un échange a appris, l'inscrit au journal, et le marque lu. */
    suspend fun record(exchange: Exchange, excerpt: String, thought: Thought, ms: Long): JournalEntry = locked {
        val now = clock()
        val entry = when (thought) {
            is Thought.Facts -> {
                var noted: List<NotedFact> = emptyList()
                replaceNotes { notes -> NoteEdits.apply(notes, thought.facts, today()).also { noted = it.second }.first }
                JournalEntry(now, exchange.time, excerpt, NoteOutcome.NOTED, noted, thought.known, ms = ms)
            }
            is Thought.Known -> JournalEntry(now, exchange.time, excerpt, NoteOutcome.KNOWN, known = thought.facts, ms = ms)
            Thought.Empty -> JournalEntry(now, exchange.time, excerpt, NoteOutcome.NOTHING, ms = ms)
            is Thought.Failed -> JournalEntry(now, exchange.time, excerpt, NoteOutcome.FAILED, ms = ms)
        }
        addJournal(entry)
        setProcessed(exchange.time)
        entry
    }

    /** Échange lu sans rien noter ([entry] : ce qu'il faut en garder au journal). */
    suspend fun markRead(exchange: Exchange, entry: JournalEntry?) = locked {
        entry?.let(::addJournal)
        setProcessed(exchange.time)
    }

    /** Annule une prise de notes : ses faits ne seront pas renotés. */
    suspend fun undo(entry: JournalEntry) = locked {
        replaceNotes { NoteEdits.undo(it, entry.noted) }
        addForgotten(entry.noted.map { it.text })
        _journal.value = _journal.value.map { if (it.time == entry.time && it.messageTime == entry.messageTime) it.copy(undone = true) else it }
        saveJournal()
    }

    /**
     * « Oublie [what] » ; [what] vide : la dernière prise de notes. Ce qui est oublié n'est pas renoté.
     */
    suspend fun forget(what: String, messageTime: Long, excerpt: String): Forgetting = locked {
        val forgetting = if (what.isBlank()) {
            val last = _journal.value.firstOrNull { it.outcome == NoteOutcome.NOTED && !it.undone }
            if (last == null) {
                Forgetting()
            } else {
                val before = _notes.value
                replaceNotes { NoteEdits.undo(it, last.noted) }
                _journal.value = _journal.value.map { if (it === last) it.copy(undone = true) else it }
                // Ce qui a disparu des notes, pour pouvoir le remettre.
                val after = _notes.value.associateBy { it.id }
                Forgetting(
                    notes = before.filter { it.id !in after },
                    facts = last.noted.filter { it.noteId in after }.map { n ->
                        n.noteId to (before.first { it.id == n.noteId }.facts.firstOrNull { it.text == n.text } ?: Fact(n.text, today()))
                    },
                )
            }
        } else {
            var result = Forgetting()
            replaceNotes { notes -> NoteEdits.forget(notes, what, today()).also { result = it.second }.first }
            result
        }
        addForgotten(forgetting.texts)
        addJournal(JournalEntry(clock(), messageTime, excerpt, NoteOutcome.FORGOTTEN, forgotten = forgetting.texts))
        forgetting
    }

    /** Annule un « oublie… ». */
    suspend fun restore(forgetting: Forgetting) = locked {
        replaceNotes { NoteEdits.restore(it, forgetting) }
        val texts = forgetting.texts.toSet()
        forgottenTexts = forgottenTexts.filter { it !in texts }
        writeAtomic(forgottenFile, forgottenTexts.joinToString("\n"))
    }

    /**
     * Un fait corrigé ou ajouté à la main ([old] null : nouveau fait, daté du jour). Vide : le fait est
     * supprimé. La version corrigée ne sera pas renotée.
     */
    suspend fun editFact(id: String, old: Fact?, text: String, wasBefore: Boolean = false) {
        val clean = text.replace(SPACES, " ").trim()
        if (clean.isEmpty()) {
            if (old != null) deleteFact(id, old, wasBefore)
            return
        }
        if (old?.text == clean) return
        locked {
            val day = today()
            replaceNotes { notes ->
                notes.map { n ->
                    if (n.id != id) return@map n
                    fun List<Fact>.edited() = if (old == null) this + Fact(clean, day) else map { if (it == old) it.copy(text = clean) else it }
                    if (wasBefore) n.copy(before = n.before.edited(), modified = day) else n.copy(facts = n.facts.edited(), modified = day)
                }
            }
            if (old != null && !wasBefore) addForgotten(listOf(old.text))
        }
    }

    /** Supprime un fait (écran du Cerveau) : il ne sera pas renoté. */
    suspend fun deleteFact(id: String, fact: Fact, wasBefore: Boolean) = locked {
        replaceNotes { notes ->
            notes.map {
                if (it.id != id) it
                else if (wasBefore) it.copy(before = it.before - fact, modified = today())
                else it.copy(facts = it.facts - fact, modified = today())
            }
        }
        if (!wasBefore) addForgotten(listOf(fact.text))
    }

    suspend fun deleteNote(id: String) = locked {
        val note = _notes.value.firstOrNull { it.id == id } ?: return@locked
        if (note.isMe) {
            // « Moi » reste, vidée.
            replaceNotes { notes ->
                notes.map { if (it.isMe) it.copy(facts = emptyList(), before = emptyList(), links = emptyList(), extra = "", modified = today()) else it }
            }
        } else {
            replaceNotes { notes -> notes.filter { it.id != id } }
        }
        addForgotten(note.facts.map { it.text })
    }

    /** Vide tout. Comme à la première ouverture, les messages déjà échangés ne seront pas relus. */
    suspend fun clear() = lock.withLock {
        withContext(Dispatchers.IO) {
            dir.deleteRecursively()
            loaded = false
            loadFiles()
        }
    }

    private fun addJournal(entry: JournalEntry) {
        _journal.value = (listOf(entry) + _journal.value).take(MAX_JOURNAL)
        saveJournal()
    }

    private fun saveJournal() = writeAtomic(journalFile, JournalFormat.write(_journal.value))

    private fun addForgotten(texts: List<String>) {
        if (texts.isEmpty()) return
        forgottenTexts = (forgottenTexts + texts.map { it.replace('\n', ' ') }).distinct().takeLast(MAX_FORGOTTEN)
        writeAtomic(forgottenFile, forgottenTexts.joinToString("\n"))
    }

    private fun setProcessed(time: Long) {
        if (time <= processedUntil) return
        processedUntil = time
        writeAtomic(stateFile, time.toString())
    }

    companion object {
        const val MAX_JOURNAL = 60
        const val MAX_FORGOTTEN = 500
        private val SPACES = Regex("\\s+")
    }
}
