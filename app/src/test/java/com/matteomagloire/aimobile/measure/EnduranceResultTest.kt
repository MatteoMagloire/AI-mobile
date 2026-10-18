package com.matteomagloire.aimobile.measure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnduranceResultTest {
    private fun result(vararg points: Pair<Int, Double>) =
        EnduranceResult(0, "m", points.map { (s, tps) -> EndurancePoint(s, tps, 30f) }, 0)

    @Test
    fun slowdownComparesFirstAndLastMinute() {
        val r = result(20 to 12.0, 40 to 12.0, 300 to 11.0, 560 to 10.0, 600 to 9.0)
        assertEquals(12.0, r.startSpeed, 1e-9)
        assertEquals(9.5, r.endSpeed, 1e-9)
        assertEquals(1 - 9.5 / 12.0, r.slowdown, 1e-9)
        assertTrue(r.complete)
    }

    @Test
    fun fasterAtTheEndIsNoSlowdown() {
        val r = result(20 to 10.0, 600 to 11.0)
        assertEquals(0.0, r.slowdown, 0.0)
    }

    @Test
    fun stoppedEarlyIsNotComplete() {
        val r = result(20 to 10.0, 120 to 10.0)
        assertFalse(r.complete)
        assertEquals(120, r.durationSeconds)
    }
}
