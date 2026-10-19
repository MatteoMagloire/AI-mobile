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
import com.matteomagloire.aimobile.brain.Brain
import com.matteomagloire.aimobile.llm.LlamaEngine
import com.matteomagloire.aimobile.model.ModelCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class Settings(
    /** Créativité du modèle du quotidien (le mode Réflexion garde ses propres réglages). */
    val temperature: Float = DEFAULT_TEMPERATURE,
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
    val threads: Int = LlamaEngine.DEFAULT_THREADS,
    /** Une note ni ouverte ni utilisée depuis ce nombre de jours passe en veille. */
    val dormantDays: Int = Brain.DEFAULT_DORMANT_DAYS,
    /** Le graphe du Cerveau se recentre sur la note où Axo travaille. */
    val followAxo: Boolean = true,
    /** Pendant qu'Axo réfléchit, un petit graphe de ce qu'il relit s'affiche au-dessus de sa réponse. */
    val liveInChat: Boolean = true,
)

/**
 * Créativité conseillée par Mistral pour Ministral 3 Instruct (0,1 à 0,15). Plus haut, il s'éparpille :
 * à 0,7, la valeur des premières versions, il mélangeait les sujets et inventait.
 */
const val DEFAULT_TEMPERATURE = 0.15f

/** Le modèle Reasoning garde les réglages conseillés par Mistral pour lui. */
const val REASONING_TEMPERATURE = 0.7f
const val REASONING_TOP_P = 0.95f

/** Personnalité d'Axo (cahier des charges, « Base de ses instructions »). */
const val DEFAULT_SYSTEM_PROMPT =
    "Tu es Axo, l'ami virtuel de Matteo, et c'est lui qui t'écrit. Tu n'es pas un assistant mais un ami qui est là pour l'aider. " +
        "Tu le tutoies, avec chaleur et un peu d'humour. " +
        "Honnête, tu donnes ton avis et tu dis quand tu ne sais pas.\n" +
        "Il écrit vite (« tu c'est » pour « tu sais ») : comprends ce qu'il veut dire sans relever ses fautes. " +
        "Réponds à son dernier message, sans relancer de toi-même un ancien sujet. " +
        "Écris comme entre amis : quelques phrases, sans titres, gras ni actions entre astérisques, " +
        "un emoji au plus. Une liste ou des détails seulement s'il en demande.\n" +
        "Ce que tu sais de lui arrive sous [Mémoire d'Axo] : sers-t'en si ça aide, sans le réciter, " +
        "et n'invente rien sur lui ni sur ses proches.\n" +
        "Tu vis dans son téléphone, sans internet : tu ne connais ni la météo ni l'actualité, dis-le plutôt que d'inventer. " +
        "Tu assumes d'être un ami virtuel, sans prétendre être humain.\n" +
        "Jamais de « En tant qu'IA » ni « N'hésite pas à ». Réponds en français. " +
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
        val dormantDays = intPreferencesKey("dormant_days")
        val followAxo = booleanPreferencesKey("follow_axo")
        val liveInChat = booleanPreferencesKey("live_in_chat")
        val samplingVersion = intPreferencesKey("sampling_version")
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
            p[Keys.dormantDays] = next.dormantDays
            p[Keys.followAxo] = next.followAxo
            p[Keys.liveInChat] = next.liveInChat
            p[Keys.samplingVersion] = SAMPLING_VERSION
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
            temperature = temperatureFrom(this[Keys.temperature], this[Keys.samplingVersion]),
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
            threads = (this[Keys.threads] ?: d.threads).coerceIn(LlamaEngine.MIN_THREADS, LlamaEngine.MAX_THREADS),
            dormantDays = this[Keys.dormantDays]?.takeIf { it in Brain.DORMANT_CHOICES } ?: d.dormantDays,
            followAxo = this[Keys.followAxo] ?: d.followAxo,
            liveInChat = this[Keys.liveInChat] ?: d.liveInChat,
        )
    }
}

// La créativité par défaut jusqu'à la v0.4.1, enregistrée chez tous ceux qui ont touché aux réglages,
// et le numéro des réglages qui l'a remplacée.
internal const val OLD_DEFAULT_TEMPERATURE = 0.7f
private const val SAMPLING_VERSION = 2

/**
 * Créativité enregistrée -> créativité à utiliser : l'ancienne valeur par défaut, enregistrée avant
 * les réglages n° [SAMPLING_VERSION], devient celle conseillée. Une valeur choisie depuis reste.
 */
internal fun temperatureFrom(stored: Float?, version: Int?): Float =
    if (stored == null || (version == null && stored == OLD_DEFAULT_TEMPERATURE)) DEFAULT_TEMPERATURE else stored

/** Prompt enregistré -> prompt à utiliser : l'ancien prompt par défaut devient celui d'Axo. */
internal fun systemPromptFrom(stored: String?): String =
    if (stored == null || stored == LEGACY_SYSTEM_PROMPT) DEFAULT_SYSTEM_PROMPT else stored
