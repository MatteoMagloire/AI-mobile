package com.matteomagloire.aimobile.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.matteomagloire.aimobile.model.ModelInfo
import com.matteomagloire.aimobile.ui.theme.Axo

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
    // Centré dans l'écran, et défilable si le texte ne tient pas (petite police, écran partagé).
    BoxWithConstraints(Modifier.fillMaxSize().background(Axo.Ink).safeDrawingPadding()) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(min = maxHeight).fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Axo et sa lueur ambre.
            Box(
                Modifier.size(200.dp).drawBehind {
                    drawCircle(Brush.radialGradient(listOf(Axo.Amber.copy(alpha = 0.16f), Axo.Amber.copy(alpha = 0f))))
                },
                contentAlignment = Alignment.Center,
            ) {
                Image(AxoIcons.Face, contentDescription = "Axo", modifier = Modifier.size(150.dp))
            }
            Text("Salut, moi c'est Axo", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Text(
                "Je vis entièrement dans ton téléphone, sans internet et sans serveur. Pour penser, j'ai besoin " +
                    "de mon modèle, ${model.displayName} (${gigabytes(model.approxSizeBytes)}). Il se télécharge une seule fois.",
                style = MaterialTheme.typography.bodyLarge,
                color = Axo.TextSecondary,
                textAlign = TextAlign.Center,
            )
            when {
                download == null -> AxoButton(
                    "Télécharger (${gigabytes(model.approxSizeBytes)})",
                    onClick = onDownload,
                    icon = AxoIcons.Download,
                    modifier = Modifier.fillMaxWidth(),
                )
                else -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AmberProgress(download.percent / 100f)
                    Text(
                        when {
                            !download.waitingForNetwork -> "Téléchargement : ${download.percent} %"
                            wifiOnly -> "En attente du Wi-Fi… (${download.percent} %)"
                            else -> "En attente de connexion… (${download.percent} %)"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Axo.Muted,
                    )
                }
            }
            if (wifiOnly) {
                Text(
                    "En Wi-Fi seulement (modifiable dans les réglages).",
                    style = MaterialTheme.typography.bodySmall,
                    color = Axo.Faint,
                    textAlign = TextAlign.Center,
                )
            }
            error?.let { Text(it, color = Axo.Blush, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
            AxoTextButton("Réglages", onClick = onOpenSettings, color = Axo.TextSecondary)
        }
    }
}
