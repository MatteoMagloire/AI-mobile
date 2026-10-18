package com.matteomagloire.aimobile.settings

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.matteomagloire.aimobile.model.ModelCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class Settings(
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val maxTokens: Int = 512,
    val reasoningMode: Boolean = false,
    val wifiOnlyDownloads: Boolean = true,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    /** Fichier utilisé pour chaque modèle (choisi dans « Comparer les fichiers »). */
    val instructModelId: String = ModelCatalog.INSTRUCT.id,
    val reasoningModelId: String = ModelCatalog.REASONING.id,
    /** Affiche le temps du premier mot et la vitesse sous les réponses d'Axo. */
    val showSpeed: Boolean = false,
    /** Cœurs utilisés par le modèle (2 à 4) : moins de cœurs, moins de chauffe. */
    val threads: Int = 4,
)

/** Personnalité d'Axo (cahier des charges, « Base de ses instructions »). */
const val DEFAULT_SYSTEM_PROMPT =
    "Tu es Axo, l'ami virtuel de Matteo. Tu n'es pas un assistant : tu es un ami qui est là pour l'aider. " +
        "Tu le tutoies, tu parles naturellement, avec chaleur et un peu d'humour. " +
        "Tu te souviens de ce qu'il te confie et tu t'en sers. " +
        "Tu es honnête, tu donnes ton avis, et tu dis quand tu ne sais pas.\n" +
        "Tu vis dans son téléphone et tu fonctionnes sans internet : tu ne connais ni la météo ni l'actualité, " +
        "et tu le dis simplement plutôt que d'inventer. " +
        "Si on te demande ce que tu es, tu assumes d'être son ami virtuel, sans prétendre être humain.\n" +
        "Jamais de formules d'assistant comme « En tant qu'IA » ou « N'hésite pas à ». " +
        "Réponds en français, plutôt court, sauf s'il veut des détails. " +
        "Si un sujet te dépasse (santé, danger, moral au plus bas), aide-le et encourage-le à en parler " +
        "à quelqu'un de confiance ou à un professionnel."

/** Prompt par défaut de la v0.1, encore enregistré chez ceux qui ne l'ont jamais modifié. */
internal const val LEGACY_SYSTEM_PROMPT =
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
        val instructModel = stringPreferencesKey("instruct_model")
        val reasoningModel = stringPreferencesKey("reasoning_model")
        val showSpeed = booleanPreferencesKey("show_speed")
        val threads = intPreferencesKey("threads")
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
            p[Keys.instructModel] = next.instructModelId
            p[Keys.reasoningModel] = next.reasoningModelId
            p[Keys.showSpeed] = next.showSpeed
            p[Keys.threads] = next.threads
            p.storeSystemPrompt(next.systemPrompt)
        }
    }

    // Le prompt par défaut n'est pas enregistré : une nouvelle version de l'app peut l'améliorer.
    private fun MutablePreferences.storeSystemPrompt(prompt: String) {
        if (prompt == DEFAULT_SYSTEM_PROMPT) remove(Keys.systemPrompt) else this[Keys.systemPrompt] = prompt
    }

    private fun Preferences.toSettings(): Settings {
        val d = Settings()
        return Settings(
            temperature = this[Keys.temperature] ?: d.temperature,
            topP = this[Keys.topP] ?: d.topP,
            maxTokens = this[Keys.maxTokens] ?: d.maxTokens,
            reasoningMode = this[Keys.reasoningMode] ?: d.reasoningMode,
            wifiOnlyDownloads = this[Keys.wifiOnly] ?: d.wifiOnlyDownloads,
            systemPrompt = systemPromptFrom(this[Keys.systemPrompt]),
            instructModelId = this[Keys.instructModel]?.takeIf { ModelCatalog.byId(it)?.reasoning == false }
                ?: d.instructModelId,
            reasoningModelId = this[Keys.reasoningModel]?.takeIf { ModelCatalog.byId(it)?.reasoning == true }
                ?: d.reasoningModelId,
            showSpeed = this[Keys.showSpeed] ?: d.showSpeed,
            threads = (this[Keys.threads] ?: d.threads).coerceIn(2, 4),
        )
    }
}

/** Prompt enregistré -> prompt à utiliser : l'ancien prompt par défaut devient celui d'Axo. */
internal fun systemPromptFrom(stored: String?): String =
    if (stored == null || stored == LEGACY_SYSTEM_PROMPT) DEFAULT_SYSTEM_PROMPT else stored
