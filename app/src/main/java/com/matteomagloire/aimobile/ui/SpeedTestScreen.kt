package com.matteomagloire.aimobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.matteomagloire.aimobile.llm.LlamaEngine
import com.matteomagloire.aimobile.measure.DeviceStats
import com.matteomagloire.aimobile.measure.EndurancePoint
import com.matteomagloire.aimobile.measure.EnduranceResult
import com.matteomagloire.aimobile.measure.SpeedResult
import com.matteomagloire.aimobile.measure.Targets
import com.matteomagloire.aimobile.model.ModelCatalog
import com.matteomagloire.aimobile.ui.theme.Axo
import com.matteomagloire.aimobile.ui.theme.MonoStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SpeedTestScreen(
    state: ChatUiState,
    engineInfo: String,
    onRunSpeed: () -> Unit,
    onRunEndurance: () -> Unit,
    onThreadsChange: (Int) -> Unit,
    onStop: () -> Unit,
    onDismissError: () -> Unit,
    onBack: () -> Unit,
) {
    val measure = state.measure
    val running = measure.running
    KeepScreenOn(running != null)

    Column(Modifier.fillMaxSize().background(Axo.Ink).statusBarsPadding().navigationBarsPadding()) {
        AxoTopBar("Test de vitesse", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Mesure Axo sur ton téléphone avec le fichier ${state.activeModel.quant} " +
                    "(${state.activeModel.displayName}). La conversation est mise de côté pendant le test, puis reprise.",
                style = MaterialTheme.typography.bodyMedium,
                color = Axo.TextSecondary,
            )
            measure.error?.let {
                Text(it, color = Axo.Blush, style = MaterialTheme.typography.bodyMedium)
                AxoTextButton("OK", onClick = onDismissError)
            }

            AxoCard {
                Text("Cœurs utilisés", style = MaterialTheme.typography.titleMedium)
                Text(
                    "4 cœurs : le plus rapide. 3 ou 2 : un peu plus lent, mais le téléphone chauffe moins. " +
                        "Le choix vaut aussi pour la discussion.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Axo.Muted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (n in LlamaEngine.MIN_THREADS..LlamaEngine.MAX_THREADS) {
                        ChoiceChip("$n cœurs", selected = state.settings.threads == n, enabled = running == null) { onThreadsChange(n) }
                    }
                }
            }

            AxoCard(highlighted = running == MeasureKind.SPEED) {
                Text("Test rapide", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Un texte d'environ 250 mots à résumer, puis une question courte. Environ une minute.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Axo.Muted,
                )
                if (running == MeasureKind.SPEED) {
                    RunningStep(measure.step, measure.progress, onStop)
                } else {
                    AxoButton("Lancer le test", onClick = onRunSpeed, enabled = running == null && state.modelDownloaded, icon = AxoIcons.Speed)
                }
            }

            measure.speed.lastOrNull()?.let { SpeedResultCard(it, latest = true) }

            AxoCard(highlighted = running == MeasureKind.ENDURANCE) {
                Text("Endurance (10 minutes)", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Axo écrit sans pause pendant 10 minutes, le pire cas pour la chauffe. La vitesse de la fin " +
                        "est comparée à celle du début. Garde l'écran allumé.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Axo.Muted,
                )
                if (running == MeasureKind.ENDURANCE) {
                    RunningStep(measure.step, measure.progress, onStop)
                    if (measure.enduranceLive.size >= 2) SpeedChart(measure.enduranceLive)
                    measure.enduranceLive.lastOrNull()?.let {
                        Text(
                            "${tokensPerSecond(it.tokensPerSecond)} · batterie ${celsius(it.batteryC)}",
                            style = MonoStyle,
                            color = Axo.Muted,
                        )
                    }
                } else {
                    AxoOutlineButton("Lancer l'endurance", onClick = onRunEndurance, enabled = running == null && state.modelDownloaded)
                }
            }

            measure.endurance.lastOrNull()?.let { EnduranceCard(it) }

            if (measure.speed.size > 1) {
                SectionTitle("Historique")
                AxoCard {
                    measure.speed.asReversed().drop(1).take(10).forEach { HistoryRow(it) }
                }
            }

            SectionTitle("Détails techniques")
            AxoCard {
                if (engineInfo.isNotEmpty()) Text(engineInfo, style = MonoStyle, color = Axo.Muted)
                Text(
                    "Les températures sont celles de la batterie, le meilleur repère de chauffe accessible à une app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Axo.Muted,
                )
            }
        }
    }
}

@Composable
fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
fun RunningStep(step: String, progress: Float?, onStop: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AmberProgress(progress)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(step, style = MaterialTheme.typography.bodyMedium, color = Axo.Amber, modifier = Modifier.weight(1f))
            AxoTextButton("Arrêter", onClick = onStop, color = Axo.TextSecondary)
        }
    }
}

