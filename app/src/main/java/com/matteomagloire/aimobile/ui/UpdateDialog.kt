package com.matteomagloire.aimobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Fenêtre « Nouvelle version disponible » : on propose, on n'impose pas. */
@Composable
fun UpdateDialog(update: UpdateUi, onInstall: () -> Unit, onLater: () -> Unit) {
    when (update) {
        UpdateUi.Hidden -> Unit
        is UpdateUi.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Mise à jour ${update.info.version}") },
            text = {
                Column {
                    Text("Téléchargement : ${update.percent} %")
                    LinearProgressIndicator(progress = { update.percent / 100f })
                }
            },
            confirmButton = {},
        )
        else -> {
            val info = when (update) {
                is UpdateUi.Available -> update.info
                is UpdateUi.NeedsPermission -> update.info
                is UpdateUi.Failed -> update.info
                else -> return
            }
            AlertDialog(
                onDismissRequest = onLater,
                title = { Text("Nouvelle version disponible : ${info.version}") },
                text = {
                    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                        when (update) {
                            is UpdateUi.NeedsPermission -> Text(
                                "Autorise « Installer des applis inconnues » pour l'assistant, puis reviens et appuie sur Mettre à jour.",
                            )
                            is UpdateUi.Failed -> Text(update.message, color = MaterialTheme.colorScheme.error)
                            else -> Unit
                        }
                        if (info.notes.isNotBlank()) Text(info.notes)
                        Text("Ta mémoire et tes réglages sont conservés.", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = { TextButton(onClick = onInstall) { Text("Mettre à jour") } },
                dismissButton = { TextButton(onClick = onLater) { Text("Plus tard") } },
            )
        }
    }
}
