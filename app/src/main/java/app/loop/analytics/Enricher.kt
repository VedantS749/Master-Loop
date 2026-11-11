package app.loop.analytics

import app.loop.model.DayRecord
import app.loop.model.MoodEntry
import app.loop.model.Settings
import app.loop.model.StrengthSession
import app.loop.model.Workout
import kotlin.math.ln
import kotlin.math.max

/**
 * Turns raw stored days into fully scored days: recovery, sleep score, strain, stress, sleep debt, mood.
 * Pure and deterministic, so it is safe to recompute whenever data or settings change.
 */
object Enricher {
    private const val BASELINE_DAYS = 28

    fun enrich(
        raw: List<DayRecord>, workouts: List<Workout>, moods: List<MoodEntry>, s: Settings,
        off: Set<String> = emptySet(), strength: List<StrengthSession> = emptyList(),
    ): List<DayRecord> {
        val workoutsByDay = workouts.groupBy { it.day }
        val moodByDay = moods.groupBy { it.day }.mapValues { (_, v) -> v.map { it.level.toDouble() }.average() }
        val strengthByDay = strength.groupBy { it.day }
        val known = raw.associateBy { it.day }
        val allDays = (known.keys + workoutsByDay.keys + moodByDay.keys + strengthByDay.keys).sorted()
        val days = allDays.map { known[it] ?: DayRecord(day = it) }

        val out = ArrayList<DayRecord>(days.size)
        val strainByDate = HashMap<java.time.LocalDate, Double>()
        val loadHistory = ArrayList<Double>() // each day's training load so far, for the personal strain scale
        for ((idx, d) in days.withIndex()) {
            val hist = days.subList(max(0, idx - BASELINE_DAYS), idx)
            val usable = hist.filter { it.day !in off } // illness and serious injuries do not set what is normal
            val weekend = d.date.dayOfWeek.value >= 6

            /** The person's normal for this kind of night: the 28-day mean, nudged towards nights of the same type (weekday or weekend). */
            fun normal(minN: Int, frac: Double, read: (DayRecord) -> Double?): Stats.Baseline? {
                val vals = usable.mapNotNull { x -> read(x)?.let { x to it } }
                val overall = Stats.baseline(vals.map { it.second }, minN, frac) ?: return null
                val same = vals.filter { (it.first.date.dayOfWeek.value >= 6) == weekend }.map { it.second }
                return if (same.size >= 4) overall.copy(mean = 0.5 * overall.mean + 0.5 * same.average()) else overall
            }
            val hbRaw = Stats.baseline(usable.map { it.hrv })
            val hbLn = normal(5, 0.03) { x -> x.hrv?.takeIf { it > 0 }?.let { ln(it) } }
            val rb = normal(5, 0.06) { it.rhr }
            val pb = normal(5, 0.06) { it.resp }
            val hrv7 = usable.takeLast(7).mapNotNull { x -> x.hrv?.takeIf { it > 0 }?.let { ln(it) } }.takeIf { it.size >= 3 }?.average()

            // Sleep debt: shortfall over the last five nights that have sleep data.
            val recent = (days.subList(max(0, idx - 4), idx + 1)).mapNotNull { it.sleepMin }
            val debt = if (d.sleepMin == null || recent.isEmpty()) null
            else (recent.sumOf { s.sleepNeedMin - it }).coerceAtLeast(0.0)

            val sleepScore = d.sleepPerf ?: Scoring.sleepScore(d.sleepMin, d.sleepEff, d.deepMin, d.remMin, s.sleepNeedMin)
            val rec = if (d.recoveryRaw != null) null else Scoring.recovery(
                Scoring.RecoveryInput(d.hrv, d.rhr, d.resp, d.sleepEff, d.skinTempDev, hbLn, rb, pb, d.sleepMin, s.sleepNeedMin, debt, hrv7),
            )
            val recovery = d.recoveryRaw ?: rec?.score

            // ---- strain: the strap's all-day load when there is one, else workouts, steps and logged strength sessions ----
            val ws = workoutsByDay[d.day].orEmpty()
            val ownLoad = d.trimp ?: ws.sumOf { Scoring.trimpFromStrain(it.strain) }.takeIf { ws.isNotEmpty() }
            val stepsLoad = if (d.trimp == null && d.strainRaw == null) d.steps?.let { Scoring.trimpFromSteps(it) } else null
            // A logged lifting session usually overlaps what the strap already saw, so it counts half when there is other load.
            val liftLoad = strengthByDay[d.day].orEmpty().sumOf { Scoring.strengthTrimp(it.durationMin, it.rpe) } * (if (ownLoad != null) 0.5 else 1.0)
            val anyLoad = ownLoad != null || stepsLoad != null || liftLoad > 0
            val totalLoad = (ownLoad ?: 0.0) + (stepsLoad ?: 0.0) + liftLoad
            val importedStrain = if (d.trimp == null) d.strainRaw else null // a figure from a WHOOP export is shown as WHOOP gave it
            val strain = importedStrain ?: run {
                if (!anyLoad) null else Scoring.strainFromTrimp(totalLoad, Scoring.personalCeiling(loadHistory.takeLast(60)))
            }
            if (importedStrain == null && anyLoad) loadHistory += totalLoad
            val kcal = d.kcal ?: ws.mapNotNull { it.kcal }.takeIf { it.isNotEmpty() }?.sum()

            strain?.let { strainByDate[d.date] = it }
            fun window(n: Int): List<Double> = (0 until n).mapNotNull { strainByDate[d.date.minusDays(it.toLong())] }
            val acute = window(7); val chronic = window(28)
            val loadRatio = if (acute.size >= 4 && chronic.size >= 14 && chronic.average() > 1.0) acute.average() / chronic.average() else null
            // Uth et al.: VO2max ~ 15.3 x (HRmax / resting HR), using the last week's resting heart rate.
            val rhr7 = days.subList(max(0, idx - 6), idx + 1).mapNotNull { it.rhr }
            val vo2 = if (rhr7.size >= 2 && rhr7.average() > 30) 15.3 * Scoring.maxHr(s) / rhr7.average() else null

            out += d.copy(
                vo2max = vo2, loadRatio = loadRatio,
                recovery = recovery,
                recoveryConf = rec?.confidence, recoveryConfNote = rec?.confidenceNote,
                strain = strain,
                kcal = kcal,
                sleepScore = sleepScore,
                sleepDebtMin = debt,
                stress = Scoring.stress(d.hrv, d.rhr, hbRaw, rb),
                mood = moodByDay[d.day],
            )
        }
        return out
    }
}
