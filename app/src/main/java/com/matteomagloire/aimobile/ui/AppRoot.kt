package com.matteomagloire.aimobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.matteomagloire.aimobile.model.ModelCatalog

private enum class Screen { CHAT, SETTINGS, SPEED, BENCH, BRAIN, NOTE, BRAIN_TEST }

@Composable
fun AppRoot(vm: ChatViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.CHAT) }
    // La note ouverte, et l'écran où revenir : le Cerveau, ou la conversation (« Voir »).
    var noteId by rememberSaveable { mutableStateOf("") }
    var noteBack by rememberSaveable { mutableStateOf(Screen.BRAIN) }

    fun openNote(id: String, from: Screen) {
        noteId = id
        noteBack = from
        vm.openNote(id)
        screen = Screen.NOTE
    }

    // Quitter un écran de mesure arrête la mesure en cours : la conversation reprend.
    fun back() {
        screen = when (screen) {
            Screen.SPEED, Screen.BENCH, Screen.BRAIN_TEST -> {
                vm.stopMeasure()
                Screen.SETTINGS
            }
            Screen.NOTE -> noteBack
            else -> Screen.CHAT
        }
    }
    BackHandler(enabled = screen != Screen.CHAT) { back() }

    when (screen) {
        Screen.SETTINGS -> SettingsScreen(
            settings = state.settings,
            instructModel = ModelCatalog.byId(state.settings.instructModelId) ?: ModelCatalog.INSTRUCT,
            reasoningModel = state.reasoningModel,
            engineInfo = state.engineInfo,
            onChange = vm::updateSettings,
            onReasoningChange = vm::setReasoningMode,
            onClearConversation = vm::clearConversation,
            onOpenSpeedTest = { screen = Screen.SPEED },
            onOpenBench = { screen = Screen.BENCH },
            onOpenBrainTest = { screen = Screen.BRAIN_TEST },
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
            onOpenNote = { openNote(it, Screen.BRAIN) },
            onUndo = vm::undoNotes,
            onBack = { back() },
        )
        Screen.NOTE -> NoteScreen(
            note = state.brain.notes.firstOrNull { it.id == noteId },
            onEditFact = { old, text, before -> vm.editFact(noteId, old, text, before) },
            onSaveRaw = { vm.rewriteNote(noteId, it) },
            onDeleteNote = { vm.deleteNote(noteId) },
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
                onOpenSettings = { screen = Screen.SETTINGS },
            )
        } else {
            ChatScreen(
                state = state,
                onSend = vm::send,
                onStop = vm::stop,
                onReasoningChange = vm::setReasoningMode,
                onOpenSettings = { screen = Screen.SETTINGS },
                onOpenBrain = { screen = Screen.BRAIN },
                onOpenNote = { openNote(it, Screen.CHAT) },
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
