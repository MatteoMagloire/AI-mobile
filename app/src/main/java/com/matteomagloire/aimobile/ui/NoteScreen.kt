package com.matteomagloire.aimobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.matteomagloire.aimobile.brain.Fact
import com.matteomagloire.aimobile.brain.Note
import com.matteomagloire.aimobile.brain.NoteFilter
import com.matteomagloire.aimobile.brain.NoteFormat
import com.matteomagloire.aimobile.brain.NoteLinks
import com.matteomagloire.aimobile.brain.TextMatch
import com.matteomagloire.aimobile.brain.USER_NAME
import com.matteomagloire.aimobile.ui.theme.Axo
import com.matteomagloire.aimobile.ui.theme.MonoStyle
import com.matteomagloire.aimobile.ui.theme.OverlineStyle
import java.time.LocalDate
import java.util.Locale

/** Le fait en cours de correction ([fact] null : un fait à ajouter), et sa rubrique. */
private data class FactEdit(val fact: Fact?, val before: Boolean)

/** Au-delà, la liste des rétroliens se replie (la note « Moi » est nommée partout). */
private const val FOLDED_LINKS = 12

/** Les noms d'autres notes soulignés en ambre : les toucher ouvre la note. */
private val LinkStyles = TextLinkStyles(SpanStyle(color = Axo.Amber, textDecoration = TextDecoration.Underline))

/**
 * Une note du Cerveau. « Lecture » : mise au propre pour toi (dates en clair, « Avant » en grisé, les
 * noms des autres notes soulignés), sans rien reformuler, avec ses liens et ses rétroliens. « Brut » :
 * le texte exact qu'Axo relit. Les deux se modifient à la main. [wokeUp] : elle était en veille.
 */
@Composable
fun NoteScreen(
    note: Note?,
    notes: List<Note>,
    links: NoteLinks.Links,
    dormantDays: Int,
    wokeUp: Boolean,
    onOpenNote: (String) -> Unit,
    onShowInGraph: () -> Unit,
    onSetLinks: (List<String>) -> Unit,
    onEditFact: (old: Fact?, text: String, before: Boolean) -> Unit,
    onSaveRaw: (String) -> Unit,
    onDeleteNote: () -> Unit,
    onBack: () -> Unit,
) {
    val key = note?.id
    var raw by rememberSaveable(key) { mutableStateOf(false) }
    var editingRaw by rememberSaveable(key) { mutableStateOf(false) }
    var draft by rememberSaveable(key) { mutableStateOf("") }
    var factEdit by remember(key) { mutableStateOf<FactEdit?>(null) }
    var confirmDelete by remember(key) { mutableStateOf(false) }
    var linking by remember(key) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Axo.Ink).statusBarsPadding().navigationBarsPadding().imePadding()) {
        AxoTopBar(note?.title ?: "Note", onBack) {
            if (note != null) {
                IconButton(onClick = onShowInGraph) {
                    Icon(AxoIcons.Brain, contentDescription = "Voir dans le graphe", tint = Axo.TextSecondary)
                }
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(AxoIcons.Delete, contentDescription = if (note.isMe) "Vider la note" else "Supprimer la note", tint = Axo.TextSecondary)
                }
            }
        }
        if (note == null) {
            Text(
                "Cette note n'existe plus.",
                style = MaterialTheme.typography.bodyMedium,
                color = Axo.Muted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip("Lecture", selected = !raw, enabled = !editingRaw) { raw = false }
                    ChoiceChip("Brut", selected = raw, enabled = true) { raw = true }
                }
                if (raw) {
                    RawView(
                        note = note,
                        editing = editingRaw,
                        draft = draft,
                        onDraft = { draft = it },
                        onStartEdit = {
                            draft = NoteFormat.write(note)
                            editingRaw = true
                        },
                        onSave = {
                            onSaveRaw(draft)
                            editingRaw = false
                        },
                        onCancel = { editingRaw = false },
                    )
                } else {
                    ReadingView(
                        note = note,
                        notes = notes,
                        links = links,
                        dormantDays = dormantDays,
                        wokeUp = wokeUp,
                        onOpenNote = onOpenNote,
                        onLink = { linking = true },
                        onEdit = { factEdit = it },
                    )
                }
            }
        }
    }

    factEdit?.let { edit ->
        var text by remember(edit) { mutableStateOf(edit.fact?.text ?: "") }
        AlertDialog(
            onDismissRequest = { factEdit = null },
            title = { Text(if (edit.fact == null) "Ajouter un fait" else "Corriger le fait") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AxoTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 96.dp), placeholder = "$USER_NAME …")
                    Text(
                        "Une phrase courte, comme Axo les écrit : « $USER_NAME joue de la guitare. »",
                        style = MaterialTheme.typography.bodySmall,
                        color = Axo.Muted,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onEditFact(edit.fact, text, edit.before); factEdit = null }, enabled = text.isNotBlank()) {
                    Text("Enregistrer")
                }
            },
            dismissButton = {
                Row {
                    if (edit.fact != null) {
                        TextButton(onClick = { onEditFact(edit.fact, "", edit.before); factEdit = null }) { Text("Supprimer", color = Axo.Blush) }
                    }
                    TextButton(onClick = { factEdit = null }) { Text("Annuler") }
                }
            },
        )
    }

    if (linking && note != null) {
        LinkDialog(
            note = note,
            notes = notes,
            links = links,
            onSave = {
                onSetLinks(it)
                linking = false
            },
            onDismiss = { linking = false },
        )
    }

    if (confirmDelete && note != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (note.isMe) "Vider « ${note.title} » ?" else "Supprimer « ${note.title} » ?") },
            text = {
                Text(
                    if (note.isMe) "Tout ce qu'Axo sait de toi sera effacé de cette note, et il ne le renotera pas."
                    else "La note et ses faits seront effacés du téléphone, et Axo ne les renotera pas.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDeleteNote()
                    if (!note.isMe) onBack()
                }) { Text(if (note.isMe) "Vider" else "Supprimer", color = Axo.Blush) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } },
        )
    }
}

