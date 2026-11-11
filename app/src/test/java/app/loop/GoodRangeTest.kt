package app.loop

import app.loop.analytics.Grade
import app.loop.analytics.GoodRanges
import app.loop.analytics.Stats
import app.loop.model.DayRecord
import app.loop.model.MetricId
import app.loop.model.Metrics
import app.loop.model.Settings
import app.loop.ui.Dashboard
import app.loop.ui.Tone
import app.loop.ui.interpret
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoodRangeTest {
    private val settings = Settings()

    /** Twelve ordinary days, then a latest day with the context some ranges depend on. */
    private fun dash(): Pair<Dashboard, DayRecord> {
        fun d(n: Int) = DayRecord("2026-11-%02d".format(n), hrv = 50.0, rhr = 55.0, resp = 14.0, dtHr = 75.0, dtHrv = 40.0, dtSdnn = 45.0)
        val latest = d(13).copy(recovery = 50.0, sleepMin = 480.0)
        val days = (1..12).map { d(it) } + latest
        return Dashboard(days = days, settings = settings, latest = latest) to latest
    }

    private fun toneOf(g: Grade) = when (g) { Grade.GOOD -> Tone.GOOD; Grade.OK -> Tone.OK; Grade.WATCH -> Tone.WARN }

    /** Every zone of every graded range must give the same verdict the written interpretation gives. */
    @Test fun zonesAgreeWithTheInterpretation() {
        val (dash, latest) = dash()
        var checked = 0
        for (def in Metrics.all) {
            val range = GoodRanges.of(def, settings, dash.baseline(def), latest) ?: continue
            if (!range.graded) continue
            for (z in range.zones) {
                if (z.hi - z.lo < 1e-3) continue
                val v = (z.lo + z.hi) / 2
                val tone = interpret(def, v, dash, latest)?.tone
                assertEquals("${def.id} at $v (zone ${z.lo} to ${z.hi})", toneOf(z.grade), tone)
                assertEquals("${def.id} gradeOf($v)", z.grade, range.gradeOf(v))
                checked++
            }
        }
        assertTrue("only $checked zones checked", checked > 90)
    }

    @Test fun everyMetricHasARangeOrAGuide() {
        val (dash, latest) = dash()
        val missing = Metrics.all.filter { GoodRanges.of(it, settings, dash.baseline(it), latest) == null }.map { it.id }
        assertTrue("no range for $missing", missing.isEmpty())
    }

    @Test fun guideOnlyMetricsAreNotGraded() {
        val (dash, latest) = dash()
        for (id in listOf(MetricId.CALORIES, MetricId.WEIGHT, MetricId.DT_KCAL)) {
            val r = GoodRanges.of(Metrics[id], settings, dash.baseline(Metrics[id]), latest)!!
            assertFalse("$id", r.graded)
            assertNull(r.gradeOf(100.0))
            assertTrue(r.good.isNotBlank())
        }
    }

    @Test fun personalRangesFollowThePersonsOwnNormal() {
        val low = GoodRanges.of(Metrics[MetricId.HRV], settings, Stats.Baseline(40.0, 4.0, 20))!!
        val high = GoodRanges.of(Metrics[MetricId.HRV], settings, Stats.Baseline(80.0, 8.0, 20))!!
        assertEquals(Grade.GOOD, low.gradeOf(50.0))
        assertEquals(Grade.WATCH, high.gradeOf(50.0)) // 50 ms is good for one person and low for another
        assertTrue(low.good.contains("43")) // 40 x 1.08 rounded
        assertTrue(high.basis.contains("80"))
    }

    @Test fun personalRangesWaitForABaseline() {
        val r = GoodRanges.of(Metrics[MetricId.HRV], settings, null)!!
        assertFalse(r.graded)
        assertTrue(r.basis.contains("5 nights"))
    }

    @Test fun rangesAdaptToTheProfile() {
        val need = GoodRanges.of(Metrics[MetricId.SLEEP_DURATION], Settings(sleepNeedMin = 420), null)!!
        assertEquals(Grade.GOOD, need.gradeOf(420.0)); assertEquals(Grade.WATCH, need.gradeOf(300.0))
        val female = GoodRanges.of(Metrics[MetricId.VO2_MAX], Settings(female = true), null)!!
        val male = GoodRanges.of(Metrics[MetricId.VO2_MAX], Settings(female = false), null)!!
        assertEquals(Grade.GOOD, female.gradeOf(36.0)); assertEquals(Grade.OK, male.gradeOf(36.0))
        val tall = GoodRanges.of(Metrics[MetricId.WEIGHT], Settings(heightCm = 190.0), null)!!
        assertTrue(tall.good, tall.good.contains("67") && tall.good.contains("90")) // BMI 18.5 and 24.9 at 1.90 m: 66.8 and 89.9 kg
        val imperial = GoodRanges.of(Metrics[MetricId.SKIN_TEMP], Settings(imperial = true), null)!!
        assertTrue(imperial.good, imperial.good.contains("°F"))
    }

    @Test fun strainRangeDependsOnThatDaysRecovery() {
        val def = Metrics[MetricId.STRAIN]
        val rested = GoodRanges.of(def, settings, null, DayRecord("2026-11-13", recovery = 85.0))!!
        val drained = GoodRanges.of(def, settings, null, DayRecord("2026-11-13", recovery = 20.0))!!
        assertEquals(Grade.GOOD, rested.gradeOf(70.0)); assertEquals(Grade.WATCH, drained.gradeOf(70.0))
        assertFalse(GoodRanges.of(def, settings, null, DayRecord("2026-11-13"))!!.graded)
        assertNotNull(rested.basis)
    }

    @Test fun valuesBeyondTheScaleKeepTheEndGrade() {
        val r = GoodRanges.of(Metrics[MetricId.RECOVERY], settings, null)!!
        assertEquals(Grade.WATCH, r.gradeOf(-5.0)); assertEquals(Grade.GOOD, r.gradeOf(120.0))
        assertNull(r.gradeOf(Double.NaN))
    }
}
