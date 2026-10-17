package com.matteomagloire.aimobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.matteomagloire.aimobile.AiMobileApp
import com.matteomagloire.aimobile.llm.LlamaEngine
import com.matteomagloire.aimobile.llm.ThinkParser
import com.matteomagloire.aimobile.model.ModelCatalog
import com.matteomagloire.aimobile.model.ModelDownloadWorker
import com.matteomagloire.aimobile.model.ModelInfo
import com.matteomagloire.aimobile.model.ModelStore
import com.matteomagloire.aimobile.settings.Settings
import com.matteomagloire.aimobile.settings.SettingsRepository
import com.matteomagloire.aimobile.update.UpdateInfo
import com.matteomagloire.aimobile.update.UpdateInstaller
import com.matteomagloire.aimobile.update.UpdateStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatMessage(
    val fromUser: Boolean,
    val text: String,
    val thinking: String = "",
    val isThinking: Boolean = false,
)

/** Téléchargement d'un modèle : null = pas en cours. */
data class DownloadState(val percent: Int, val waitingForNetwork: Boolean)

sealed interface UpdateUi {
    data object Hidden : UpdateUi
    data class Available(val info: UpdateInfo) : UpdateUi
    data class Downloading(val info: UpdateInfo, val percent: Int) : UpdateUi
    data class NeedsPermission(val info: UpdateInfo) : UpdateUi
    data class Failed(val info: UpdateInfo, val message: String) : UpdateUi
}

