package com.matteomagloire.aimobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.matteomagloire.aimobile.brain.GraphLayout
import com.matteomagloire.aimobile.brain.LiveBrain
import com.matteomagloire.aimobile.brain.Note
import com.matteomagloire.aimobile.brain.NoteFilter
import com.matteomagloire.aimobile.brain.NoteLinks
import com.matteomagloire.aimobile.ui.theme.Axo
import com.matteomagloire.aimobile.ui.theme.MonoStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private val LitEdge = Axo.Amber.copy(alpha = 0.55f)
private val Halo = Axo.Amber.copy(alpha = 0.12f)
private const val MIN_SCALE = 0.15f
private const val MAX_SCALE = 4f

/** Après un geste du doigt, la caméra laisse le graphe où il est le temps de ce délai (ms). */
private const val FOLLOW_PAUSE = 3_000L

/** Une note dessinée : sa place dans la disposition, sa taille (en dp à l'échelle 1) et son état. */
private class GraphNode(val note: Note, val index: Int, val radius: Float, val degree: Int, val dormant: Boolean)

/**
 * L'onglet Graphe : chaque note est un point, chaque lien un trait. On zoome et on se déplace avec
 * les doigts ; toucher un point l'éclaire avec ses voisines et montre sa fiche, toucher la fiche (ou
 * le point à nouveau) ouvre la note. Les notes en veille sont pâles. En direct, [live] montre ce
 * qu'Axo fait dans sa mémoire ; avec [follow], la caméra le suit.
 */
