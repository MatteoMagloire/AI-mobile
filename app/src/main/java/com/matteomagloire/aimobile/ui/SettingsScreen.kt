package com.matteomagloire.aimobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.matteomagloire.aimobile.BuildConfig
import com.matteomagloire.aimobile.brain.Brain
import com.matteomagloire.aimobile.model.ModelInfo
import com.matteomagloire.aimobile.settings.DEFAULT_SYSTEM_PROMPT
import com.matteomagloire.aimobile.settings.Settings
import com.matteomagloire.aimobile.ui.theme.Axo
import com.matteomagloire.aimobile.ui.theme.MonoStyle
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    settings: Settings,
    instructModel: ModelInfo,
    reasoningModel: ModelInfo,
    engineInfo: String,
    onChange: ((Settings) -> Settings) -> Unit,
    onReasoningChange: (Boolean) -> Unit,
    onClearConversation: () -> Unit,
    onOpenSpeedTest: () -> Unit,
    onOpenBench: () -> Unit,
    onOpenBrainTest: () -> Unit,
    onClearBrain: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    var confirmClearBrain by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    // Copie locale : le texte suit la frappe sans attendre l'enregistrement des réglages.
    var prompt by rememberSaveable { mutableStateOf(settings.systemPrompt) }
    fun setPrompt(value: String) {
        prompt = value
        onChange { it.copy(systemPrompt = value) }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Effacer la conversation ?") },
            text = { Text("Tous les messages seront supprimés du téléphone. C'est définitif.") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onClearConversation() }) { Text("Effacer", color = Axo.Blush) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Annuler") } },
        )
    }
    if (confirmClearBrain) {
        AlertDialog(
            onDismissRequest = { confirmClearBrain = false },
            title = { Text("Effacer le Cerveau ?") },
            text = { Text("Toutes les notes d'Axo et son journal seront supprimés du téléphone. La conversation reste. C'est définitif.") },
            confirmButton = {
                TextButton(onClick = { confirmClearBrain = false; onClearBrain() }) { Text("Effacer", color = Axo.Blush) }
            },
            dismissButton = { TextButton(onClick = { confirmClearBrain = false }) { Text("Annuler") } },
        )
    }
    if (showLicenses) LicensesDialog(onDismiss = { showLicenses = false })

    Column(Modifier.fillMaxSize().background(Axo.Ink).statusBarsPadding().navigationBarsPadding()) {
        AxoTopBar("Réglages", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle("Conversation")
            AxoCard {
                ToggleRow(
                    "Mode réflexion",
                    settings.reasoningMode,
                    onReasoningChange,
                    detail = "Modèle Reasoning : réfléchit avant de répondre, plus lent",
                )
                Text(
                    "Créativité : ${"%.2f".format(Locale.FRANCE, settings.temperature)}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Slider(
                    value = settings.temperature,
                    onValueChange = { v -> onChange { it.copy(temperature = (v * 20).roundToInt() / 20f) } },
                    valueRange = 0f..1.5f,
                    colors = sliderColors(),
                )
                Text("Longueur max des réponses : ${settings.maxTokens} tokens", style = MaterialTheme.typography.bodyLarge)
                Slider(
                    value = settings.maxTokens.toFloat(),
                    onValueChange = { v -> onChange { it.copy(maxTokens = ((v / 64).roundToInt() * 64).coerceAtLeast(64)) } },
                    valueRange = 64f..2048f,
                    colors = sliderColors(),
                )
                ToggleRow(
                    "Afficher la vitesse",
                    settings.showSpeed,
                    { v -> onChange { it.copy(showSpeed = v) } },
                    detail = "Premier mot et tokens par seconde sous chaque réponse",
                )
            }

            SectionTitle("Personnalité d'Axo")
            AxoCard {
                Text("Instructions de base (prompt système)", style = MaterialTheme.typography.bodySmall, color = Axo.Muted)
                BasicTextField(
                    value = prompt,
                    onValueChange = { setPrompt(it) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Axo.Linen),
                    cursorBrush = SolidColor(Axo.Amber),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Axo.Ink)
                        .border(BorderStroke(1.dp, Axo.Line), RoundedCornerShape(14.dp))
                        .padding(12.dp),
                )
                if (prompt != DEFAULT_SYSTEM_PROMPT) {
                    AxoTextButton("Rétablir la personnalité d'Axo", onClick = { setPrompt(DEFAULT_SYSTEM_PROMPT) })
                }
                Text(
                    "Les nouvelles instructions s'appliquent à la prochaine ouverture de l'app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Axo.Muted,
                )
            }

            SectionTitle("Cerveau")
            AxoCard {
                Text("Mise en veille des notes", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Une note que ni toi ni Axo n'avez ouverte ou utilisée depuis ce délai passe en veille. " +
                        "Axo puise d'abord dans les notes actives ; rien n'est effacé.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Axo.Muted,
                )
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Brain.DORMANT_CHOICES.forEach { days ->
                        ChoiceChip(dormantDelay(days), selected = settings.dormantDays == days, enabled = true) {
                            onChange { it.copy(dormantDays = days) }
                        }
                    }
                }
            }

            SectionTitle("Mesures")
            AxoCard {
                EntryRow(
                    AxoIcons.Speed,
                    "Test de vitesse",
                    "Premier mot, écriture, mémoire et chauffe sur ton téléphone",
                    onOpenSpeedTest,
                )
                EntryRow(
                    AxoIcons.Compare,
                    "Comparer les fichiers",
                    "Q4_K_M, UD-Q4_K_XL ou Q4_0 : nos 10 questions, à l'aveugle",
                    onOpenBench,
                )
                EntryRow(
                    AxoIcons.Brain,
                    "Test du Cerveau",
                    "10 messages écrits d'avance : ce qu'Axo retient, et en combien de temps",
                    onOpenBrainTest,
                )
                Text(
                    "Fichiers utilisés : ${instructModel.quant} (Instruct) · ${reasoningModel.quant} (Réflexion)",
                    style = MonoStyle,
                    color = Axo.Muted,
                )
            }

            SectionTitle("Téléchargements")
            AxoCard {
                ToggleRow(
                    "En Wi-Fi uniquement",
                    settings.wifiOnlyDownloads,
                    { v -> onChange { it.copy(wifiOnlyDownloads = v) } },
                    detail = "Les modèles pèsent environ 2 Go chacun",
                )
            }

            SectionTitle("Données")
            AxoCard {
                AxoTextButton("Effacer la conversation", onClick = { confirmClear = true }, color = Axo.Blush)
                AxoTextButton("Effacer le Cerveau", onClick = { confirmClearBrain = true }, color = Axo.Blush)
            }

            SectionTitle("À propos")
            AxoCard {
                Text("Axo ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
                if (engineInfo.isNotEmpty()) Text(engineInfo, style = MonoStyle, color = Axo.Muted)
                AxoTextButton("Licences des polices", onClick = { showLicenses = true }, color = Axo.TextSecondary)
            }
        }
    }
}

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = Axo.Amber,
    activeTrackColor = Axo.Amber,
    inactiveTrackColor = Axo.Line,
    activeTickColor = Axo.Amber,
    inactiveTickColor = Axo.Line,
)

/** Les polices sont sous licence SIL OFL 1.1, livrée avec l'app. */
@Composable
private fun LicensesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text = remember {
        listOf("bricolage-grotesque", "figtree", "jetbrains-mono").joinToString("\n\n— — —\n\n") { name ->
            try {
                context.assets.open("licenses/$name.txt").bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                ""
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Licences des polices") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Bricolage Grotesque, Figtree et JetBrains Mono : SIL Open Font License 1.1.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(text, style = MonoStyle, color = Axo.Muted, modifier = Modifier.padding(top = 12.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}