/** Les textes de la note, avec les noms des autres notes : chaque note n'est soulignée qu'une fois. */
private class LinkedNote(val extra: NoteLinks.Linked, val facts: List<NoteLinks.Linked>, val before: List<Pair<Fact, NoteLinks.Linked>>)

@Composable
private fun ReadingView(
    note: Note,
    notes: List<Note>,
    links: NoteLinks.Links,
    dormantDays: Int,
    wokeUp: Boolean,
    onOpenNote: (String) -> Unit,
    onLink: () -> Unit,
    onEdit: (FactEdit) -> Unit,
) {
    val linked = remember(note, links) {
        val seen = HashSet<String>()
        LinkedNote(
            extra = NoteLinks.linked(note.extra.trim(), links.index, note.id, seen),
            facts = note.facts.map { NoteLinks.linked(it.text, links.index, note.id, seen) },
            before = note.before.sortedWith(compareByDescending { it.until ?: it.date }).map { it to NoteLinks.linked(it.text, links.index, note.id, seen) },
        )
    }
    Text(
        if (note.isMe) "Ce qu'Axo sait de toi. Touche un fait pour le corriger ou le supprimer, un nom souligné pour ouvrir sa note."
        else "Touche un fait pour le corriger ou le supprimer, un nom souligné pour ouvrir sa note.",
        style = MaterialTheme.typography.bodySmall,
        color = Axo.Muted,
    )
    if (wokeUp) {
        Text("Cette note était en veille : en l'ouvrant, tu l'as réveillée.", style = MaterialTheme.typography.bodySmall, color = Axo.Frost)
    }
    val tags = note.tags.filter { it != Note.ME_TAG }
    if (tags.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { tags.forEach { Badge(tagLabel(it), active = true) } }
    }
    // Le texte écrit à la main sous le titre, en tête, dans un encadré.
    if (note.extra.isNotBlank()) {
        Text(
            annotated(linked.extra, onOpenNote),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Axo.Ember)
                .border(BorderStroke(1.dp, Axo.EmberBorder), RoundedCornerShape(14.dp))
                .padding(12.dp),
        )
    }
    SectionTitle("Faits")
    AxoCard {
        if (note.facts.isEmpty()) Text("Aucun fait pour l'instant.", style = MaterialTheme.typography.bodyMedium, color = Axo.Muted)
        note.facts.forEachIndexed { i, f -> FactRow(f, linked.facts[i], past = false, onOpenNote) { onEdit(FactEdit(f, before = false)) } }
        AxoTextButton("Ajouter un fait", onClick = { onEdit(FactEdit(null, before = false)) })
    }
    if (linked.before.isNotEmpty()) {
        SectionTitle("Avant")
        AxoCard {
            linked.before.forEach { (f, text) -> FactRow(f, text, past = true, onOpenNote) { onEdit(FactEdit(f, before = true)) } }
        }
    }

    val today = LocalDate.now()
    val byId = remember(notes) { notes.associateBy { it.id } }
    val outgoing = links.linksOf(note.id).mapNotNull { byId[it] }
    val incoming = links.backlinksOf(note.id).mapNotNull { byId[it] }
    LinkList("Liens", outgoing, today, dormantDays, onOpenNote)
    AxoTextButton("Relier à une autre note", onClick = onLink)
    LinkList("Rétroliens", incoming, today, dormantDays, onOpenNote)
    if (outgoing.isEmpty() && incoming.isEmpty()) {
        Text(
            "Aucune note n'est reliée à celle-ci. Quand un fait nomme une autre note, elles se relient toutes seules.",
            style = MaterialTheme.typography.bodySmall,
            color = Axo.Muted,
        )
    }

    val used = note.used?.let { " · utilisée par Axo ${relativeDay(it)}" }.orEmpty()
    Text(
        "Créée ${relativeDay(note.created)} · modifiée ${relativeDay(note.modified)}$used",
        style = MaterialTheme.typography.bodySmall,
        color = Axo.Faint,
    )
    Text("${note.id}.md", style = MonoStyle, color = Axo.Faint)
}

