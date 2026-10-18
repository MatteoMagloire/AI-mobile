package com.matteomagloire.aimobile.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class Settings(
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val maxTokens: Int = 512,
    val reasoningMode: Boolean = false,
    val wifiOnlyDownloads: Boolean = true,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
)

const val DEFAULT_SYSTEM_PROMPT =
    "Tu es un assistant personnel qui tourne entièrement sur le téléphone de l'utilisateur, sans internet. " +
        "Réponds en français, de façon claire et concise."

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val temperature = floatPreferencesKey("temperature")
        val topP = floatPreferencesKey("top_p")
        val maxTokens = intPreferencesKey("max_tokens")
        val reasoningMode = booleanPreferencesKey("reasoning_mode")
        val wifiOnly = booleanPreferencesKey("wifi_only_downloads")
        val systemPrompt = stringPreferencesKey("system_prompt")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { it.toSettings() }

    suspend fun update(transform: (Settings) -> Settings) {
        context.dataStore.edit { p ->
            val next = transform(p.toSettings())
            p[Keys.temperature] = next.temperature
            p[Keys.topP] = next.topP
            p[Keys.maxTokens] = next.maxTokens
            p[Keys.reasoningMode] = next.reasoningMode
            p[Keys.wifiOnly] = next.wifiOnlyDownloads
            p[Keys.systemPrompt] = next.systemPrompt
        }
    }

    private fun Preferences.toSettings(): Settings {
        val d = Settings()
        return Settings(
            temperature = this[Keys.temperature] ?: d.temperature,
            topP = this[Keys.topP] ?: d.topP,
            maxTokens = this[Keys.maxTokens] ?: d.maxTokens,
            reasoningMode = this[Keys.reasoningMode] ?: d.reasoningMode,
            wifiOnlyDownloads = this[Keys.wifiOnly] ?: d.wifiOnlyDownloads,
            systemPrompt = this[Keys.systemPrompt] ?: d.systemPrompt,
        )
    }
}
