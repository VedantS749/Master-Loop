package app.loop.analytics

import app.loop.model.Settings
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/*
 * Loop's scoring. Everything runs on-device from your own history.
 *
 * Recovery follows the same transparent z-score + logistic recipe used by the NOOP project this app was
 * rebuilt from (HRV-dominant, baseline-normalised). Strain uses Edwards TRIMP heart-rate zones.
 * These are approximations of established sports-science methods, NOT medical measurements.
 */
object Scoring {

    // ---------- Recovery ----------
    private const val W_HRV = 0.50
    private const val W_RHR = 0.20
    private const val W_RESP = 0.05
    private const val W_SLEEP = 0.20
    private const val W_SKIN = 0.05
    private const val LOGISTIC_K = 1.6
    private const val LOGISTIC_Z0 = -0.20 // Z = 0 maps to ~58%, the population average

    /**
     * Last night against the person's own normal. HRV is skewed (a few high nights drag a plain average up), so it is
     * judged in logs: [hrvBase] is the baseline of ln(HRV). [hrv7] is the mean ln(HRV) of the last week, so the score
     * reflects both last night against the long baseline and against the recent trend. Sleep counts length and debt as
     * well as efficiency, because a short night can be perfectly "efficient".
     */
    data class RecoveryInput(
        val hrv: Double?, val rhr: Double?, val resp: Double?, val sleepEff: Double?, val skinDev: Double?,
        val hrvBase: Stats.Baseline?, val rhrBase: Stats.Baseline?, val respBase: Stats.Baseline?,
        val sleepMin: Double? = null, val sleepNeedMin: Int = 480, val sleepDebtMin: Double? = null,
        val hrv7: Double? = null,
    )

    /** Contribution of each signal in z-units (positive = helped recovery), and how far the score can be trusted. */
    data class RecoveryResult(
        val score: Double, val zHrv: Double?, val zRhr: Double?, val zResp: Double?, val zSleep: Double?,
        /** 0 low, 1 medium, 2 high. */
        val confidence: Int = 2, val confidenceNote: String? = null,
    )

    fun recovery(i: RecoveryInput): RecoveryResult? {
        // HRV is the dominant term; without it and its baseline we don't pretend to know.
        if (i.hrv == null || i.hrv <= 0 || i.hrvBase == null) return null
        val lnH = ln(i.hrv)
        val zNight = i.hrvBase.z(lnH)
        val zHrv = if (i.hrv7 != null) 0.7 * zNight + 0.3 * ((lnH - i.hrv7) / i.hrvBase.sd) else zNight
        val zRhr = if (i.rhr != null && i.rhrBase != null) -i.rhrBase.z(i.rhr) else null
        val zResp = if (i.resp != null && i.respBase != null) -i.respBase.z(i.resp) else null
        val zSleep = sleepZ(i)
        val zSkin = i.skinDev?.let { -kotlin.math.abs(it) }
        var num = W_HRV * zHrv.coerceIn(-4.0, 4.0); var den = W_HRV
        zRhr?.let { num += W_RHR * it.coerceIn(-4.0, 4.0); den += W_RHR }
        zResp?.let { num += W_RESP * it.coerceIn(-4.0, 4.0); den += W_RESP }
        zSleep?.let { num += W_SLEEP * it; den += W_SLEEP }
        zSkin?.let { num += W_SKIN * it; den += W_SKIN }
        val z = (num / den).coerceIn(-4.0, 4.0)
        val score = 100.0 / (1.0 + exp(-LOGISTIC_K * (z - LOGISTIC_Z0)))
        val (conf, note) = recoveryConfidence(i)
        return RecoveryResult(score, zHrv, zRhr, zResp, zSleep, conf, note)
    }

    /** Sleep in z-units: efficiency, length against need and recent debt, each only when known. */
    private fun sleepZ(i: RecoveryInput): Double? {
        var num = 0.0; var den = 0.0
        i.sleepEff?.let { num += 0.3 * ((it / 100.0 - 0.85) / 0.12).coerceIn(-3.0, 3.0); den += 0.3 }
        if (i.sleepMin != null && i.sleepNeedMin > 0) { num += 0.5 * ((i.sleepMin / i.sleepNeedMin - 0.92) / 0.12).coerceIn(-3.0, 3.0); den += 0.5 }
        i.sleepDebtMin?.let { num += 0.2 * (-(it / 60.0) / 2.5).coerceIn(-3.0, 0.0); den += 0.2 }
        return if (den > 0) num / den else null
    }

