package app.loop

import app.loop.analytics.RhythmCheck
import app.loop.analytics.RhythmLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

class RhythmTest {
    // ---------------------------------------------------------------- rhythm regularity
    private fun sinus(n: Int, seed: Long = 1): List<Int> {
        val r = Random(seed)
        return List(n) { (1000 + 50 * sin(2 * PI * 0.25 * it) + r.nextGaussian() * 5).roundToInt() }
    }

    private fun afLike(n: Int, seed: Long = 2): List<Int> {
        val r = Random(seed)
        return List(n) { 500 + r.nextInt(600) }
    }

    @Test fun smoothSinusRhythmWithBreathingIsRegular() {
        val res = RhythmCheck.analyze(sinus(300))
        assertEquals("votes ${res.votes} nr=${res.nRmssd} tpr=${res.turningPoints} h=${res.entropy}", RhythmLevel.REGULAR, res.level)
    }

    @Test fun randomBeatTimingIsIrregular() {
        val res = RhythmCheck.analyze(afLike(300))
        assertEquals("votes ${res.votes} nr=${res.nRmssd} tpr=${res.turningPoints} h=${res.entropy}", RhythmLevel.IRREGULAR, res.level)
    }

    @Test fun tooFewBeatsIsNotJudged() {
        assertEquals(RhythmLevel.UNKNOWN, RhythmCheck.analyze(afLike(40)).level)
        assertNull(RhythmCheck.isIrregular(afLike(40)))
    }

    @Test fun artefactsOutsidePlausibleRangeAreDropped() {
        val noisy = sinus(300) + List(50) { 5000 } // sensor glitches
        assertEquals(RhythmLevel.REGULAR, RhythmCheck.analyze(noisy).level)
    }

    @Test fun overnightShareCountsIrregularStretches() {
        val stretches = List(8) { sinus(300, it.toLong()) } + List(2) { afLike(300, it.toLong()) }
        assertEquals(20.0, RhythmCheck.irregularShare(stretches)!!, 0.001)
        assertNull(RhythmCheck.irregularShare(List(3) { sinus(300) })) // too few to judge
    }

}
