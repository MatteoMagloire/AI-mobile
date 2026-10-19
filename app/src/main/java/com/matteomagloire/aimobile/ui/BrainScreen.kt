package com.matteomagloire.aimobile.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.matteomagloire.aimobile.brain.JournalEntry
import com.matteomagloire.aimobile.brain.Note
import com.matteomagloire.aimobile.brain.NoteFilter
import com.matteomagloire.aimobile.brain.NoteOutcome
import com.matteomagloire.aimobile.ui.theme.Axo
import com.matteomagloire.aimobile.ui.theme.MonoStyle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val JOURNAL_ROWS = 20

/** Le Cerveau : les notes d'Axo (« Moi » d'abord), la recherche, et ce qu'il a fait de tes derniers messages. */
@Composable
fun BrainScreen(
    state: ChatUiState,
    onOpenNote: (String) -> Unit,
    onUndo: (JournalEntry) -> Unit,
    onBack: () -> Unit,
) {
    val brain = state.brain
    var query by rememberSaveable { mutableStateOf("") }
    var tag by rememberSaveable { mutableStateOf<String?>(null) }
    val tags = remember(brain.notes) { NoteFilter.tags(brain.notes) }
    // Une étiquette qui n'existe plus (note supprimée) ne filtre plus rien.
    val activeTag = tag?.takeIf { it in tags }
    val searching = query.isNotBlank() || activeTag != null
    val found = remember(brain.notes, query, activeTag) { NoteFilter.filter(brain.notes, query, activeTag) }
    val me = brain.notes.firstOrNull { it.isMe }
    val others = brain.notes.filter { !it.isMe }
    val empty = brain.notes.all { it.facts.isEmpty() && it.before.isEmpty() }

    Column(Modifier.fillMaxSize().background(Axo.Ink).statusBarsPadding().navigationBarsPadding().imePadding()) {
        AxoTopBar("Cerveau", onBack)
        if (brain.noting) AmberProgress(null)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                AxoTextField(
                    query,
                    { query = it },
                    Modifier.fillMaxWidth(),
                    placeholder = "Chercher dans les notes",
                    singleLine = true,
                    background = Axo.Card,
                )
            }
            if (tags.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChoiceChip("Toutes", selected = activeTag == null, enabled = true) { tag = null }
                        tags.forEach { t ->
                            ChoiceChip(tagLabel(t), selected = activeTag == t, enabled = true) { tag = if (activeTag == t) null else t }
                        }
                    }
                }
            }
            if (brain.noting) {
                item {
                    Text("Axo prend des notes sur ton dernier message…", style = MaterialTheme.typography.bodySmall, color = Axo.Amber)
                }
            }
            if (searching) {
                if (found.isEmpty()) {
                    item {
                        Text(
                            if (query.isNotBlank()) "Aucune note ne parle de « ${query.trim()} »." else "Aucune note avec cette étiquette.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Axo.Muted,
                        )
                    }
                }
                items(found, key = { it.id }) { note -> NoteCard(note, query) { onOpenNote(note.id) } }
            } else {
                item(key = Note.ME_ID) { MeCard(me) { me?.let { onOpenNote(it.id) } } }
                items(others, key = { it.id }) { note -> NoteCard(note, "") { onOpenNote(note.id) } }
                if (empty) item { HowItWorks() }
                if (brain.journal.isNotEmpty()) {
                    item { SectionTitle("Ce qu'Axo a fait de tes messages") }
                    item {
                        AxoCard {
                            brain.journal.take(JOURNAL_ROWS).forEachIndexed { i, entry ->
                                if (i > 0) HorizontalDivider(thickness = 1.dp, color = Axo.Line)
                                JournalRow(entry, onUndo = { onUndo(entry) }, onOpenNote = onOpenNote)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MeCard(me: Note?, onOpen: () -> Unit) {
    val facts = me?.facts.orEmpty()
    AxoCard(
        Modifier.clip(RoundedCornerShape(20.dp)).then(if (me != null) Modifier.clickable(onClick = onOpen) else Modifier),
        highlighted = true,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(AxoIcons.Face, contentDescription = null, modifier = Modifier.size(40.dp))
            Column(Modifier.weight(1f)) {
                Text(Note.ME_TITLE, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (facts.isEmpty()) "Ce qu'Axo sait de toi : rien pour l'instant" else "Ce qu'Axo sait de toi · ${factCount(facts.size)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Axo.Muted,
                )
            }
            if (me != null) Icon(AxoIcons.Chevron, contentDescription = null, tint = Axo.Faint, modifier = Modifier.size(18.dp))
        }
        facts.asReversed().take(3).forEach {
            Text(NoteFilter.pretty(it.text), style = MaterialTheme.typography.bodyMedium, color = Axo.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun NoteCard(note: Note, query: String, onClick: () -> Unit) {
    AxoCard(Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(note.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(factCount(note.facts.size), style = MaterialTheme.typography.bodySmall, color = Axo.Muted)
        }
        val tags = note.tags.filter { it != Note.ME_TAG }
        if (tags.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { tags.forEach { Badge(tagLabel(it)) } }
        }
        NoteFilter.preview(note, query).forEach {
            Text(NoteFilter.pretty(it.text), style = MaterialTheme.typography.bodyMedium, color = Axo.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text("Modifiée ${relativeDay(note.modified)}", style = MaterialTheme.typography.bodySmall, color = Axo.Faint)
    }
}

@Composable
private fun HowItWorks() {
    AxoCard {
        Text("Comment ça marche", style = MaterialTheme.typography.titleMedium)
        listOf(
            "Parle de toi à Axo : ta ville, ton travail, tes proches, tes goûts. Après sa réponse, il prend des notes tout seul.",
            "« Retiens que… » : il note ce que tu lui dis, même un détail.",
            "« Oublie… » : il efface ce qui en parle. « Oublie ça » efface ce qu'il vient de noter.",
            "Quand tu reparles d'un sujet, il relit ce qu'il en sait avant de répondre.",
            "Tout reste sur ton téléphone, dans des notes en texte que tu peux lire et corriger ici.",
        ).forEach { Text("· $it", style = MaterialTheme.typography.bodyMedium, color = Axo.TextSecondary) }
    }
}

/** Une ligne du journal : le message, ce qu'Axo en a fait, et « Annuler » pour ce qu'il a noté. */
@Composable
private fun JournalRow(entry: JournalEntry, onUndo: () -> Unit, onOpenNote: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(moment(entry.messageTime.takeIf { it > 0 } ?: entry.time), style = MaterialTheme.typography.labelSmall, color = Axo.Faint, modifier = Modifier.weight(1f))
            if (entry.ms > 0 && entry.outcome != NoteOutcome.IGNORED && entry.outcome != NoteOutcome.FORGOTTEN) {
                Text(seconds(entry.ms), style = MonoStyle, color = Axo.Faint)
            }
        }
        Text("« ${entry.message} »", style = MaterialTheme.typography.bodySmall, color = Axo.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val (label, color) = outcomeLabel(entry)
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
        when (entry.outcome) {
            NoteOutcome.NOTED -> {
                entry.noted.forEach { n ->
                    Text(
                        "· " + n.text + (n.replaced?.let { " (avant : ${it.text})" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (entry.undone) Axo.Faint else Axo.Linen,
                        textDecoration = if (entry.undone) TextDecoration.LineThrough else null,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = !entry.undone) { onOpenNote(n.noteId) },
                    )
                }
                if (!entry.undone) AxoTextButton("Annuler", onClick = onUndo, color = Axo.TextSecondary)
            }
            NoteOutcome.KNOWN -> entry.known.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall, color = Axo.Muted) }
            NoteOutcome.FORGOTTEN -> entry.forgotten.forEach {
                Text("· $it", style = MaterialTheme.typography.bodySmall, color = Axo.Muted, textDecoration = TextDecoration.LineThrough)
            }
            else -> Unit
        }
    }
}

private fun outcomeLabel(entry: JournalEntry): Pair<String, Color> = when (entry.outcome) {
    NoteOutcome.NOTED ->
        if (entry.undone) "Noté, puis annulé" to Axo.Faint
        else "Noté dans " + entry.noted.map { it.noteTitle }.distinct().joinToString(", ") to Axo.Amber
    NoteOutcome.NOTHING -> "Rien de durable à retenir" to Axo.Muted
    NoteOutcome.KNOWN -> "Rien de neuf : déjà noté, ou oublié à ta demande" to Axo.Muted
    NoteOutcome.IGNORED -> "Pas lu : message court, ou qui ne parle pas de toi" to Axo.Faint
    NoteOutcome.FORGOTTEN ->
        if (entry.forgotten.isEmpty()) "« Oublie » : rien ne correspondait" to Axo.Muted
        else "Oublié à ta demande" to Axo.Frost
    NoteOutcome.FAILED -> "La prise de notes n'a pas abouti" to Axo.Blush
}

fun factCount(n: Int) = if (n == 1) "1 fait" else "$n faits"

fun tagLabel(tag: String) = tag.replaceFirstChar { it.titlecase(Locale.FRENCH) }

/** « 19 octobre 2026 », « 1er mai 2026 ». */
fun longDate(d: LocalDate): String =
    (if (d.dayOfMonth == 1) "1er " else "${d.dayOfMonth} ") + d.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.FRENCH))

/** « aujourd'hui », « hier », « le 12 octobre 2026 ». */
fun relativeDay(d: LocalDate, today: LocalDate = LocalDate.now()): String = when (d) {
    today -> "aujourd'hui"
    today.minusDays(1) -> "hier"
    else -> "le " + longDate(d)
}

private fun moment(time: Long): String {
    val t = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val hour = t.format(DateTimeFormatter.ofPattern("HH:mm"))
    return when (t.toLocalDate()) {
        today -> "Aujourd'hui à $hour"
        today.minusDays(1) -> "Hier à $hour"
        else -> t.format(DateTimeFormatter.ofPattern("d MMM", Locale.FRENCH)) + " à $hour"
    }
}
