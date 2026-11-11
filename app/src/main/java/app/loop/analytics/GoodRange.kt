package app.loop.analytics

import app.loop.model.DayRecord
import app.loop.model.MetricDef
import app.loop.model.MetricId
import app.loop.model.Settings
import kotlin.math.roundToInt

/** How a value sits against its range. */
enum class Grade(val points: Int, val word: String) {
    GOOD(100, "Good"),
    OK(60, "Okay"),
    WATCH(20, "Needs attention"),
}

/** One stretch of a metric's scale, from [lo] up to (not including) [hi], and how it grades. */
data class Zone(val lo: Double, val hi: Double, val grade: Grade)

/**
 * Where a metric's values count as good, okay or worth watching, for this person.
 *
 * Standard ranges (recovery, sleep score, steps and so on) are fixed. Personal ones (heart-rate variability, resting
 * heart rate, breathing) are built around the person's own recent normal, because for those a "good number" only has
 * meaning against yourself. [zones] is empty for metrics with no right answer (calories, weight): only a [good] guide
 * is shown there and nothing is graded.
 */
class GoodRange(
    val scaleLo: Double,
    val scaleHi: Double,
    val zones: List<Zone>,
    /** Short text for the good range, such as "67–100%". For guide-only metrics this is the guide itself. */
    val good: String,
    /** Short text for the okay range, when there is one. */
    val okay: String?,
    /** Where the numbers come from, in a sentence. */
    val basis: String,
) {
    val graded: Boolean get() = zones.isNotEmpty()

    fun gradeOf(v: Double): Grade? {
        if (zones.isEmpty() || v.isNaN()) return null
        zones.forEach { if (v >= it.lo && v < it.hi) return it.grade }
        return if (v < zones.first().lo) zones.first().grade else zones.last().grade
    }

    /** The first zone with the given grade, for drawing and wording. */
    fun zone(g: Grade): Zone? = zones.firstOrNull { it.grade == g }
}

object GoodRanges {
    private const val STANDARD = "A standard guide for most adults."
    private const val EPS = 1e-4