@Composable
fun BrainGraph(
    notes: List<Note>,
    links: NoteLinks.Links,
    dormant: Set<String>,
    view: BrainView,
    live: LiveBrain,
    follow: Boolean,
    onFollowChange: (Boolean) -> Unit,
    onOpenNote: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val camera = view.camera
    val byId = remember(notes) { notes.associateBy { it.id } }
    val allIds = remember(notes) { notes.map { it.id }.sorted() }
    val allEdges = links.edges
    val tags = remember(notes) { NoteFilter.tags(notes) }

    // Toutes les notes, disposées à part du fil de l'écran (500 notes : quelques dixièmes de seconde).
    // On repart de la disposition précédente : une note ajoutée ne bouscule pas le dessin.
    LaunchedEffect(allIds, allEdges) {
        val old = view.drawing
        if (old != null && old.ids == allIds && old.edges == allEdges) return@LaunchedEffect
        val center = Note.ME_ID.takeIf { it in byId }
        val positions = withContext(Dispatchers.Default) { GraphLayout.layout(allIds, allEdges, center, old?.positions) }
        view.drawing = GraphDrawing(allIds, allEdges, positions)
    }

    // Un filtre qui ne montre plus rien (étiquette ou note disparue) revient à toutes les notes.
    val filter = when (val f = view.filter) {
        is GraphFilter.Around -> if (f.noteId in byId) f else GraphFilter.All
        is GraphFilter.Tag -> if (f.tag in tags) f else GraphFilter.All
        else -> f
    }
    // Le graphe local : une note et ses voisines, disposées autour d'elle.
    val local by produceState<GraphDrawing?>(null, filter, allEdges) {
        val around = filter as? GraphFilter.Around
        value = if (around == null) {
            null
        } else {
            val ids = (links.neighbors(around.noteId) + around.noteId).filter { it in byId }.sorted()
            val kept = ids.toSet()
            val edges = allEdges.filter { it.first in kept && it.second in kept }
            withContext(Dispatchers.Default) { GraphDrawing(ids, edges, GraphLayout.layout(ids, edges, around.noteId)) }
        }
    }
    val drawing = if (filter is GraphFilter.Around) local else view.drawing

    val visible: Set<String> = remember(drawing, filter, dormant, byId) {
        val d = drawing ?: return@remember emptySet()
        when (filter) {
            GraphFilter.Active -> d.ids.filter { it !in dormant }
            is GraphFilter.Tag -> d.ids.filter { id -> byId[id]?.let { it.isMe || filter.tag in it.tags } == true }
            else -> d.ids
        }.filter { it in byId }.toSet()
    }
    val nodes: List<GraphNode> = remember(drawing, visible, byId, dormant, links) {
        val d = drawing ?: return@remember emptyList()
        d.ids.mapNotNull { id ->
            val note = byId[id]
            val index = d.positions.indexOf(id)
            if (note == null || index < 0 || id !in visible) return@mapNotNull null
            val degree = links.neighbors(id).size
            val asleep = id in dormant
            val radius = when {
                note.isMe -> 16f
                asleep -> min(6f, 5f + sqrt(degree.toFloat()) / 2)
                else -> min(11f, 5f + 1.5f * sqrt(degree.toFloat()))
            }
            GraphNode(note, index, radius, degree, asleep)
        }
    }
    // Les traits, par paires d'indices dans la disposition (et leurs notes, pour le direct) : rien à
    // chercher pendant le dessin.
    val edges: Pair<IntArray, List<Pair<String, String>>> = remember(drawing, visible) {
        val d = drawing ?: return@remember IntArray(0) to emptyList()
        val out = ArrayList<Int>()
        val keys = ArrayList<Pair<String, String>>()
        for ((a, b) in d.edges) {
            val i = d.positions.indexOf(a)
            val j = d.positions.indexOf(b)
            if (a in visible && b in visible && i >= 0 && j >= 0) {
                out += i
                out += j
                keys += LiveBrain.edge(a, b)
            }
        }
        out.toIntArray() to keys
    }
    val selected = view.selected?.takeIf { it in visible }
    val selectedIndex = selected?.let { drawing?.positions?.indexOf(it) } ?: -1
    val lit: Set<String> = remember(selected, links) { selected?.let { links.neighbors(it) + it }.orEmpty() }

    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current.density
    // Lu ici plutôt que le cadrage lui-même : déplacer le graphe ne redessine que le dessin, pas l'écran.
    val unframed by remember(camera) { derivedStateOf { camera.offset.isUnspecified } }

    // Cadrage : tout le graphe à l'écran, ou la note à montrer au milieu.
    LaunchedEffect(drawing, visible, size, camera.focus, unframed) {
        val d = drawing ?: return@LaunchedEffect
        if (size == IntSize.Zero) return@LaunchedEffect
        if (camera.offset.isUnspecified || !camera.moved) fit(camera, d.positions, visible, size, density)
        val focus = camera.focus ?: return@LaunchedEffect
        val i = d.positions.indexOf(focus)
        if (i >= 0 && focus in visible) {
            camera.scale = max(camera.scale, 1.2f)
            val s = camera.scale * density
            camera.offset = Offset(size.width / 2f - d.positions.x[i] * s, size.height * 0.4f - d.positions.y[i] * s)
            camera.moved = true
            camera.focus = null
        }
    }

    // Le direct : l'horloge avance image par image tant que quelque chose bouge, et la caméra suit
    // doucement la note où Axo travaille.
    val revision by live.revision.collectAsState()
    var clock by remember { mutableLongStateOf(LiveBrain.now()) }
    val latestDrawing by rememberUpdatedState(drawing)
    val latestVisible by rememberUpdatedState(visible)
    val latestSize by rememberUpdatedState(size)
    val latestFollow by rememberUpdatedState(follow)
    LaunchedEffect(live, revision) {
        clock = LiveBrain.now()
        while (live.busy(clock)) {
            withFrameMillis { clock = it }
            if (latestFollow) followAxo(camera, live.focus(clock), latestDrawing, latestVisible, latestSize, density, clock)
        }
    }

    val measurer = rememberTextMeasurer()
    val labels = remember { HashMap<String, TextLayoutResult>() }
    val labelStyle = MaterialTheme.typography.bodySmall
    val meStyle = MaterialTheme.typography.labelLarge
    val latestNodes by rememberUpdatedState(nodes)
    val latestOpen by rememberUpdatedState(onOpenNote)

    Box(modifier.fillMaxSize().onSizeChanged { size = it }) {
        Canvas(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = "Graphe des notes : ${nodes.size} notes, ${edges.second.size} liens" }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        if (camera.offset.isUnspecified) return@detectTransformGestures
                        val scale = (camera.scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                        val f = scale / camera.scale
                        camera.offset = (camera.offset - centroid) * f + centroid + pan
                        camera.scale = scale
                        camera.moved = true
                        camera.touched = LiveBrain.now()
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { p ->
                        val d = latestDrawing ?: return@detectTapGestures
                        val hit = hitTest(p, latestNodes, d.positions, camera, density)
                        when {
                            hit == null -> view.selected = null
                            hit == view.selected -> latestOpen(hit)
                            else -> view.selected = hit
                        }
                    }
                },
        ) {
            val d = drawing ?: return@Canvas
            if (camera.offset.isUnspecified) return@Canvas
            val now = clock
            val f = live.frame(now)
            val s = camera.scale * density
            val ox = camera.offset.x
            val oy = camera.offset.y
            val px = d.positions.x
            val py = d.positions.y
            val ids = d.positions.ids
            fun at(i: Int) = Offset(ox + px[i] * s, oy + py[i] * s)
            fun shown(id: String) = f.appear[id] ?: 1f
            // Les points grossissent moins vite que le graphe : lisibles de loin, discrets de près.
            val dot = sqrt(camera.scale).coerceIn(0.55f, 1.6f) * density
            val thin = 1.2.dp.toPx()
            val thick = 1.6.dp.toPx()
            val (pairs, keys) = edges
            // Les liens qui se tracent en ce moment : dessinés à part, au fur et à mesure.
            val tracing = if (f.strokes.isEmpty()) emptySet() else f.strokes.mapTo(HashSet()) { LiveBrain.edge(it.from, it.to) }

            // Les liens de la note touchée, et ceux qu'Axo vient d'emprunter, passent devant, en ambre.
            for (pass in 0..1) {
                if (pass == 1 && selectedIndex < 0 && f.trail.isEmpty()) break
                for (k in pairs.indices step 2) {
                    val i = pairs[k]
                    val j = pairs[k + 1]
                    val key = keys[k / 2]
                    if (key in tracing) continue
                    val trail = f.trail[key] ?: 0f
                    val bright = i == selectedIndex || j == selectedIndex || trail > 0f
                    if (bright != (pass == 1)) continue
                    val alpha = min(shown(ids[i]), shown(ids[j]))
                    if (alpha <= 0f) continue
                    val color = when {
                        trail > 0f -> Axo.Amber.copy(alpha = (0.3f + 0.6f * trail) * alpha)
                        bright -> LitEdge
                        else -> EdgeColor.copy(alpha = alpha)
                    }
                    drawLine(color, at(i), at(j), if (bright) thick else thin)
                }
            }
            for (m in f.strokes) {
                if (m.progress <= 0f || m.from !in visible || m.to !in visible) continue
                val i = d.positions.indexOf(m.from)
                val j = d.positions.indexOf(m.to)
                if (i < 0 || j < 0) continue
                val a = at(i)
                drawLine(Axo.Amber, a, a + (at(j) - a) * m.progress, thick)
            }
            for (node in nodes) {
                val id = node.note.id
                val alpha = shown(id)
                if (alpha <= 0f) continue
                val c = at(node.index)
                val grow = f.grow[id] ?: 0f
                val r = node.radius * dot * (1f + 0.35f * grow)
                val light = f.light[id] ?: 0f
                if (light > 0f) drawLight(c, r, light * alpha)
                f.pulse[id]?.let { drawPulse(c, r, it, density) }
                if (node.note.isMe || id == selected) drawCircle(Halo, r * 2.1f, c, alpha = alpha)
                val base = when {
                    node.dormant -> Sleeping
                    node.note.isMe -> Axo.Amber
                    lit.isEmpty() -> if (node.degree >= 3) Axo.Amber else NodeLight
                    id in lit -> Axo.Amber
                    else -> NodeLight
                }
                drawCircle(liveColor(base, light, f.wake[id]), r, c, alpha = alpha)
                if (id == selected) drawCircle(Axo.AmberLight, r + 3.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
                if (grow > 0f) drawWriting(c, r, now, grow, density)
            }
            for (b in f.balls) {
                if (b.from !in visible || b.to !in visible) continue
                val i = d.positions.indexOf(b.from)
                val j = d.positions.indexOf(b.to)
                if (i < 0 || j < 0) continue
                val a = at(i)
                drawBall(a + (at(j) - a) * b.progress, density)
            }
            // Les noms : tous si le graphe est assez grand à l'écran, sinon ceux qui comptent (et ceux
            // qu'Axo touche en ce moment).
            val allNames = nodes.size <= 12 || camera.scale >= 0.75f
            val maxWidth = 140.dp.roundToPx()
            for (node in nodes) {
                val id = node.note.id
                val important = node.note.isMe || id in lit || node.degree >= 6 || id in f.light || id in f.pulse || id in f.grow
                if (!allNames && !important) continue
                val alpha = shown(id)
                if (alpha <= 0f) continue
                val style = if (node.note.isMe) meStyle else labelStyle
                val text = labels.getOrPut("$id\n${node.note.title}\n${node.note.isMe}") {
                    measurer.measure(
                        node.note.title,
                        style = style,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                        constraints = Constraints(maxWidth = maxWidth),
                    )
                }
                val c = at(node.index)
                val color = when {
                    id in f.light || id in f.pulse -> Axo.AmberLight
                    node.dormant -> Axo.Frost
                    node.note.isMe -> Axo.Linen
                    else -> Axo.TextSecondary
                }
                val grow = f.grow[id] ?: 0f
                val top = c.y + node.radius * dot * (1f + 0.35f * grow) + 3.dp.toPx()
                drawText(text, color = color, topLeft = Offset(c.x - text.size.width / 2f, top), alpha = alpha)
            }
        }

        Column(Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LegendRow(Axo.Amber, "Active")
            LegendRow(Axo.Frost.copy(alpha = 0.6f), "En veille")
            if (filter != GraphFilter.All) {
                Text(
                    filterLabel(filter, byId) + "  ✕",
                    style = MaterialTheme.typography.labelMedium,
                    color = Axo.Amber,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Axo.Ember)
                        .border(BorderStroke(1.dp, Axo.AmberBorder), RoundedCornerShape(10.dp))
                        .clickable {
                            view.filter = GraphFilter.All
                            camera.refit()
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }

        Column(Modifier.align(Alignment.TopEnd).padding(end = 16.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var menu by remember { mutableStateOf(false) }
            Box {
                GraphButton(AxoIcons.Filter, "Filtrer") { menu = true }
                DropdownMenu(
                    expanded = menu,
                    onDismissRequest = { menu = false },
                    shape = RoundedCornerShape(14.dp),
                    containerColor = Axo.Card,
                    border = BorderStroke(1.dp, Axo.Line),
                ) {
                    fun choose(f: GraphFilter) {
                        menu = false
                        view.filter = f
                        camera.focus = null
                        camera.refit()
                    }
                    FilterItem("Toutes les notes", filter == GraphFilter.All) { choose(GraphFilter.All) }
                    FilterItem("Actives seulement", filter == GraphFilter.Active) { choose(GraphFilter.Active) }
                    val around = selected ?: (filter as? GraphFilter.Around)?.noteId
                    val aroundNote = around?.let { byId[it] }
                    if (aroundNote != null) {
                        FilterItem("Autour de « ${aroundNote.title} »", filter == GraphFilter.Around(aroundNote.id)) {
                            choose(GraphFilter.Around(aroundNote.id))
                        }
                    }
                    tags.forEach { t -> FilterItem("#$t", filter == GraphFilter.Tag(t)) { choose(GraphFilter.Tag(t)) } }
                }
            }
            GraphButton(AxoIcons.Recenter, "Recentrer le graphe") {
                camera.focus = null
                camera.refit()
            }
            GraphButton(AxoIcons.Follow, if (follow) "Ne plus suivre Axo" else "Suivre Axo", active = follow) { onFollowChange(!follow) }
            val canReplay = remember(revision) { live.canReplay }
            if (canReplay) GraphButton(AxoIcons.Replay, "Rejouer ce qu'Axo vient de faire") { live.replay(LiveBrain.now()) }
        }

        val hint = when {
            notes.none { !it.isMe } -> "Le graphe se remplit au fil des notes d'Axo : chaque note est un point, chaque lien un trait."
            drawing == null -> "Axo dispose ses notes…"
            nodes.isEmpty() -> "Aucune note pour ce filtre."
            else -> null
        }
        val note = selected?.let { byId[it] }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            LiveCaption(
                live,
                clock = { clock },
                title = { byId[it]?.title },
                modifier = Modifier
                    .padding(bottom = 10.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Axo.Ember)
                    .border(BorderStroke(1.dp, Axo.EmberBorder), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            if (hint != null && note == null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Axo.Muted,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
                )
            }
            if (note != null) {
                GraphNoteCard(
                    note = note,
                    dormant = note.id in dormant,
                    links = links.neighbors(note.id).size,
                    onOpen = { onOpenNote(note.id) },
                )
            }
        }
    }
}

/** Recentre doucement la caméra sur [focus], sauf juste après un geste du doigt. */
private fun followAxo(
    camera: GraphCamera,
    focus: String?,
    drawing: GraphDrawing?,
    visible: Set<String>,
    size: IntSize,
    density: Float,
    now: Long,
) {
    if (focus == null || drawing == null || focus !in visible || size == IntSize.Zero || camera.offset.isUnspecified) return
    val touched = camera.touched
    if (touched != null && now - touched < FOLLOW_PAUSE) return
    val i = drawing.positions.indexOf(focus)
    if (i < 0) return
    val s = camera.scale * density
    val target = Offset(size.width / 2f - drawing.positions.x[i] * s, size.height * 0.4f - drawing.positions.y[i] * s)
    val delta = target - camera.offset
    if (delta.getDistance() < 0.5f) return
    camera.offset = camera.offset + delta * 0.08f
    camera.moved = true
}

/** Cadre [visible] dans l'écran, avec une marge pour les noms. */
private fun fit(camera: GraphCamera, positions: GraphLayout.Positions, visible: Set<String>, size: IntSize, density: Float) {
    var minX = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (id in visible) {
        val i = positions.indexOf(id)
        if (i < 0) continue
        minX = min(minX, positions.x[i])
        maxX = max(maxX, positions.x[i])
        minY = min(minY, positions.y[i])
        maxY = max(maxY, positions.y[i])
    }
    if (minX > maxX) {
        camera.scale = 1f
        camera.offset = Offset(size.width / 2f, size.height / 2f)
        return
    }
    val margin = 56f
    val width = size.width / density
    val height = size.height / density
    val scale = min(width / (maxX - minX + 2 * margin), height / (maxY - minY + 2 * margin)).coerceIn(MIN_SCALE, 1.6f)
    val s = scale * density
    camera.scale = scale
    camera.offset = Offset(size.width / 2f - (minX + maxX) / 2 * s, size.height / 2f - (minY + maxY) / 2 * s)
}

/** La note sous le doigt : la plus proche, à moins d'un doigt de distance. */
private fun hitTest(p: Offset, nodes: List<GraphNode>, positions: GraphLayout.Positions, camera: GraphCamera, density: Float): String? {
    if (camera.offset.isUnspecified) return null
    val s = camera.scale * density
    val dot = sqrt(camera.scale).coerceIn(0.55f, 1.6f) * density
    var best: String? = null
    var bestDistance = Float.MAX_VALUE
    for (node in nodes) {
        val x = camera.offset.x + positions.x[node.index] * s
        val y = camera.offset.y + positions.y[node.index] * s
        val d = hypot(x - p.x, y - p.y)
        if (d <= max(node.radius * dot, 24 * density) && d < bestDistance) {
            best = node.note.id
            bestDistance = d
        }
    }
    return best
}

private fun filterLabel(filter: GraphFilter, notes: Map<String, Note>): String = when (filter) {
    GraphFilter.All -> "Toutes les notes"
    GraphFilter.Active -> "Actives seulement"
    is GraphFilter.Tag -> "#${filter.tag}"
    is GraphFilter.Around -> "Autour de « ${notes[filter.noteId]?.title ?: ""} »"
}

fun linkCount(n: Int) = when (n) {
    0 -> "aucun lien"
    1 -> "1 lien"
    else -> "$n liens"
}

@Composable
private fun LegendRow(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.bodySmall, color = Axo.Muted)
    }
}

@Composable
private fun GraphButton(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) Axo.Ember else Axo.Card)
            .border(BorderStroke(1.dp, if (active) Axo.AmberBorder else Axo.Line), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (active) Axo.Amber else Axo.TextSecondary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun FilterItem(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, style = MaterialTheme.typography.bodyMedium, color = if (selected) Axo.Amber else Axo.Linen) },
        onClick = onClick,
        trailingIcon = if (selected) {
            { Icon(AxoIcons.Check, contentDescription = null, tint = Axo.Amber, modifier = Modifier.size(18.dp)) }
        } else {
            null
        },
    )
}

