package com.matteomagloire.aimobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.matteomagloire.aimobile.AiMobileApp
import com.matteomagloire.aimobile.chat.ChatMessage
import com.matteomagloire.aimobile.chat.HistoryWindow
import com.matteomagloire.aimobile.llm.LlamaEngine
import com.matteomagloire.aimobile.llm.SessionCache
import com.matteomagloire.aimobile.llm.ThinkParser
import com.matteomagloire.aimobile.measure.BENCH_QUESTIONS
import com.matteomagloire.aimobile.measure.BenchAnswer
import com.matteomagloire.aimobile.measure.BenchRun
import com.matteomagloire.aimobile.measure.BenchSettings
import com.matteomagloire.aimobile.measure.BenchState
import com.matteomagloire.aimobile.measure.DeviceStats
import com.matteomagloire.aimobile.measure.EndurancePoint
import com.matteomagloire.aimobile.measure.EnduranceResult
import com.matteomagloire.aimobile.measure.MeasureStore
import com.matteomagloire.aimobile.measure.SpeedResult
import com.matteomagloire.aimobile.measure.SpeedTestPrompts
import com.matteomagloire.aimobile.measure.Targets
import com.matteomagloire.aimobile.model.ModelCatalog
import com.matteomagloire.aimobile.model.ModelDownloadWorker
import com.matteomagloire.aimobile.model.ModelInfo
import com.matteomagloire.aimobile.model.ModelStore
import com.matteomagloire.aimobile.settings.DEFAULT_SYSTEM_PROMPT
import com.matteomagloire.aimobile.settings.Settings
import com.matteomagloire.aimobile.settings.SettingsRepository
import com.matteomagloire.aimobile.update.UpdateInfo
import com.matteomagloire.aimobile.update.UpdateInstaller
import com.matteomagloire.aimobile.update.UpdateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Téléchargement d'un modèle : null = pas en cours. */
data class DownloadState(val percent: Int, val waitingForNetwork: Boolean)

sealed interface UpdateUi {
    data object Hidden : UpdateUi
    data class Available(val info: UpdateInfo) : UpdateUi
    data class Downloading(val info: UpdateInfo, val percent: Int) : UpdateUi
    data class NeedsPermission(val info: UpdateInfo) : UpdateUi
    data class Failed(val info: UpdateInfo, val message: String) : UpdateUi
}

enum class MeasureKind { SPEED, ENDURANCE, BENCH }

/** Test de vitesse, endurance et comparaison des fichiers. */
data class MeasureUi(
    val running: MeasureKind? = null,
    val step: String = "",
    /** Avancement de 0 à 1, null si on ne sait pas. */
    val progress: Float? = null,
    val speed: List<SpeedResult> = emptyList(),
    val endurance: List<EnduranceResult> = emptyList(),
    /** Points de l'endurance en cours. */
    val enduranceLive: List<EndurancePoint> = emptyList(),
    val benchInstruct: BenchState = BenchState(reasoning = false),
    val benchReasoning: BenchState = BenchState(reasoning = true),
    val error: String? = null,
) {
    fun bench(reasoning: Boolean) = if (reasoning) benchReasoning else benchInstruct
}

