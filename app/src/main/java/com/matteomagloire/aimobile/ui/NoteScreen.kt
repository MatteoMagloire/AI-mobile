package com.matteomagloire.aimobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import com.matteomagloire.aimobile.brain.Fact
import com.matteomagloire.aimobile.brain.Note
import com.matteomagloire.aimobile.brain.NoteFilter
import com.matteomagloire.aimobile.brain.NoteFormat
import com.matteomagloire.aimobile.brain.USER_NAME
import com.matteomagloire.aimobile.ui.theme.Axo
import com.matteomagloire.aimobile.ui.theme.MonoStyle

/** Le fait en cours de correction ([fact] null : un fait à ajouter), et sa rubrique. */
private data class FactEdit(val fact: Fact?, val before: Boolean)

/**
 * Une note du Cerveau. « Lecture » : mise au propre pour toi (dates en clair, « Avant » en grisé),
 * sans rien reformuler. « Brut » : le texte exact qu'Axo relit. Les deux se modifient à la main.
 */
@Composable
fun NoteScreen(
    note: Note?,
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

    Column(Modifier.fillMaxSize().background(Axo.Ink).statusBarsPadding().navigationBarsPadding().imePadding()) {
        AxoTopBar(note?.title ?: "Note", onBack) {
            if (note != null) {
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
                    ReadingView(note) { factEdit = it }
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

@Composable
private fun ReadingView(note: Note, onEdit: (FactEdit) -> Unit) {
    Text(
        if (note.isMe) "Ce qu'Axo sait de toi. Touche un fait pour le corriger ou le supprimer."
        else "Touche un fait pour le corriger ou le supprimer.",
        style = MaterialTheme.typography.bodySmall,
        color = Axo.Muted,
    )
    val tags = note.tags.filter { it != Note.ME_TAG }
    if (tags.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { tags.forEach { Badge(tagLabel(it), active = true) } }
    }
    // Le texte écrit à la main sous le titre, en tête, dans un encadré.
    if (note.extra.isNotBlank()) {
        Text(
            note.extra.trim(),
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
        note.facts.forEach { f -> FactRow(f, past = false) { onEdit(FactEdit(f, before = false)) } }
        AxoTextButton("Ajouter un fait", onClick = { onEdit(FactEdit(null, before = false)) })
    }
    if (note.before.isNotEmpty()) {
        SectionTitle("Avant")
        AxoCard {
            note.before.sortedWith(compareByDescending { it.until ?: it.date }).forEach { f ->
                FactRow(f, past = true) { onEdit(FactEdit(f, before = true)) }
            }
        }
    }
    Text(
        "Créée ${relativeDay(note.created)} · modifiée ${relativeDay(note.modified)}",
        style = MaterialTheme.typography.bodySmall,
        color = Axo.Faint,
    )
    Text("${note.id}.md", style = MonoStyle, color = Axo.Faint)
}

/** Un fait, remis au propre, avec sa date en clair. Les faits d'« Avant » sont grisés. */
@Composable
private fun FactRow(fact: Fact, past: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(NoteFilter.pretty(fact.text), style = MaterialTheme.typography.bodyLarge, color = if (past) Axo.Muted else Axo.Linen)
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
            "facultative). Le texte écrit sous le titre est gardé tel quel.",
        style = MaterialTheme.typography.bodySmall,
        color = Axo.Muted,
    )
}
