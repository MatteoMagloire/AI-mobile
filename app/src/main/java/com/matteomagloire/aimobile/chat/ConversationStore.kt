package com.matteomagloire.aimobile.chat

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ChatMessage(
    val fromUser: Boolean,
    val text: String,
    val thinking: String = "",
    val isThinking: Boolean = false,
    /** Heure d'envoi (ms depuis 1970), 0 pour les messages de la v0.1. */
    val time: Long = 0,
    /** Durée de la réflexion (mode Réflexion), 0 sinon. */
    val thinkingMs: Long = 0,
    /** Temps avant le premier mot, et vitesse d'écriture de la réponse. */
    val firstWordMs: Long = 0,
    val tokensPerSecond: Float = 0f,
)

/** L'unique conversation, gardée sur le téléphone pour reprendre là où on s'était arrêté. */
class ConversationStore(context: Context) {
    private val file = File(context.filesDir, "conversation.json")

    // Deux enregistrements ne doivent pas écrire le fichier temporaire en même temps.
    private val lock = Mutex()

    suspend fun load(): List<ChatMessage> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        try {
            val array = JSONArray(file.readText())
            List(array.length()) { i ->
                val o = array.getJSONObject(i)
                ChatMessage(
                    fromUser = o.getBoolean("user"),
                    text = o.optString("text"),
                    thinking = o.optString("thinking"),
                    time = o.optLong("time"),
                    thinkingMs = o.optLong("thinkingMs"),
                    firstWordMs = o.optLong("firstWordMs"),
                    tokensPerSecond = o.optDouble("tps", 0.0).toFloat(),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun save(messages: List<ChatMessage>) = lock.withLock {
        withContext(Dispatchers.IO) {
            val array = JSONArray()
            messages.toSave().forEach { m ->
                array.put(
                    JSONObject()
                        .put("user", m.fromUser)
                        .put("text", m.text)
                        .apply {
                            if (m.thinking.isNotEmpty()) put("thinking", m.thinking)
                            if (m.time > 0) put("time", m.time)
                            if (m.thinkingMs > 0) put("thinkingMs", m.thinkingMs)
                            if (m.firstWordMs > 0) put("firstWordMs", m.firstWordMs)
                            if (m.tokensPerSecond > 0) put("tps", m.tokensPerSecond.toDouble())
                        },
                )
            }
            // Écriture dans un fichier temporaire puis renommage : pas de fichier à moitié écrit si l'app est tuée.
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(array.toString())
            tmp.renameTo(file)
        }
    }

    suspend fun clear() = lock.withLock { withContext(Dispatchers.IO) { file.delete() } }
}

/** Les messages gardés dans le fichier : une réponse arrêtée avant le premier mot n'y est pas. */
internal fun List<ChatMessage>.toSave() = filter { it.fromUser || it.text.isNotEmpty() || it.thinking.isNotEmpty() }

/** Un message vu par le modèle : `true` = l'utilisateur, `false` = Axo. */
typealias Turn = Pair<Boolean, String>

/**
 * Choisit les derniers messages à redonner au modèle au démarrage : le contexte (4096 tokens)
 * ne peut pas tout contenir. Les plus anciens restent affichés mais le modèle ne les voit plus.
 */
object HistoryWindow {
    /** Environ 2 000 tokens de français : laisse la place au prompt système et à la réponse. */
    const val DEFAULT_BUDGET_CHARS = 7_000

    /**
     * La conversation telle que le modèle la voit : chaque message de l'utilisateur est suivi d'une
     * réponse (vide si elle a été arrêtée avant le premier mot), comme l'exige le template de chat.
     */
    fun turns(messages: List<ChatMessage>): List<Turn> {
        val out = ArrayList<Turn>(messages.size + 1)
        for (m in messages) {
            if (!m.fromUser && m.text.isEmpty()) continue
            val last = out.lastOrNull()
            if (m.fromUser && last != null && last.first) out.add(false to "")
            if (!m.fromUser && (last == null || !last.first)) continue
            out.add(m.fromUser to m.text)
        }
        if (out.lastOrNull()?.first == true) out.add(false to "")
        return out
    }

    /** Les derniers messages qui tiennent dans [budgetChars], en commençant par un message de l'utilisateur. */
    fun select(turns: List<Turn>, budgetChars: Int = DEFAULT_BUDGET_CHARS): List<Turn> {
        var used = 0
        var start = turns.size
        while (start > 0) {
            val size = turns[start - 1].second.length
            if (used + size > budgetChars) break
            used += size
            start--
        }
        // On commence toujours par un message de l'utilisateur, comme une vraie conversation.
        while (start < turns.size && !turns[start].first) start++
        return turns.subList(start, turns.size)
    }

    fun chars(turns: List<Turn>): Int = turns.sumOf { it.second.length }
}