data class ChatUiState(
    val settings: Settings = Settings(),
    val activeModel: ModelInfo = ModelCatalog.INSTRUCT,
    val modelDownloaded: Boolean = false,
    /** Téléchargements en cours, par identifiant de modèle. */
    val downloads: Map<String, DownloadState> = emptyMap(),
    /** Modèles présents sur le téléphone. */
    val downloaded: Set<String> = emptySet(),
    val engine: LlamaEngine.State = LlamaEngine.State.Empty,
    /** La conversation est reprise dans le moteur : on peut écrire à Axo. */
    val conversationReady: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val generating: Boolean = false,
    val error: String? = null,
    val update: UpdateUi = UpdateUi.Hidden,
    /** Proposition de télécharger le modèle Reasoning (affichée quand on active « Réflexion »). */
    val reasoningOffer: Boolean = false,
    val notice: String? = null,
    val measure: MeasureUi = MeasureUi(),
    /** Version de llama.cpp, cœurs, contexte : affiché dans les réglages et le test de vitesse. */
    val engineInfo: String = "",
) {
    val download: DownloadState? get() = downloads[activeModel.id]
    val reasoningModel: ModelInfo get() = ModelCatalog.byId(settings.reasoningModelId) ?: ModelCatalog.REASONING

    /** Téléchargement du modèle Reasoning en arrière-plan, pendant qu'on continue à discuter. */
    val reasoningDownload: DownloadState? get() = downloads[reasoningModel.id]
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val axo = app as AiMobileApp
    private val engine = axo.engine
    private val sessions = axo.sessions
    private val conversation = axo.conversation
    private val settingsRepo = SettingsRepository(app)
    private val store = ModelStore(app)
    private val measures = MeasureStore(app)
    private val updates = UpdateStore(app)
    private val installer = UpdateInstaller(app)
    private val workManager = WorkManager.getInstance(app)

    private val _state = MutableStateFlow(ChatUiState(downloaded = downloadedIds()))
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val conversationLoaded: Job
    private var opening: Job? = null
    private var generation: Job? = null
    private var measure: Job? = null
    private var dismissedUpdate: String? = null

    // Téléchargements vus en cours depuis l'ouverture : seuls ceux-là sont annoncés à la fin.
    private val seenDownloads = mutableSetOf<String>()

    init {
        conversationLoaded = viewModelScope.launch {
            val saved = conversation.load()
            _state.update { it.copy(messages = saved) }
        }
        viewModelScope.launch {
            engine.state.collect { s -> _state.update { it.copy(engine = s) } }
        }
        viewModelScope.launch {
            settingsRepo.settings.collect { s -> _state.update { it.copy(settings = s) } }
        }
        // Modèle actif : suit le mode Réflexion et le fichier choisi pour chaque modèle.
        viewModelScope.launch {
            settingsRepo.settings.map { activeModelFor(it) }.distinctUntilChanged().collect { model ->
                if (model.reasoning && !store.isDownloaded(model)) {
                    // Modèle Reasoning absent : on reste en mode rapide.
                    settingsRepo.update { it.copy(reasoningMode = false) }
                } else {
                    selectModel(model)
                }
            }
        }
        watchDownloads()
        viewModelScope.launch(Dispatchers.IO) {
            // Charge la bibliothèque native hors du fil de l'interface.
            val info = try {
                "llama.cpp ${LlamaEngine.buildRef} · ${LlamaEngine.DEFAULT_THREADS} cœurs · contexte ${LlamaEngine.DEFAULT_CONTEXT}"
            } catch (e: Throwable) {
                ""
            }
            _state.update { it.copy(engineInfo = info) }
        }
        viewModelScope.launch {
            val speed = measures.loadSpeed()
            val endurance = measures.loadEndurance()
            val benchInstruct = measures.loadBench(reasoning = false)
            val benchReasoning = measures.loadBench(reasoning = true)
            updateMeasure {
                it.copy(speed = speed, endurance = endurance, benchInstruct = benchInstruct, benchReasoning = benchReasoning)
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

    private fun activeModelFor(s: Settings): ModelInfo =
        if (s.reasoningMode) ModelCatalog.byId(s.reasoningModelId) ?: ModelCatalog.REASONING
        else ModelCatalog.byId(s.instructModelId) ?: ModelCatalog.INSTRUCT

    private fun downloadedIds(): Set<String> = ModelCatalog.all.filter { store.isDownloaded(it) }.map { it.id }.toSet()

    private fun refreshDownloaded() = _state.update { it.copy(downloaded = downloadedIds()) }

    private fun selectModel(model: ModelInfo) {
        generation?.cancel()
        _state.update { it.copy(activeModel = model, modelDownloaded = store.isDownloaded(model)) }
        if (store.isDownloaded(model)) openChat()
    }

    // --- Conversation ---

    /**
     * Charge le modèle actif et y reprend la conversation : rechargement de la session enregistrée
     * si possible (instantané), sinon relecture des derniers messages.
     */
    private fun openChat() {
        val previous = opening
        opening = viewModelScope.launch {
            // Les mesures et les réponses en cours passent avant : elles tiennent le verrou de la conversation.
            previous?.join()
            conversationLoaded.join()
            _state.update { it.copy(conversationReady = false) }
            val model = _state.value.activeModel
            if (!store.isDownloaded(model)) return@launch
            try {
                val settings = settingsRepo.settings.first()
                val file = store.fileFor(model)
                val turns = HistoryWindow.turns(_state.value.messages)
                val resumed = sessions.use {
                    if (engine.loadedModelId() != model.id) {
                        detach(engine)
                        engine.load(model.id, file)
                    }
                    if (engine.loadedModelId() != model.id) return@use false
                    val key = SessionCache.Key(
                        modelId = model.id,
                        modelBytes = file.length(),
                        contextSize = LlamaEngine.DEFAULT_CONTEXT,
                        systemPrompt = settings.systemPrompt,
                        format = SessionCache.FORMAT,
                    )
                    resume(engine, key, turns)
                    true
                }
                // Le modèle a pu changer pendant la reprise (bouton Réflexion) : la suivante prend le relais.
                if (resumed && engine.loadedModelId() == _state.value.activeModel.id) {
                    _state.update { it.copy(conversationReady = true) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    private fun saveConversation() {
        val messages = _state.value.messages
        // Hors du scope de l'écran : l'enregistrement finit même si l'écran est fermé.
        axo.appScope.launch { conversation.save(messages) }
    }

    fun downloadModel() = download(_state.value.activeModel)

    fun send(text: String) {
        val message = text.trim()
        val s = _state.value
        if (message.isEmpty() || s.generating || !s.conversationReady || measure?.isActive == true) return
        val now = System.currentTimeMillis()
        _state.update {
            it.copy(
                messages = it.messages + ChatMessage(fromUser = true, text = message, time = now) +
                    ChatMessage(fromUser = false, text = "", time = now),
                generating = true,
                error = null,
            )
        }
        saveConversation()
        generation = viewModelScope.launch {
            val settings = _state.value.settings
            val raw = StringBuilder()
            val start = System.nanoTime()
            var firstWordMs = 0L
            var thinkingStart = 0L
            var thinkingMs = 0L
            var failed = false
            try {
                sessions.use {
                    try {
                        engine.configureSampler(settings.temperature, settings.topP)
                        engine.generate(message, settings.maxTokens).collect { piece ->
                            val t = System.nanoTime()
                            if (firstWordMs == 0L) firstWordMs = ((t - start) / 1_000_000).coerceAtLeast(1)
                            raw.append(piece)
                            val parsed = ThinkParser.parse(raw.toString())
                            if (parsed.isThinking && thinkingStart == 0L) thinkingStart = t
                            if (!parsed.isThinking && thinkingStart != 0L && thinkingMs == 0L) {
                                thinkingMs = ((t - thinkingStart) / 1_000_000).coerceAtLeast(1)
                            }
                            replaceLast {
                                it.copy(
                                    text = parsed.answer,
                                    thinking = parsed.thinking,
                                    isThinking = parsed.isThinking,
                                    thinkingMs = thinkingMs,
                                    firstWordMs = firstWordMs,
                                )
                            }
                        }
                        val stats = engine.lastStats()
                        replaceLast { it.copy(tokensPerSecond = stats.genTokensPerSecond.toFloat()) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Le contexte du moteur n'est plus sûr : il sera reconstruit, sans l'enregistrer.
                        failed = true
                        invalidate()
                        throw e
                    } finally {
                        if (!failed) update(HistoryWindow.turns(_state.value.messages))
                    }
                }
            } catch (e: CancellationException) {
                // Arrêt demandé : la réponse écrite jusque-là est gardée.
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            } finally {
                if (thinkingStart != 0L && thinkingMs == 0L) thinkingMs = (System.nanoTime() - thinkingStart) / 1_000_000
                replaceLast { if (it.fromUser) it else it.copy(isThinking = false, thinkingMs = thinkingMs) }
                _state.update { it.copy(generating = false) }
                saveConversation()
                if (failed) openChat()
            }
        }
    }

    private fun replaceLast(transform: (ChatMessage) -> ChatMessage) {
        _state.update { s ->
            val last = s.messages.lastOrNull() ?: return@update s
            s.copy(messages = s.messages.dropLast(1) + transform(last))
        }
    }

    fun stop() {
        generation?.cancel()
    }

    /** Efface toute la conversation (demandé depuis les réglages, avec confirmation). */
    fun clearConversation() {
        generation?.cancel()
        viewModelScope.launch {
            generation?.join()
            _state.update { it.copy(messages = emptyList(), error = null) }
            conversation.clear()
            sessions.use { forgetSessions() }
            openChat()
        }
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        viewModelScope.launch { settingsRepo.update(transform) }
    }

    fun setReasoningMode(enabled: Boolean) {
        val reasoning = _state.value.reasoningModel
        if (enabled && !store.isDownloaded(reasoning)) {
            if (_state.value.reasoningDownload == null) _state.update { it.copy(reasoningOffer = true) }
            else _state.update { it.copy(notice = "Le modèle de réflexion est en cours de téléchargement.") }
            return
        }
        updateSettings { it.copy(reasoningMode = enabled) }
    }

    fun confirmReasoningDownload() {
        _state.update { it.copy(reasoningOffer = false) }
        download(_state.value.reasoningModel)
    }

    fun cancelReasoningOffer() = _state.update { it.copy(reasoningOffer = false) }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    fun dismissError() = _state.update { it.copy(error = null) }

    // --- Fichiers des modèles ---

    fun download(model: ModelInfo) {
        ModelDownloadWorker.enqueue(getApplication(), model, _state.value.settings.wifiOnlyDownloads)
    }

    fun cancelDownload(model: ModelInfo) {
        ModelDownloadWorker.cancel(getApplication(), model)
    }

    /** Supprime un fichier qu'Axo n'utilise pas, pour libérer de la place. */
    fun deleteModel(model: ModelInfo) {
        val s = _state.value.settings
        if (model.id == s.instructModelId || model.id == s.reasoningModelId || measure?.isActive == true) return
        viewModelScope.launch {
            sessions.use { forgetModel(model.id) }
            store.delete(model)
            refreshDownloaded()
        }
    }

    /** Fait d'un fichier celui qu'Axo utilise pour ce modèle. */
    fun useModel(model: ModelInfo) {
        if (!store.isDownloaded(model)) return
        updateSettings { if (model.reasoning) it.copy(reasoningModelId = model.id) else it.copy(instructModelId = model.id) }
    }

    private fun watchDownloads() {
        for (model in ModelCatalog.all) {
            viewModelScope.launch {
                workManager.getWorkInfosForUniqueWorkFlow(ModelDownloadWorker.workName(model)).collect { infos ->
                    val info = infos.firstOrNull() ?: return@collect
                    when (info.state) {
                        WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                            seenDownloads += model.id
                            val running = info.state == WorkInfo.State.RUNNING
                            val percent = if (running) info.progress.getInt(ModelDownloadWorker.KEY_PROGRESS, 0)
                            else store.partialPercent(model)
                            setDownload(model.id, DownloadState(percent, waitingForNetwork = !running))
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            setDownload(model.id, null)
                            refreshDownloaded()
                            if (seenDownloads.remove(model.id)) onDownloaded(model)
                        }
                        WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                            setDownload(model.id, null)
                            if (seenDownloads.remove(model.id) && info.state == WorkInfo.State.FAILED) {
                                _state.update { it.copy(notice = "Le téléchargement de ${model.displayName} (${model.quant}) a échoué.") }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun setDownload(id: String, download: DownloadState?) {
        _state.update { s -> s.copy(downloads = if (download == null) s.downloads - id else s.downloads + (id to download)) }
    }

    private fun onDownloaded(model: ModelInfo) {
        val s = _state.value
        when {
            model.id == s.activeModel.id && !s.modelDownloaded -> {
                _state.update { it.copy(modelDownloaded = true) }
                openChat()
            }
            model.reasoning && model.id == s.settings.reasoningModelId && !s.settings.reasoningMode ->
                _state.update { it.copy(notice = "Le mode réflexion est prêt : appuie sur « Réflexion » pour l'activer.") }
        }
    }

    // --- Mesures ---

    private fun updateMeasure(transform: (MeasureUi) -> MeasureUi) = _state.update { it.copy(measure = transform(it.measure)) }

    private fun step(text: String, progress: Float? = null) = updateMeasure { it.copy(step = text, progress = progress) }

    /**
     * Lance une mesure : la conversation est enregistrée, le moteur sert au test, puis la
     * conversation est reprise (rechargée depuis la session, donc sans tout relire).
     */
    private fun runMeasure(kind: MeasureKind, block: suspend SessionCache.Session.() -> Unit) {
        if (measure?.isActive == true || _state.value.generating) return
        updateMeasure { it.copy(running = kind, step = "Préparation…", progress = null, error = null) }
        _state.update { it.copy(conversationReady = false) }
        measure = viewModelScope.launch {
            try {
                sessions.use {
                    _state.update { it.copy(conversationReady = false) }
                    detach(engine)
                    block()
                }
            } catch (e: CancellationException) {
                // Arrêté par l'utilisateur.
            } catch (e: Exception) {
                updateMeasure { it.copy(error = e.message ?: "Erreur inconnue") }
            } finally {
                updateMeasure { it.copy(running = null, step = "", progress = null, enduranceLive = emptyList()) }
                openChat()
            }
        }
    }

    fun stopMeasure() {
        measure?.cancel()
    }

    private suspend fun ensureLoaded(model: ModelInfo): Long {
        engine.load(model.id, store.fileFor(model))
        val ready = engine.state.value as? LlamaEngine.State.Ready
        if (ready == null || ready.modelId != model.id) error("Impossible de charger ${model.displayName} (${model.quant})")
        return ready.loadMs
    }

    /** Collecte une réponse complète : texte brut, temps du premier mot, durée totale (ms). */
    private suspend fun answer(message: String, maxTokens: Int): Triple<String, Long, Long> {
        val raw = StringBuilder()
        val start = System.nanoTime()
        var first = 0L
        engine.generate(message, maxTokens).collect { piece ->
            if (first == 0L) first = ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1)
            raw.append(piece)
        }
        return Triple(raw.toString(), first, (System.nanoTime() - start) / 1_000_000)
    }

    fun startSpeedTest() {
        val model = _state.value.activeModel
        val app = getApplication<Application>()
        runMeasure(MeasureKind.SPEED) {
            val batteryBefore = DeviceStats.batteryTemperature(app)
            step("Chargement du modèle…")
            val loadMs = ensureLoaded(model)
            engine.configureSampler(BenchSettings.TEMPERATURE, BenchSettings.TOP_P, BenchSettings.SEED)
            engine.startConversation(_state.value.settings.systemPrompt)

            step("Lecture d'un long texte…", 0.2f)
            val (_, longFirst, _) = answer(SpeedTestPrompts.LONG_MESSAGE, SpeedTestPrompts.LONG_MAX_TOKENS)
            val long = engine.lastStats()

            step("Premier mot d'une réponse courte…", 0.8f)
            val (_, shortFirst, _) = answer(SpeedTestPrompts.SHORT_MESSAGE, SpeedTestPrompts.SHORT_MAX_TOKENS)
            val short = engine.lastStats()

            val genMs = long.genMs + short.genMs
            val memory = DeviceStats.memory()
            val result = SpeedResult(
                time = System.currentTimeMillis(),
                modelId = model.id,
                loadMs = loadMs,
                firstWordMs = shortFirst,
                longFirstWordMs = longFirst,
                promptTokens = long.promptTokens,
                promptTokensPerSecond = long.promptTokensPerSecond,
                tokensPerSecond = if (genMs > 0) (long.genTokens + short.genTokens) * 1000.0 / genMs else 0.0,
                rssBytes = memory.rssBytes,
                peakRssBytes = memory.peakBytes,
                batteryBefore = batteryBefore,
                batteryAfter = DeviceStats.batteryTemperature(app),
                thermalStatus = DeviceStats.thermalStatus(app),
                threads = LlamaEngine.DEFAULT_THREADS,
            )
            val all = (_state.value.measure.speed + result).takeLast(20)
            updateMeasure { it.copy(speed = all) }
            measures.saveSpeed(all)
        }
    }

    fun startEndurance() {
        val model = _state.value.activeModel
        val app = getApplication<Application>()
        runMeasure(MeasureKind.ENDURANCE) {
            step("Chargement du modèle…")
            ensureLoaded(model)
            val points = mutableListOf<EndurancePoint>()
            val start = System.currentTimeMillis()
            fun result() = EnduranceResult(start, model.id, points.toList(), DeviceStats.thermalStatus(app))
            try {
                while (System.currentTimeMillis() - start < Targets.ENDURANCE_MS) {
                    val elapsed = System.currentTimeMillis() - start
                    step("Écriture en continu… ${elapsed / 60_000} min sur 10", elapsed.toFloat() / Targets.ENDURANCE_MS)
                    // Même graine à chaque tour : la même réponse, donc des vitesses comparables.
                    engine.configureSampler(0.7f, BenchSettings.TOP_P, BenchSettings.SEED)
                    engine.startConversation(_state.value.settings.systemPrompt)
                    answer(SpeedTestPrompts.ENDURANCE_MESSAGE, SpeedTestPrompts.ENDURANCE_MAX_TOKENS)
                    val stats = engine.lastStats()
                    val seconds = ((System.currentTimeMillis() - start) / 1000).toInt()
                    points += EndurancePoint(seconds, stats.genTokensPerSecond, DeviceStats.batteryTemperature(app))
                    updateMeasure { it.copy(enduranceLive = points.toList()) }
                }
            } finally {
                // Même arrêté avant la fin, le début de la courbe est gardé.
                if (points.size >= 2) {
                    val all = (_state.value.measure.endurance + result()).takeLast(5)
                    updateMeasure { it.copy(endurance = all) }
                    withContext(NonCancellable) { measures.saveEndurance(all) }
                }
            }
        }
    }

    /** Pose les 10 questions à chaque fichier téléchargé qui n'y a pas encore répondu. */
    fun startBench(reasoning: Boolean) {
        val models = ModelCatalog.variants(reasoning).filter { store.isDownloaded(it) }
        if (models.size < 2) return
        runMeasure(MeasureKind.BENCH) {
            var bench = _state.value.measure.bench(reasoning)
            val todo = models.filter { bench.runs[it.id]?.complete != true }
            val total = todo.sumOf { m -> BENCH_QUESTIONS.indices.count { it !in (bench.runs[m.id]?.answers ?: emptyMap()) } }
            var done = 0
            for (model in todo) {
                step("${model.quant} : chargement…", if (total > 0) done.toFloat() / total else null)
                val loadMs = ensureLoaded(model)
                var run = bench.runs[model.id] ?: BenchRun(model.id)
                run = run.copy(loadMs = loadMs)
                for ((index, question) in BENCH_QUESTIONS.withIndex()) {
                    if (index in run.answers) continue
                    step("${model.quant} : question ${index + 1} sur ${BENCH_QUESTIONS.size}", done.toFloat() / total)
                    engine.configureSampler(BenchSettings.TEMPERATURE, BenchSettings.TOP_P, BenchSettings.SEED)
                    // Même instructions pour tous les fichiers : la personnalité d'Axo par défaut.
                    engine.startConversation(DEFAULT_SYSTEM_PROMPT)
                    val maxTokens = if (reasoning) BenchSettings.MAX_TOKENS_REASONING else BenchSettings.MAX_TOKENS
                    val (raw, first, total0) = answer(question.text, maxTokens)
                    val stats = engine.lastStats()
                    val parsed = ThinkParser.parse(raw)
                    run = run.copy(
                        answers = run.answers + (
                            index to BenchAnswer(
                                text = parsed.answer.trim(),
                                thinking = parsed.thinking.trim(),
                                firstWordMs = first,
                                tokensPerSecond = stats.genTokensPerSecond,
                                tokens = stats.genTokens,
                                totalMs = total0,
                            )
                            ),
                        peakRssBytes = maxOf(run.peakRssBytes, DeviceStats.memory().rssBytes),
                    )
                    bench = bench.copy(runs = bench.runs + (model.id to run))
                    // Enregistré à chaque réponse : un test interrompu reprend où il s'était arrêté.
                    saveBench(bench)
                    done++
                }
            }
            saveBench(bench.withVoters())
        }
    }

    private suspend fun saveBench(state: BenchState) {
        updateMeasure { if (state.reasoning) it.copy(benchReasoning = state) else it.copy(benchInstruct = state) }
        measures.saveBench(state)
    }

    /** Vote à l'aveugle : [modelId] a donné la meilleure réponse, ou [BenchState.TIE]. */
    fun vote(reasoning: Boolean, question: Int, modelId: String) {
        val bench = _state.value.measure.bench(reasoning).withVoters()
        viewModelScope.launch { saveBench(bench.copy(votes = bench.votes + (question to modelId))) }
    }

    /** Recommence les votes (les réponses sont gardées). */
    fun resetVotes(reasoning: Boolean) {
        val bench = _state.value.measure.bench(reasoning)
        viewModelScope.launch { saveBench(bench.copy(voters = emptyList(), order = emptyMap(), votes = emptyMap()).withVoters()) }
    }

    /** Efface toute la comparaison de ce modèle (réponses et votes). */
    fun resetBench(reasoning: Boolean) {
        if (measure?.isActive == true) return
        viewModelScope.launch { saveBench(BenchState(reasoning)) }
    }

    fun dismissMeasureError() = updateMeasure { it.copy(error = null) }

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
