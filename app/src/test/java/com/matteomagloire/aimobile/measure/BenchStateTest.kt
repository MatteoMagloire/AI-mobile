package com.matteomagloire.aimobile.measure

import com.matteomagloire.aimobile.model.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BenchStateTest {
    private val q4km = ModelCatalog.INSTRUCT.id
    private val ud = ModelCatalog.INSTRUCT_UD.id
    private val q40 = ModelCatalog.INSTRUCT_Q40.id

    private fun answer(tps: Double = 10.0) = BenchAnswer("réponse", "", 800, tps, 100, 10_000)

    private fun completeRun(id: String, tps: Double = 10.0) =
        BenchRun(id, BENCH_QUESTIONS.indices.associateWith { answer(tps) })

    private fun benchWith(vararg ids: String) =
        BenchState(reasoning = false, runs = ids.associateWith { completeRun(it) }).withVoters(Random(1))

    private fun BenchState.votedAll(vararg votes: String) =
        copy(votes = votes.withIndex().associate { (i, v) -> i to v })

    @Test
    fun tenQuestionsWithExpectations() {
        assertEquals(10, BENCH_QUESTIONS.size)
        assertTrue(BENCH_QUESTIONS.all { it.text.isNotBlank() && it.expected.isNotBlank() })
    }

    @Test
    fun votingNeedsTwoCompleteFiles() {
        val one = BenchState(reasoning = false, runs = mapOf(q4km to completeRun(q4km))).withVoters()
        assertFalse(one.canVote)
        assertNull(one.nextVote)

        val partial = BenchState(
            reasoning = false,
            runs = mapOf(q4km to completeRun(q4km), ud to BenchRun(ud, mapOf(0 to answer()))),
        ).withVoters()
        assertFalse(partial.canVote)

        val two = benchWith(q4km, ud)
        assertTrue(two.canVote)
        assertEquals(0, two.nextVote)
    }

    @Test
    fun blindOrderIsAShuffleOfTheVoters() {
        val bench = benchWith(q4km, ud, q40)
        assertEquals(listOf(q4km, ud, q40), bench.voters)
        BENCH_QUESTIONS.indices.forEach { q -> assertEquals(bench.voters.toSet(), bench.order[q]!!.toSet()) }
    }

    @Test
    fun votesResetWhenAFileIsAdded() {
        val voted = benchWith(q4km, ud).votedAll(q4km)
        assertEquals(1, voted.votes.size)
        val withThird = voted.copy(runs = voted.runs + (q40 to completeRun(q40))).withVoters()
        assertEquals(listOf(q4km, ud, q40), withThird.voters)
        assertTrue(withThird.votes.isEmpty())
        // Sans nouveau fichier, rien ne change.
        assertEquals(voted, voted.withVoters())
    }

    @Test
    fun scoreCountsWinsAndHalfTies() {
        val bench = benchWith(q4km, ud).votedAll(q4km, q4km, ud, BenchState.TIE)
        assertEquals(2.5, bench.score(q4km), 0.0)
        assertEquals(1.5, bench.score(ud), 0.0)
    }

    @Test
    fun recommendationWaitsForAllVotes() {
        val bench = benchWith(q4km, ud).votedAll(q4km, ud)
        assertFalse(bench.votingDone)
        assertNull(bench.recommendation())
    }

    @Test
    fun bestScoreWins() {
        val votes = Array(10) { if (it < 6) q40 else ud }
        assertEquals(q40, benchWith(q4km, ud, q40).votedAll(*votes).recommendation())
    }

    @Test
    fun udIsKeptWhenAtLeastAsGood() {
        // Décision du cahier des charges : UD-Q4_K_XL s'il est au moins aussi bon que Q4_K_M.
        val votes = Array(10) { if (it < 5) q4km else ud }
        val bench = benchWith(q4km, ud).votedAll(*votes)
        assertTrue(bench.votingDone)
        assertEquals(ud, bench.recommendation())

        val allTies = benchWith(q4km, ud, q40).votedAll(*Array(10) { BenchState.TIE })
        assertEquals(ud, allTies.recommendation())
    }

    @Test
    fun runAverages() {
        val run = BenchRun(q4km, mapOf(0 to answer(8.0), 1 to answer(12.0)))
        assertEquals(10.0, run.averageTokensPerSecond, 1e-9)
        assertEquals(800.0, run.averageFirstWordMs, 1e-9)
        assertFalse(run.complete)
        assertTrue(completeRun(q4km).complete)
    }
}
