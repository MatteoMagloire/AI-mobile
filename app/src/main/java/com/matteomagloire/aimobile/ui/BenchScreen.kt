package com.matteomagloire.aimobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.matteomagloire.aimobile.measure.BENCH_QUESTIONS
import com.matteomagloire.aimobile.measure.BenchState
import com.matteomagloire.aimobile.model.ModelCatalog
import com.matteomagloire.aimobile.model.ModelInfo
import com.matteomagloire.aimobile.ui.theme.Axo
import com.matteomagloire.aimobile.ui.theme.MonoStyle
import java.util.Locale

/** Comparaison des trois fichiers d'un modèle : 10 questions, vote à l'aveugle, puis le fichier à garder. */
@Composable
fun BenchScreen(
    state: ChatUiState,
    onRun: (reasoning: Boolean) -> Unit,
    onStop: () -> Unit,
    onVote: (reasoning: Boolean, question: Int, modelId: String) -> Unit,
    onResetVotes: (reasoning: Boolean) -> Unit,
    onResetBench: (reasoning: Boolean) -> Unit,
    onDownload: (ModelInfo) -> Unit,
    onCancelDownload: (ModelInfo) -> Unit,
    onDelete: (ModelInfo) -> Unit,
    onUse: (ModelInfo) -> Unit,
    onDismissError: () -> Unit,
    onBack: () -> Unit,
) {
    var reasoning by rememberSaveable { mutableStateOf(false) }
    val measure = state.measure
    val running = measure.running == MeasureKind.BENCH
    val busy = measure.running != null
    val bench = measure.bench(reasoning)
    val variants = ModelCatalog.variants(reasoning)
    val usedId = if (reasoning) state.settings.reasoningModelId else state.settings.instructModelId
    val downloadedCount = variants.count { it.id in state.downloaded }
    val pending = variants.any { it.id in state.downloaded && bench.runs[it.id]?.complete != true }
    KeepScreenOn(running)

    Column(Modifier.fillMaxSize().background(Axo.Ink).statusBarsPadding().navigationBarsPadding()) {
        AxoTopBar("Comparer les fichiers", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip("Instruct", selected = !reasoning, enabled = !busy) { reasoning = false }
                ChoiceChip("Réflexion", selected = reasoning, enabled = !busy) { reasoning = true }
            }
            Text(
                "Trois versions 4 bits du même modèle. Axo pose nos 10 questions à chacune, avec les mêmes " +
                    "réglages, puis tu votes sans savoir laquelle a répondu. Compte quelques minutes par fichier" +
                    (if (reasoning) ", plus en mode réflexion." else "."),
                style = MaterialTheme.typography.bodyMedium,
                color = Axo.TextSecondary,
            )
            measure.error?.let {
                Text(it, color = Axo.Blush, style = MaterialTheme.typography.bodyMedium)
                AxoTextButton("OK", onClick = onDismissError)
            }

            variants.forEach { model ->
                VariantCard(
                    model = model,
                    used = model.id == usedId,
                    downloaded = model.id in state.downloaded,
                    download = state.downloads[model.id],
                    bench = bench,
                    busy = busy,
                    onDownload = { onDownload(model) },
                    onCancelDownload = { onCancelDownload(model) },
                    onDelete = { onDelete(model) },
                    onUse = { onUse(model) },
                )
            }

            AxoCard(highlighted = running) {
                when {
                    running -> {
                        RunningStep(measure.step, measure.progress, onStop)
                        Text("Garde l'écran allumé, Axo travaille.", style = MaterialTheme.typography.bodySmall, color = Axo.Muted)
                    }
                    downloadedCount < 2 -> Text(
                        "Télécharge au moins deux fichiers pour les comparer (environ 2 Go chacun, en Wi-Fi de préférence).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Axo.Muted,
                    )
                    pending -> AxoButton(
                        if (bench.runs.isEmpty()) "Lancer la comparaison" else "Continuer la comparaison",
                        onClick = { onRun(reasoning) },
                        enabled = !busy,
                        icon = AxoIcons.Compare,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    else -> Text(
                        "Tous les fichiers téléchargés ont répondu aux 10 questions.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Axo.Muted,
                    )
                }
            }

            bench.nextVote?.let { q -> VoteCard(bench, q, onVote = { id -> onVote(reasoning, q, id) }) }
            if (bench.canVote && bench.voters != bench.completeRuns && !running) {
                // Un nouveau fichier a fini : les votes repartent avec lui.
                AxoOutlineButton("Voter avec tous les fichiers", onClick = { onResetVotes(reasoning) }, modifier = Modifier.fillMaxWidth())
            }
            if (bench.votingDone) {
                ResultsCard(bench, usedId, onUse = { id -> ModelCatalog.byId(id)?.let(onUse) }, onRevote = { onResetVotes(reasoning) })
            }
            if (bench.runs.isNotEmpty() && !busy) {
                AxoTextButton("Effacer cette comparaison", onClick = { onResetBench(reasoning) }, color = Axo.Muted)
            }
        }
    }
}

