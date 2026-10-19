package com.matteomagloire.aimobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.matteomagloire.aimobile.brain.JournalEntry
import com.matteomagloire.aimobile.brain.Note
import com.matteomagloire.aimobile.brain.NoteFilter
import com.matteomagloire.aimobile.brain.NoteLinks
import com.matteomagloire.aimobile.brain.NoteOutcome
import com.matteomagloire.aimobile.ui.theme.Axo
import com.matteomagloire.aimobile.ui.theme.MonoStyle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val JOURNAL_ROWS = 20

/**
 * Le Cerveau, en trois onglets : les notes d'Axo (« Moi » d'abord, la recherche, et ce qu'il a fait de
 * tes derniers messages), le graphe de leurs liens, et les notes en veille. [view] garde l'onglet, la
 * recherche et le graphe quand on ouvre une note puis qu'on revient.
 */
@Composable
fun BrainScreen(
    state: ChatUiState,
    view: BrainView,
    onOpenNote: (String) -> Unit,
    onUndo: (JournalEntry) -> Unit,
    onBack: () -> Unit,
) {
    val brain = state.brain
    val days = state.settings.dormantDays
    val today = LocalDate.now()
    val dormant = remember(brain.notes, days, today) { brain.notes.filter { it.isDormant(today, days) }.map { it.id }.toSet() }

    Column(Modifier.fillMaxSize().background(Axo.Ink).statusBarsPadding().navigationBarsPadding().imePadding()) {
        AxoTopBar("Cerveau", onBack) {
            Text(
                noteCount(brain.notes.size) + if (dormant.isEmpty()) "" else " · ${dormant.size} en veille",
                style = MonoStyle.copy(fontSize = 12.sp),
                color = Axo.Muted,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
        if (brain.noting) AmberProgress(null)
        BrainTabs(view.tab) { view.tab = it }
        when (view.tab) {
            BrainTab.NOTES -> NotesTab(brain, view, dormant, onOpenNote, onUndo)
            BrainTab.GRAPH -> BrainGraph(brain.notes, brain.links, dormant, view, onOpenNote, Modifier.weight(1f))
            BrainTab.ASLEEP -> AsleepTab(brain, dormant, days, onOpenNote)
        }
    }
}

/** Les trois onglets, en boutons segmentés. */
@Composable
private fun BrainTabs(tab: BrainTab, onTab: (BrainTab) -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Axo.Card)
            .border(BorderStroke(1.dp, Axo.Line), RoundedCornerShape(16.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf(BrainTab.NOTES to "Notes", BrainTab.GRAPH to "Graphe", BrainTab.ASLEEP to "En veille").forEach { (t, label) ->
            val on = t == tab
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (on) Axo.Ember else Color.Transparent)
                    .selectable(selected = on, role = Role.Tab, onClick = { onTab(t) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) Axo.Amber else Axo.Muted)
            }
        }
    }
}

