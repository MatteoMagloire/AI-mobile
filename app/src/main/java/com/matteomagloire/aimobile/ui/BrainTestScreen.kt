package com.matteomagloire.aimobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.matteomagloire.aimobile.brain.BrainScenario
import com.matteomagloire.aimobile.brain.BrainTestResult
import com.matteomagloire.aimobile.brain.BrainTestRow
import com.matteomagloire.aimobile.brain.NoteOutcome
import com.matteomagloire.aimobile.model.ModelCatalog
import com.matteomagloire.aimobile.ui.theme.Axo
import com.matteomagloire.aimobile.ui.theme.MonoStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Test du Cerveau : Axo lit une petite conversation écrite d'avance et prend ses notes dans un Cerveau
 * à part. On voit ce qu'il retient, ce qu'il note pour rien, et le temps que ça prend.
 */
@Composable
fun BrainTestScreen(
    state: ChatUiState,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onDismissError: () -> Unit,
    onBack: () -> Unit,
) {
    val measure = state.measure
    val running = measure.running == MeasureKind.BRAIN
    val busy = measure.running != null
    val model = ModelCatalog.byId(state.settings.instructModelId) ?: ModelCatalog.INSTRUCT
    val ready = model.id in state.downloaded
    KeepScreenOn(running)

    Column(Modifier.fillMaxSize().background(Axo.Ink).statusBarsPadding().navigationBarsPadding()) {
        AxoTopBar("Test du Cerveau", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Axo lit ${BrainScenario.exchanges.size} messages écrits d'avance, comme si tu les lui avais envoyés, et prend " +
                    "ses notes dans un Cerveau à part : le tien n'est pas touché. On compte ce qu'il retient, ce qu'il note " +
                    "pour rien, s'il suit un déménagement, s'il retrouve ses souvenirs, et le temps de chaque prise de notes.",
                style = MaterialTheme.typography.bodyMedium,
                color = Axo.TextSecondary,
            )
            measure.error?.let {
                Text(it, color = Axo.Blush, style = MaterialTheme.typography.bodyMedium)
                AxoTextButton("OK", onClick = onDismissError)
            }

            AxoCard(highlighted = running) {
                Text("Prise de notes", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Modèle Instruct (${model.quant}) · quelques minutes · garde l'écran allumé",
                    style = MaterialTheme.typography.bodySmall,
                    color = Axo.Muted,
                )
                if (running) {
                    RunningStep(measure.step, measure.progress, onStop)
                    measure.brainLive.forEachIndexed { i, row -> TestRow(i, row) }
                } else {
                    AxoButton(
                        if (measure.brain == null) "Lancer le test" else "Relancer le test",
                        onClick = onRun,
                        enabled = !busy && ready,
                        icon = AxoIcons.Brain,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!ready) {
                        Text("Le modèle Instruct n'est pas sur le téléphone.", style = MaterialTheme.typography.bodySmall, color = Axo.Muted)
                    }
                }
            }

            measure.brain?.let { result ->
                ResultCard(result)
                SectionTitle("Message par message")
                AxoCard {
                    result.rows.forEachIndexed { i, row ->
                        if (i > 0) HorizontalDivider(thickness = 1.dp, color = Axo.Line)
                        TestRow(i, row)
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultCard(r: BrainTestResult) {
    AxoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Dernier résultat", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                "${ModelCatalog.byId(r.modelId)?.quant ?: r.modelId} · ${SimpleDateFormat("d MMM HH:mm", Locale.FRENCH).format(Date(r.time))}",
                style = MonoStyle,
                color = Axo.Muted,
            )
        }
        MetricRow("Faits retenus", "${r.found} sur ${r.expected}", target = "cible : au moins ${r.target}", ok = r.passed)
        MetricRow(
            "Notés pour rien",
            "${r.unexpected}",
            target = "sur les messages sans rien à retenir · cible : 0",
            ok = r.unexpected == 0,
        )
        MetricRow(
            "Déménagement suivi",
            if (r.updateOk) "oui" else "non",
            target = "Lyon passe dans « Avant », Paris devient sa ville",
            ok = r.updateOk,
        )
        MetricRow(
            "Souvenirs retrouvés",
            "${r.recallFound} sur ${r.recallTotal}",
            target = "recherche : ${"%.1f".format(Locale.FRANCE, r.recallMs)} ms par question",
            ok = r.recallFound == r.recallTotal,
        )
        MetricRow(
            "Durée d'une prise de notes",
            seconds(r.averageMs),
            target = "en moyenne, après la réponse · la plus longue : ${seconds(r.maxMs)}",
        )
        // Les consignes : reprises du lancement précédent, lues (et gardées pour le prochain), ou déjà en mémoire.
        when {
            r.prefixRestoredTokens > 0 -> MetricRow(
                "Lecture des consignes",
                seconds(r.prefixRestoreMs + r.prefixMs),
                target = "${r.prefixRestoredTokens} tokens repris du lancement précédent" +
                    if (r.prefixTokens > 0) ", ${r.prefixTokens} lus car les consignes ont changé" else ", rien à relire",
            )
            r.prefixTokens > 0 -> MetricRow(
                "Lecture des consignes",
                seconds(r.prefixMs),
                target = "${r.prefixTokens} tokens lus" +
                    if (r.prefixSavedBytes > 0) ", puis gardés sur le téléphone (${megabytes(r.prefixSavedBytes)}) pour le prochain lancement"
                    else ", une fois par chargement du modèle",
            )
            else -> MetricRow("Lecture des consignes", seconds(0), target = "déjà en mémoire depuis le chargement du modèle")
        }
        if (r.notes.isNotEmpty()) {
            Text(
                "Notes à la fin : " + r.notes.joinToString(" · ") { (title, facts) -> "$title ($facts)" },
                style = MaterialTheme.typography.bodySmall,
                color = Axo.Muted,
            )
        }
    }
}

/** Un message du test : ce qu'il fallait retenir, ce qui a été noté, et en combien de temps. */
@Composable
private fun TestRow(index: Int, row: BrainTestRow) {
    val ok = if (row.expected == 0) row.unexpected == 0 else row.found == row.expected
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${index + 1}. ${row.message}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Icon(
                if (ok) AxoIcons.Check else AxoIcons.Close,
                contentDescription = if (ok) "Réussi" else "Raté",
                tint = if (ok) Axo.Good else Axo.Blush,
                modifier = Modifier.size(18.dp),
            )
        }
        val result = when {
            row.expected == 0 && row.unexpected == 0 -> "rien à retenir, rien noté"
            row.expected == 0 -> "rien à retenir, ${row.unexpected} noté pour rien"
            else -> "${row.found} sur ${row.expected} retenu"
        }
        Text("$result · ${outcome(row.outcome)} · ${seconds(row.ms)}", style = MonoStyle, color = Axo.Muted)
        if (row.steps > 0) {
            Text(
                "${row.steps} ${if (row.steps > 1) "étapes" else "étape"} · lecture ${seconds(row.readMs)} · écriture ${seconds(row.writeMs)}",
                style = MonoStyle,
                color = Axo.Muted,
            )
        }
        row.noted.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall, color = Axo.TextSecondary) }
    }
}

private fun outcome(o: NoteOutcome) = when (o) {
    NoteOutcome.NOTED -> "noté"
    NoteOutcome.NOTHING -> "rien trouvé"
    NoteOutcome.KNOWN -> "déjà connu"
    NoteOutcome.IGNORED -> "pas lu"
    NoteOutcome.FORGOTTEN -> "oubli"
    NoteOutcome.FAILED -> "échec"
}
