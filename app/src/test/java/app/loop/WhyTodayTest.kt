package app.loop

import app.loop.analytics.Effect
import app.loop.analytics.WhyTodays
import app.loop.model.DayRecord
import app.loop.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhyTodayTest {
    private val s = Settings()
    private fun usual(n: Int) = (1..n).map { DayRecord("2026-11-%02d".format(it), hrv = 60.0, rhr = 55.0, resp = 14.0, sleepMin = 480.0) }

    @Test fun noDataGivesNothing() = assertNull(WhyTodays.build(emptyList(), s))

    @Test fun biggestDragComesFirstAndSleepIsOneLine() {
        val today = DayRecord("2026-11-20", hrv = 45.0, rhr = 59.0, resp = 14.0, sleepMin = 390.0, sleepDebtMin = 380.0, recovery = 25.0)
        val why = WhyTodays.build(usual(12) + today, s)!!
        assertEquals("Heart-rate variability", why.factors.first().title) // 25% below usual outweighs the rest
        assertEquals(1, why.factors.count { it.title == "Sleep" }) // short night and sleep debt are one line, not two
        val sleep = why.factors.first { it.title == "Sleep" }
        assertTrue(sleep.detail, sleep.detail.contains("short") && sleep.detail.contains("behind"))
        assertTrue(why.factors.all { it.effect == Effect.DRAGGING })
        assertTrue(why.bottomLine!!.startsWith("Mostly"))
    }

    @Test fun factorsShowTheNumberAgainstTheUsual() {
        val today = DayRecord("2026-11-20", hrv = 72.0, rhr = 55.0, sleepMin = 500.0, recovery = 85.0)
        val hrv = WhyTodays.build(usual(12) + today, s)!!.factors.first { it.title == "Heart-rate variability" }
        assertEquals("72 ms", hrv.value); assertEquals(Effect.LIFTING, hrv.effect)
        assertTrue(hrv.detail, hrv.detail.contains("above your usual 60 ms"))
    }

    @Test fun aSteadyDayIsSaidPlainly() {
        val today = DayRecord("2026-11-20", hrv = 60.0, rhr = 55.0, resp = 14.0, sleepMin = 470.0, recovery = 70.0)
        val why = WhyTodays.build(usual(12) + today, s)!!
        assertNotNull(why.bottomLine)
        assertTrue(why.factors.size >= 1)
    }
}
