package app.loop.analytics

import app.loop.model.DayRecord
import kotlin.math.ln
import kotlin.math.roundToInt

/** How much sleep Loop suggests as the person's need, and why. */
class SleepAdvice(
    val needMin: Int,
    /** True when it comes from the person's own nights rather than the age guideline. */
    val personal: Boolean,
    val guidelineMin: Int, val guidelineMax: Int,
    val nightsUsed: Int,
    val basis: String,
)

/** Tonight's target: the base need, plus a little extra after a hard day or when sleep debt has built up. */
class TonightAdvice(val targetMin: Int, val baseMin: Int, val reasons: List<String>)

/**
 * Recommends a sleep need instead of leaving the person to guess. It starts from the published age guideline, then,
 * once there are enough nights, moves to the length after which this person's own heart-rate variability and resting
 * heart rate are best. Tonight's target adds a small allowance for a hard day and for sleep debt.
 * A suggestion, not medical advice.
 */
object SleepCoach {
    private const val MIN_NIGHTS = 14
    private const val BUCKET_MIN_NIGHTS = 4

    /** Recommended range in minutes for adults and children, from the usual consensus guidance (National Sleep Foundation). */
    fun guideline(age: Int): IntRange = when {
        age < 14 -> 540..660
        age < 18 -> 480..600
        age < 65 -> 420..540
        else -> 420..480
    }

    // Bucket edges in hours, with the length each stands for.
    private val buckets = listOf(0.0 to 330, 6.0 to 390, 7.0 to 450, 8.0 to 510, 9.0 to 570)

    fun recommend(age: Int, days: List<DayRecord>): SleepAdvice {
        val g = guideline(age)
        val mid = roundTo15((g.first + g.last) / 2.0)
        val personal = personalNeed(days, g)
        val fmtG = "${g.first / 60}–${g.last / 60} hours"
        return if (personal != null) SleepAdvice(
            personal.first, true, g.first, g.last, personal.second,
            "From your last ${personal.second} nights: you recover best after about ${hm(personal.first)}. The guideline for your age is $fmtG.",
        ) else SleepAdvice(
            mid, false, g.first, g.last, 0,
            "The guideline for your age is $fmtG. Loop will fine-tune this to you after about two weeks of nights.",
        )
    }

    /** The length of sleep after which the person's own numbers are best, or null when the data does not clearly say. */
    private fun personalNeed(days: List<DayRecord>, g: IntRange): Pair<Int, Int>? {
        val nights = days.filter { (it.sleepMin ?: 0.0) >= 240 && it.hrv != null && it.hrv > 0 }
        if (nights.size < MIN_NIGHTS) return null
        val lnH = nights.map { ln(it.hrv!!) }
        val mh = lnH.average(); val sh = (Stats.std(lnH) ?: return null).takeIf { it > 1e-6 } ?: return null
        val rhrs = nights.mapNotNull { it.rhr }
        val mr = rhrs.takeIf { it.size >= MIN_NIGHTS }?.average(); val sr = rhrs.takeIf { it.size >= MIN_NIGHTS }?.let { Stats.std(it) }?.takeIf { it > 1e-6 }
        // A night's score: how high its HRV was, and how low its resting heart rate, each against the person's own average.
        val scored = nights.mapIndexed { i, n ->
            val zh = (lnH[i] - mh) / sh
            val zr = if (mr != null && sr != null && n.rhr != null) -(n.rhr - mr) / sr else null
            n.sleepMin!! to (if (zr != null) (zh + zr) / 2 else zh)
        }
        val overall = scored.map { it.second }.average()
        val byBucket = buckets.mapNotNull { (lo, mid) ->
            val hi = buckets.firstOrNull { it.first > lo }?.first ?: 99.0
            val inB = scored.filter { it.first / 60.0 >= lo && it.first / 60.0 < hi }
            if (inB.size < BUCKET_MIN_NIGHTS) null else Triple(mid, inB.map { it.second }.average(), inB.size)
        }
        if (byBucket.size < 2) return null
        val best = byBucket.maxByOrNull { it.second }!!; val worst = byBucket.minByOrNull { it.second }!!
        // Only trust it when one length is clearly better than the person's average and than their worst.
        if (best.second - overall < 0.25 || best.second - worst.second < 0.4) return null
        val lo = maxOf(360, g.first - 30); val hi = g.last + 45
        return best.first.coerceIn(lo, hi) to nights.size
    }

    /** Tonight's target. [latest] is the most recent scored day. */
    fun tonight(baseMin: Int, latest: DayRecord?): TonightAdvice {
        val reasons = ArrayList<String>()
        var extra = 0
        val strain = latest?.strain
        if (strain != null && strain >= 75) { extra += 30; reasons += "30 minutes more after a very hard day (strain ${strain.roundToInt()})" }
        else if (strain != null && strain >= 55) { extra += 15; reasons += "15 minutes more after a hard day (strain ${strain.roundToInt()})" }
        val debt = latest?.sleepDebtMin
        if (debt != null && debt >= 90) {
            val back = minOf(45.0, debt * 0.25)
            extra += roundTo15(back)
            reasons += "${roundTo15(back)} minutes towards paying back about ${hm(debt.roundToInt())} of sleep debt"
        }
        if (latest?.recovery != null && latest.recovery < 34) { extra += 15; reasons += "15 minutes more because your body is under-recovered" }
        extra = extra.coerceAtMost(60)
        return TonightAdvice(baseMin + extra, baseMin, reasons)
    }

    fun roundTo15(v: Double) = ((v / 15.0).roundToInt() * 15)
    fun roundTo15(v: Int) = roundTo15(v.toDouble())
    fun hm(min: Int) = "${min / 60}h ${"%02d".format(min % 60)}m"
}
