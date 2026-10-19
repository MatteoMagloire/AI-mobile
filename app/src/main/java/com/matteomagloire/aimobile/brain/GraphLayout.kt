package com.matteomagloire.aimobile.brain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * La disposition du graphe des notes, sans Android. Chaque note repousse ses voisines, chaque lien
 * tire ses deux notes l'une vers l'autre comme un ressort (méthode de Fruchterman et Reingold), et
 * une légère gravité garde les notes isolées près des autres. [center] (« Moi ») reste au milieu.
 *
 * Les mêmes notes donnent toujours le même dessin. Avec [previous], les notes déjà placées repartent
 * de leur place et une nouvelle note se pose près de ses voisines : le graphe ne se redessine pas tout
 * entier à chaque note ajoutée.
 */
object GraphLayout {
    /** Distance visée entre deux notes reliées, en dp à l'échelle 1. */
    const val SPACING = 64f

    // Au-delà de cette distance, deux notes ne se repoussent plus : une note isolée ne part pas au loin.
    private const val REACH = SPACING * 2.5f
    private const val GRAVITY = 0.03f

    // Une note sans lien est plus attirée par le centre : elle reste près des autres.
    private const val ORPHAN_GRAVITY = 0.2f

    // Les liens d'une note très reliée (« Moi ») tirent moins fort : les autres liens restent lisibles.
    private const val HUB = 0.3

    /** Les places des notes, en dp autour de (0, 0). */
    class Positions(val ids: List<String>, val x: FloatArray, val y: FloatArray) {
        private val at: Map<String, Int> = ids.withIndex().associate { (i, id) -> id to i }

        fun indexOf(id: String): Int = at[id] ?: -1

        val size: Int get() = ids.size

        companion object {
            val EMPTY = Positions(emptyList(), FloatArray(0), FloatArray(0))
        }
    }

    fun layout(
        ids: List<String>,
        edges: List<Pair<String, String>>,
        center: String? = null,
        previous: Positions? = null,
    ): Positions {
        // Un ordre fixe : le dessin ne dépend pas de l'ordre des notes à l'écran.
        val order = ids.distinct().sorted()
        val n = order.size
        if (n == 0) return Positions.EMPTY
        val index = order.withIndex().associate { (i, id) -> id to i }
        val links = edges.mapNotNull { (p, q) ->
            val i = index[p] ?: return@mapNotNull null
            val j = index[q] ?: return@mapNotNull null
            if (i == j) null else if (i < j) i to j else j to i
        }.distinct().sortedWith(compareBy({ it.first }, { it.second }))
        val a = IntArray(links.size) { links[it].first }
        val b = IntArray(links.size) { links[it].second }
        val near = Array(n) { ArrayList<Int>() }
        for (e in links.indices) {
            near[a[e]] += b[e]
            near[b[e]] += a[e]
        }
        val pull = FloatArray(links.size) { e -> max(near[a[e]].size, near[b[e]].size).toDouble().pow(-HUB).toFloat() / SPACING }
        val pin = center?.let { index[it] } ?: -1

        // 1. Places de départ : l'ancienne place, sinon près d'une voisine déjà placée, sinon au hasard
        // (un hasard tiré du nom de la note : toujours le même).
        val x = FloatArray(n)
        val y = FloatArray(n)
        val placed = BooleanArray(n)
        var known = 0
        if (previous != null) {
            for (i in 0 until n) {
                val j = previous.indexOf(order[i])
                if (j >= 0) {
                    x[i] = previous.x[j]
                    y[i] = previous.y[j]
                    placed[i] = true
                    known++
                }
            }
        }
        if (pin >= 0) {
            x[pin] = 0f
            y[pin] = 0f
            placed[pin] = true
        }
        val queue = ArrayDeque((0 until n).filter { placed[it] })
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            for (j in near[i]) {
                if (placed[j]) continue
                val angle = random(order[j]).nextFloat() * 2 * PI.toFloat()
                x[j] = x[i] + cos(angle) * SPACING
                y[j] = y[i] + sin(angle) * SPACING
                placed[j] = true
                queue.addLast(j)
            }
        }
        val spread = SPACING * sqrt(n.toFloat())
        for (i in 0 until n) {
            if (placed[i]) continue
            val r = random(order[i])
            val angle = r.nextFloat() * 2 * PI.toFloat()
            val radius = spread * sqrt(r.nextFloat())
            x[i] = cos(angle) * radius
            y[i] = sin(angle) * radius
        }

        // 2. Les forces : beaucoup de pas si tout est nouveau, quelques retouches sinon.
        val fresh = known * 2 < n
        val steps = when {
            !fresh -> 80
            n <= 60 -> 400
            n <= 200 -> 250
            else -> 160
        }
        var heat = if (fresh) SPACING * 2 else SPACING / 2
        val cooling = heat / (steps + 1)
        val k2 = SPACING * SPACING
        val reach2 = REACH * REACH
        val dx = FloatArray(n)
        val dy = FloatArray(n)
        repeat(steps) {
            dx.fill(0f)
            dy.fill(0f)
            for (i in 0 until n) {
                val xi = x[i]
                val yi = y[i]
                for (j in i + 1 until n) {
                    var ex = xi - x[j]
                    var ey = yi - y[j]
                    var d2 = ex * ex + ey * ey
                    if (d2 > reach2) continue
                    if (d2 < 0.01f) {
                        // Deux notes au même endroit : on les écarte dans une direction fixe.
                        ex = 0.1f * (1 + (i % 7))
                        ey = 0.1f * (1 + (j % 5))
                        d2 = ex * ex + ey * ey
                    }
                    val f = k2 / d2
                    dx[i] += ex * f
                    dy[i] += ey * f
                    dx[j] -= ex * f
                    dy[j] -= ey * f
                }
            }
            for (e in a.indices) {
                val i = a[e]
                val j = b[e]
                val ex = x[i] - x[j]
                val ey = y[i] - y[j]
                val f = sqrt(ex * ex + ey * ey) * pull[e]
                dx[i] -= ex * f
                dy[i] -= ey * f
                dx[j] += ex * f
                dy[j] += ey * f
            }
            for (i in 0 until n) {
                if (i == pin) continue
                val g = if (near[i].isEmpty()) ORPHAN_GRAVITY else GRAVITY
                val fx = dx[i] - x[i] * g
                val fy = dy[i] - y[i] * g
                val len = sqrt(fx * fx + fy * fy)
                if (len > 0f) {
                    val s = min(len, heat) / len
                    x[i] += fx * s
                    y[i] += fy * s
                }
            }
            heat = max(heat - cooling, 0.5f)
        }
        return Positions(order, x, y)
    }

    private fun random(id: String) = Random(id.hashCode())
}
