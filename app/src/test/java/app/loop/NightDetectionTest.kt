package app.loop

import app.loop.model.Settings
import app.loop.whoop.HistRecord
import app.loop.whoop.StrapAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Random

/** Regression: a night that is very still, with a few turns in bed, was missed because the stillness cutoff fell below sleeping noise. */
class NightDetectionTest {
    private val zone = ZoneId.of("UTC")
    private val wake = LocalDate.of(2026, 11, 12)

    /** One clip per minute (six samples a second apart), from 18:00 the evening before to 14:00. [motion] gives the per-sample gyro step. */
    private fun history(motionAt: (Int, Random) -> Double, hrAt: (Int) -> Int): List<HistRecord> {
        val r = Random(11)
        val start = wake.minusDays(1).atTime(18, 0).atZone(zone).toEpochSecond()
        val out = ArrayList<HistRecord>()
        for (min in 0 until 20 * 60) {
            val m = motionAt(min, r).toFloat()
            for (k in 0 until 6) out += HistRecord(start + min * 60L + k, hrAt(min) + r.nextInt(3) - 1, IntArray(0), 1, if (k % 2 == 0) 0f else m, 0f, 0f, null, null)
        }
        return out
    }

    // minute 0 is 18:00. Awake until 23:30 (minute 330), asleep until 07:30 (minute 810), then awake again.
    private fun asleep(min: Int) = min in 330 until 810

    @Test fun aVeryStillNightWithAFewTurnsIsStillFound() {
        val recs = history(
            motionAt = { min, r -> if (!asleep(min)) 0.15 else if (r.nextInt(12) == 0) 0.03 else 0.0015 }, // sleeping: tiny motion, a turn about every 12 minutes
            hrAt = { min -> if (asleep(min)) 62 else 88 },
        )
        val out = StrapAnalyzer.analyze(recs, Settings(), 60.0, zone)
        val night = out.days.firstOrNull { it.day == wake.toString() }
        assertNotNull("night not detected", night?.sleepMin)
        assertTrue("slept ${night!!.sleepMin}", night.sleepMin!! in 6 * 60.0..8.6 * 60.0)
        assertEquals(1, out.nights)
    }

    @Test fun anOrdinaryRestlessEveningIsNotMistakenForSleep() {
        // no real sleep: awake and moving all day, heart rate high throughout
        val recs = history(motionAt = { _, _ -> 0.12 }, hrAt = { 90 })
        val out = StrapAnalyzer.analyze(recs, Settings(), 60.0, zone)
        assertEquals(0, out.nights)
    }

    @Test fun aNormalNightStillWorks() {
        val recs = history(
            motionAt = { min, r -> if (asleep(min)) 0.004 + r.nextDouble() * 0.004 else 0.12 },
            hrAt = { min -> if (asleep(min)) 60 else 85 },
        )
        val out = StrapAnalyzer.analyze(recs, Settings(), 60.0, zone)
        assertEquals(1, out.nights)
    }

    // ---- several days: minute 0 is 18:00 on 2026-11-10 ----
    private fun days(totalHours: Int, sleepSpans: List<IntRange>): List<HistRecord> {
        val r = Random(5)
        val start = wake.minusDays(2).atTime(18, 0).atZone(zone).toEpochSecond()
        val out = ArrayList<HistRecord>()
        for (min in 0 until totalHours * 60) {
            val sl = sleepSpans.any { min in it }
            val m = (if (sl) 0.004 + r.nextDouble() * 0.004 else 0.12).toFloat()
            val hr = if (sl) 60 else 88
            for (k in 0 until 6) out += HistRecord(start + min * 60L + k, hr + r.nextInt(3) - 1, IntArray(0), 1, if (k % 2 == 0) 0f else m, 0f, 0f, null, null)
        }
        return out
    }

    @Test fun anAllNighterIsFlaggedAsNoSleepNotAsMissingData() {
        // night 1 normal (23:30-07:30), then awake right through the next night until the end at 14:00 on the 12th
        val recs = days(44, listOf(330 until 810))
        val out = StrapAnalyzer.analyze(recs, Settings(), 60.0, zone)
        val first = out.days.first { it.day == wake.minusDays(1).toString() }
        val second = out.days.first { it.day == wake.toString() }
        assertNotNull(first.sleepMin)
        assertTrue("all-nighter not flagged: $second", second.noSleep == true && second.sleepMin == null)
    }

    @Test fun daytimeRecoverySleepAfterAnAllNighterCountsForThatDay() {
        // awake all night, then 09:00-14:00 asleep the next morning (minutes 2220..2520 from 18:00)
        val recs = days(46, listOf(330 until 810, 1980 until 2280))
        val out = StrapAnalyzer.analyze(recs, Settings(), 60.0, zone)
        val d = out.days.first { it.day == wake.toString() }
        assertTrue("recovery sleep not counted: $d", (d.sleepMin ?: 0.0) >= 240)
        assertTrue(d.noSleep != true)
    }

    @Test fun aShortAfternoonNapIsRecordedAsANapAndLeavesTheNightAlone() {
        // night 23:30-07:30, then a 45 minute nap at 14:00 the next day (minutes 1200..1245)
        val recs = days(30, listOf(330 until 810, 1200 until 1245))
        val out = StrapAnalyzer.analyze(recs, Settings(), 60.0, zone)
        val d = out.days.first { it.day == wake.minusDays(1).toString() }
        assertTrue("night changed: ${d.sleepMin}", d.sleepMin!! in 6 * 60.0..8.6 * 60.0)
        assertTrue("nap missing: ${d.napMin}", (d.napMin ?: 0.0) in 30.0..60.0)
    }

    @Test fun aManualSleepEntryOverridesWhatTheStrapSaw() {
        // the strap saw nothing (awake all day and night) but the person says they slept 23:30-07:30
        val recs = days(40, emptyList())
        val from = wake.minusDays(2).atTime(23, 30).atZone(zone).toEpochSecond()
        val to = wake.minusDays(1).atTime(7, 30).atZone(zone).toEpochSecond()
        val out = StrapAnalyzer.analyze(recs, Settings(), 60.0, zone, manualSleep = listOf(StrapAnalyzer.ManualSleepSpan(from, to)))
        val d = out.days.first { it.day == wake.minusDays(1).toString() }
        assertTrue("manual sleep ignored: $d", (d.sleepMin ?: 0.0) >= 6 * 60.0)
    }
}
