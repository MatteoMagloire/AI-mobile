package com.matteomagloire.aimobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.matteomagloire.aimobile.brain.LiveBrain
import com.matteomagloire.aimobile.brain.Note
import com.matteomagloire.aimobile.brain.NoteLinks
import com.matteomagloire.aimobile.ui.theme.Axo
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

private val DRAWING_HEIGHT = 88.dp

// La ligne où se posent les notes, depuis le haut du dessin : les liens passent au-dessus en arcs,
// les noms en dessous.
private val BASELINE = 54.dp

/**
 * Au-dessus de la réponse qu'Axo prépare puis écrit : les notes qu'il touche pour te répondre, de
 * gauche à droite dans l'ordre où il les touche, et leurs liens en arcs. Chaque geste s'y voit comme
 * dans le graphe du Cerveau. Rien s'il ne touche aucune note ; la bande se replie quand [visible]
 * passe à faux. La toucher ouvre le graphe ([onOpen]).
 */
@Composable
fun LiveMiniGraph(
    live: LiveBrain,
    visible: Boolean,
    notes: List<Note>,
    links: NoteLinks.Links,
    dormantDays: Int,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val revision by live.revision.collectAsState()
    val byId = remember(notes) { notes.associateBy { it.id } }
    val ids = remember(revision, byId) { if (live.answering) live.involved.filter { it in byId } else emptyList() }
    // Les dernières notes restent dessinées le temps que la bande se replie.
    val held = remember { HeldIds() }
    if (ids.isNotEmpty()) held.ids = ids
    AnimatedVisibility(
        visible = visible && ids.isNotEmpty(),
        modifier = modifier,
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
    ) {
        MiniGraph(live, revision, held.ids, byId, links, dormantDays, onOpen)
    }
}

private class HeldIds {
    var ids: List<String> = emptyList()
}

