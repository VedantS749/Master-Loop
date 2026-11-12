package app.loop

import app.loop.analytics.Enricher
import app.loop.analytics.Scoring
import app.loop.model.DayRecord
import app.loop.model.Settings
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepScoreStressTest {
    @Test fun aShortNightDoesNotScoreWellEvenWhenUnbroken() {
        val s = Scoring.sleepScore(282.0, 98.0, 50.0, 60.0, 480)!!
        assertTrue("4h42 scored $s", s < 62)
    }

    @Test fun aFullGoodNightStillScoresHigh() {
        val s = Scoring.sleepScore(480.0, 94.0, 90.0, 110.0, 480)!!
        assertTrue("full night scored $s", s >= 90)
    }

    @Test fun sevenHoursIsGoodButNotPerfect() {
        val s = Scoring.sleepScore(420.0, 92.0, 70.0, 90.0, 480)!!
        assertTrue("7h scored $s", s in 78.0..90.0)
    }

    @Test fun nightBodyStressAppearsAfterTwoNights() {
        val raw = listOf(
            DayRecord(day = "2026-11-10", hrv = 68.0, rhr = 58.0, sleepMin = 320.0),
            DayRecord(day = "2026-11-11", hrv = 72.0, rhr = 60.0, sleepMin = 283.0),
            DayRecord(day = "2026-11-12", hrv = 66.0, rhr = 61.0, sleepMin = 529.0),
        )
        val out = Enricher.enrich(raw, emptyList(), emptyList(), Settings())
        assertNotNull("no stress on the third night", out.last().stress)
    }
}
