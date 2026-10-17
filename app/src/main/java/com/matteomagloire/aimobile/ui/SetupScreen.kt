package com.matteomagloire.aimobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.matteomagloire.aimobile.model.ModelInfo
import java.util.Locale

/** Premier lancement : téléchargement du modèle (une seule fois, ensuite tout marche hors ligne). */
@Composable
fun SetupScreen(
    model: ModelInfo,
    download: DownloadState?,
    wifiOnly: Boolean,
    error: String?,
    onDownload: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Bienvenue", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Pour fonctionner hors ligne, l'assistant a besoin du modèle ${model.displayName} " +
                "(environ ${"%.1f".format(Locale.FRANCE, model.approxSizeBytes / 1e9)} Go). Il est téléchargé une seule fois.",
            style = MaterialTheme.typography.bodyLarge,
        )
        when {
            download == null -> Button(onClick = onDownload) { Text("Télécharger le modèle") }
            download.waitingForNetwork -> {
                LinearProgressIndicator(progress = { download.percent / 100f }, modifier = Modifier.fillMaxWidth())
                Text(
                    if (wifiOnly) "En attente du Wi-Fi… (${download.percent} %)"
                    else "En attente de connexion… (${download.percent} %)",
                )
            }
            else -> {
                LinearProgressIndicator(progress = { download.percent / 100f }, modifier = Modifier.fillMaxWidth())
                Text("Téléchargement : ${download.percent} %")
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = onOpenSettings) { Text("Réglages") }
    }
}
