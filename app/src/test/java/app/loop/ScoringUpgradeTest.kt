package app.loop

import app.loop.analytics.Enricher
import app.loop.analytics.Scoring
import app.loop.analytics.SleepCoach
import app.loop.analytics.Stats
import app.loop.model.DayRecord
import app.loop.model.Settings
import app.loop.model.StrengthExercise
import app.loop.model.StrengthSession
import app.loop.model.StrengthSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Random
import kotlin.math.ln

class ScoringUpgradeTest {
    private val base = Stats.Baseline(mean = ln(60.0), sd = 0.15, n = 20)
    private val rhrBase = Stats.Baseline(55.0, 3.0, 20)

    private fun input(hrv: Double, sleepMin: Double? = 480.0, debt: Double? = 0.0, eff: Double? = 90.0, hrv7: Double? = null, n: Int = 20) =
        Scoring.RecoveryInput(hrv, 55.0, 14.0, eff, null, base.copy(n = n), rhrBase, Stats.Baseline(14.0, 1.0, 20), sleepMin, 480, debt, hrv7)

    // ---------------------------------------------------------------- recovery

    @Test fun hrvIsJudgedInLogsSoEqualRatiosScoreAlike() {
        val up = Scoring.recovery(input(60 * 1.25))!!.zHrv!!
        val down = Scoring.recovery(input(60 / 1.25))!!.zHrv!!
        assertEquals(up, -down, 1e-6) // 25% up and 25% down are the same distance in logs
    }

    @Test fun aShortNightLowersRecoveryEvenWhenItIsEfficient() {
        val full = Scoring.recovery(input(60.0, sleepMin = 480.0))!!.score
        val short = Scoring.recovery(input(60.0, sleepMin = 282.0))!!.score
        assertTrue("$full vs $short", full - short > 8)
    }

    @Test fun sleepDebtLowersRecoveryToo() {
        val clear = Scoring.recovery(input(60.0, debt = 0.0))!!.score
        val owing = Scoring.recovery(input(60.0, debt = 300.0))!!.score
        assertTrue(clear > owing)
    }

    @Test fun theRecentTrendNudgesTheScore() {
        val falling = Scoring.recovery(input(60.0, hrv7 = ln(75.0)))!!.score // last night is below the last week
        val steady = Scoring.recovery(input(60.0, hrv7 = ln(60.0)))!!.score
        assertTrue(falling < steady)
    }

    @Test fun confidenceDropsWithFewNightsAndShortSleep() {
        assertEquals(2, Scoring.recovery(input(60.0, n = 20))!!.confidence)
        val thin = Scoring.recovery(input(60.0, sleepMin = 250.0, n = 4))!!
        assertEquals(0, thin.confidence)
        assertTrue(thin.confidenceNote!!, thin.confidenceNote!!.contains("4 nights") && thin.confidenceNote!!.contains("short"))
    }

    @Test fun noHrvMeansNoScore() = assertNull(Scoring.recovery(Scoring.RecoveryInput(null, 55.0, 14.0, 90.0, null, base, rhrBase, null)))

    // ---------------------------------------------------------------- strain

    @Test fun loadIsASmoothCurveAndIgnoresOrdinaryLife() {
        val rest = 55.0; val max = 190.0
        assertEquals(0.0, Scoring.trimpMinute(rest + 10, rest, max, false), 0.0) // desk work counts nothing
        val a = Scoring.trimpMinute(rest + 0.69 * (max - rest), rest, max, false)
        val b = Scoring.trimpMinute(rest + 0.71 * (max - rest), rest, max, false)
        assertTrue("no jump between neighbouring heart rates: $a $b", b - a < 0.25 && b > a)
        assertTrue(Scoring.trimpMinute(max, rest, max, false) > Scoring.trimpMinute(rest + 0.6 * (max - rest), rest, max, false))
    }

