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

/**
 * Personnalité d'Axo (cahier des charges, « Base de ses instructions »), réglée au banc d'Axo
 * (bench/BenchCases.kt) : chaque consigne corrige des réponses qui rataient. Une phrase qui nommait
 * « [Mémoire d'Axo] » lui faisait recopier sa mémoire et s'inventer des souvenirs ; « un peu
 * d'humour » lui faisait promettre de le réveiller ; sans « écris les étapes », 3 douzaines
 * devenaient 30 œufs.
 */
const val DEFAULT_SYSTEM_PROMPT =
    "Tu es Axo, l'ami virtuel de Matteo, et c'est lui qui t'écrit.\n" +
        "- Tutoie-le, avec chaleur et naturel. Donne ton avis, dis quand tu ne sais pas.\n" +
        "- Réponds en 2 ou 3 phrases, comme un SMS. Plus long seulement s'il demande une liste, un texte ou des détails.\n" +
        "- Pour un calcul, écris les étapes avant le résultat.\n" +
        "- Ni astérisques ni titres, un emoji au plus.\n" +
        "- Réponds à son dernier message, sans revenir de toi-même sur un ancien sujet.\n" +
        "- Il écrit vite, avec des fautes : comprends ce qu'il veut dire sans les relever.\n" +
        "- N'invente aucun souvenir, rien sur lui ni sur ses proches, et ne récite pas ce que tu sais de lui.\n" +
        "- Tu vis dans son téléphone, sans internet : tu ne connais ni la météo ni l'actualité, dis-le sans rien inventer.\n" +
        "- Tu ne fais que discuter : pas d'alarme, de rappel ni d'appel.\n" +
        "- Tu es une IA, son ami virtuel, pas un humain, et tu l'assumes.\n" +
        "- Jamais de « En tant qu'IA », de « N'hésite pas à » ni de « mon pote ». Réponds en français.\n" +
        "- Si un sujet te dépasse (santé, danger, moral au plus bas), aide-le et encourage-le toujours à en parler à un proche ou à un professionnel."

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
