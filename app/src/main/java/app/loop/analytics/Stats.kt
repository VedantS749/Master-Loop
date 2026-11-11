package app.loop.analytics

import kotlin.math.abs
import kotlin.math.sqrt

/** Small, dependency-free statistics helpers. All functions ignore nulls / NaN. */
object Stats {
    fun mean(v: List<Double?>): Double? {
        val x = v.filterNotNull()
        return if (x.isEmpty()) null else x.sum() / x.size
    }

    fun std(v: List<Double?>): Double? {
        val x = v.filterNotNull()
        if (x.size < 2) return null
        val m = x.sum() / x.size
        return sqrt(x.sumOf { (it - m) * (it - m) } / (x.size - 1))
    }

    fun median(v: List<Double?>): Double? {
        val x = v.filterNotNull().sorted()
        if (x.isEmpty()) return null
        return if (x.size % 2 == 1) x[x.size / 2] else (x[x.size / 2 - 1] + x[x.size / 2]) / 2
    }

    fun min(v: List<Double?>): Double? = v.filterNotNull().minOrNull()
    fun max(v: List<Double?>): Double? = v.filterNotNull().maxOrNull()

    /** Pearson correlation of paired values (pairs with a null on either side are dropped). Null when < [minPairs]. */
    fun correlation(a: List<Double?>, b: List<Double?>, minPairs: Int = 10): Double? {
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        for (i in 0 until minOf(a.size, b.size)) {
            val x = a[i]; val y = b[i]
            if (x != null && y != null) { xs += x; ys += y }
        }
        if (xs.size < minPairs) return null
        val mx = xs.average(); val my = ys.average()
        var sxy = 0.0; var sxx = 0.0; var syy = 0.0
        for (i in xs.indices) {
            sxy += (xs[i] - mx) * (ys[i] - my); sxx += (xs[i] - mx) * (xs[i] - mx); syy += (ys[i] - my) * (ys[i] - my)
        }
        if (sxx == 0.0 || syy == 0.0) return null
        return sxy / sqrt(sxx * syy)
    }

    /** Least-squares slope per index step over the non-null points. */
    fun slope(v: List<Double?>): Double? {
        val pts = v.mapIndexedNotNull { i, d -> if (d == null) null else i.toDouble() to d }
        if (pts.size < 3) return null
        val mx = pts.sumOf { it.first } / pts.size
        val my = pts.sumOf { it.second } / pts.size
        val den = pts.sumOf { (it.first - mx) * (it.first - mx) }
        if (den == 0.0) return null
        return pts.sumOf { (it.first - mx) * (it.second - my) } / den
    }

    fun clamp(v: Double, lo: Double, hi: Double) = if (v < lo) lo else if (v > hi) hi else v

    /** Personal baseline: mean + a spread floored so tiny histories can't produce silly z-scores. */
    data class Baseline(val mean: Double, val sd: Double, val n: Int) {
        fun z(v: Double) = (v - mean) / sd
    }

    fun baseline(history: List<Double?>, minN: Int = 5, sdFloorFrac: Double = 0.06): Baseline? {
        val x = history.filterNotNull()
        if (x.size < minN) return null
        val m = x.sum() / x.size
        val s = std(x) ?: 0.0
        return Baseline(m, maxOf(s, abs(m) * sdFloorFrac, 1e-6), x.size)
    }
}
