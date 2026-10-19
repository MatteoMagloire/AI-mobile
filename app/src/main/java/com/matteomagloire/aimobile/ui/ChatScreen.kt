package com.matteomagloire.aimobile.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.matteomagloire.aimobile.chat.ChatMessage
import com.matteomagloire.aimobile.llm.LlamaEngine
import com.matteomagloire.aimobile.ui.theme.Axo
import com.matteomagloire.aimobile.ui.theme.MonoStyle
import com.matteomagloire.aimobile.ui.theme.OverlineStyle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun ChatScreen(
    state: ChatUiState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onReasoningChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBrain: () -> Unit,
    onOpenNote: (String) -> Unit,
    onUndoBrain: () -> Unit,
    onDismissBrain: () -> Unit,
    onDismissError: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    // Liste inversée : le bas reste collé au dernier message pendant qu'Axo écrit.
    val rows = remember(state.messages) { chatItems(state.messages).asReversed() }
    val waking = !state.conversationReady && state.engine !is LlamaEngine.State.Failed

    LaunchedEffect(state.messages.size) {
        if (rows.isNotEmpty()) listState.scrollToItem(0)
    }

    Column(Modifier.fillMaxSize().background(Axo.Ink).statusBarsPadding().navigationBarsPadding().imePadding()) {
        ChatHeader(state, waking, onOpenSettings, onOpenBrain)
        if (waking) AmberProgress(null)

        (state.engine as? LlamaEngine.State.Failed)?.let { Banner(it.message, Axo.Blush) }
        state.reasoningDownload?.let { d ->
            AmberProgress(d.percent / 100f)
            Banner(
                if (d.waitingForNetwork) "Modèle de réflexion : en attente du réseau (${d.percent} %)"
                else "Téléchargement du modèle de réflexion : ${d.percent} %",
                Axo.Muted,
            )
        }
        state.notice?.let { Banner(it, Axo.Amber, onDismissNotice) }
        state.error?.let { Banner(it, Axo.Blush, onDismissError) }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.Bottom),
        ) {
            if (state.messages.isEmpty() && state.conversationReady) {
                item { EmptyChat() }
            }
            items(rows, key = { it.key }) { item ->
                when (item) {
                    is ChatItem.Day -> Text(
                        item.label.uppercase(Locale.FRENCH),
                        style = OverlineStyle,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    is ChatItem.Message -> MessageBubble(
                        message = item.message,
                        streaming = state.generating && item.last,
                        showSpeed = state.settings.showSpeed,
                    )
                }
            }
        }

        state.brain.chip?.let { chip ->
            BrainChipBar(
                chip = chip,
                onView = {
                    val id = (chip as? BrainChip.Noted)?.entry?.noted?.firstOrNull()?.noteId
                    if (id != null) onOpenNote(id) else onOpenBrain()
                },
                onUndo = onUndoBrain,
                onDismiss = onDismissBrain,
            )
        }

        InputBar(
            input = input,
            onInput = { input = it },
            reasoning = state.settings.reasoningMode,
            generating = state.generating,
            canSend = state.conversationReady && !state.generating && input.isNotBlank(),
            onReasoningChange = onReasoningChange,
            onSend = { onSend(input); input = "" },
            onStop = onStop,
        )
    }
}

@Composable
private fun ChatHeader(state: ChatUiState, waking: Boolean, onOpenSettings: () -> Unit, onOpenBrain: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Axo.Ember)
                .border(BorderStroke(1.dp, Axo.AmberBorder), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Image(AxoIcons.Face, contentDescription = null, modifier = Modifier.size(36.dp))
        }
        Column(Modifier.weight(1f)) {
            Text("Axo", style = MaterialTheme.typography.titleLarge)
            val status = when {
                state.engine is LlamaEngine.State.Failed -> "Endormi : le modèle ne s'est pas chargé"
                waking -> "Se réveille…"
                state.generating && state.messages.lastOrNull()?.isThinking == true -> "Réfléchit…"
                state.generating -> "Écrit…"
                state.brain.noting -> "Prend des notes…"
                state.settings.reasoningMode -> "Mode réflexion · hors ligne"
                else -> "Hors ligne · sur ton téléphone"
            }
            Text(status, style = MaterialTheme.typography.bodySmall, color = Axo.Muted)
        }
        IconButton(onClick = onOpenBrain) {
            Icon(AxoIcons.Brain, contentDescription = "Cerveau", tint = if (state.brain.noting) Axo.Amber else Axo.TextSecondary)
        }
        IconButton(onClick = onOpenSettings) {
            Icon(AxoIcons.Settings, contentDescription = "Réglages", tint = Axo.TextSecondary)
        }
    }
}