    @Test fun aRealMaximumHeartRateWinsOnlyWhenHigher() {
        val tanaka = Scoring.maxHr(Settings(age = 40))
        assertEquals(tanaka, Scoring.maxHr(Settings(age = 40, observedMaxHr = 0)), 0.0)
        assertEquals(tanaka, Scoring.maxHr(Settings(age = 40, observedMaxHr = (tanaka - 5).toInt())), 0.0)
        assertEquals(205.0, Scoring.maxHr(Settings(age = 40, observedMaxHr = 205)), 0.0)
    }

    @Test fun strainScalesToThePersonsOwnHardDays() {
        val few = Scoring.personalCeiling(List(5) { 500.0 })
        assertEquals(Scoring.DEFAULT_TRIMP_CEILING, few, 0.0) // not enough days to personalise
        val lowRange = Scoring.personalCeiling(List(30) { 150.0 + it })
        val highRange = Scoring.personalCeiling(List(30) { 900.0 + it * 10 })
        assertTrue("$lowRange $highRange", lowRange < highRange)
        // the same workout reads as harder to someone who rarely trains hard
        assertTrue(Scoring.strainFromTrimp(400.0, lowRange) > Scoring.strainFromTrimp(400.0, highRange))
        assertTrue(highRange in 1200.0..4000.0 && lowRange in 1200.0..4000.0)
    }

    @Test fun aStrengthSessionAddsLoadAndHalfCountsWhenTheStrapSawIt() {
        val day = LocalDate.of(2026, 11, 20)
        val session = StrengthSession(day.atTime(18, 0).atZone(java.time.ZoneId.systemDefault()).toEpochSecond(), "Push", 50, 8, listOf(StrengthExercise("Bench", listOf(StrengthSet(8, 60.0)))))
        val plain = Enricher.enrich(listOf(DayRecord(day.toString(), steps = 4000.0)), emptyList(), emptyList(), Settings())
        val withLift = Enricher.enrich(listOf(DayRecord(day.toString(), steps = 4000.0)), emptyList(), emptyList(), Settings(), emptySet(), listOf(session))
        assertTrue(withLift.first().strain!! > plain.first().strain!!)
        val strap = Enricher.enrich(listOf(DayRecord(day.toString(), trimp = 300.0)), emptyList(), emptyList(), Settings())
        val strapAndLift = Enricher.enrich(listOf(DayRecord(day.toString(), trimp = 300.0)), emptyList(), emptyList(), Settings(), emptySet(), listOf(session))
        val full = Enricher.enrich(listOf(DayRecord(day.toString(), steps = 4000.0)), emptyList(), emptyList(), Settings(), emptySet(), listOf(session)).first().strain!!
        assertTrue(strapAndLift.first().strain!! > strap.first().strain!!)
        assertNotNull(full)
    }

    @Test fun aStrapLoadIsUsedInsteadOfAnOldStoredStrain() {
        val day = LocalDate.of(2026, 11, 20).toString()
        val d = Enricher.enrich(listOf(DayRecord(day, trimp = 600.0, strainRaw = 5.0)), emptyList(), emptyList(), Settings()).first()
        assertEquals(Scoring.strainFromTrimp(600.0), d.strain!!, 0.01)
        val imported = Enricher.enrich(listOf(DayRecord(day, strainRaw = 47.0)), emptyList(), emptyList(), Settings()).first()
        assertEquals(47.0, imported.strain!!, 0.0) // an export's figure is shown as WHOOP gave it
    }

    // ---------------------------------------------------------------- enricher end to end

    private fun nights(n: Int, make: (Int, Random) -> DayRecord): List<DayRecord> {
        val r = Random(3); val start = LocalDate.of(2026, 10, 1)
        return (0 until n).map { i -> make(i, r).copy(day = start.plusDays(i.toLong()).toString()) }
    }

