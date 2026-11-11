package app.loop

import app.loop.analytics.Confidence
import app.loop.analytics.HealthLogs
import app.loop.analytics.MedicineImpacts
import app.loop.analytics.PainTrend
import app.loop.analytics.Readiness
import app.loop.analytics.RecoveryChecks
import app.loop.model.DayRecord
import app.loop.model.DoseTaken
import app.loop.model.HealthLog
import app.loop.model.Medicine
import app.loop.model.PainEntry
import app.loop.model.Problem
import app.loop.model.ProblemKind
import app.loop.model.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Random

class HealthLogTest {
    private val today = LocalDate.of(2026, 11, 30)
    private fun d(back: Int) = today.minusDays(back.toLong()).toString()

    /** Daily records for [n] days ending today; [make] gets how many days ago it was. */
    private fun days(n: Int, make: (Int, Random) -> DayRecord): List<DayRecord> {
        val r = Random(5)
        return (n - 1 downTo 0).map { make(it, r) }
    }

    private fun rec(back: Int, rhr: Double, hrv: Double, recovery: Double = 60.0, sleep: Double = 450.0, strain: Double? = null, load: Double? = null) =
        DayRecord(d(back), rhr = rhr, hrv = hrv, recovery = recovery, sleepMin = sleep, strain = strain, loadRatio = load)

    // ---------------------------------------------------------------- medicines

    @Test fun aDailyMedicineIsComparedBeforeAndAfter() {
        val med = Medicine(1, "Metoprolol", "50 mg", Schedule.DAILY, d(14))
        val data = days(40) { back, r -> if (back > 14) rec(back, 58 + r.nextGaussian(), 60 + r.nextGaussian()) else rec(back, 52 + r.nextGaussian(), 56 + r.nextGaussian()) }
        val impact = MedicineImpacts.compute(med, data, emptyList(), today)
        assertTrue(impact.enough)
        val rhr = impact.effects.first { it.label == "Resting heart rate" }
        assertTrue("delta ${rhr.delta}", rhr.delta < -4)
        assertEquals(Confidence.CLEAR, rhr.confidence)
        assertEquals(false, rhr.higherIsBetter) // lower is the good direction for resting heart rate
    }

    @Test fun tooLittleDataSaysSoInsteadOfGuessing() {
        val med = Medicine(1, "X", "", Schedule.DAILY, d(3))
        val impact = MedicineImpacts.compute(med, days(20) { b, _ -> rec(b, 55.0, 60.0) }, emptyList(), today)
        assertFalse(impact.enough)
        assertTrue(impact.effects.isEmpty())
    }

    @Test fun noChangeIsNotReportedAsAnEffect() {
        val med = Medicine(1, "Vitamin D", "", Schedule.DAILY, d(14))
        val data = days(40) { b, r -> rec(b, 55 + r.nextGaussian(), 60 + r.nextGaussian()) }
        val impact = MedicineImpacts.compute(med, data, emptyList(), today)
        assertTrue(impact.enough)
        assertTrue(impact.effects.none { it.confidence == Confidence.CLEAR })
    }

    @Test fun aWhenNeededMedicineLooksAtTheMorningAfter() {
        val med = Medicine(2, "Antihistamine", "", Schedule.AS_NEEDED, d(30))
        val doseDays = listOf(20, 17, 14, 11, 8, 5)
        val doses = doseDays.map { DoseTaken(2, d(it)) }
        val data = days(32) { b, r -> rec(b, 55 + r.nextGaussian(), 60 + r.nextGaussian(), sleep = if ((b + 1) in doseDays) 380.0 else 460.0 + r.nextGaussian()) }
        val sleep = MedicineImpacts.compute(med, data, doses, today).effects.first { it.label == "Sleep" }
        assertTrue("delta ${sleep.delta}", sleep.delta < -60)
    }

    @Test fun anUnloggedWhenNeededMedicineHasNothingToCompare() {
        val med = Medicine(3, "X", "", Schedule.AS_NEEDED, d(10))
        assertFalse(MedicineImpacts.compute(med, days(20) { b, _ -> rec(b, 55.0, 60.0) }, emptyList(), today).enough)
    }

    @Test fun generalNotesMatchByNameAndStayQuietOtherwise() {
        assertNotNull(MedicineImpacts.noteFor("Metoprolol XL"))
        assertTrue(MedicineImpacts.noteFor("sertraline")!!.contains("heart-rate variability"))
        assertNull(MedicineImpacts.noteFor("Some unknown thing"))
    }