data class ChatUiState(
    val settings: Settings = Settings(),
    val activeModel: ModelInfo = ModelCatalog.INSTRUCT,
    val modelDownloaded: Boolean = false,
    val download: DownloadState? = null,
    val engine: LlamaEngine.State = LlamaEngine.State.Empty,
    val messages: List<ChatMessage> = emptyList(),
    val generating: Boolean = false,
    val error: String? = null,
    val update: UpdateUi = UpdateUi.Hidden,
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val engine = (app as AiMobileApp).engine
    private val settingsRepo = SettingsRepository(app)
    private val store = ModelStore(app)
    private val updates = UpdateStore(app)
    private val installer = UpdateInstaller(app)
    private val workManager = WorkManager.getInstance(app)

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var generation: Job? = null
    private var downloadWatch: Job? = null
    private var dismissedUpdate: String? = null

    init {
        viewModelScope.launch {
            engine.state.collect { s -> _state.update { it.copy(engine = s) } }
        }
        viewModelScope.launch {
            settingsRepo.settings.collect { s -> _state.update { it.copy(settings = s) } }
        }
        // Change de modèle quand on active/désactive le mode réflexion.
        viewModelScope.launch {
            settingsRepo.settings.map { it.reasoningMode }.distinctUntilChanged().collect { reasoning ->
                selectModel(if (reasoning) ModelCatalog.REASONING else ModelCatalog.INSTRUCT)
            }
        }
        viewModelScope.launch {
            settingsRepo.settings.map { it.temperature to it.topP }.distinctUntilChanged().collect { (t, p) ->
                engine.updateSampler(t, p)
            }
        }
        viewModelScope.launch {
            updates.observe().collect { info ->
                _state.update { s ->
                    val keep = s.update !is UpdateUi.Hidden && s.update !is UpdateUi.Available
                    when {
                        keep -> s
                        info == null || info.version == dismissedUpdate -> s.copy(update = UpdateUi.Hidden)
                        else -> s.copy(update = UpdateUi.Available(info))
                    }
                }
            }
        }
    }

    private fun selectModel(model: ModelInfo) {
        generation?.cancel()
        _state.update {
            it.copy(activeModel = model, modelDownloaded = store.isDownloaded(model), messages = emptyList())
        }
        watchDownload(model)
        if (store.isDownloaded(model)) loadAndStart(model)
    }

    private fun watchDownload(model: ModelInfo) {
        downloadWatch?.cancel()
        downloadWatch = viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(ModelDownloadWorker.workName(model)).collect { infos ->
                val info = infos.firstOrNull() ?: return@collect
                if (_state.value.activeModel.id != model.id) return@collect
                when (info.state) {
                    WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                        val percent = info.progress.getInt(ModelDownloadWorker.KEY_PROGRESS, 0)
                        _state.update {
                            it.copy(download = DownloadState(percent, info.state != WorkInfo.State.RUNNING))
                        }
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        if (_state.value.modelDownloaded) return@collect
                        _state.update { it.copy(download = null, modelDownloaded = store.isDownloaded(model)) }
                        if (store.isDownloaded(model)) loadAndStart(model)
                    }
                    WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                        _state.update { it.copy(download = null, error = "Le téléchargement du modèle a échoué") }
                    }
                }
            }
        }
    }

    private fun loadAndStart(model: ModelInfo) {
        viewModelScope.launch {
            try {
                engine.load(model.id, store.fileFor(model))
                if (engine.state.value is LlamaEngine.State.Ready) startConversation()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    private suspend fun startConversation() {
        val s = _state.value.settings
        engine.startConversation(s.systemPrompt, s.temperature, s.topP)
    }

    fun downloadModel() {
        ModelDownloadWorker.enqueue(getApplication(), _state.value.activeModel, _state.value.settings.wifiOnlyDownloads)
    }

    fun send(text: String) {
        val message = text.trim()
        if (message.isEmpty() || _state.value.generating || _state.value.engine !is LlamaEngine.State.Ready) return
        _state.update {
            it.copy(
                messages = it.messages + ChatMessage(fromUser = true, text = message) + ChatMessage(fromUser = false, text = ""),
                generating = true,
                error = null,
            )
        }
        generation = viewModelScope.launch {
            val raw = StringBuilder()
            try {
                engine.generate(message, _state.value.settings.maxTokens).collect { piece ->
                    raw.append(piece)
                    val parsed = ThinkParser.parse(raw.toString())
                    replaceLast(ChatMessage(false, parsed.answer, parsed.thinking, parsed.isThinking))
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) _state.update { it.copy(error = e.message) }
            } finally {
                _state.update { it.copy(generating = false) }
            }
        }
    }

    private fun replaceLast(message: ChatMessage) {
        _state.update { it.copy(messages = it.messages.dropLast(1) + message) }
    }

    fun stop() {
        generation?.cancel()
    }

    fun newConversation() {
        generation?.cancel()
        _state.update { it.copy(messages = emptyList(), error = null) }
        viewModelScope.launch {
            generation?.join()
            if (engine.state.value is LlamaEngine.State.Ready) startConversation()
        }
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        viewModelScope.launch { settingsRepo.update(transform) }
    }

    fun setReasoningMode(enabled: Boolean) = updateSettings { it.copy(reasoningMode = enabled) }

    fun dismissError() = _state.update { it.copy(error = null) }

    // --- Mises à jour de l'app ---

    fun dismissUpdate() {
        val current = _state.value.update
        val info = when (current) {
            is UpdateUi.Available -> current.info
            is UpdateUi.NeedsPermission -> current.info
            is UpdateUi.Failed -> current.info
            else -> null
        }
        dismissedUpdate = info?.version
        _state.update { it.copy(update = UpdateUi.Hidden) }
    }

    fun installUpdate() {
        val info = when (val u = _state.value.update) {
            is UpdateUi.Available -> u.info
            is UpdateUi.NeedsPermission -> u.info
            is UpdateUi.Failed -> u.info
            else -> return
        }
        if (!installer.canInstall()) {
            _state.update { it.copy(update = UpdateUi.NeedsPermission(info)) }
            installer.openInstallPermissionSettings()
            return
        }
        _state.update { it.copy(update = UpdateUi.Downloading(info, 0)) }
        viewModelScope.launch {
            try {
                installer.downloadAndInstall(info) { p ->
                    _state.update { it.copy(update = UpdateUi.Downloading(info, p)) }
                }
                _state.update { it.copy(update = UpdateUi.Hidden) }
            } catch (e: Exception) {
                _state.update { it.copy(update = UpdateUi.Failed(info, e.message ?: "Erreur inconnue")) }
            }
        }
    }
}
