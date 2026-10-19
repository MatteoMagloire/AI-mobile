package com.matteomagloire.aimobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.matteomagloire.aimobile.brain.LiveBrain
import com.matteomagloire.aimobile.model.ModelCatalog
import java.time.LocalDate

private enum class Screen { CHAT, SETTINGS, SPEED, BENCH, BRAIN, NOTE, BRAIN_TEST }

/** Un écran ouvert : l'écran, et la note pour NOTE. */
private data class Dest(val screen: Screen, val noteId: String = "") {
    fun encode() = if (screen == Screen.NOTE) "NOTE:$noteId" else screen.name

    companion object {
        fun decode(saved: String): List<Dest> = saved.split('|').mapNotNull { s ->
            if (s.startsWith("NOTE:")) Dest(Screen.NOTE, s.removePrefix("NOTE:")) else Screen.entries.firstOrNull { it.name == s }?.let { Dest(it) }
        }.ifEmpty { listOf(Dest(Screen.CHAT)) }
    }
}

private val MEASURES = setOf(Screen.SPEED, Screen.BENCH, Screen.BRAIN_TEST)

@Composable
fun AppRoot(vm: ChatViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    // Les écrans ouverts, la conversation au fond : « Retour » revient au précédent. Une note ouverte
    // depuis une autre revient à celle-ci.
    var saved by rememberSaveable { mutableStateOf(Screen.CHAT.name) }
    val stack = remember(saved) { Dest.decode(saved) }
    val top = stack.last()
    // Le Cerveau garde son onglet, sa recherche et son graphe quand on ouvre une note puis qu'on revient.
    val brainView = remember { BrainView() }
    // La note qu'on vient de réveiller en l'ouvrant : elle était en veille.
    var woke by remember { mutableStateOf<String?>(null) }

    fun go(next: List<Dest>) {
        saved = next.joinToString("|") { it.encode() }
    }

    fun push(dest: Dest) = go(Dest.decode(saved) + dest)

    fun openNote(id: String) {
        val today = LocalDate.now()
        woke = id.takeIf { state.brain.notes.firstOrNull { n -> n.id == id }?.isDormant(today, state.settings.dormantDays) == true }
        vm.openNote(id)
        // Déjà ouverte juste avant (de lien en lien) : on y revient, sans tourner en rond dans la pile.
        val now = Dest.decode(saved)
        val at = now.indexOfLast { it.screen == Screen.NOTE && it.noteId == id }
        if (at >= 0 && now.drop(at).all { it.screen == Screen.NOTE }) go(now.take(at + 1)) else push(Dest(Screen.NOTE, id))
    }

    fun openBrain() {
        brainView.reset()
        push(Dest(Screen.BRAIN))
    }

    // Le Cerveau déjà ouvert plus bas, ou un nouveau.
    fun toBrain() {
        val now = Dest.decode(saved)
        val at = now.indexOfLast { it.screen == Screen.BRAIN }
        if (at >= 0) go(now.take(at + 1)) else push(Dest(Screen.BRAIN))
    }

    // « Voir dans le graphe » depuis une note.
    fun showInGraph(id: String) {
        brainView.showInGraph(id)
        toBrain()
    }

    // Le mini-graphe touché dans la conversation : le graphe entier, pour regarder Axo travailler.
    fun openLive() {
        brainView.showLive(vm.live.focus(LiveBrain.now()))
        toBrain()
    }

    // Quitter un écran de mesure arrête la mesure en cours : la conversation reprend.
    fun back() {
        val now = Dest.decode(saved)
        if (now.last().screen in MEASURES) vm.stopMeasure()
        if (now.size > 1) go(now.dropLast(1))
    }
    BackHandler(enabled = stack.size > 1) { back() }

    when (top.screen) {
        Screen.SETTINGS -> SettingsScreen(
            settings = state.settings,
            instructModel = ModelCatalog.byId(state.settings.instructModelId) ?: ModelCatalog.INSTRUCT,
            reasoningModel = state.reasoningModel,
            engineInfo = state.engineInfo,
            onChange = vm::updateSettings,
            onReasoningChange = vm::setReasoningMode,
            onClearConversation = vm::clearConversation,
            onOpenSpeedTest = { push(Dest(Screen.SPEED)) },
            onOpenBench = { push(Dest(Screen.BENCH)) },
            onOpenBrainTest = { push(Dest(Screen.BRAIN_TEST)) },
            onClearBrain = vm::clearBrain,
            onBack = { back() },
        )
        Screen.SPEED -> SpeedTestScreen(
            state = state,
            engineInfo = state.engineInfo,
            onRunSpeed = vm::startSpeedTest,
            onRunEndurance = vm::startEndurance,
            onThreadsChange = vm::setThreads,
            onStop = vm::stopMeasure,
            onDismissError = vm::dismissMeasureError,
            onBack = { back() },
        )
        Screen.BENCH -> BenchScreen(
            state = state,
            onRun = vm::startBench,
            onStop = vm::stopMeasure,
            onVote = vm::vote,
            onResetVotes = vm::resetVotes,
            onResetBench = vm::resetBench,
            onDownload = vm::download,
            onCancelDownload = vm::cancelDownload,
            onDelete = vm::deleteModel,
            onUse = vm::useModel,
            onDismissError = vm::dismissMeasureError,
            onBack = { back() },
        )
        Screen.BRAIN -> BrainScreen(
            state = state,
            view = brainView,
            live = vm.live,
            onOpenNote = { openNote(it) },
            onUndo = vm::undoNotes,
            onFollowChange = { follow -> vm.updateSettings { it.copy(followAxo = follow) } },
            onBack = { back() },
        )
        Screen.NOTE -> NoteScreen(
            note = state.brain.notes.firstOrNull { it.id == top.noteId },
            notes = state.brain.notes,
            links = state.brain.links,
            dormantDays = state.settings.dormantDays,
            wokeUp = woke == top.noteId,
            onOpenNote = { openNote(it) },
            onShowInGraph = { showInGraph(top.noteId) },
            onSetLinks = { vm.setLinks(top.noteId, it) },
            onEditFact = { old, text, before -> vm.editFact(top.noteId, old, text, before) },
            onSaveRaw = { vm.rewriteNote(top.noteId, it) },
            onDeleteNote = { vm.deleteNote(top.noteId) },
            onBack = { back() },
        )
        Screen.BRAIN_TEST -> BrainTestScreen(
            state = state,
            onRun = vm::startBrainTest,
            onStop = vm::stopMeasure,
            onDismissError = vm::dismissMeasureError,
            onBack = { back() },
        )
        Screen.CHAT -> if (!state.modelDownloaded) {
            SetupScreen(
                model = state.activeModel,
                download = state.download,
                wifiOnly = state.settings.wifiOnlyDownloads,
                error = state.error,
                onDownload = vm::downloadModel,
                onOpenSettings = { push(Dest(Screen.SETTINGS)) },
            )
        } else {
            ChatScreen(
                state = state,
                live = vm.live,
                onSend = vm::send,
                onStop = vm::stop,
                onReasoningChange = vm::setReasoningMode,
                onOpenSettings = { push(Dest(Screen.SETTINGS)) },
                onOpenBrain = { openBrain() },
                onOpenLive = { openLive() },
                onOpenNote = { openNote(it) },
                onUndoBrain = vm::undoBrainChip,
                onDismissBrain = vm::dismissBrainChip,
                onDismissError = vm::dismissError,
                onDismissNotice = vm::dismissNotice,
            )
        }
    }

    if (state.reasoningOffer) {
        ReasoningOfferDialog(
            model = state.reasoningModel,
            wifiOnly = state.settings.wifiOnlyDownloads,
            onDownload = vm::confirmReasoningDownload,
            onCancel = vm::cancelReasoningOffer,
        )
    }

    UpdateDialog(
        update = state.update,
        onInstall = vm::installUpdate,
        onLater = vm::dismissUpdate,
    )
}