private fun quantOf(modelId: String) = ModelCatalog.byId(modelId)?.quant ?: modelId

private fun date(time: Long) = SimpleDateFormat("d MMM HH:mm", Locale.FRENCH).format(Date(time))

@Composable
private fun SpeedResultCard(r: SpeedResult, latest: Boolean) {
    AxoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (latest) "Dernier résultat" else "Résultat", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("${quantOf(r.modelId)} · ${r.threads} cœurs · ${date(r.time)}", style = MonoStyle, color = Axo.Muted)
        }
        MetricRow(
            "Premier mot",
            seconds(r.firstWordMs),
            target = "cible : moins de 1 s",
            ok = r.firstWordMs in 1..Targets.FIRST_WORD_MS,
        )
        MetricRow(
            "Vitesse d'écriture",
            tokensPerSecond(r.tokensPerSecond),
            target = "cible : plus de 10 tok/s",
            ok = r.tokensPerSecond > Targets.TOKENS_PER_SECOND,
        )
        MetricRow(
            "Mémoire d'Axo",
            gigabytes(r.rssBytes),
            target = "cible : moins de 3 Go · pic ${gigabytes(r.peakRssBytes)}",
            ok = r.rssBytes in 1 until Targets.RAM_BYTES,
        )
        MetricRow("Lecture d'un message", tokensPerSecond(r.promptTokensPerSecond), target = "${r.promptTokens} tokens lus")
        MetricRow("Premier mot après un long texte", seconds(r.longFirstWordMs))
        MetricRow("Chargement du modèle", seconds(r.loadMs))
        MetricRow(
            "Batterie",
            "${celsius(r.batteryBefore)} → ${celsius(r.batteryAfter)}",
            target = "chauffe : ${DeviceStats.thermalLabel(r.thermalStatus)}",
            ok = r.thermalStatus < android.os.PowerManager.THERMAL_STATUS_MODERATE,
        )
    }
}

@Composable
private fun EnduranceCard(r: EnduranceResult) {
    AxoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Dernière endurance", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("${quantOf(r.modelId)} · ${r.threads} cœurs · ${date(r.time)}", style = MonoStyle, color = Axo.Muted)
        }
        if (r.points.size >= 2) SpeedChart(r.points)
        MetricRow(
            "Ralentissement",
            "${(r.slowdown * 100).roundToInt()} %",
            target = "${tokensPerSecond(r.startSpeed)} au début, ${tokensPerSecond(r.endSpeed)} à la fin",
            ok = if (r.complete) r.slowdown < Targets.MAX_SLOWDOWN else null,
        )
        MetricRow("Durée", "${r.durationSeconds / 60} min ${r.durationSeconds % 60} s", target = if (r.complete) null else "arrêtée avant les 10 minutes")
        val first = r.points.firstOrNull()?.batteryC ?: Float.NaN
        val last = r.points.lastOrNull()?.batteryC ?: Float.NaN
        MetricRow("Batterie", "${celsius(first)} → ${celsius(last)}", target = "chauffe à la fin : ${DeviceStats.thermalLabel(r.thermalStatus)}")
    }
}

@Composable
private fun HistoryRow(r: SpeedResult) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(date(r.time), style = MaterialTheme.typography.bodySmall, color = Axo.Muted, modifier = Modifier.weight(1f))
        Text(
            "${quantOf(r.modelId)} ${r.threads}c  ${seconds(r.firstWordMs)}  ${tokensPerSecond(r.tokensPerSecond)}  ${gigabytes(r.rssBytes)}",
            style = MonoStyle,
        )
    }
}

/** Vitesse d'écriture au fil des minutes, avec la ligne des 10 tok/s en pointillé. */
@Composable
private fun SpeedChart(points: List<EndurancePoint>) {
    val maxSpeed = maxOf(points.maxOf { it.tokensPerSecond }, Targets.TOKENS_PER_SECOND) * 1.15
    val maxSeconds = maxOf(points.last().seconds, 60).toFloat()
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        fun x(s: Int) = s / maxSeconds * size.width
        fun y(v: Double) = (size.height * (1 - v / maxSpeed)).toFloat()
        val target = y(Targets.TOKENS_PER_SECOND)
        drawLine(
            Axo.Faint,
            Offset(0f, target),
            Offset(size.width, target),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
        )
        val path = Path()
        points.forEachIndexed { i, p ->
            if (i == 0) path.moveTo(x(p.seconds), y(p.tokensPerSecond)) else path.lineTo(x(p.seconds), y(p.tokensPerSecond))
        }
        drawPath(path, Axo.Amber, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}