/** La fiche de la note touchée, en bas du graphe : la toucher ouvre la note. */
@Composable
private fun GraphNoteCard(note: Note, dormant: Boolean, links: Int, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 20.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Axo.Card)
            .border(BorderStroke(1.dp, Axo.Line), RoundedCornerShape(24.dp))
            .clickable(onClick = onOpen)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(EdgeColor))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(note.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            val status = when {
                dormant -> "en veille"
                note.opened != null -> "ouverte ${relativeDay(note.opened)}"
                else -> "créée ${relativeDay(note.created)}"
            }
            Text(status, style = MonoStyle.copy(fontSize = 11.sp), color = if (dormant) Axo.Frost else Axo.Muted)
            Icon(AxoIcons.Chevron, contentDescription = "Ouvrir la note", tint = Axo.Faint, modifier = Modifier.padding(start = 6.dp).size(18.dp))
        }
        val summary = note.facts.take(2).joinToString(" ") { NoteFilter.pretty(NoteLinks.plain(it.text)) }
        if (summary.isNotEmpty()) {
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = Axo.TextSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            note.tags.filter { it != Note.ME_TAG }.take(3).forEach { CardChip("#$it", Axo.Ember, Axo.Amber) }
            CardChip(linkCount(links), Axo.Slate, Axo.TextSecondary)
        }
    }
}

@Composable
private fun CardChip(text: String, background: Color, color: Color) {
    Text(
        text,
        style = MonoStyle.copy(fontSize = 12.sp),
        color = color,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(background).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
