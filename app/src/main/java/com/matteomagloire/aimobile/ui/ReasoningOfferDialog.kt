package com.matteomagloire.aimobile.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.matteomagloire.aimobile.model.ModelCatalog
import java.util.Locale

/** Le mode réflexion utilise un second modèle : on propose de le télécharger sans quitter le chat. */
@Composable
fun ReasoningOfferDialog(wifiOnly: Boolean, onDownload: () -> Unit, onCancel: () -> Unit) {
    val size = "%.1f".format(Locale.FRANCE, ModelCatalog.REASONING.approxSizeBytes / 1e9)
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Activer le mode réflexion ?") },
        text = {
            Text(
                "Le mode réflexion utilise un second modèle, ${ModelCatalog.REASONING.displayName} " +
                    "(environ $size Go). Il se télécharge une seule fois en arrière-plan" +
                    (if (wifiOnly) ", en Wi-Fi" else "") +
                    ". Tu peux continuer à discuter pendant ce temps.",
            )
        },
        confirmButton = { TextButton(onClick = onDownload) { Text("Télécharger") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Plus tard") } },
    )
}