    /**
     * The range for [def], or null when Loop has nothing sensible to say. [base] is the person's recent normal for this
     * metric; [day] supplies same-day context where the right range depends on it (strain needs that day's recovery, deep
     * and REM need how long they slept, daytime low needs their resting heart rate).
     */
    fun of(def: MetricDef, s: Settings, base: Stats.Baseline?, day: DayRecord? = null): GoodRange? {
        val need = s.sleepNeedMin.toDouble()
        return when (def.id) {
            MetricId.RECOVERY -> bands(0.0, 100.0, listOf(0.0 to Grade.WATCH, 34.0 to Grade.OK, 67.0 to Grade.GOOD), "67–100%", "34–66%", "Loop's recovery bands: green, yellow and red.")
            MetricId.HRV -> personal(base, "ms", 0.5, 1.5, 0.92, 1.08)
            MetricId.RHR -> base?.let { b ->
                val m = b.mean
                bands(m - 12, m + 12, listOf(m - 12 to Grade.GOOD, m - 2 to Grade.OK, m + 2 to Grade.WATCH), "${r(m - 2)} bpm or lower", "${r(m - 2)}–${r(m + 2)} bpm", usual(m, "bpm"))
            } ?: guide(40.0, 100.0, "Lower than your own usual is good", "Your usual appears after about 5 nights of data.")
            MetricId.SLEEP_SCORE -> bands(0.0, 100.0, listOf(0.0 to Grade.WATCH, 50.0 to Grade.OK, 70.0 to Grade.GOOD), "70–100", "50–69", "Combines how long, how efficient and how restorative your night was.")
            MetricId.SLEEP_DURATION -> bands(
                0.0, need * 1.35, listOf(0.0 to Grade.WATCH, need * 0.8 to Grade.OK, need * 0.95 to Grade.GOOD),
                "${Scoring.hm(need * 0.95)} or more", "${Scoring.hm(need * 0.8)}–${Scoring.hm(need * 0.95)}",
                "Your sleep need is ${Scoring.hm(need)}. You can change it in your profile.",
            )
            MetricId.SLEEP_EFFICIENCY -> bands(50.0, 100.0, listOf(50.0 to Grade.WATCH, 85.0 to Grade.GOOD), "85–100%", null, "The usual benchmark for healthy sleep is 85% or more.")
            MetricId.DEEP -> stage(day?.sleepMin ?: need, day?.sleepMin != null, 0.10, 0.28, 0.4, "13–23% of sleep is typical", need)
            MetricId.REM -> stage(day?.sleepMin ?: need, day?.sleepMin != null, 0.17, 0.30, 0.45, "20–25% of sleep is typical", need)
            MetricId.SLEEP_DEBT -> bands(0.0, 360.0, listOf(0.0 to Grade.GOOD, 60.0 to Grade.OK, 180.0 to Grade.WATCH), "under ${Scoring.hm(60.0)}", "${Scoring.hm(60.0)}–${Scoring.hm(180.0)}", "How much sleep you owe from the last few nights.")
            MetricId.STRAIN -> day?.recovery?.let { rec ->
                val t = Scoring.strainTarget(rec)
                bands(
                    0.0, 100.0, listOf(0.0 to Grade.OK, t.first.toDouble() to Grade.GOOD, t.last + 10.0 to Grade.WATCH),
                    "${t.first}–${t.last + 10}", "under ${t.first}",
                    "Best for a recovery of ${rec.roundToInt()}%: aim for ${t.first} to ${t.last}. Up to 10 over still counts as on target.",
                )
            } ?: guide(0.0, 100.0, "Depends on your recovery: recovered days can take more", "Loop pairs strain with your recovery score.")
            MetricId.CALORIES -> guide(0.0, 1500.0, "No right number: it depends on your goals", "Active calories add up from your workouts.")
            MetricId.STEPS -> bands(0.0, 12000.0, listOf(0.0 to Grade.WATCH, 4000.0 to Grade.OK, 7000.0 to Grade.GOOD), "7,000 or more", "4,000–6,999", "7,000 to 10,000 steps a day is linked to better long-term health.")
            MetricId.RESP -> base?.let { b ->
                val m = b.mean
                bands(m - 3, m + 3, listOf(m - 3 to Grade.OK, m - 1 to Grade.GOOD, m + 1 to Grade.WATCH), "${f1(m - 1)}–${f1(m + 1)} br/min", "below ${f1(m - 1)} br/min", usual(m, "br/min", 1))
            } ?: bands(6.0, 30.0, listOf(6.0 to Grade.OK, 12.0 to Grade.GOOD, 20.0 + EPS to Grade.OK), "12–20 br/min", "outside 12–20", "The normal adult range at rest. Loop will learn what is normal for you.")
            MetricId.SKIN_TEMP -> {
                val k = if (s.imperial) 1.8 else 1.0
                val u = if (s.imperial) "°F" else "°C"
                bands(-2.0, 2.0, listOf(-2.0 to Grade.OK, -0.5 to Grade.GOOD, 0.5 to Grade.OK, 1.0 to Grade.WATCH), "within ±${f1(0.5 * k)} $u", "up to ±${f1(1.0 * k)} $u", "Measured against your personal baseline, not an absolute temperature.")
            }
            MetricId.WEIGHT -> {
                val h = s.heightCm / 100.0
                val k = if (s.imperial) 2.20462 else 1.0
                val u = if (s.imperial) "lb" else "kg"
                guide(40.0, 140.0, "A BMI of 18.5–24.9 is about ${r(18.5 * h * h * k)}–${r(24.9 * h * h * k)} $u for your height", "A rough guide only. BMI ignores muscle and build, and the slow trend matters more than any day.")
            }
            MetricId.STRESS, MetricId.DAY_STRESS, MetricId.DT_STRESS -> bands(0.0, 100.0, listOf(0.0 to Grade.GOOD, 35.0 to Grade.OK, 65.0 to Grade.WATCH), "under 35", "35–64", if (def.id == MetricId.DT_STRESS) "A live estimate, so treat it as a guide." else STANDARD)
            MetricId.MOOD -> bands(1.0, 5.0, listOf(1.0 to Grade.WATCH, 2.5 to Grade.OK, 4.0 to Grade.GOOD), "4–5", "2.5–3.9", "Your own rating. Low days are normal; weeks of them are worth talking about.")
            MetricId.HR_RECOVERY -> bands(0.0, 40.0, listOf(0.0 to Grade.WATCH, 12.0 to Grade.OK, 18.0 to Grade.GOOD), "18 bpm or more", "12–17 bpm", "How far your heart rate falls in the first minute after hard effort.")
            MetricId.VO2_MAX -> {
                val a = if (s.female) 5.0 else 0.0
                bands(10.0, 70.0, listOf(10.0 to Grade.WATCH, 30.0 - a to Grade.OK, 40.0 - a to Grade.GOOD), "${r(40 - a)} mL/kg/min or more", "${r(30 - a)}–${r(40 - a)}", "Varies a lot with age and sex. Shown for an adult ${if (s.female) "woman" else "man"}.")
            }
            MetricId.LOAD_RATIO -> bands(0.4, 2.0, listOf(0.4 to Grade.OK, 0.8 to Grade.GOOD, 1.3 to Grade.OK, 1.5 to Grade.WATCH), "0.8–1.3", "0.4–0.8 or 1.3–1.5", "Your last 7 days of strain against your usual 28.")
            MetricId.STRESS_HIGH -> bands(0.0, 300.0, listOf(0.0 to Grade.GOOD, 30.0 to Grade.OK, 120.0 to Grade.WATCH), "under ${Scoring.hm(30.0)}", "${Scoring.hm(30.0)}–${Scoring.hm(120.0)}", "Minutes spent in a high-stress state while resting.")
            MetricId.SKIN_TEMP_REL -> bands(-3.0, 3.0, listOf(-3.0 to Grade.WATCH, -2.0 to Grade.OK, -1.0 to Grade.GOOD, 1.0 to Grade.OK, 2.0 to Grade.WATCH), "−1 to 1 σ", "1 to 2 σ either way", "In standard deviations from your own recent nights.")
            MetricId.WEAR_TIME -> bands(0.0, 1440.0, listOf(0.0 to Grade.WATCH, 960.0 to Grade.OK, 1320.0 to Grade.GOOD), "${Scoring.hm(1320.0)} or more", "${Scoring.hm(960.0)}–${Scoring.hm(1320.0)}", "Scores are only as good as the data behind them.")
            MetricId.DT_HR -> base?.let { b ->
                val m = b.mean
                bands(m - 15, m + 15, listOf(m - 15 to Grade.GOOD, m - 4 to Grade.OK, m + 5 to Grade.WATCH), "${r(m - 4)} bpm or lower", "${r(m - 4)}–${r(m + 5)} bpm", usual(m, "bpm") + " For the same activity, lower is better.")
            } ?: guide(40.0, 120.0, "Compare with your own usual days", "Needs a few days of logged daytime data.")
            MetricId.DT_HR_LOW -> day?.rhr?.let { rhr ->
                bands(rhr - 5, rhr + 40, listOf(rhr - 5 to Grade.GOOD, rhr + 12 to Grade.OK), "up to ${r(rhr + 12)} bpm", "above ${r(rhr + 12)} bpm", "Within 12 beats of your sleeping resting heart rate (${r(rhr)} bpm).")
            } ?: guide(40.0, 100.0, "A few beats above your sleeping resting heart rate", "Needs a night of data to compare with.")
            MetricId.DT_HRV, MetricId.DT_SDNN -> personal(base, "ms", 0.5, 1.5, 0.90, 1.10, daytime = true)
            MetricId.DT_BREATH -> bands(6.0, 30.0, listOf(6.0 to Grade.OK, 12.0 to Grade.GOOD, 20.0 + EPS to Grade.WATCH), "12–20 br/min", "under 12", "The normal adult range at rest. A live estimate.")
            MetricId.DT_COHERENCE -> bands(0.0, 100.0, listOf(0.0 to Grade.OK, 50.0 to Grade.GOOD), "50% or more", "under 50%", "Higher when slow, even breathing makes your heart rhythm smooth. A live estimate.")
            MetricId.DT_LFHF -> bands(0.0, 8.0, listOf(0.0 to Grade.GOOD, 2.0 + EPS to Grade.OK, 4.0 + EPS to Grade.WATCH), "2 or lower", "2–4", "A rough, debated measure. Look at the trend, not one number.")
            MetricId.DT_KCAL -> guide(0.0, 1500.0, "No right number: it depends on your day", "Energy burned above rest across the minutes Loop logged.")
            MetricId.DT_LOGGED -> bands(0.0, 1200.0, listOf(0.0 to Grade.WATCH, 240.0 to Grade.OK, 600.0 to Grade.GOOD), "${Scoring.hm(600.0)} or more", "${Scoring.hm(240.0)}–${Scoring.hm(600.0)}", "More logged time makes the daytime estimates more trustworthy.")
            MetricId.RHYTHM_IRREG -> bands(0.0, 50.0, listOf(0.0 to Grade.GOOD, 5.0 to Grade.OK, 20.0 to Grade.WATCH), "under 5%", "5–19%", "Share of the night with uneven beat timing. Not a diagnosis.")
            else -> null
        }
    }

