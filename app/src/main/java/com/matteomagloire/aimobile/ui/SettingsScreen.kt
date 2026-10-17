package com.matteomagloire.aimobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.matteomagloire.aimobile.BuildConfig
import com.matteomagloire.aimobile.settings.DEFAULT_SYSTEM_PROMPT
import com.matteomagloire.aimobile.settings.Settings
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    onChange: ((Settings) -> Settings) -> Unit,
    onReasoningChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Réglages") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Retour") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ToggleRow(
                "Mode réflexion (modèle Reasoning, plus lent)",
                settings.reasoningMode,
                onReasoningChange,
            )

            Text("Température : ${"%.2f".format(Locale.FRANCE, settings.temperature)}")
            Slider(
                value = settings.temperature,
                onValueChange = { v -> onChange { it.copy(temperature = (v * 20).roundToInt() / 20f) } },
                valueRange = 0f..1.5f,
            )

            Text("Longueur max de la réponse : ${settings.maxTokens} tokens")
            Slider(
                value = settings.maxTokens.toFloat(),
                onValueChange = { v -> onChange { it.copy(maxTokens = ((v / 64).roundToInt() * 64).coerceAtLeast(64)) } },
                valueRange = 64f..2048f,
            )

            ToggleRow(
                "Télécharger les modèles en Wi-Fi uniquement",
                settings.wifiOnlyDownloads,
            ) { v -> onChange { it.copy(wifiOnlyDownloads = v) } }

            OutlinedTextField(
                value = settings.systemPrompt,
                onValueChange = { v -> onChange { it.copy(systemPrompt = v) } },
                label = { Text("Instructions de l'assistant (prompt système)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
            TextButton(onClick = { onChange { it.copy(systemPrompt = DEFAULT_SYSTEM_PROMPT) } }) {
                Text("Rétablir les instructions par défaut")
            }
            Text(
                "Les nouvelles instructions s'appliquent à la prochaine conversation.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
