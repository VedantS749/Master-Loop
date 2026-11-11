package app.loop

import app.loop.analytics.LiveHrv
import app.loop.data.DayLogRow
import app.loop.data.DaytimeStore
import app.loop.whoop.StrapAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.sin

class LiveMetricsTest {
    /** R-R series whose length breathes at [breathsPerMin]: the classic respiratory sinus arrhythmia pattern. */
    private fun breathing(breathsPerMin: Double, seconds: Int, amp: Double = 45.0, noise: Double = 3.0): List<Int> {
        val rnd = java.util.Random(3)
        val out = ArrayList<Int>(); var t = 0.0
        while (t < seconds) {
            val rr = 1000 + amp * sin(2 * PI * (breathsPerMin / 60.0) * t) + rnd.nextGaussian() * noise
            out += rr.toInt(); t += rr / 1000.0
        }
        return out
    }

    @Test fun breathingRateIsRecoveredFromHeartbeatTiming() {
        val sp = LiveHrv.spectral(breathing(15.0, 200))
        assertNotNull(sp)
        assertEquals(15.0, sp!!.breathRate, 1.2)
        assertTrue("clarity ${sp.breathClarity}", sp.breathClarity > 0.5)
        val est = StrapAnalyzer.respEstimate(breathing(12.0, 200))
        assertEquals(12.0, est!!.first, 1.2)
    }

    @Test fun noiseGivesLowClarity() {
        val rnd = java.util.Random(9)
        val rr = List(260) { (1000 + rnd.nextGaussian() * 40).toInt() }
        val sp = LiveHrv.spectral(rr)
        assertNotNull(sp)
        assertTrue("clarity ${sp!!.breathClarity}", sp.breathClarity < 0.35)
    }

    @Test fun tooLittleDataIsNotGuessed() {
        assertEquals(null, LiveHrv.spectral(breathing(15.0, 40)))
        assertEquals(null, LiveHrv.timeDomain(List(10) { 900 }))
    }

    @Test fun timeDomainMetrics() {
        val td = LiveHrv.timeDomain(List(60) { if (it % 2 == 0) 900 else 960 })!!
        assertEquals(60.0, td.rmssd, 0.5)
        assertEquals(930.0, td.meanRr, 0.5)
        assertEquals(100.0, td.pnn50, 0.5) // every successive change is 60 ms, over the 50 ms threshold
    }

    @Test fun stressRisesWithHeartRateAndFallsWithHrv() {
        val n = StrapAnalyzer.DaytimeNorm(hrMed = 70.0, hrMad = 5.0, lnMed = Math.log(40.0), lnMad = 0.3, windows = 50)
        val calm = StrapAnalyzer.stressScore(62.0, 60.0, n)
        val usual = StrapAnalyzer.stressScore(70.0, 40.0, n)
        val tense = StrapAnalyzer.stressScore(82.0, 22.0, n)
        assertTrue("$calm $usual $tense", calm < usual && usual < tense)
        assertEquals(50.0, usual, 1.0)
    }

    @Test fun dailyRollupSeparatesDaysAndTakesTheLowestSteadyHeartRate() {
        val zone = ZoneId.of("UTC")
        val d1 = java.time.LocalDate.of(2026, 11, 9).atTime(10, 0).atZone(zone).toEpochSecond()
        val d2 = java.time.LocalDate.of(2026, 11, 10).atTime(10, 0).atZone(zone).toEpochSecond()
        fun row(ts: Long, hr: Double, steady: Boolean, rmssd: Double?) = DayLogRow(ts, hr, steady, rmssd, null, null, null, null, null, null, 1.0)
        val days = DaytimeStore.rollup(listOf(row(d1, 80.0, false, null), row(d1 + 60, 62.0, true, 40.0), row(d1 + 120, 70.0, true, 50.0), row(d2, 90.0, false, null)), zone)
        assertEquals(2, days.size)
        assertEquals(62.0, days[0].dtHrLow!!, 0.01)
        assertEquals(45.0, days[0].dtHrv!!, 0.01)
        assertEquals(3.0, days[0].dtMin!!, 0.0)
        assertEquals(null, days[1].dtHrLow) // no steady minute that day
    }
}