@Composable
private fun VariantCard(
    model: ModelInfo,
    used: Boolean,
    downloaded: Boolean,
    download: DownloadState?,
    bench: BenchState,
    busy: Boolean,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDelete: () -> Unit,
    onUse: () -> Unit,
) {
    AxoCard(highlighted = used) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(model.quant, style = MonoStyle.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize), modifier = Modifier.weight(1f))
            when {
                used -> Badge("Utilisé par Axo", active = true)
                downloaded -> Badge("Sur le téléphone")
            }
        }
        Text("${model.source} · ${gigabytes(model.approxSizeBytes)}", style = MaterialTheme.typography.bodySmall, color = Axo.Muted)
        bench.runs[model.id]?.takeIf { it.answers.isNotEmpty() }?.let { run ->
            Text(
                "Réponses : ${run.answers.size}/${BENCH_QUESTIONS.size} · ${tokensPerSecond(run.averageTokensPerSecond)} · " +
                    "premier mot ${seconds(run.averageFirstWordMs.toLong())}",
                style = MonoStyle,
                color = Axo.TextSecondary,
            )
        }
        when {
            download != null -> {
                AmberProgress(download.percent / 100f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (download.waitingForNetwork) "En attente du réseau (${download.percent} %)" else "Téléchargement : ${download.percent} %",
                        style = MaterialTheme.typography.bodySmall,
                        color = Axo.Muted,
                        modifier = Modifier.weight(1f),
                    )
                    AxoTextButton("Annuler", onClick = onCancelDownload, color = Axo.TextSecondary)
                }
            }
            !downloaded -> AxoOutlineButton("Télécharger", onClick = onDownload, icon = AxoIcons.Download)
            !used -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AxoOutlineButton("Utiliser", onClick = onUse, enabled = !busy, icon = AxoIcons.Check)
                AxoOutlineButton("Supprimer", onClick = onDelete, enabled = !busy, icon = AxoIcons.Delete, color = Axo.TextSecondary)
            }
        }
    }
}

@Composable
private fun VoteCard(bench: BenchState, question: Int, onVote: (String) -> Unit) {
    val q = BENCH_QUESTIONS[question]
    val order = bench.order[question] ?: bench.voters
    AxoCard(highlighted = true) {
        Text("Vote à l'aveugle · question ${question + 1} sur ${BENCH_QUESTIONS.size}", style = MaterialTheme.typography.titleMedium)
        Text(q.text, style = MaterialTheme.typography.bodyLarge)
        Text("Attendu : ${q.expected}", style = MaterialTheme.typography.bodySmall, color = Axo.AmberSoft)
        order.forEachIndexed { i, id ->
            val letter = ('A' + i).toString()
            val answer = bench.runs[id]?.answers?.get(question)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Axo.Ink)
                    .border(BorderStroke(1.dp, Axo.Line), RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Réponse $letter", style = MonoStyle, color = Axo.Amber)
                Text(
                    answer?.text?.ifBlank { "(réponse vide)" } ?: "(pas de réponse)",
                    style = MaterialTheme.typography.bodyMedium,
                )
                AxoOutlineButton("$letter est la meilleure", onClick = { onVote(id) })
            }
        }
        AxoOutlineButton("Égalité", onClick = { onVote(BenchState.TIE) }, color = Axo.TextSecondary, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ResultsCard(bench: BenchState, usedId: String, onUse: (String) -> Unit, onRevote: () -> Unit) {
    val best = bench.recommendation()
    AxoCard {
        Text("Résultats", style = MaterialTheme.typography.titleMedium)
        bench.voters.sortedByDescending { bench.score(it) }.forEach { id ->
            val model = ModelCatalog.byId(id)
            val run = bench.runs[id]
            val wins = bench.votes.values.count { it == id }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(model?.quant ?: id, style = MonoStyle, color = if (id == best) Axo.Amber else Axo.Linen, modifier = Modifier.weight(1f))
                    Text(
                        "%s pts".format(Locale.FRANCE, formatScore(bench.score(id))),
                        style = MonoStyle,
                        color = if (id == best) Axo.Amber else Axo.Linen,
                    )
                }
                if (run != null) {
                    Text(
                        "$wins victoires · ${tokensPerSecond(run.averageTokensPerSecond)} · premier mot " +
                            "${seconds(run.averageFirstWordMs.toLong())} · mémoire ${gigabytes(run.peakRssBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Axo.Muted,
                    )
                }
            }
        }
        val ties = bench.votes.values.count { it == BenchState.TIE }
        if (ties > 0) Text("$ties égalités (½ point chacun)", style = MaterialTheme.typography.bodySmall, color = Axo.Muted)
        if (best != null) {
            val model = ModelCatalog.byId(best)
            Text(
                "À garder : ${model?.quant ?: best}",
                style = MaterialTheme.typography.titleSmall,
                color = Axo.Amber,
            )
            if (best == usedId) {
                Text("C'est déjà le fichier d'Axo.", style = MaterialTheme.typography.bodySmall, color = Axo.Muted)
            } else {
                AxoButton("Utiliser ce fichier", onClick = { onUse(best) }, icon = AxoIcons.Check, modifier = Modifier.fillMaxWidth())
                Text(
                    "Tu pourras ensuite supprimer les autres pour libérer de la place.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Axo.Muted,
                    textAlign = TextAlign.Start,
                )
            }
        }
        AxoTextButton("Revoter", onClick = onRevote, color = Axo.TextSecondary)
    }
}

private fun formatScore(score: Double): String =
    if (score % 1.0 == 0.0) score.toInt().toString() else "%.1f".format(Locale.FRANCE, score)
