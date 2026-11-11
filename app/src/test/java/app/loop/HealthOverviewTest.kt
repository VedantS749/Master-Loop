package app.loop

import app.loop.analytics.Grade
import app.loop.analytics.HealthOverviews
import app.loop.analytics.Period
import app.loop.model.Category
import app.loop.model.DayRecord
import app.loop.model.MetricId
import app.loop.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HealthOverviewTest {
    private val settings = Settings()
    private val today = LocalDate.of(2026, 11, 17)

    private fun well(day: LocalDate) = DayRecord(
        day.toString(), recovery = 80.0, sleepScore = 86.0, sleepMin = 490.0, sleepEff = 92.0, steps = 9000.0, stress = 20.0, strain = 65.0,
    )

    private fun poorly(day: LocalDate) = DayRecord(
        day.toString(), recovery = 20.0, sleepScore = 40.0, sleepMin = 300.0, sleepEff = 70.0, steps = 2000.0, stress = 80.0, strain = 90.0,
    )

    /** [n] days ending on [today], each built by [make]. */
    private fun run(n: Int, make: (LocalDate) -> DayRecord) = (n - 1 downTo 0).map { make(today.minusDays(it.toLong())) }

    @Test fun noDataGivesNoScore() {
        val s = HealthOverviews.build(Period.WEEK, emptyList(), settings, today)
        assertNull(s.score); assertNull(s.grade); assertEquals("Not enough data yet", s.headline)
        assertTrue(s.categories.isEmpty())
    }

    @Test fun aGoodWeekScoresHighAndAPoorOneScoresLow() {
        val good = HealthOverviews.build(Period.WEEK, run(7, ::well), settings, today)
        assertEquals(100, good.score); assertEquals(Grade.GOOD, good.grade); assertEquals("Excellent", good.label)
        assertEquals("Your last 7 days look excellent", good.headline)
        assertEquals(7, good.daysWithData)
        val bad = HealthOverviews.build(Period.WEEK, run(7, ::poorly), settings, today)
        assertTrue("score ${bad.score}", bad.score!! <= 30); assertEquals(Grade.WATCH, bad.grade)
        assertEquals("Your last 7 days need attention", bad.headline)
    }

    @Test fun theSummaryComparesWithThePreviousPeriod() {
        val days = run(14) { if (it.isBefore(today.minusDays(6))) poorly(it) else well(it) }
        val s = HealthOverviews.build(Period.WEEK, days, settings, today)
        assertEquals(100, s.score)
        assertNotNull(s.prevScore); assertTrue(s.prevScore!! <= 30)
        assertEquals(s.score!! - s.prevScore!!, s.change)
        val rec = s.categories.first { it.category == Category.RECOVERY }.metrics.first { it.def.id == MetricId.RECOVERY }
        assertEquals(80.0, rec.avg, 0.01); assertEquals(20.0, rec.prevAvg!!, 0.01)
        assertEquals(true, rec.changeGood)
        val strain = s.categories.first { it.category == Category.ACTIVITY }.metrics.first { it.def.id == MetricId.STRAIN }
        assertEquals(65.0, strain.avg, 0.01); assertEquals(90.0, strain.prevAvg!!, 0.01)
    }

    @Test fun oneDayUsesOnlyTheLatestDay() {
        val days = run(10) { if (it == today) poorly(it) else well(it) }
        val day = HealthOverviews.build(Period.DAY, days, settings, today)
        assertEquals(today, day.to); assertEquals(today, day.from); assertEquals(1, day.daysWithData)
        assertTrue(day.score!! <= 30)
        val month = HealthOverviews.build(Period.MONTH, days, settings, today)
        assertTrue("score ${month.score}", month.score!! > 80) // nine good days outweigh one poor one
        assertEquals(10, month.daysWithData)
    }

    @Test fun windowsAreAnchoredToTheLatestDayWithData() {
        val stale = run(10) { well(it) }.map { it.copy(day = LocalDate.parse(it.day).minusDays(5).toString()) }
        val s = HealthOverviews.build(Period.WEEK, stale, settings, today)
        assertEquals(today.minusDays(5), s.to)
        assertEquals(7, s.daysWithData)
    }

    @Test fun strengthsAndFocusPointAtTheRightMetrics() {
        val mixed = run(7) { well(it).copy(steps = 1500.0, sleepMin = 280.0) }
        val s = HealthOverviews.build(Period.WEEK, mixed, settings, today)
        val focus = s.focus.map { it.def.id }
        assertTrue("focus $focus", MetricId.STEPS in focus && MetricId.SLEEP_DURATION in focus)
        val strengths = s.strengths.map { it.def.id }
        assertTrue("strengths $strengths", MetricId.RECOVERY in strengths)
        assertTrue(strengths.none { it in focus })
        assertTrue(s.strengths.size <= 3 && s.focus.size <= 3)
    }

    @Test fun goodDaysAreCounted() {
        val days = run(7) { if (it.dayOfMonth % 2 == 0) well(it) else poorly(it) }
        val rec = HealthOverviews.build(Period.WEEK, days, settings, today).categories.flatMap { it.metrics }.first { it.def.id == MetricId.RECOVERY }
        assertEquals(7, rec.gradedDays); assertTrue(rec.goodDays in 3..4)
        assertEquals(rec.goodDays, days.count { it.recovery == 80.0 })
    }

    @Test fun unchangedValuesAreNeitherGoodNorBadNews() {
        val s = HealthOverviews.build(Period.WEEK, run(14, ::well), settings, today)
        val rec = s.categories.flatMap { it.metrics }.first { it.def.id == MetricId.RECOVERY }
        assertNull(rec.changeGood)
    }

    @Test fun tooFewGradedMetricsGiveNoOverallScore() {
        val thin = run(3) { DayRecord(it.toString(), steps = 9000.0) }
        val s = HealthOverviews.build(Period.WEEK, thin, settings, today)
        assertNull(s.score)
        assertEquals(1, s.categories.size)
    }

    @Test fun nowListsTheLatestDaysReadingsWithTheirGrades() {
        val days = run(5, ::well).dropLast(1) + poorly(today)
        val now = HealthOverviews.now(days, settings, today)
        val byId = now.associateBy { it.def.id }
        assertEquals(Grade.WATCH, byId.getValue(MetricId.RECOVERY).grade)
        assertEquals(20.0, byId.getValue(MetricId.RECOVERY).value, 0.01)
        assertEquals(Grade.WATCH, byId.getValue(MetricId.SLEEP_SCORE).grade)
        assertNotNull(byId.getValue(MetricId.STEPS).range) // shown against its range...
        assertNull(byId.getValue(MetricId.STEPS).grade) // ...but today's total is still building, so it is not judged
        assertNull(byId.getValue(MetricId.STRAIN).grade)
        assertTrue(byId.getValue(MetricId.STEPS).partial && !byId.getValue(MetricId.RECOVERY).partial)
        assertNull(byId[MetricId.HRV]) // no value that day
        assertTrue(HealthOverviews.now(emptyList(), settings, today).isEmpty())
        // once the day is over the total is judged
        val tomorrow = HealthOverviews.now(days, settings, today.plusDays(1))
        assertEquals(Grade.WATCH, tomorrow.first { it.def.id == MetricId.STEPS }.grade)
    }

    @Test fun coverageNumbersAreHiddenAndNeverScored() {
        val days = run(7) { well(it).copy(wearMin = 200.0) }
        val o = HealthOverviews.build(Period.WEEK, days, settings, today)
        assertTrue(o.categories.flatMap { it.metrics }.none { it.def.id == MetricId.WEAR_TIME }) // advanced: not in everyday lists
        assertEquals(100, o.score) // and a strap that was off does not drag the health score down
    }

    @Test fun aStepCounterStuckAtZeroIsIgnored() {
        val days = run(7) { well(it).copy(steps = 0.0) }
        val s = HealthOverviews.build(Period.WEEK, days, settings, today)
        assertTrue(s.categories.flatMap { it.metrics }.none { it.def.id == MetricId.STEPS })
        assertEquals(100, s.score)
    }

    @Test fun todaysRunningTotalsAreShownButNotGraded() {
        val days = listOf(well(today).copy(steps = 3000.0))
        val ms = HealthOverviews.build(Period.DAY, days, settings, today).categories.flatMap { it.metrics }.first { it.def.id == MetricId.STEPS }
        assertTrue(ms.partial); assertNull(ms.grade); assertNull(ms.score)
        assertEquals(3000.0, ms.avg, 0.01)
        val yesterday = listOf(well(today.minusDays(1)).copy(steps = 3000.0))
        val done = HealthOverviews.build(Period.DAY, yesterday, settings, today).categories.flatMap { it.metrics }.first { it.def.id == MetricId.STEPS }
        assertEquals(Grade.WATCH, done.grade)
    }

    @Test fun liveDaytimeEstimatesAreNeitherListedNorScored() {
        val days = run(5) { DayRecord(it.toString(), dtHr = 70.0, dtStress = 20.0, dtBreath = 15.0, dtCoherence = 60.0, dtLfHf = 1.5, dtHrLow = 55.0) }
        val o = HealthOverviews.build(Period.WEEK, days, settings, today)
        assertNull(o.score)
        assertTrue(o.categories.isEmpty())
    }
}
