package com.matteomagloire.aimobile.chat

import android.content.Context
import com.matteomagloire.aimobile.ui.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** L'unique conversation, gardée sur le téléphone pour reprendre là où on s'était arrêté. */
class ConversationStore(context: Context) {
    private val file = File(context.filesDir, "conversation.json")

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
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun save(messages: List<ChatMessage>) = withContext(Dispatchers.IO) {
        val array = JSONArray()
        messages.filter { it.fromUser || it.text.isNotEmpty() }.forEach { m ->
            array.put(
                JSONObject()
                    .put("user", m.fromUser)
                    .put("text", m.text)
                    .apply { if (m.thinking.isNotEmpty()) put("thinking", m.thinking) },
            )
        }
        // Écriture dans un fichier temporaire puis renommage : pas de fichier à moitié écrit si l'app est tuée.
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(array.toString())
        tmp.renameTo(file)
    }

    suspend fun clear() = withContext(Dispatchers.IO) { file.delete() }
}

/**
 * Choisit les derniers messages à redonner au modèle au démarrage : le contexte (4096 tokens)
 * ne peut pas tout contenir. Les plus anciens restent affichés mais le modèle ne les voit plus.
 */
object HistoryWindow {
    /** Environ 2 000 tokens de français : laisse la place au prompt système et à la réponse. */
    const val DEFAULT_BUDGET_CHARS = 7_000

    fun select(messages: List<ChatMessage>, budgetChars: Int = DEFAULT_BUDGET_CHARS): List<ChatMessage> {
        val usable = messages.filter { it.fromUser || it.text.isNotEmpty() }
        var used = 0
        var start = usable.size
        while (start > 0) {
            val size = usable[start - 1].text.length
            if (used + size > budgetChars) break
            used += size
            start--
        }
        // On commence toujours par un message de l'utilisateur, comme une vraie conversation.
        while (start < usable.size && !usable[start].fromUser) start++
        return usable.subList(start, usable.size)
    }
}
