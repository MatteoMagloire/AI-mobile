package com.matteomagloire.aimobile.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class GraphLayoutTest {
    private val ids = listOf("moi", "pixel", "lea", "velo", "jazz", "miles")
    private val edges = listOf("moi" to "pixel", "lea" to "moi", "moi" to "jazz", "jazz" to "miles")

    private fun GraphLayout.Positions.distance(p: String, q: String): Double {
        val i = indexOf(p)
        val j = indexOf(q)
        return hypot((x[i] - x[j]).toDouble(), (y[i] - y[j]).toDouble())
    }

    @Test
    fun theSameNotesAlwaysGiveTheSameDrawing() {
        val one = GraphLayout.layout(ids, edges, "moi")
        val two = GraphLayout.layout(ids.reversed(), edges.reversed(), "moi")
        for (id in ids) {
            assertEquals(one.x[one.indexOf(id)], two.x[two.indexOf(id)], 0f)
            assertEquals(one.y[one.indexOf(id)], two.y[two.indexOf(id)], 0f)
        }
    }

    @Test
    fun meStaysInTheMiddleAndLinkedNotesStayClose() {
        val p = GraphLayout.layout(ids, edges, "moi")
        val me = p.indexOf("moi")
        assertEquals(0f, p.x[me], 0f)
        assertEquals(0f, p.y[me], 0f)
        for (i in 0 until p.size) assertTrue(p.x[i].isFinite() && p.y[i].isFinite())
        // Les notes reliées sont à peu près à la distance visée ; aucune note n'en recouvre une autre.
        for ((a, b) in edges) assertTrue("$a-$b", p.distance(a, b) in GraphLayout.SPACING * 0.4..GraphLayout.SPACING * 2.5)
        for (a in ids) for (b in ids) if (a < b) assertTrue("$a-$b", p.distance(a, b) > 20)
        // La note sans lien reste à portée de vue.
        assertTrue(p.distance("moi", "velo") < GraphLayout.SPACING * 4)
    }

    @Test
    fun aNewNoteDoesNotShakeTheOthers() {
        val big = (1..60).map { "n$it" } + "moi"
        val links = (1..60).filter { it % 7 != 0 }.map { "moi" to "n$it" } + (1..59 step 3).map { "n$it" to "n${it + 1}" }
        val before = GraphLayout.layout(big, links, "moi")
        val after = GraphLayout.layout(big + "neuve", links + ("neuve" to "n5"), "moi", previous = before)
        val moved = big.map { id ->
            val i = before.indexOf(id)
            val j = after.indexOf(id)
            hypot((before.x[i] - after.x[j]).toDouble(), (before.y[i] - after.y[j]).toDouble())
        }.average()
        assertTrue("déplacement moyen $moved", moved < GraphLayout.SPACING / 2)
        // La nouvelle note se pose près de sa voisine.
        assertTrue(after.distance("neuve", "n5") < GraphLayout.SPACING * 2)
    }

    @Test
    fun fiveHundredNotesAreQuick() {
        val big = listOf("moi") + (1..499).map { "n$it" }
        val links = (1..499).filter { it % 6 != 0 }.map { "moi" to "n$it" } + (1..498).map { "n$it" to "n${(it * 7) % 499 + 1}" }
        val start = System.nanoTime()
        val p = GraphLayout.layout(big, links, "moi")
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(500, p.size)
        assertTrue("$ms ms", ms < 3_000)
        assertEquals(0, GraphLayout.layout(emptyList(), emptyList(), "moi").size)
    }
}
