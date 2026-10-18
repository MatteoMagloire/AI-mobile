package com.matteomagloire.aimobile.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.matteomagloire.aimobile.model.ModelInfo
import com.matteomagloire.aimobile.ui.theme.Axo

/** Le mode réflexion utilise un second modèle : on propose de le télécharger sans quitter le chat. */
@Composable
fun ReasoningOfferDialog(model: ModelInfo, wifiOnly: Boolean, onDownload: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Axo.Slate,
        title = { Text("Activer le mode réflexion ?") },
        text = {
            Text(
                "Pour réfléchir avant de répondre, Axo utilise un second modèle, ${model.displayName} " +
                    "(${model.quant}, environ ${gigabytes(model.approxSizeBytes)}). Il se télécharge une seule fois en arrière-plan" +
                    (if (wifiOnly) ", en Wi-Fi" else "") +
                    ". Tu peux continuer à discuter pendant ce temps.",
            )
        },
        confirmButton = { TextButton(onClick = onDownload) { Text("Télécharger") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Plus tard", color = Axo.TextSecondary) } },
    )
}