    // ---------------------------------------------------------------- builders

    private fun r(v: Double) = v.roundToInt()
    private fun f1(v: Double) = "%.1f".format(v)
    private fun usual(m: Double, unit: String, dec: Int = 0) = "Your usual is ${if (dec == 0) r(m).toString() else f1(m)} $unit, from the last 4 weeks."

    /** Zones from ascending (lower bound, grade) pairs, each running to the next bound and the last to [scaleHi]. */
    private fun bands(scaleLo: Double, scaleHi: Double, cuts: List<Pair<Double, Grade>>, good: String, okay: String?, basis: String): GoodRange {
        val zones = cuts.mapIndexed { i, (lo, g) -> Zone(lo, cuts.getOrNull(i + 1)?.first ?: scaleHi, g) }
        return GoodRange(scaleLo, scaleHi, zones, good, okay, basis)
    }

    private fun guide(scaleLo: Double, scaleHi: Double, text: String, basis: String) = GoodRange(scaleLo, scaleHi, emptyList(), text, null, basis)

    /** A range around the person's own usual: good above [goodAt] of it, okay within, watch below [okAt]. */
    private fun personal(base: Stats.Baseline?, unit: String, scaleLoF: Double, scaleHiF: Double, okAt: Double, goodAt: Double, daytime: Boolean = false): GoodRange {
        if (base == null) return guide(0.0, 120.0, "Personal to you: compare it with your own usual", if (daytime) "Needs several days of logged daytime data." else "Needs about 5 nights of data to learn your usual.")
        val m = base.mean
        return bands(
            m * scaleLoF, m * scaleHiF, listOf(m * scaleLoF to Grade.WATCH, m * okAt to Grade.OK, m * goodAt to Grade.GOOD),
            "${r(m * goodAt)} $unit or more", "${r(m * okAt)}–${r(m * goodAt)} $unit",
            "Your usual is ${r(m)} $unit, from the last ${if (daytime) "few weeks of logged days" else "4 weeks"}. Higher is better.",
        )
    }

    /** A sleep stage as a share of the night: [warnBelow] and [okAbove] are fractions of time asleep. */
    private fun stage(asleep: Double, real: Boolean, warnBelow: Double, okAbove: Double, scaleFrac: Double, typical: String, need: Double): GoodRange {
        val a = asleep
        return bands(
            0.0, a * scaleFrac, listOf(0.0 to Grade.WATCH, a * warnBelow to Grade.GOOD, a * okAbove to Grade.OK),
            "${Scoring.hm(a * warnBelow)}–${Scoring.hm(a * okAbove)}", "over ${Scoring.hm(a * okAbove)}",
            "${typical.replaceFirstChar { it.uppercase() }}. Worked out for ${if (real) "a sleep of ${Scoring.hm(a)}" else "your sleep need of ${Scoring.hm(need)}"}.",
        )
    }
}
