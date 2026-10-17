package com.matteomagloire.aimobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun AppRoot(vm: ChatViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = showSettings) { showSettings = false }

    when {
        showSettings -> SettingsScreen(
            settings = state.settings,
            onChange = vm::updateSettings,
            onBack = { showSettings = false },
        )
        !state.modelDownloaded -> SetupScreen(
            model = state.activeModel,
            download = state.download,
            wifiOnly = state.settings.wifiOnlyDownloads,
            error = state.error,
            onDownload = vm::downloadModel,
            onOpenSettings = { showSettings = true },
        )
        else -> ChatScreen(
            state = state,
            onSend = vm::send,
            onStop = vm::stop,
            onNewConversation = vm::newConversation,
            onReasoningChange = vm::setReasoningMode,
            onOpenSettings = { showSettings = true },
            onDismissError = vm::dismissError,
        )
    }

    UpdateDialog(
        update = state.update,
        onInstall = vm::installUpdate,
        onLater = vm::dismissUpdate,
    )
}