@Composable
private fun Banner(text: String, color: androidx.compose.ui.graphics.Color, onDismiss: (() -> Unit)? = null) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onDismiss != null) Modifier.clickable(onClick = onDismiss) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun EmptyChat() {
    Column(
        Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(AxoIcons.Face, contentDescription = null, modifier = Modifier.size(96.dp))
        Text("Salut ! Je suis là.", style = MaterialTheme.typography.titleMedium)
        Text(
            "Écris-moi ce que tu veux : tout reste sur ton téléphone.",
            style = MaterialTheme.typography.bodyMedium,
            color = Axo.Muted,
        )
    }
}

// Clés stables : l'état d'un message (réflexion dépliée) le suit quand de nouveaux messages arrivent.
private sealed interface ChatItem {
    val key: String

    data class Day(val label: String) : ChatItem {
        override val key get() = "day:$label"
    }

    data class Message(val message: ChatMessage, val last: Boolean, val index: Int) : ChatItem {
        override val key get() = "message:$index"
    }
}

/** Messages avec un intertitre à chaque nouveau jour (« Aujourd'hui », « Hier », « 12 octobre »). */
private fun chatItems(messages: List<ChatMessage>): List<ChatItem> {
    val out = ArrayList<ChatItem>(messages.size + 4)
    var lastDay: LocalDate? = null
    val today = LocalDate.now()
    messages.forEachIndexed { i, m ->
        if (m.time > 0) {
            val day = Instant.ofEpochMilli(m.time).atZone(ZoneId.systemDefault()).toLocalDate()
            if (day != lastDay) {
                out += ChatItem.Day(dayLabel(day, today))
                lastDay = day
            }
        }
        out += ChatItem.Message(m, last = i == messages.lastIndex, index = i)
    }
    return out
}

private fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Aujourd'hui"
    today.minusDays(1) -> "Hier"
    else -> day.format(DateTimeFormatter.ofPattern(if (day.year == today.year) "d MMMM" else "d MMMM yyyy", Locale.FRENCH))
}

