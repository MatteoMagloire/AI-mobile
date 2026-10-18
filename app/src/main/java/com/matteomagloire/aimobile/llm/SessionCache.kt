package com.matteomagloire.aimobile.llm

import android.content.Context
import android.util.Log
import com.matteomagloire.aimobile.chat.HistoryWindow
import com.matteomagloire.aimobile.chat.Turn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Reprise rapide de la conversation, sans tout relire (la relecture prend presque une minute).
 *
 * - Session : le KV de toute la conversation, écrit quand l'app passe en arrière-plan ou change de
 *   modèle. Au démarrage, il est rechargé si la conversation enregistrée commence par les mêmes
 *   messages ; seuls les messages arrivés depuis sont relus (par exemple ceux échangés avec l'autre modèle).
 * - Prompt : le KV du prompt système seul, pour ne pas le relire quand la session ne sert pas.
 *
 * Toutes les opérations sur la conversation du moteur passent par [use], une à la fois : reprise,
 * réponse, effacement, enregistrement et mesures ne peuvent pas s'intercaler.
 */
class SessionCache(context: Context) {
    private val dir = File(context.cacheDir, "kv").apply { mkdirs() }

    // Présent seulement pendant un rechargement : s'il reste au démarrage, le rechargement
    // précédent a planté l'app, et on repart sans cache plutôt que de replanter en boucle.
    private val guard = File(dir, "restore.lock")

    private val lock = Mutex()
    private val session = Session()

    // La conversation qui est dans le moteur, les messages qu'elle contient, et s'ils ont changé
    // depuis le dernier enregistrement. Lus et modifiés seulement sous [lock].
    private var current: Key? = null
    private var covered: List<Turn> = emptyList()
    private var dirty = false

    init {
        if (guard.exists()) {
            Log.w(TAG, "le dernier rechargement de session a échoué : cache vidé")
            clearFiles()
        }
    }

    /** Ce qui doit être identique pour réutiliser un KV. */
    data class Key(
        val modelId: String,
        val modelBytes: Long,
        val contextSize: Int,
        val systemPrompt: String,
        val format: String,
    )

    private data class Meta(
        val key: Key,
        val messageCount: Int,
        val messagesHash: String,
        val systemPromptTokens: Int,
        val bytes: Long,
    )

    enum class Resume {
        /** La conversation était déjà dans le moteur. */
        MEMORY,

        /** Rechargée depuis la session enregistrée. */
        SESSION,

        /** Prompt système gardé, derniers messages relus. */
        PROMPT,

        /** Tout relu. */
        FULL,
    }

    suspend fun <T> use(block: suspend Session.() -> T): T = lock.withLock { session.block() }

    inner class Session internal constructor() {
        /** Modèle dont la conversation est dans le moteur, null s'il sert à autre chose (mesures). */
        val modelId: String? get() = current?.modelId

        /**
         * Prépare le moteur (déjà chargé avec le modèle de [key]) pour la conversation [turns],
         * tous les messages du plus ancien au plus récent (voir [HistoryWindow.turns]).
         */
        suspend fun resume(engine: LlamaEngine, key: Key, turns: List<Turn>): Resume {
            val budget = HistoryWindow.DEFAULT_BUDGET_CHARS

            // 1. Déjà là (retour sur l'app, réponse arrivée pendant qu'on était ailleurs).
            if (current == key && startsWith(turns, covered) &&
                HistoryWindow.chars(turns.subList(covered.size, turns.size)) <= budget
            ) {
                engine.replay(turns.subList(covered.size, turns.size))
                if (turns.size > covered.size) dirty = true
                covered = turns
                return Resume.MEMORY
            }
            current = null
            covered = emptyList()
            dirty = false

            // 2. La session enregistrée, plus les messages arrivés depuis.
            val sessionFile = sessionFile(key.modelId)
            val meta = readMeta(sessionFile)
            if (meta != null && meta.key == key && meta.messageCount <= turns.size &&
                hash(turns.subList(0, meta.messageCount)) == meta.messagesHash &&
                HistoryWindow.chars(turns.subList(meta.messageCount, turns.size)) <= budget
            ) {
                val inKv = HistoryWindow.select(turns.subList(0, meta.messageCount))
                val restored = guarded {
                    engine.restoreSession(sessionFile, meta.bytes, key.systemPrompt, meta.systemPromptTokens, inKv)
                }
                if (restored) {
                    engine.replay(turns.subList(meta.messageCount, turns.size))
                    current = key
                    covered = turns
                    dirty = turns.size > meta.messageCount
                    return Resume.SESSION
                }
                delete(sessionFile)
            }

            // 3. Le prompt système (en mémoire ou enregistré), puis les derniers messages.
            var how = Resume.FULL
            if (engine.hasSystemPromptInMemory(key.systemPrompt)) {
                engine.startConversation(key.systemPrompt)
                how = Resume.PROMPT
            } else {
                val promptFile = promptFile(key.modelId)
                val pm = readMeta(promptFile)
                if (pm != null && pm.key == key &&
                    guarded { engine.restoreSession(promptFile, pm.bytes, key.systemPrompt, pm.systemPromptTokens, emptyList()) }
                ) {
                    how = Resume.PROMPT
                } else {
                    engine.startConversation(key.systemPrompt)
                    write(engine, promptFile, key, emptyList())
                }
            }
            engine.replay(HistoryWindow.select(turns))
            current = key
            covered = turns
            dirty = turns.isNotEmpty()
            return how
        }

        /** À appeler après chaque réponse : [turns] sont maintenant tous dans le moteur. */
        fun update(turns: List<Turn>) {
            if (current == null) return
            covered = turns
            dirty = true
        }

        /** Enregistre la conversation du moteur si elle a changé. */
        suspend fun save(engine: LlamaEngine): Boolean {
            val key = current ?: return false
            if (!dirty) return true
            if (engine.loadedModelId() != key.modelId) return false
            val ok = write(engine, sessionFile(key.modelId), key, covered)
            if (ok) dirty = false
            return ok
        }

        /** Le contexte du moteur n'est plus fiable (erreur) : il sera reconstruit, sans être enregistré. */
        fun invalidate() {
            current = null
            covered = emptyList()
            dirty = false
        }

        /** Le moteur va servir à autre chose (changement de modèle, mesures) : on garde la conversation. */
        suspend fun detach(engine: LlamaEngine) {
            save(engine)
            current = null
            covered = emptyList()
            dirty = false
        }

        /** Conversation effacée : les sessions enregistrées ne servent plus (les prompts, si). */
        fun forgetSessions() {
            dir.listFiles()?.filter { it.name.contains(".session") }?.forEach { it.delete() }
            current = null
            covered = emptyList()
            dirty = false
        }

        /** Fichier du modèle supprimé : son cache aussi. */
        fun forgetModel(modelId: String) {
            delete(sessionFile(modelId))
            delete(promptFile(modelId))
            if (current?.modelId == modelId) {
                current = null
                covered = emptyList()
                dirty = false
            }
        }
    }

    private fun sessionFile(modelId: String) = File(dir, "$modelId.session")
    private fun promptFile(modelId: String) = File(dir, "$modelId.prompt")
    private fun metaFile(file: File) = File(file.path + ".json")

    private fun clearFiles() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private suspend fun write(engine: LlamaEngine, file: File, key: Key, turns: List<Turn>): Boolean {
        // Sans méta, un fichier n'est jamais rechargé : on l'enlève d'abord, on l'écrit en dernier.
        withContext(Dispatchers.IO) { metaFile(file).delete() }
        val tmp = File(file.path + ".tmp")
        val saved = engine.saveSession(tmp)
        return withContext(Dispatchers.IO) {
            if (saved == null || !tmp.renameTo(file)) {
                tmp.delete()
                file.delete()
                return@withContext false
            }
            val meta = Meta(key, turns.size, hash(turns), saved.systemPromptTokens, saved.bytes)
            val json = File(metaFile(file).path + ".tmp")
            json.writeText(toJson(meta).toString())
            json.renameTo(metaFile(file))
        }
    }

    private fun delete(file: File) {
        metaFile(file).delete()
        file.delete()
    }

    private suspend fun guarded(block: suspend () -> Boolean): Boolean {
        withContext(Dispatchers.IO) { guard.writeText("1") }
        return try {
            block()
        } finally {
            withContext(Dispatchers.IO) { guard.delete() }
        }
    }

    private suspend fun readMeta(file: File): Meta? = withContext(Dispatchers.IO) {
        val meta = metaFile(file)
        if (!file.exists() || !meta.exists()) return@withContext null
        try {
            val o = JSONObject(meta.readText())
            Meta(
                key = Key(
                    modelId = o.getString("model"),
                    modelBytes = o.getLong("modelBytes"),
                    contextSize = o.getInt("context"),
                    systemPrompt = o.getString("systemPrompt"),
                    format = o.getString("format"),
                ),
                messageCount = o.getInt("messages"),
                messagesHash = o.getString("hash"),
                systemPromptTokens = o.getInt("systemPromptTokens"),
                bytes = o.getLong("bytes"),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun toJson(m: Meta) = JSONObject()
        .put("model", m.key.modelId)
        .put("modelBytes", m.key.modelBytes)
        .put("context", m.key.contextSize)
        .put("systemPrompt", m.key.systemPrompt)
        .put("format", m.key.format)
        .put("messages", m.messageCount)
        .put("hash", m.messagesHash)
        .put("systemPromptTokens", m.systemPromptTokens)
        .put("bytes", m.bytes)

    companion object {
        private const val TAG = "SessionCache"

        /** Change quand llama.cpp ou la façon de remplir le contexte change. */
        val FORMAT: String get() = "llama-${LlamaEngine.buildRef}-v1"

        /** Empreinte d'une liste de messages : même empreinte = même conversation. */
        fun hash(turns: List<Turn>): String {
            val md = MessageDigest.getInstance("SHA-256")
            for ((fromUser, text) in turns) {
                md.update((if (fromUser) 1 else 2).toByte())
                md.update(text.toByteArray(Charsets.UTF_8))
                md.update(0.toByte())
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        fun startsWith(list: List<Turn>, prefix: List<Turn>): Boolean =
            prefix.size <= list.size && list.subList(0, prefix.size) == prefix
    }
}
