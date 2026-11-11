package app.loop.analytics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real-time heart-rate-variability estimates from a short window of beat-to-beat (R-R) intervals.
 *
 * Everything here is an ESTIMATE. Short windows and a moving wearer make these rougher than the overnight values,
 * which is why the app labels them as estimates and shows the sleep-based numbers beside them when it has them.
 */
object LiveHrv {
    class TimeDomain(val rmssd: Double, val sdnn: Double, val pnn50: Double, val meanRr: Double, val beats: Int)

    /** RMSSD, SDNN and pNN50 over clean intervals. Intervals outside 300 to 2200 ms or jumping more than 20% are dropped. */
    fun timeDomain(rr: List<Int>): TimeDomain? {
        val clean = ArrayList<Int>()
        for (v in rr) if (v in 300..2200) {
            val prev = clean.lastOrNull()
            if (prev == null || abs(v - prev).toDouble() / prev <= 0.20) clean += v
        }
        if (clean.size < 20) return null
        val mean = clean.average()
        val sdnn = sqrt(clean.sumOf { (it - mean) * (it - mean) } / (clean.size - 1))
        var sq = 0.0; var over50 = 0
        for (i in 1 until clean.size) { val d = (clean[i] - clean[i - 1]).toDouble(); sq += d * d; if (abs(d) > 50) over50++ }
        return TimeDomain(sqrt(sq / (clean.size - 1)), sdnn, 100.0 * over50 / (clean.size - 1), mean, clean.size)
    }

    class Spectral(
        val breathRate: Double,      // breaths per minute
        val breathClarity: Double,   // 0..1, share of respiratory-band power at the peak
        val lfHf: Double?,           // low/high frequency power ratio (autonomic balance)
        val coherence: Double,       // 0..100, how single-toned the slow heart rhythm is
    )

    /** Frequency-domain estimates. Needs about 100 seconds of intervals or more. */
    fun spectral(rr: List<Int>): Spectral? {
        val clean = rr.filter { it in 300..2200 }
        if (clean.size < 100) return null
        val t = DoubleArray(clean.size); var acc = 0.0
        for (i in clean.indices) { acc += clean[i] / 1000.0; t[i] = acc }
        val dur = t.last() - t.first()
        if (dur < 100) return null
        val fs = 4.0; val n = (dur * fs).toInt()
        val x = DoubleArray(n); var p = 0
        for (k in 0 until n) {
            val tt = t[0] + k / fs
            while (p < t.size - 2 && t[p + 1] < tt) p++
            val f = ((tt - t[p]) / (t[p + 1] - t[p])).coerceIn(0.0, 1.0)
            x[k] = clean[p] * (1 - f) + clean[p + 1] * f
        }
        val mean = x.average()
        for (k in 0 until n) x[k] = (x[k] - mean) * (0.5 - 0.5 * cos(2 * PI * k / (n - 1)))

        val step = 0.005; val f0 = 0.04
        val bins = ((0.50 - f0) / step).toInt() + 1
        val freq = DoubleArray(bins) { f0 + it * step }
        val pw = DoubleArray(bins)
        for (b in 0 until bins) {
            val w = 2 * PI * freq[b] / fs
            var re = 0.0; var im = 0.0
            for (k in 0 until n) { re += x[k] * cos(w * k); im -= x[k] * sin(w * k) }
            pw[b] = re * re + im * im
        }
        fun band(lo: Double, hi: Double) = (0 until bins).filter { freq[it] in lo..hi }
        fun sum(idx: List<Int>) = idx.sumOf { pw[it] }

        val resp = band(0.10, 0.50)
        val respTotal = sum(resp)
        if (respTotal <= 0) return null
        val pk = resp.maxByOrNull { pw[it] } ?: return null
        val breathShare = (max(resp.first(), pk - 3)..min(resp.last(), pk + 3)).sumOf { pw[it] } / respTotal

        val lf = sum(band(0.04, 0.15)); val hf = sum(band(0.15, 0.40))
        val lfHf = if (hf > 0) (lf / hf).coerceIn(0.1, 10.0) else null

        val slow = band(0.04, 0.26)
        val slowTotal = sum(slow)
        val sp = slow.maxByOrNull { pw[it] } ?: return null
        val coherence = if (slowTotal > 0) 100.0 * (max(slow.first(), sp - 3)..min(slow.last(), sp + 3)).sumOf { pw[it] } / slowTotal else 0.0

        return Spectral(freq[pk] * 60, breathShare, lfHf, coherence)
    }

    /** Heart-rate trend over a window of per-second samples, in bpm per minute (positive = rising). */
    fun trend(hr: List<Int>): Double? {
        if (hr.size < 30) return null
        val k = min(10, hr.size / 3)
        val first = hr.take(k).average(); val last = hr.takeLast(k).average()
        return (last - first) / (hr.size - k) * 60.0
    }
}
