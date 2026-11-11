package app.loop.analytics

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/*
 * A wrist strap gives beat-to-beat (R-R) timing, and the REGULARITY of that timing is what an irregular-rhythm screen
 * looks at. [RhythmCheck] scores it overnight. It is a prompt to see a clinician, never a diagnosis.
 * Every threshold is a plain heuristic, unit-tested on synthetic data only.
 */

/** Regularity of a run of R-R intervals. */
enum class RhythmLevel(val label: String) {
    REGULAR("Regular"), MIXED("Some irregularity"), IRREGULAR("Irregular"), UNKNOWN("Not enough clean beats")
}

class RhythmResult(
    val level: RhythmLevel,
    /** Clean beats the verdict rests on. */
    val beats: Int,
    /** RMSSD divided by mean R-R. Big beat-to-beat swings relative to the heart period. */
    val nRmssd: Double,
    /** Share of points that are local peaks or troughs. ~0.67 for random timing, far lower for smooth sinus rhythm. */
    val turningPoints: Double,
    /** Spread of successive differences, 0 (all alike) to 1 (evenly spread). */
    val entropy: Double,
    /** How many of the three signs pointed to irregularity. */
    val votes: Int,
)

object RhythmCheck {
    const val MIN_BEATS = 100
    private const val BIN_MS = 50
    private const val BINS = 16 // covers -400..+400 ms in 50 ms steps; larger jumps land in the end bins

    // The three signs, after the irregular-rhythm screens that use R-R statistics (RMSSD/mean, turning points, Shannon entropy).
    const val NRMSSD_THRESHOLD = 0.12
    const val TPR_THRESHOLD = 0.60
    const val ENTROPY_THRESHOLD = 0.55

    fun analyze(rr: List<Int>): RhythmResult {
        val clean = rr.filter { it in 300..2000 }
        if (clean.size < MIN_BEATS) return RhythmResult(RhythmLevel.UNKNOWN, clean.size, 0.0, 0.0, 0.0, 0)

        val diffs = IntArray(clean.size - 1) { clean[it + 1] - clean[it] }
        val meanRr = clean.average()
        val rmssd = sqrt(diffs.sumOf { it.toDouble() * it } / diffs.size)
        val nRmssd = rmssd / meanRr

        var turns = 0
        for (i in 1 until clean.size - 1) {
            val a = clean[i - 1]; val b = clean[i]; val c = clean[i + 1]
            if ((b > a && b > c) || (b < a && b < c)) turns++
        }
        val tpr = turns.toDouble() / (clean.size - 2)

        val hist = IntArray(BINS)
        for (d in diffs) hist[((d + BINS / 2 * BIN_MS).floorDiv(BIN_MS)).coerceIn(0, BINS - 1)]++
        var h = 0.0
        for (n in hist) if (n > 0) { val p = n.toDouble() / diffs.size; h -= p * ln(p) }
        val entropy = h / ln(BINS.toDouble())

        val votes = (if (nRmssd > NRMSSD_THRESHOLD) 1 else 0) + (if (tpr > TPR_THRESHOLD) 1 else 0) + (if (entropy > ENTROPY_THRESHOLD) 1 else 0)
        val level = when {
            votes >= 3 -> RhythmLevel.IRREGULAR
            votes == 2 -> RhythmLevel.MIXED
            else -> RhythmLevel.REGULAR
        }
        return RhythmResult(level, clean.size, nRmssd, tpr, entropy, votes)
    }

    /** True when a stretch looks irregular enough to count towards the overnight irregularity share. */
    fun isIrregular(rr: List<Int>): Boolean? = analyze(rr).let { if (it.level == RhythmLevel.UNKNOWN) null else it.level == RhythmLevel.IRREGULAR }

    /** Percentage of evaluated stretches that were irregular. Null when too few stretches could be judged. */
    fun irregularShare(stretches: List<List<Int>>, minJudged: Int = 6): Double? {
        val judged = stretches.mapNotNull { isIrregular(it) }
        if (judged.size < minJudged) return null
        return 100.0 * judged.count { it } / judged.size
    }

    fun adviceFor(pctIrregular: Double): String = when {
        pctIrregular < 5 -> "Your overnight heartbeat timing was regular. Nothing to act on."
        pctIrregular < 20 -> "A small share of the night had uneven beat timing. That is common (movement, ectopic beats, a noisy signal). Watch whether it repeats."
        else -> "A large share of the night had irregular beat timing. This is not a diagnosis. If it repeats, or comes with palpitations, dizziness or breathlessness, see a clinician."
    }
}
