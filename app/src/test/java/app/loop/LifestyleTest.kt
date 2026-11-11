package app.loop

import app.loop.analytics.AlarmTime
import app.loop.analytics.Confidence
import app.loop.analytics.CycleEngine
import app.loop.analytics.CyclePhase
import app.loop.analytics.Enricher
import app.loop.analytics.JournalImpact
import app.loop.analytics.StrengthMath
import app.loop.model.DayRecord
import app.loop.model.JournalEntry
import app.loop.model.Settings
import app.loop.whoop.HistRecord
import app.loop.whoop.StrapAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class LifestyleTest {
    private fun rec(ts: Long, steps: Int?) = HistRecord(ts, 70, intArrayOf(), 1, 0f, 0f, 1f, null, steps)

    @Test fun stepsCountPositiveDeltasAndHandleWrapAndGaps() {
        val zone = ZoneId.of("UTC")
        val t0 = LocalDate.now().minusDays(1).atTime(10, 0).atZone(zone).toEpochSecond()
        val recs = listOf(
            rec(t0, 100), rec(t0 + 1, 103), rec(t0 + 2, 105),       // +5
            rec(t0 + 3, 65534), rec(t0 + 4, 3),                      // implausible jump ignored, then wrap = +5
            rec(t0 + 1000, 10), rec(t0 + 1001, 12),                  // gap ignored, then +2
            rec(t0 + 1002, 2),                                       // reset ignored
        )
        val total = StrapAnalyzer.stepsByDay(recs, zone).values.sum()
        assertEquals(5 + 5 + 2, total)
    }

    @Test fun journalImpactFindsAFactorThatLowersRecovery() {
        val start = LocalDate.now().minusDays(40)
        val days = ArrayList<DayRecord>(); val entries = ArrayList<JournalEntry>()
        for (i in 0 until 30) {
            val d = start.plusDays(i.toLong())
            val alcohol = i % 3 == 0
            entries += JournalEntry(d.toString(), if (alcohol) listOf("alcohol") else emptyList())
            // recovery the NEXT morning is lower after alcohol
            days += DayRecord(d.plusDays(1).toString(), recovery = if (alcohol) 45.0 + (i % 5) else 70.0 + (i % 4))
        }
        val fx = JournalImpact.compute(days, entries).first { it.factorId == "alcohol" }
        assertTrue(fx.recoveryDelta!! < -15)
        assertEquals(Confidence.CLEAR, fx.confidence)
    }

    @Test fun cyclePhasesFollowTheLastPeriodStart() {
        val today = LocalDate.of(2026, 11, 10)
        val starts = listOf(today.minusDays(56), today.minusDays(28), today.minusDays(2))
        assertEquals(28, CycleEngine.cycleLength(starts))
        assertEquals(CyclePhase.MENSTRUAL, CycleEngine.phaseOn(today, starts)!!.second)
        assertEquals(CyclePhase.FOLLICULAR, CycleEngine.phaseOn(today.plusDays(6), starts)!!.second)
        assertEquals(CyclePhase.OVULATORY, CycleEngine.phaseOn(today.plusDays(12), starts)!!.second)
        assertEquals(CyclePhase.LUTEAL, CycleEngine.phaseOn(today.plusDays(20), starts)!!.second)
    }

    @Test fun estimatedOneRepMax() {
        assertEquals(100.0, StrengthMath.e1rm(100.0, 1), 0.001)
        assertEquals(116.667, StrengthMath.e1rm(100.0, 5), 0.01)
        assertEquals(0.0, StrengthMath.e1rm(40.0, 20), 0.0)
    }

    @Test fun vo2AndLoadRatioAreDerived() {
        val start = LocalDate.now().minusDays(40)
        val raw = (0 until 40).map { DayRecord(start.plusDays(it.toLong()).toString(), rhr = 50.0, strainRaw = if (it >= 33) 80.0 else 40.0, steps = 5000.0) }
        val out = Enricher.enrich(raw, emptyList(), emptyList(), Settings(age = 30))
        val last = out.last()
        assertEquals(15.3 * (208 - 0.7 * 30) / 50.0, last.vo2max!!, 0.01)
        assertNotNull(last.loadRatio)
        assertTrue("ratio ${last.loadRatio}", last.loadRatio!! > 1.3)
    }

    @Test fun alarmIsAlwaysInTheFuture() {
        val zone = ZoneId.of("UTC")
        val now = LocalDate.of(2026, 11, 10).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        val next = AlarmTime.nextOccurrenceMs(7, 0, now, zone)
        assertEquals(LocalDate.of(2026, 11, 11).atTime(7, 0).atZone(zone).toInstant().toEpochMilli(), next)
        assertTrue(AlarmTime.nextOccurrenceMs(9, 0, now, zone) > now)
    }
}