@Composable
private fun MessageBubble(message: ChatMessage, streaming: Boolean, showSpeed: Boolean) {
    if (message.fromUser) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                message.text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                    .background(Axo.Slate)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
            if (message.memory.isNotEmpty()) MemoryLine(message.memory)
        }
        return
    }
    var showThinking by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // La réflexion du mode Reasoning est repliée par défaut.
        if (message.thinking.isNotEmpty()) {
            ThinkingChip(message, expanded = showThinking) { showThinking = !showThinking }
            if (showThinking) {
                Text(
                    message.thinking,
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color = Axo.Muted,
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Axo.Card)
                        .padding(12.dp),
                )
            }
        }
        if (message.text.isNotEmpty() || (message.thinking.isEmpty())) {
            val text = buildAnnotatedString {
                append(message.text)
                if (streaming) withStyle(SpanStyle(color = Axo.Amber)) { append(" ▍") }
            }
            Text(
                if (message.text.isEmpty() && !streaming) buildAnnotatedString { append("…") } else text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp))
                    .background(Axo.Ember)
                    .border(BorderStroke(1.dp, Axo.EmberBorder), RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        if (showSpeed && !streaming && message.tokensPerSecond > 0f) {
            Text(
                "premier mot ${seconds(message.firstWordMs)} · ${tokensPerSecond(message.tokensPerSecond.toDouble())}",
                style = MonoStyle,
                color = Axo.Muted,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/** Sous un message : les souvenirs qu'Axo a relus avant de répondre (repliés). */
@Composable
private fun MemoryLine(memory: String) {
    val facts = remember(memory) { memory.lines().filter { it.startsWith("- ") }.map { it.removePrefix("- ") } }
    if (facts.isEmpty()) return
    var open by rememberSaveable { mutableStateOf(false) }
    val forgot = memory.contains("tu viens d'oublier")
    val label = when {
        forgot -> "Oublié à ta demande"
        facts.size == 1 -> "1 souvenir relu par Axo"
        else -> "${facts.size} souvenirs relus par Axo"
    }
    Column(
        Modifier
            .widthIn(max = 300.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable { open = !open }
            .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(AxoIcons.Brain, contentDescription = null, tint = Axo.Frost, modifier = Modifier.size(12.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = Axo.Frost)
        }
        if (open) {
            facts.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = Axo.Muted) }
        }
    }
}

/** Dernière action du Cerveau, au-dessus du champ de saisie : « Voir », « Annuler ». */
@Composable
private fun BrainChipBar(chip: BrainChip, onView: () -> Unit, onUndo: () -> Unit, onDismiss: () -> Unit) {
    val title: String
    val detail: String
    var canUndo = true
    when (chip) {
        is BrainChip.Noted -> {
            title = "Noté dans le Cerveau · " + chip.entry.noted.map { it.noteTitle }.distinct().joinToString(", ")
            detail = chip.entry.noted.joinToString("  ·  ") { it.text }
        }
        is BrainChip.Forgot -> {
            canUndo = !chip.forgetting.isEmpty
            title = if (canUndo) "Oublié" else "Rien à oublier : Axo n'avait rien noté là-dessus"
            detail = chip.forgetting.texts.joinToString("  ·  ")
        }
    }
    Row(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Axo.Card)
            .border(BorderStroke(1.dp, Axo.AmberBorder), RoundedCornerShape(14.dp))
            .padding(start = 12.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(AxoIcons.Brain, contentDescription = null, tint = Axo.Amber, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = Axo.Amber, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = Axo.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (chip is BrainChip.Noted) AxoTextButton("Voir", onClick = onView)
        if (canUndo) AxoTextButton("Annuler", onClick = onUndo, color = Axo.TextSecondary)
        IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
            Icon(AxoIcons.Close, contentDescription = "Fermer", tint = Axo.Faint, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ThinkingChip(message: ChatMessage, expanded: Boolean, onClick: () -> Unit) {
    val label = when {
        message.isThinking -> "Réfléchit…"
        message.thinkingMs > 0 -> "A réfléchi pendant ${((message.thinkingMs + 500) / 1000).coerceAtLeast(1)} s"
        else -> "Sa réflexion"
    }
    val pulse = rememberInfiniteTransition(label = "réflexion")
    val alpha by pulse.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "lueur",
    )
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .drawBehind {
                drawRoundRect(
                    color = Axo.AmberBorder,
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
                    cornerRadius = CornerRadius(12.dp.toPx()),
                )
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (message.isThinking) {
            Box(Modifier.size(8.dp).alpha(alpha).clip(CircleShape).background(Axo.Amber))
        } else {
            Icon(
                AxoIcons.Chevron,
                contentDescription = null,
                tint = Axo.AmberSoft,
                modifier = Modifier.size(14.dp).then(if (expanded) Modifier.alpha(0.6f) else Modifier),
            )
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = Axo.AmberSoft)
    }
}

@Composable
private fun InputBar(
    input: String,
    onInput: (String) -> Unit,
    reasoning: Boolean,
    generating: Boolean,
    canSend: Boolean,
    onReasoningChange: (Boolean) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(if (reasoning) Axo.Ember else androidx.compose.ui.graphics.Color.Transparent)
                .border(BorderStroke(1.dp, if (reasoning) Axo.AmberBorder else Axo.Line), RoundedCornerShape(10.dp))
                .clickable(enabled = !generating) { onReasoningChange(!reasoning) }
                .heightIn(min = 36.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val color = if (reasoning) Axo.Amber else Axo.Muted
            Icon(AxoIcons.Thinking, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            Text("Réflexion", style = MaterialTheme.typography.labelMedium, color = color)
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicTextField(
                value = input,
                onValueChange = onInput,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Axo.Linen),
                cursorBrush = SolidColor(Axo.Amber),
                maxLines = 5,
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .heightIn(min = 52.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Axo.Card)
                            .border(BorderStroke(1.dp, Axo.Line), RoundedCornerShape(18.dp))
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (input.isEmpty()) {
                            Text("Écris à Axo…", style = MaterialTheme.typography.bodyLarge, color = Axo.Faint)
                        }
                        inner()
                    }
                },
            )
            if (generating) {
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(18.dp)).background(Axo.Ember)
                        .border(BorderStroke(1.dp, Axo.AmberBorder), RoundedCornerShape(18.dp))
                        .clickable(onClick = onStop),
                    contentAlignment = Alignment.Center,
                ) { Icon(AxoIcons.Stop, contentDescription = "Arrêter la réponse", tint = Axo.Amber) }
            } else {
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(18.dp))
                        .background(if (canSend) Axo.Amber else Axo.Slate)
                        .clickable(enabled = canSend, onClick = onSend),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(AxoIcons.Send, contentDescription = "Envoyer", tint = if (canSend) Axo.OnAmber else Axo.Faint)
                }
            }
        }
    }
}
