package com.matteomagloire.aimobile.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveBrainTest {
    private fun answer(live: LiveBrain, at: Long = 0) {
        live.push(BrainEvent.Begin(BrainEvent.Work.ANSWER), at)
        live.push(BrainEvent.Searched(listOf("pixel", "moi", "lea"), listOf("pixel", "moi")), at)
        live.push(BrainEvent.Read("pixel"), at)
        live.push(BrainEvent.Read("moi"), at)
        live.push(BrainEvent.Followed("moi", "hugo"), at)
        live.push(BrainEvent.Read("hugo"), at)
        live.push(BrainEvent.Woke("hugo"), at)
    }

    @Test
    fun aBurstOfGesturesPlaysOneAfterTheOther() {
        val live = LiveBrain()
        answer(live)
        assertTrue(live.working)
        assertTrue(live.answering)
        // Les trouvailles s'allument l'une après l'autre (0, 110, 220 ms).
        assertTrue("pixel" in live.frame(50).light)
        assertFalse("lea" in live.frame(50).light)
        assertTrue("lea" in live.frame(300).light)
        assertEquals(Caption.Kind.SEARCHING, live.frame(300).caption?.kind)
        // Puis seules celles qu'il garde restent éclairées, et il lit (590 ms, 910 ms).
        val reading = live.frame(800)
        assertFalse("lea" in reading.light)
        assertTrue("pixel" in reading.pulse)
        assertEquals(LiveBrain.LIT_LEVEL, reading.light.getValue("pixel"), 0.001f)
        assertEquals(Caption(Caption.Kind.READING, "pixel"), reading.caption)
        // La boule suit le lien de 1230 à 1650 ms, la caméra va vers Hugo.
        val ball = live.frame(1440).balls.single()
        assertEquals("moi" to "hugo", ball.from to ball.to)
        assertTrue(ball.progress in 0.3f..0.7f)
        assertEquals("hugo", live.frame(1440).focus)
        assertEquals(1f, live.frame(1700).trail.getValue(LiveBrain.edge("moi", "hugo")))
        assertTrue("hugo" in live.frame(1700).pulse)
        // Hugo se réveille ensuite : encore givre avant, ambre après.
        assertEquals(0f, live.frame(1900).wake.getValue("hugo"))
        assertTrue(live.frame(2300).wake.getValue("hugo") in 0.1f..0.9f)
        assertFalse("hugo" in live.frame(2800).wake)
    }

    @Test
    fun readingLastsUntilTheAnswerThenEverythingFades() {
        val live = LiveBrain()
        answer(live)
        // Le premier mot arrive pendant le déroulé : il attend la fin des gestes (2370 ms).
        live.push(BrainEvent.Answering, 1000)
        assertTrue("pixel" in live.frame(2300).pulse)
        val answering = live.frame(2400)
        assertTrue(answering.pulse.isEmpty())
        assertEquals(Caption.Kind.ANSWERING, answering.caption?.kind)
        assertTrue("pixel" in answering.light)
        // Pendant la réponse, l'image ne bouge plus : rien à redessiner.
        assertFalse(live.busy(4000))
        live.push(BrainEvent.End(BrainEvent.Work.ANSWER), 5000)
        assertFalse(live.working)
        assertTrue(live.canReplay)
        assertEquals(LiveBrain.LIT_LEVEL / 2, live.frame(5750).light.getValue("pixel"), 0.01f)
        assertNull(live.frame(5750).caption)
        assertEquals("hugo", live.frame(5750).focus)
        assertFalse("pixel" in live.frame(6600).light)
        assertNull(live.frame(6600).focus)
        // Les liens empruntés restent éclairés quelques secondes de plus.
        assertEquals(0.5f, live.frame(8000).trail.getValue(LiveBrain.edge("moi", "hugo")), 0.01f)
        assertTrue(live.busy(10_900))
        assertFalse(live.busy(11_100))
        assertTrue(live.frame(11_100).isEmpty)
    }

    @Test
    fun replayIsSlowerAndLiveWorkComesFirst() {
        val live = LiveBrain()
        answer(live)
        live.push(BrainEvent.Answering, 3000)
        live.push(BrainEvent.End(BrainEvent.Work.ANSWER), 3000)
        live.replay(20_000)
        // « Axo pense à ton message… » un moment, puis tout deux fois plus lentement : la deuxième
        // trouvaille s'allume 220 ms après la première au lieu de 110.
        val start = 20_000 + (LiveBrain.LEAD / LiveBrain.REPLAY_SPEED).toLong()
        assertEquals(Caption.Kind.THINKING, live.frame(start - 10).caption?.kind)
        assertFalse("moi" in live.frame(start + 200).light)
        assertTrue("moi" in live.frame(start + 240).light)
        val ball = live.frame(start + 2 * 1230 + 420).balls.single()
        assertEquals(0.5f, ball.progress, 0.05f)
        assertFalse(live.working)
        // Axo se remet au travail : le « Rejouer » s'arrête.
        live.push(BrainEvent.Begin(BrainEvent.Work.ANSWER), 21_000)
        val now = live.frame(21_001)
        assertTrue(now.light.isEmpty() && now.balls.isEmpty())
        assertEquals(Caption.Kind.THINKING, now.caption?.kind)
        assertFalse(live.canReplay)
    }

    @Test
    fun writingANewNoteDrawsItAndItsLinks() {
        val live = LiveBrain()
        live.push(BrainEvent.Begin(BrainEvent.Work.NOTES), 0)
        assertFalse(live.answering)
        live.push(BrainEvent.Read("lea"), 0)
        live.push(BrainEvent.Wrote("lea", created = false), 0)
        live.push(BrainEvent.Wrote("hugo", created = true, links = listOf("moi")), 0)
        // Pendant la prise de notes, la lecture ne dure qu'un moment.
        assertTrue("lea" in live.frame(1000).pulse)
        assertFalse("lea" in live.frame(1500).pulse)
        assertTrue(live.frame(670).grow.getValue("lea") > 0.9f)
        // La note nouvelle n'est pas là avant d'être écrite (1020 ms), puis apparaît.
        assertEquals(0f, live.frame(500).appear.getValue("hugo"))
        assertEquals(0.5f, live.frame(1270).appear.getValue("hugo"), 0.01f)
        assertEquals(Caption(Caption.Kind.CREATING, "hugo"), live.frame(1270).caption)
        // Son lien vers « Moi » se trace pendant qu'elle finit de gonfler (1370 à 1930 ms).
        assertEquals(0f, live.frame(1300).strokes.single().progress)
        assertTrue(live.frame(1650).strokes.single().progress in 0.3f..0.7f)
        assertTrue(live.frame(2000).strokes.isEmpty())
        live.push(BrainEvent.End(BrainEvent.Work.NOTES), 2500)
        assertTrue(live.canReplay)
    }

    @Test
    fun aLateEndFromInterruptedNotesLeavesTheAnswerAlone() {
        val live = LiveBrain()
        live.push(BrainEvent.Begin(BrainEvent.Work.NOTES), 0)
        live.push(BrainEvent.Read("lea"), 0)
        // Un message arrive : la réponse commence avant que la prise de notes interrompue ait fini.
        answer(live, at = 100)
        live.push(BrainEvent.End(BrainEvent.Work.NOTES), 150)
        assertTrue(live.answering)
        assertTrue("pixel" in live.frame(1000).pulse)
        live.push(BrainEvent.Answering, 3000)
        assertEquals(Caption.Kind.ANSWERING, live.frame(3100).caption?.kind)
        live.push(BrainEvent.End(BrainEvent.Work.ANSWER), 4000)
        assertFalse(live.working)
    }

    @Test
    fun replayShowsTheWholeExchange() {
        val live = LiveBrain()
        answer(live)
        live.push(BrainEvent.Answering, 3000)
        live.push(BrainEvent.End(BrainEvent.Work.ANSWER), 6000)
        live.push(BrainEvent.Begin(BrainEvent.Work.NOTES), 6100)
        live.push(BrainEvent.Wrote("hugo", created = false), 6100)
        live.push(BrainEvent.End(BrainEvent.Work.NOTES), 7000)
        live.replay(20_000)
        // La recherche, la réponse, puis les notes, dans l'ordre.
        val kinds = (20_000L..40_000L step 20).mapNotNull { live.caption(it)?.kind }.distinct()
        assertEquals(
            listOf(
                Caption.Kind.THINKING, Caption.Kind.SEARCHING, Caption.Kind.READING, Caption.Kind.FOLLOWING,
                Caption.Kind.WAKING, Caption.Kind.ANSWERING, Caption.Kind.NOTING, Caption.Kind.WRITING,
            ),
            kinds,
        )
        assertFalse(live.busy(40_000))
        // Une nouvelle réponse sans aucun geste : rien à rejouer, l'échange d'avant est passé.
        live.push(BrainEvent.Begin(BrainEvent.Work.ANSWER), 50_000)
        live.push(BrainEvent.End(BrainEvent.Work.ANSWER), 50_010)
        assertFalse(live.canReplay)
    }

    @Test
    fun nothingWithoutWork() {
        val live = LiveBrain()
        assertTrue(live.frame(0).isEmpty)
        assertFalse(live.busy(0))
        // Un « oublie… » n'ouvre pas de travail : la réponse qui suit ne montre rien.
        live.push(BrainEvent.Answering, 10)
        live.push(BrainEvent.End(BrainEvent.Work.ANSWER), 20)
        assertTrue(live.frame(30).isEmpty)
        assertFalse(live.canReplay)
        // Un travail sans aucun geste ne se rejoue pas.
        live.push(BrainEvent.Begin(BrainEvent.Work.ANSWER), 40)
        live.push(BrainEvent.End(BrainEvent.Work.ANSWER), 50)
        assertFalse(live.canReplay)
    }
}