@Composable
private fun MiniGraph(
    live: LiveBrain,
    revision: Int,
    ids: List<String>,
    byId: Map<String, Note>,
    links: NoteLinks.Links,
    dormantDays: Int,
    onOpen: () -> Unit,
) {
    // Les liens entre ces notes, par places dans la rangée (la plus à gauche d'abord).
    val arcs = remember(ids, links) {
        val at = ids.withIndex().associate { (i, id) -> id to i }
        links.edges.mapNotNull { (a, b) ->
            val i = at[a] ?: return@mapNotNull null
            val j = at[b] ?: return@mapNotNull null
            Triple(min(i, j), max(i, j), LiveBrain.edge(a, b))
        }
    }
    val dormant = remember(ids, byId, dormantDays) {
        val today = LocalDate.now()
        ids.filter { byId[it]?.isDormant(today, dormantDays) == true }.toSet()
    }
    var clock by remember { mutableLongStateOf(LiveBrain.now()) }
    LaunchedEffect(live, revision) {
        clock = LiveBrain.now()
        while (live.busy(clock)) withFrameMillis { clock = it }
    }
    val measurer = rememberTextMeasurer()
    val labels = remember { HashMap<String, TextLayoutResult>() }
    val labelStyle = MaterialTheme.typography.labelSmall

    Column(
        Modifier
            .widthIn(max = 320.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Axo.Night)
            .border(BorderStroke(1.dp, Axo.NightBorder), RoundedCornerShape(18.dp))
            .clickable(onClickLabel = "Ouvrir le graphe du Cerveau", onClick = onOpen),
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(DRAWING_HEIGHT)
                .semantics { contentDescription = "Axo dans sa mémoire : ${noteCount(ids.size)}" },
        ) {
            if (ids.isEmpty()) return@Canvas
            val f = live.frame(clock)
            val pad = 20.dp.toPx()
            val base = BASELINE.toPx()
            // Le haut des arcs, avec la place pour la boule qui y passe.
            val top = 16.dp.toPx()
            val step = (size.width - 2 * pad) / ids.size
            fun x(i: Int) = pad + (i + 0.5f) * step
            fun point(i: Int) = Offset(x(i), base)
            // Un lien : un arc au-dessus de la ligne, d'autant plus haut que ses notes sont loin.
            fun control(i: Int, j: Int): Offset {
                val span = x(j) - x(i)
                return Offset((x(i) + x(j)) / 2, base - min(span * 0.9f, 2 * (base - top)))
            }
            fun along(from: Int, to: Int, t: Float): Offset {
                val a = point(from)
                val b = point(to)
                val c = control(min(from, to), max(from, to))
                val u = 1 - t
                return Offset(u * u * a.x + 2 * u * t * c.x + t * t * b.x, u * u * a.y + 2 * u * t * c.y + t * t * b.y)
            }
            val thin = 1.2.dp.toPx()
            val thick = 1.6.dp.toPx()

            // Les liens, puis ceux qu'Axo vient d'emprunter, devant, en ambre.
            for (pass in 0..1) {
                if (pass == 1 && f.trail.isEmpty()) break
                for ((i, j, key) in arcs) {
                    val trail = f.trail[key] ?: 0f
                    if ((trail > 0f) != (pass == 1)) continue
                    val c = control(i, j)
                    val path = Path().apply {
                        moveTo(x(i), base)
                        quadraticTo(c.x, c.y, x(j), base)
                    }
                    val color = if (trail > 0f) Axo.Amber.copy(alpha = 0.3f + 0.6f * trail) else EdgeColor
                    drawPath(path, color, style = Stroke(if (trail > 0f) thick else thin))
                }
            }
            for ((i, id) in ids.withIndex()) {
                val note = byId[id] ?: continue
                val c = point(i)
                val r = when {
                    note.isMe -> 6.5f
                    id in dormant -> 3.5f
                    else -> 4.5f
                }.dp.toPx()
                val light = f.light[id] ?: 0f
                if (light > 0f) drawLight(c, r, light)
                f.pulse[id]?.let { drawPulse(c, r, it, density) }
                val color = when {
                    id in dormant -> Sleeping
                    note.isMe -> Axo.Amber
                    else -> NodeLight
                }
                drawCircle(liveColor(color, light, f.wake[id]), r, c)
            }
            for (b in f.balls) {
                val i = ids.indexOf(b.from)
                val j = ids.indexOf(b.to)
                if (i < 0 || j < 0) continue
                drawBall(along(i, j, b.progress), density)
            }

            // Les noms de ce qu'il lit et garde, les plus importants d'abord, sans qu'ils se chevauchent.
            val named = ids.indices
                .filter { i -> ids[i] == f.focus || ids[i] in f.pulse || (f.light[ids[i]] ?: 0f) >= 0.3f }
                .sortedWith(
                    compareByDescending<Int> { ids[it] == f.focus }
                        .thenByDescending { ids[it] in f.pulse }
                        .thenByDescending { f.light[ids[it]] ?: 0f },
                )
            val taken = ArrayList<Rect>()
            val labelTop = base + 10.dp.toPx()
            val margin = 4.dp.toPx()
            val maxWidth = 110.dp.roundToPx()
            for (i in named) {
                val note = byId[ids[i]] ?: continue
                val text = labels.getOrPut("${note.id}\n${note.title}") {
                    measurer.measure(
                        note.title,
                        style = labelStyle,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                        constraints = Constraints(maxWidth = maxWidth),
                    )
                }
                val w = text.size.width.toFloat()
                val left = max(margin, min(x(i) - w / 2, size.width - margin - w))
                val rect = Rect(left - margin, labelTop, left + w + margin, labelTop + text.size.height)
                if (taken.any { it.overlaps(rect) }) continue
                taken += rect
                val strength = if (note.id == f.focus || note.id in f.pulse) 1f else f.light[note.id] ?: 0f
                drawText(text, color = Axo.AmberLight, topLeft = Offset(left, labelTop), alpha = 0.4f + 0.6f * strength.coerceIn(0f, 1f))
            }
        }
        LiveCaption(
            live,
            clock = { clock },
            title = { byId[it]?.title },
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
        )
    }
}