/** Les notes actives, la recherche (dans toutes les notes, en veille comprises) et le journal. */
@Composable
private fun NotesTab(
    brain: BrainUi,
    view: BrainView,
    dormant: Set<String>,
    onOpenNote: (String) -> Unit,
    onUndo: (JournalEntry) -> Unit,
) {
    val query = view.query
    val tags = remember(brain.notes) { NoteFilter.tags(brain.notes) }
    // Une étiquette qui n'existe plus (note supprimée) ne filtre plus rien.
    val activeTag = view.tag?.takeIf { it in tags }
    val searching = query.isNotBlank() || activeTag != null
    val found = remember(brain.notes, query, activeTag) { NoteFilter.filter(brain.notes, query, activeTag) }
    val me = brain.notes.firstOrNull { it.isMe }
    val others = brain.notes.filter { !it.isMe && it.id !in dormant }
    val empty = brain.notes.all { it.facts.isEmpty() && it.before.isEmpty() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            AxoTextField(
                query,
                { view.query = it },
                Modifier.fillMaxWidth(),
                placeholder = "Chercher dans les notes",
                singleLine = true,
                background = Axo.Card,
            )
        }
        if (tags.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip("Toutes", selected = activeTag == null, enabled = true) { view.tag = null }
                    tags.forEach { t ->
                        ChoiceChip(tagLabel(t), selected = activeTag == t, enabled = true) { view.tag = if (activeTag == t) null else t }
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
            items(found, key = { it.id }) { note ->
                NoteCard(note, query, note.id in dormant, brain.links.neighbors(note.id).size) { onOpenNote(note.id) }
            }
        } else {
            item(key = Note.ME_ID) { MeCard(me) { me?.let { onOpenNote(it.id) } } }
            items(others, key = { it.id }) { note -> NoteCard(note, "", false, brain.links.neighbors(note.id).size) { onOpenNote(note.id) } }
            if (dormant.isNotEmpty()) {
                item {
                    AxoTextButton(
                        (if (dormant.size == 1) "1 note en veille" else "${dormant.size} notes en veille") + " : les voir",
                        onClick = { view.tab = BrainTab.ASLEEP },
                        color = Axo.Frost,
                    )
                }
            }
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

/** La mémoire froide : les notes dont personne ne s'est servi depuis le délai réglé. */
@Composable
private fun AsleepTab(brain: BrainUi, dormant: Set<String>, days: Int, onOpenNote: (String) -> Unit) {
    val asleep = remember(brain.notes, dormant) { brain.notes.filter { it.id in dormant }.sortedByDescending { it.lastActive } }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Une note passe en veille quand ni toi ni Axo ne l'avez ouverte ou utilisée depuis plus de ${dormantDelay(days)}. " +
                    "Rien n'est effacé : elle reste cherchable et visible, pâle, dans le graphe. Axo puise d'abord dans les notes " +
                    "actives, et une note se réveille dès que tu l'ouvres ou qu'Axo s'en sert. Le délai se règle dans les réglages.",
                style = MaterialTheme.typography.bodySmall,
                color = Axo.Muted,
            )
        }
        if (asleep.isEmpty()) {
            item { Text("Aucune note en veille pour l'instant.", style = MaterialTheme.typography.bodyMedium, color = Axo.Frost) }
        }
        items(asleep, key = { it.id }) { note -> NoteCard(note, "", true, brain.links.neighbors(note.id).size) { onOpenNote(note.id) } }
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
            Text(NoteFilter.pretty(NoteLinks.plain(it.text)), style = MaterialTheme.typography.bodyMedium, color = Axo.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun NoteCard(note: Note, query: String, asleep: Boolean, links: Int, onClick: () -> Unit) {
    AxoCard(Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick), asleep = asleep) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                note.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (asleep) Axo.Muted else Axo.Linen,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(factCount(note.facts.size), style = MaterialTheme.typography.bodySmall, color = Axo.Muted)
        }
        val tags = note.tags.filter { it != Note.ME_TAG }
        if (tags.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { tags.forEach { Badge(tagLabel(it)) } }
        }
        NoteFilter.preview(note, query).forEach {
            Text(
                NoteFilter.pretty(NoteLinks.plain(it.text)),
                style = MaterialTheme.typography.bodyMedium,
                color = if (asleep) Axo.Muted else Axo.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val linked = if (links > 0) " · ${linkCount(links)}" else ""
        if (asleep) {
            Text("En veille · touchée pour la dernière fois ${relativeDay(note.lastActive)}$linked", style = MaterialTheme.typography.bodySmall, color = Axo.Frost)
        } else {
            Text("Modifiée ${relativeDay(note.modified)}$linked", style = MaterialTheme.typography.bodySmall, color = Axo.Faint)
        }
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
            "Une note qui en nomme une autre (« Pixel est le chat de Matteo ») y est reliée : touche le nom souligné pour " +
                "passer de l'une à l'autre, ou ouvre l'onglet Graphe.",
            "Une note dont personne ne s'est servi depuis un moment passe en veille : rien n'est effacé.",
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

fun noteCount(n: Int) = if (n == 1) "1 note" else "$n notes"

/** « 1 jour », « 30 jours ». */
fun dormantDelay(days: Int) = if (days == 1) "1 jour" else "$days jours"

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