/** Un texte avec ses liens : les noms d'autres notes soulignés, qui ouvrent la note touchée. */
private fun annotated(linked: NoteLinks.Linked, onOpenNote: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    append(linked.text)
    for (span in linked.spans) {
        addLink(LinkAnnotation.Clickable(span.noteId, LinkStyles) { onOpenNote(span.noteId) }, span.start, span.end)
    }
}

/** Un fait, remis au propre, avec sa date en clair. Les faits d'« Avant » sont grisés. */
@Composable
private fun FactRow(fact: Fact, text: NoteLinks.Linked, past: Boolean, onOpenNote: (String) -> Unit, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            annotated(NoteFilter.pretty(text), onOpenNote),
            style = MaterialTheme.typography.bodyLarge,
            color = if (past) Axo.Muted else Axo.Linen,
        )
        val from = fact.date
        val until = fact.until
        val date = when {
            from != null && until != null -> "du ${longDate(from)} au ${longDate(until)}"
            until != null -> "jusqu'au ${longDate(until)}"
            from != null -> "noté le ${longDate(from)}"
            else -> null
        }
        if (date != null) Text(date, style = MaterialTheme.typography.bodySmall, color = Axo.Faint)
    }
}

/** « LIENS · 2 » ou « RÉTROLIENS · 3 », puis une ligne par note (pâle si elle est en veille). */
@Composable
private fun LinkList(title: String, notes: List<Note>, today: LocalDate, dormantDays: Int, onOpenNote: (String) -> Unit) {
    if (notes.isEmpty()) return
    var unfolded by remember(notes.size) { mutableStateOf(false) }
    Text("${title.uppercase(Locale.FRENCH)} · ${notes.size}", style = OverlineStyle.copy(color = Axo.Muted), modifier = Modifier.padding(start = 4.dp, top = 8.dp))
    val shown = if (unfolded || notes.size <= FOLDED_LINKS) notes else notes.take(FOLDED_LINKS - 2)
    shown.forEach { n -> LinkRow(n, n.isDormant(today, dormantDays)) { onOpenNote(n.id) } }
    if (shown.size < notes.size) {
        AxoTextButton("Afficher les ${notes.size} notes", onClick = { unfolded = true }, color = Axo.TextSecondary)
    }
}