    // ---------------------------------------------------------------- injuries and illness

    private val knee = Problem(1, ProblemKind.INJURY, "Knee", d(10), 6)

    @Test fun highPainMeansRestAndLowPainWithGoodRecoveryMeansEasingBack() {
        val data = days(30) { b, _ -> rec(b, 55.0, 60.0, recovery = 70.0) }
        val sore = RecoveryChecks.status(knee, listOf(PainEntry(1, d(0), 7)), data, today)
        assertEquals(Readiness.REST, sore.readiness)
        val better = RecoveryChecks.status(knee, listOf(PainEntry(1, d(2), 3), PainEntry(1, d(1), 2), PainEntry(1, d(0), 1)), data, today)
        assertEquals(Readiness.EASING, better.readiness)
        assertEquals(PainTrend.IMPROVING, better.trend)
    }

    @Test fun lowPainButPoorRecoveryStaysGentle() {
        val data = days(30) { b, _ -> rec(b, if (b < 3) 66.0 else 55.0, 60.0, recovery = if (b < 3) 25.0 else 70.0) }
        val s = RecoveryChecks.status(knee, listOf(PainEntry(1, d(0), 1)), data, today)
        assertEquals(Readiness.GENTLE, s.readiness)
    }

    @Test fun hardTrainingWhileInjuredIsFlagged() {
        val data = days(30) { b, _ -> rec(b, 55.0, 60.0, strain = if (b == 0) 75.0 else 30.0) }
        assertNotNull(RecoveryChecks.status(knee, emptyList(), data, today).warning)
        val calm = days(30) { b, _ -> rec(b, 55.0, 60.0, strain = 30.0, load = 1.0) }
        assertNull(RecoveryChecks.status(knee, emptyList(), calm, today).warning)
    }

    @Test fun anIllnessIsRecoveredWhenRestingHeartRateIsBackToNormal() {
        val flu = Problem(2, ProblemKind.ILLNESS, "Cold or flu", d(6), 6)
        val data = days(40) { b, _ -> rec(b, if (b in 3..6) 66.0 else 55.0, 60.0, recovery = 65.0) }
        val still = RecoveryChecks.status(flu, listOf(PainEntry(2, d(4), 5)), data.dropLast(3), today.minusDays(3))
        assertTrue(still.readiness != Readiness.RECOVERED)
        val over = RecoveryChecks.status(flu, listOf(PainEntry(2, d(0), 1)), data, today)
        assertEquals(Readiness.RECOVERED, over.readiness)
    }

    @Test fun seriesCoverTheLastTwoWeeks() {
        val data = days(30) { b, _ -> rec(b, 55.0, 60.0, recovery = 50.0 + b) }
        val s = RecoveryChecks.status(knee, listOf(PainEntry(1, d(1), 4), PainEntry(1, d(0), 3)), data, today)
        assertEquals(14, s.painSeries.size); assertEquals(3.0, s.painSeries.last()!!, 0.0); assertNull(s.painSeries.first())
        assertEquals(14, s.recoverySeries.size)
    }

    // ---------------------------------------------------------------- baselines and events

    @Test fun illnessDaysAreLeftOutOfBaselinesButMildInjuriesAreNot() {
        val log = HealthLog(
            problems = listOf(
                Problem(1, ProblemKind.ILLNESS, "Fever", d(5), 5, d(3)),
                Problem(2, ProblemKind.INJURY, "Ankle", d(20), 4),
                Problem(3, ProblemKind.INJURY, "Back", d(40), 8, d(35)),
            ),
        )
        val off = HealthLogs.offDays(log, today)
        assertEquals(setOf(d(5), d(4), d(3)) + (35..40).map { d(it) }, off)
        assertFalse(d(20) in off)
    }

    @Test fun anOpenEndedProblemIsCappedAtThreeWeeks() {
        val off = HealthLogs.offDays(HealthLog(problems = listOf(Problem(1, ProblemKind.ILLNESS, "Cold", d(60), 5))), today)
        assertEquals(22, off.size)
    }

    @Test fun eventsListStartsAndStopsInsideTheWindow() {
        val log = HealthLog(
            medicines = listOf(Medicine(1, "Metoprolol", "", Schedule.DAILY, d(10), d(2))),
            problems = listOf(Problem(2, ProblemKind.INJURY, "Knee", d(40), 5)),
        )
        val e = HealthLogs.events(log, today.minusDays(29), today)
        assertEquals(listOf("Stopped Metoprolol", "Started Metoprolol"), e.map { it.text }) // the knee began before the window
    }
}
