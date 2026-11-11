package app.loop.data

import app.loop.analytics.Scoring
import app.loop.model.DayRecord
import app.loop.model.MoodEntry
import app.loop.model.JournalEntry
import app.loop.model.StrengthExercise
import app.loop.model.StrengthSession
import app.loop.model.StrengthSet
import app.loop.model.Workout
import java.time.LocalDate
import java.time.ZoneId
import java.util.Random
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 90 days of synthetic data so a first-time user can explore every screen with no strap and no import.
 * Fields are correlated on purpose (short sleep lowers HRV, hard days cost tomorrow, mood follows recovery)
 * so the scoring and the Insights engine have something realistic to find. Recovery and strain are NOT
 * stored: Loop's own scorers compute them, exactly as they would for real data.
 */
object DemoData {
    private val sports = listOf("Running", "Cycling", "Strength", "HIIT", "Swimming", "Walking")

    fun generate(today: LocalDate = LocalDate.now(), nDays: Int = 90): LoopData {
        val rnd = Random(42)
        val days = ArrayList<DayRecord>()
        val workouts = ArrayList<Workout>()
        val moods = ArrayList<MoodEntry>()

        var fitness = 0.0         // slow upward drift in HRV
        var prevStrain = 30.0
        var prevFactors: List<String> = emptyList()
        val journal = ArrayList<JournalEntry>()
        val strength = ArrayList<StrengthSession>()
        var weight = 74.8
        val zone = ZoneId.systemDefault()

        for (i in nDays - 1 downTo 0) {
            val date = today.minusDays(i.toLong())
            val dow = date.dayOfWeek.value // 1=Mon
            fitness += 0.04

            val weekendLate = dow == 6 || dow == 7
            // Journal: factors for today. Their effects land on tomorrow morning below, so the Journal screen has real patterns to find.
            val todayFactors = ArrayList<String>().also { f ->
                if (rnd.nextDouble() < (if (weekendLate) 0.35 else 0.12)) f += "alcohol"
                if (rnd.nextDouble() < 0.25) f += "caffeine_late"
                if (rnd.nextDouble() < 0.30) f += "screens_bed"
                if (rnd.nextDouble() < 0.20) f += "meditation"
                if (rnd.nextDouble() < 0.20) f += "late_meal"
                if (rnd.nextDouble() < 0.25) f += "stretching"
                if (rnd.nextDouble() < 0.25) f += "stressful_day"
                if (rnd.nextDouble() < 0.50) f += "hydrated"
            }
            val sleepH = (7.1 + rnd.nextGaussian() * 0.75 - (if (weekendLate) -0.3 else 0.0) - (if ("screens_bed" in prevFactors) 0.4 else 0.0)).coerceIn(4.8, 9.2)
            val sleepMin = sleepH * 60
            val eff = (91 + rnd.nextGaussian() * 3.5 - max(0.0, (prevStrain - 75) * 0.05)).coerceIn(78.0, 98.0)
            val deepFrac = (0.19 + rnd.nextGaussian() * 0.025).coerceIn(0.10, 0.28)
            val remFrac = (0.22 + rnd.nextGaussian() * 0.03).coerceIn(0.12, 0.31)
            val deep = sleepMin * deepFrac
            val rem = sleepMin * remFrac
            val light = sleepMin - deep - rem
            val awake = sleepMin * (100 - eff) / eff

            val sleepEffect = (sleepH - 7.0) * 4.5
            val strainEffect = -max(0.0, prevStrain - 55) * 0.22
            val hrv = (60 + fitness + sleepEffect + strainEffect + (if ("alcohol" in prevFactors) -10.0 else 0.0) + (if ("meditation" in prevFactors) 5.0 else 0.0) + rnd.nextGaussian() * 4.5).coerceIn(28.0, 110.0)
            val rhr = (55 - fitness * 0.1 - (hrv - 60) * 0.12 + rnd.nextGaussian() * 1.4).coerceIn(44.0, 72.0)
            val resp = (14.6 + rnd.nextGaussian() * 0.35 + max(0.0, (-sleepEffect - 3)) * 0.04).coerceIn(12.0, 18.0)
            val skin = rnd.nextGaussian() * 0.22
            weight += rnd.nextGaussian() * 0.12 - 0.012

            // Workouts: ~4 a week, none the day after a very hard day
            val trains = rnd.nextDouble() < (if (dow == 7) 0.25 else if (dow == 3) 0.5 else 0.62) && prevStrain < 85
            var dayTrimp = 0.0
            var dayKcal = 0.0
            if (trains) {
                val sport = sports[rnd.nextInt(sports.size)]
                val mins = when (sport) { "Walking" -> 45.0; "Strength" -> 55.0; "HIIT" -> 30.0; else -> 40.0 + rnd.nextInt(30) }
                val intensity = when (sport) { "Walking" -> 0.35; "Strength" -> 0.55; "HIIT" -> 0.85; "Swimming" -> 0.62; else -> 0.6 + rnd.nextDouble() * 0.2 }
                val z = DoubleArray(5)
                for (k in 0 until 5) {
                    val centre = intensity * 4.0
                    val w = Math.exp(-Math.pow((k - centre), 2.0) / 1.6)
                    z[k] = w
                }
                val total = z.sum()
                val zm = z.map { it / total * mins }
                // The first two "zones" in demo are really warm-up/light so keep them generous.
                val trimp = Scoring.trimp(zm)
                val avgHr = (118 + intensity * 55 + rnd.nextGaussian() * 3).roundToInt()
                val kcal = mins * (4.0 + intensity * 9.0) * (weight / 75.0)
                val start = date.atTime(7 + rnd.nextInt(11), listOf(0, 15, 30, 45)[rnd.nextInt(4)]).atZone(zone).toEpochSecond()
                workouts += Workout(
                    id = start, sport = sport, durationMin = mins, avgHr = avgHr, maxHr = min(195, avgHr + 22 + rnd.nextInt(12)),
                    strain = Scoring.strainFromTrimp(trimp), kcal = kcal, zoneMin = zm, source = "demo",
                )
                dayTrimp += trimp
                dayKcal += kcal
            }
            val steps = (if (trains) 9500.0 else 6800.0) + rnd.nextGaussian() * 1800
            dayTrimp += Scoring.trimpFromSteps(steps.coerceAtLeast(1500.0))
            prevStrain = Scoring.strainFromTrimp(dayTrimp)

            val dayStress = (38 + (60 - hrv) * 0.7 + (if ("stressful_day" in todayFactors) 18 else 0) + rnd.nextGaussian() * 6).coerceIn(5.0, 95.0)
            days += DayRecord(
                dtHr = rhr + 24 + rnd.nextGaussian() * 3, dtHrLow = rhr + 7 + rnd.nextGaussian(), dtHrv = hrv * 0.62 + rnd.nextGaussian() * 2.5,
                dtSdnn = hrv * 0.8 + rnd.nextGaussian() * 3, dtBreath = (resp + 1.6 + rnd.nextGaussian() * 0.4), dtStress = (dayStress + rnd.nextGaussian() * 4).coerceIn(5.0, 95.0),
                dtCoherence = (38 + rnd.nextGaussian() * 9).coerceIn(8.0, 90.0), dtLfHf = (1.7 + (dayStress - 40) * 0.03 + rnd.nextGaussian() * 0.3).coerceIn(0.4, 6.0),
                dtKcal = 140 + (if (trains) 220.0 else 0.0) + rnd.nextGaussian() * 25, dtMin = (780 + rnd.nextGaussian() * 60).coerceIn(300.0, 1200.0),
                wearMin = (1290 + rnd.nextGaussian() * 40).coerceIn(900.0, 1430.0), dayStress = dayStress,
                stressHighMin = ((dayStress - 35).coerceAtLeast(0.0) * 5.5), hrr = if (trains) (18 + fitness * 0.6 + rnd.nextGaussian() * 3).coerceIn(8.0, 45.0) else null,
                day = date.toString(), hrv = hrv, rhr = rhr, resp = resp, skinTempDev = skin,
                sleepMin = sleepMin, sleepEff = eff, deepMin = deep, remMin = rem, lightMin = light, awakeMin = awake,
                steps = steps.coerceAtLeast(1500.0).roundToInt().toDouble(), weightKg = if (i % 3 == 0) (weight * 10).roundToInt() / 10.0 else null,
                rhythmIrreg = (1.2 + rnd.nextDouble() * 2.5).let { (it * 10).roundToInt() / 10.0 },
            )

            if (rnd.nextDouble() < 0.85) journal += JournalEntry(date.toString(), todayFactors)
            prevFactors = todayFactors
            if (dow == 1 || dow == 3 || dow == 5) if (rnd.nextDouble() < 0.7) {
                val prog = (nDays - 1 - i) / 14.0 * 2.5
                val plan = when (dow) {
                    1 -> listOf("Bench press" to 60.0, "Overhead press" to 38.0, "Dip" to 12.0)
                    3 -> listOf("Barbell row" to 55.0, "Pull-up" to 8.0, "Biceps curl" to 14.0)
                    else -> listOf("Back squat" to 80.0, "Romanian deadlift" to 70.0, "Lunge" to 24.0)
                }
                val reps = 5 + rnd.nextInt(4)
                strength += StrengthSession(
                    id = date.atTime(18, 0).atZone(zone).toEpochSecond(), name = when (dow) { 1 -> "Push"; 3 -> "Pull"; else -> "Legs" },
                    durationMin = 50 + rnd.nextInt(15), rpe = 6 + rnd.nextInt(3),
                    exercises = plan.map { (n, w) -> StrengthExercise(n, List(3 + rnd.nextInt(2)) { StrengthSet(reps, ((w + prog) * 2).roundToInt() / 2.0) }) },
                )
            }

            // Mood follows how well rested you are, loosely, on about 6 days in 10
            if (rnd.nextDouble() < 0.6) {
                val raw = 3.0 + (sleepH - 7.0) * 0.7 + (hrv - 60) * 0.02 + rnd.nextGaussian() * 0.7
                moods += MoodEntry(date.toString(), raw.roundToInt().coerceIn(1, 5), emptyList())
            }
        }
        // A period started 11 days ago and roughly every 28 days before that.
        val cycles = (0..3).map { today.minusDays(11L + 28L * it).toString() }.sorted()
        return LoopData(days, workouts.sortedBy { it.id }, moods, journal, cycles, strength.sortedBy { it.id })
    }
}