    private fun recoveryConfidence(i: RecoveryInput): Pair<Int, String?> {
        var pts = 0
        val reasons = ArrayList<String>()
        val n = i.hrvBase?.n ?: 0
        if (n >= 14) pts += 2 else if (n >= 7) pts += 1 else reasons += "only $n nights of history"
        if (i.sleepMin != null && i.sleepMin >= 0.75 * i.sleepNeedMin) pts += 1 else reasons += "a short or missing night"
        if (i.rhr != null) pts += 1 else reasons += "no resting heart rate"
        val level = when { pts >= 4 -> 2; pts >= 2 -> 1; else -> 0 }
        return level to (if (level == 2 || reasons.isEmpty()) null else "Based on " + reasons.joinToString(" and "))
    }

    // ---------- Sleep ----------
    /**
     * 0..100 night score: 60% how much of the need was slept, 20% efficiency, 20% restorative (deep + REM) share.
     * Duration counts for more than the other parts and falls away faster than it rises, so a short night cannot score
     * well just because the little sleep there was came in one unbroken piece: about 4 h 40 against an 8 h need scores
     * in the 50s, 7 h in the mid 80s, and only a full night with good quality reaches the 90s.
     */
    fun sleepScore(asleepMin: Double?, eff: Double?, deepMin: Double?, remMin: Double?, needMin: Int): Double? {
        if (asleepMin == null) return null
        val dur = Stats.clamp(asleepMin / needMin, 0.0, 1.0)
        val durS = dur * dur
        val effS = if (eff != null) Stats.clamp((eff - 75.0) / 22.0, 0.0, 1.0) else 0.6
        val restorative = if (deepMin != null && remMin != null && asleepMin > 0)
            Stats.clamp(((deepMin + remMin) / asleepMin) / 0.45, 0.0, 1.0) else 0.6
        return 100.0 * (0.6 * durS + 0.2 * effS + 0.2 * restorative)
    }

    // ---------- Stress ----------
    /** 0..100. High when HRV is below and resting HR above your baseline. */
    fun stress(hrv: Double?, rhr: Double?, hrvBase: Stats.Baseline?, rhrBase: Stats.Baseline?): Double? {
        val zh = if (hrv != null && hrvBase != null) hrvBase.z(hrv) else null
        val zr = if (rhr != null && rhrBase != null) rhrBase.z(rhr) else null
        if (zh == null && zr == null) return null
        val z = (-(zh ?: 0.0) * 0.7) + ((zr ?: 0.0) * 0.3)
        return Stats.clamp(45.0 + z * 20.0, 0.0, 100.0)
    }

    // ---------- Heart-rate zones & strain ----------
    fun maxHr(age: Int): Double = 208.0 - 0.7 * age // Tanaka

    /** The age estimate (Tanaka), unless this person has clearly reached a higher heart rate for a sustained spell. */
    fun maxHr(s: Settings): Double = maxOf(maxHr(s.age), s.observedMaxHr.toDouble())

    /** Zone 1..5 (or 0 below zone 1) from % of heart-rate reserve. */
    fun zone(bpm: Double, restingHr: Double, maxHr: Double): Int {
        val reserve = (maxHr - restingHr).coerceAtLeast(20.0)
        val pct = (bpm - restingHr) / reserve
        return when {
            pct >= 0.90 -> 5
            pct >= 0.80 -> 4
            pct >= 0.70 -> 3
            pct >= 0.60 -> 2
            pct >= 0.50 -> 1
            else -> 0
        }
    }

    val zoneNames = listOf("Warm-up", "Light", "Aerobic", "Hard", "Peak")

    /** Edwards TRIMP: minutes in each zone weighted 1..5. */
    fun trimp(zoneMinutes: List<Double>): Double = zoneMinutes.mapIndexed { i, m -> m * (i + 1) }.sum()

    const val DEFAULT_TRIMP_CEILING = 2400.0
    private val TRIMP_CEIL = ln(1.0 + DEFAULT_TRIMP_CEILING)