    @Test fun recoveryIsHigherAfterAGoodNightThanAShortOneOnTheSameHrv() {
        val data = nights(31) { i, r ->
            val last = i == 30
            DayRecord("x", hrv = 60 + r.nextGaussian() * 3, rhr = 55 + r.nextGaussian(), resp = 14.0, sleepEff = 92.0, sleepMin = if (last) 270.0 else 470.0)
        }
        val short = Enricher.enrich(data, emptyList(), emptyList(), Settings()).last()
        val good = Enricher.enrich(data.dropLast(1) + data.last().copy(sleepMin = 480.0), emptyList(), emptyList(), Settings()).last()
        assertTrue("${short.recovery} vs ${good.recovery}", good.recovery!! > short.recovery!! + 5)
        assertNotNull(good.recoveryConf)
    }

    @Test fun illnessDaysDoNotSetTheNormal() {
        val data = nights(31) { i, r -> DayRecord("x", hrv = if (i in 20..26) 30.0 else 60 + r.nextGaussian() * 3, rhr = 55.0, resp = 14.0, sleepEff = 92.0, sleepMin = 470.0) }
        val sickDays = data.slice(20..26).map { it.day }.toSet()
        val without = Enricher.enrich(data, emptyList(), emptyList(), Settings(), sickDays).last().recovery!!
        val with = Enricher.enrich(data, emptyList(), emptyList(), Settings()).last().recovery!!
        // With the sick week counted, a normal night looks unusually good against a dragged-down baseline; left out, it is just normal.
        assertTrue("$without vs $with", without < with)
    }

    // ---------------------------------------------------------------- sleep coach

    @Test fun theGuidelineFollowsAge() {
        assertEquals(420..540, SleepCoach.guideline(35)); assertEquals(480..600, SleepCoach.guideline(16)); assertEquals(420..480, SleepCoach.guideline(70))
    }

    @Test fun withoutEnoughNightsItRecommendsTheAgeMidpoint() {
        val a = SleepCoach.recommend(35, emptyList())
        assertEquals(480, a.needMin); assertTrue(!a.personal); assertTrue(a.basis.contains("7–9"))
        assertEquals(450, SleepCoach.recommend(70, emptyList()).needMin)
    }

    @Test fun itLearnsTheLengthAfterWhichThePersonRecoversBest() {
        val r = Random(9)
        val data = nights(60) { i, _ ->
            val hours = listOf(6.0, 7.0, 8.0, 9.0)[i % 4] // sleep varies, HRV peaks after 8 hours
            val hrv = when (hours) { 6.0 -> 48.0; 7.0 -> 55.0; 8.0 -> 70.0; else -> 60.0 } + r.nextGaussian() * 2
            DayRecord("x", hrv = hrv, rhr = 60 - (hrv - 55) / 5, sleepMin = hours * 60 + r.nextGaussian() * 10)
        }
        val a = SleepCoach.recommend(35, data)
        assertTrue(a.personal)
        assertEquals(510, a.needMin) // the 8 to 9 hour bucket
        assertTrue(a.basis, a.basis.contains("8h 30m"))
    }

    @Test fun noClearPatternKeepsTheGuideline() {
        val r = Random(2)
        val data = nights(60) { i, _ -> DayRecord("x", hrv = 60 + r.nextGaussian() * 3, rhr = 55.0, sleepMin = (360 + (i * 37) % 180).toDouble()) }
        assertTrue(!SleepCoach.recommend(35, data).personal)
    }

    @Test fun tonightAddsAllowanceForAHardDayAndForDebt() {
        val calm = SleepCoach.tonight(480, DayRecord("2026-11-20", strain = 30.0, sleepDebtMin = 0.0, recovery = 70.0))
        assertEquals(480, calm.targetMin); assertTrue(calm.reasons.isEmpty())
        val tough = SleepCoach.tonight(480, DayRecord("2026-11-20", strain = 80.0, sleepDebtMin = 240.0, recovery = 25.0))
        assertEquals(480 + 60, tough.targetMin) // 30 + 45 + 15 = 90 of allowances, capped at 60
        assertEquals(3, tough.reasons.size)
        assertTrue(SleepCoach.tonight(480, DayRecord("2026-11-20", strain = 80.0, sleepDebtMin = 900.0, recovery = 10.0)).targetMin <= 480 + 60)
    }
}
