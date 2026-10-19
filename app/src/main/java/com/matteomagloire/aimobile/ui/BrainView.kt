package com.matteomagloire.aimobile.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.matteomagloire.aimobile.brain.GraphLayout

enum class BrainTab { NOTES, GRAPH, ASLEEP }

/** Ce que montre le graphe : toutes les notes, les actives, une étiquette, ou une note et ses voisines. */
sealed interface GraphFilter {
    data object All : GraphFilter
    data object Active : GraphFilter
    data class Tag(val tag: String) : GraphFilter
    data class Around(val noteId: String) : GraphFilter
}

/** Une disposition du graphe, et les notes et liens qu'elle place. */
class GraphDrawing(val ids: List<String>, val edges: List<Pair<String, String>>, val positions: GraphLayout.Positions)

/** Le cadrage du graphe : le zoom, et la place à l'écran (en px) du centre du graphe. */
class GraphCamera {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Unspecified)

    /** Le doigt a déplacé le graphe : il n'est plus recadré tout seul. */
    var moved = false

    /** Le dernier geste du doigt ([com.matteomagloire.aimobile.brain.LiveBrain.now]) : la caméra ne suit pas Axo juste après. */
    var touched: Long? = null

    /** Une note à mettre au milieu de l'écran dès que le graphe est prêt (« Voir dans le graphe »). */
    var focus by mutableStateOf<String?>(null)

    /** Recadre tout le graphe au prochain dessin. */
    fun refit() {
        offset = Offset.Unspecified
        moved = false
    }
}

/**
 * Ce que l'écran du Cerveau garde quand on ouvre une note puis qu'on revient : l'onglet, la
 * recherche, et le graphe tel qu'on l'avait laissé (filtre, note touchée, cadrage, disposition).
 */
class BrainView {
    var tab by mutableStateOf(BrainTab.NOTES)
    var query by mutableStateOf("")
    var tag by mutableStateOf<String?>(null)
    var filter by mutableStateOf<GraphFilter>(GraphFilter.All)

    /** La note touchée dans le graphe : elle et ses voisines s'éclairent, sa fiche s'affiche en bas. */
    var selected by mutableStateOf<String?>(null)
    val camera = GraphCamera()

    /** La disposition de toutes les notes, gardée d'une visite à l'autre : le graphe ne bouge pas. */
    var drawing by mutableStateOf<GraphDrawing?>(null)

    /** Le graphe, centré sur [noteId] (« Voir dans le graphe » depuis une note). */
    fun showInGraph(noteId: String) {
        tab = BrainTab.GRAPH
        filter = GraphFilter.All
        selected = noteId
        camera.focus = noteId
    }

    /**
     * Le graphe entier, pour regarder Axo travailler (le mini-graphe touché dans la conversation) :
     * centré sur [focus], la note où il en est, sinon cadré en entier.
     */
    fun showLive(focus: String?) {
        tab = BrainTab.GRAPH
        filter = GraphFilter.All
        selected = null
        if (focus == null) camera.refit()
        camera.focus = focus
        camera.touched = null
    }

    /** Retour à l'état de départ (le Cerveau ouvert depuis la conversation). */
    fun reset() {
        tab = BrainTab.NOTES
        query = ""
        tag = null
        filter = GraphFilter.All
        selected = null
        camera.focus = null
        camera.refit()
    }
}