@Composable
private fun LinkRow(note: Note, asleep: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (asleep) Axo.Night else Axo.Card)
            .border(BorderStroke(1.dp, if (asleep) Axo.NightBorder else Axo.Line), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (asleep) Axo.Frost else Axo.Amber))
        Text(
            note.title + if (asleep) " · en veille" else "",
            style = MaterialTheme.typography.bodyLarge,
            color = if (asleep) Axo.Muted else Axo.Linen,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(AxoIcons.Chevron, contentDescription = null, tint = Axo.Faint, modifier = Modifier.size(16.dp))
    }
}

/**
 * « Relier à une autre note » : coche les notes à relier à la main (rubrique « ## Liens »). Celles que
 * le texte nomme déjà sont reliées d'office.
 */
@Composable
private fun LinkDialog(note: Note, notes: List<Note>, links: NoteLinks.Links, onSave: (List<String>) -> Unit, onDismiss: () -> Unit) {
    val named = remember(note, links) { NoteLinks.outgoing(note.copy(links = emptyList()), links.index).toSet() }
    var chosen by remember(note) { mutableStateOf(note.links.mapNotNull { links.index.resolve(it) }.toSet()) }
    var query by remember { mutableStateOf("") }
    val others = remember(notes, note) { notes.filter { it.id != note.id }.sortedBy { TextMatch.fold(it.title) } }
    val shown = remember(others, query) { NoteFilter.filter(others, query) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Relier « ${note.title} »") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AxoTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = "Chercher une note", singleLine = true)
                if (shown.isEmpty()) Text("Aucune autre note.", style = MaterialTheme.typography.bodyMedium, color = Axo.Muted)
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(shown, key = { it.id }) { other ->
                        val auto = other.id in named
                        val on = auto || other.id in chosen
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = !auto) { chosen = if (other.id in chosen) chosen - other.id else chosen + other.id }
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(22.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (on) Axo.Ember else Color.Transparent)
                                    .border(BorderStroke(1.dp, if (on) Axo.AmberBorder else Axo.Line), RoundedCornerShape(6.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (on) Icon(AxoIcons.Check, contentDescription = null, tint = Axo.Amber, modifier = Modifier.size(16.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(other.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (auto) Text("Nommée dans le texte", style = MaterialTheme.typography.bodySmall, color = Axo.Muted)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Les liens vers une note qui n'existe pas (encore) restent tels quels.
                val kept = note.links.filter { links.index.resolve(it) == null }
                onSave(kept + others.filter { it.id in chosen && it.id !in named }.map { it.title })
            }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun RawView(
    note: Note,
    editing: Boolean,
    draft: String,
    onDraft: (String) -> Unit,
    onStartEdit: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val text = remember(note) { NoteFormat.write(note) }
    AxoCard {
        if (editing) {
            AxoTextField(draft, onDraft, Modifier.fillMaxWidth().heightIn(min = 240.dp), textStyle = MonoStyle)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AxoButton("Enregistrer", onClick = onSave)
                AxoTextButton("Annuler", onClick = onCancel, color = Axo.TextSecondary)
            }
        } else {
            SelectionContainer { Text(text, style = MonoStyle, color = Axo.Linen) }
            AxoOutlineButton("Modifier à la main", onClick = onStartEdit)
        }
    }
    Text(
        "C'est le texte exact qu'Axo relit. Sous « ## Faits », chaque ligne « - … » est un fait (la date avant « · » est " +
            "facultative). [[Titre]] relie à une autre note, et sous « ## Liens » chaque ligne relie une note à la main. " +
            "Le texte écrit sous le titre est gardé tel quel.",
        style = MaterialTheme.typography.bodySmall,
        color = Axo.Muted,
    )
}