    /**
     * Logarithmic map so the first effort counts for more than the last. 0..100. [ceiling] is the load that reads 100:
     * the default suits most people, and [personalCeiling] scales it to someone's own hard days.
     */
    fun strainFromTrimp(t: Double, ceiling: Double = DEFAULT_TRIMP_CEILING): Double =
        (100.0 * ln(1.0 + t.coerceAtLeast(0.0)) / ln(1.0 + ceiling)).coerceIn(0.0, 100.0)
    fun trimpFromStrain(s: Double): Double = exp((s.coerceIn(0.0, 100.0) / 100.0) * TRIMP_CEIL) - 1.0

    /**
     * Banister's TRIMP for one minute at [hr]: a smooth curve of how hard the heart is working (share of heart-rate
     * reserve), instead of five steps, so 69% and 71% of reserve are no longer a jump. Below 30% of reserve (ordinary
     * daily life) it counts nothing, so a desk day is not a workout. Scaled to sit on the same scale as Edwards zones.
     */
    fun trimpMinute(hr: Double, restingHr: Double, maxHr: Double, female: Boolean): Double {
        val x = ((hr - restingHr) / (maxHr - restingHr).coerceAtLeast(20.0)).coerceIn(0.0, 1.0)
        if (x < 0.30) return 0.0
        val w = if (female) 0.86 * x * exp(1.67 * x) else 0.64 * x * exp(1.92 * x)
        return w * BANISTER_SCALE
    }

    private const val BANISTER_SCALE = 1.4

    /**
     * A strain scale that means "hard for you": the load at the person's 95th-percentile day maps to about 85. Needs a
     * fortnight of loaded days, otherwise the default. Kept within sensible bounds so one huge day cannot stretch it.
     */
    fun personalCeiling(recentDailyTrimp: List<Double>): Double {
        val v = recentDailyTrimp.filter { it > 0 }
        if (v.size < 14) return DEFAULT_TRIMP_CEILING
        val sorted = v.sorted()
        val p95 = sorted[((sorted.size - 1) * 0.95).roundToInt()]
        return (Math.pow(1.0 + p95, 1.0 / 0.85) - 1.0).coerceIn(1200.0, 4000.0)
    }

    /** Training load of a logged strength session, from its length and how hard it felt (1 to 10). */
    fun strengthTrimp(durationMin: Int, rpe: Int): Double = durationMin * (0.25 + 0.25 * rpe.coerceIn(1, 10))

    /** Keytel et al. (2005) energy expenditure, kcal per minute. */
    fun kcalPerMin(bpm: Double, s: Settings): Double {
        val v = if (s.female) (-20.4022 + 0.4472 * bpm - 0.1263 * s.weightKg + 0.074 * s.age) / 4.184
        else (-55.0969 + 0.6309 * bpm + 0.1988 * s.weightKg + 0.2017 * s.age) / 4.184
        return v.coerceAtLeast(0.0)
    }

    /** Steps contribute a little background load so a desk day isn't a flat zero. */
    fun trimpFromSteps(steps: Double): Double = steps * 0.0015

    // ---------- Wording ----------
    enum class Band { LOW, MID, HIGH }

    fun recoveryBand(v: Double) = when { v >= 67 -> Band.HIGH; v >= 34 -> Band.MID; else -> Band.LOW }

    fun recoveryWord(v: Double) = when (recoveryBand(v)) { Band.HIGH -> "Primed"; Band.MID -> "Steady"; Band.LOW -> "Recharge" }

    fun sleepWord(v: Double) = when { v >= 85 -> "Excellent"; v >= 70 -> "Good"; v >= 50 -> "Fair"; else -> "Short" }

    fun strainWord(v: Double) = when { v >= 75 -> "All-out"; v >= 55 -> "High"; v >= 30 -> "Moderate"; v > 0 -> "Light"; else -> "Rest" }

    fun stressWord(v: Double) = when { v >= 65 -> "High"; v >= 35 -> "Moderate"; else -> "Low" }

    /** Target strain window for today given recovery. */
    fun strainTarget(recovery: Double): IntRange = when (recoveryBand(recovery)) {
        Band.HIGH -> 55..80
        Band.MID -> 30..55
        Band.LOW -> 5..30
    }

    fun hm(minutes: Double): String {
        val m = minutes.roundToInt().coerceAtLeast(0)
        return if (m >= 60) "${m / 60}h ${"%02d".format(m % 60)}m" else "${m}m"
    }
}
