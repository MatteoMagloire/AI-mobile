package com.matteomagloire.aimobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.matteomagloire.aimobile.llm.LlamaEngine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onReasoningChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onDismissError: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val ready = state.engine is LlamaEngine.State.Ready

    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text?.length) {
        if (state.messages.isNotEmpty()) listState.scrollToItem(state.messages.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Assistant") },
                actions = {
                    FilterChip(
                        selected = state.settings.reasoningMode,
                        onClick = { onReasoningChange(!state.settings.reasoningMode) },
                        label = { Text("Réflexion") },
                        enabled = !state.generating,
                    )
                    TextButton(onClick = onOpenSettings) { Text("Réglages") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            when (val engine = state.engine) {
                is LlamaEngine.State.Loading -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Chargement du modèle…", Modifier.padding(8.dp))
                }
                is LlamaEngine.State.Failed -> Text(engine.message, Modifier.padding(8.dp), color = MaterialTheme.colorScheme.error)
                else -> Unit
            }
            state.reasoningDownload?.let { d ->
                LinearProgressIndicator(progress = { d.percent / 100f }, modifier = Modifier.fillMaxWidth())
                Text(
                    if (d.waitingForNetwork) "Modèle de réflexion : en attente du réseau (${d.percent} %)"
                    else "Téléchargement du modèle de réflexion : ${d.percent} %",
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            state.notice?.let {
                Text(
                    it,
                    Modifier.fillMaxWidth().clickable(onClick = onDismissNotice).padding(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            state.error?.let {
                Text(
                    it,
                    Modifier.fillMaxWidth().clickable(onClick = onDismissError).padding(8.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.messages) { MessageBubble(it) }
            }

            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Écris ton message…") },
                    maxLines = 5,
                )
                if (state.generating) {
                    OutlinedButton(onClick = onStop) { Text("Stop") }
                } else {
                    Button(
                        onClick = { onSend(input); input = "" },
                        enabled = ready && input.isNotBlank(),
                    ) { Text("Envoyer") }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    var showThinking by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth(), contentAlignment = if (message.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .background(
                    if (message.fromUser) colors.primaryContainer else colors.surfaceVariant,
                    RoundedCornerShape(16.dp),
                )
                .padding(12.dp),
        ) {
            // La réflexion du mode Reasoning est repliée par défaut.
            if (message.thinking.isNotEmpty()) {
                Text(
                    if (message.isThinking) "Réflexion en cours…" else if (showThinking) "Masquer la réflexion" else "Voir la réflexion",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.primary,
                    modifier = Modifier.clickable { showThinking = !showThinking },
                )
                if (showThinking) {
                    Text(message.thinking, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
                }
            }
            if (message.text.isNotEmpty() || message.thinking.isEmpty()) {
                Text(message.text.ifEmpty { "…" }, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
