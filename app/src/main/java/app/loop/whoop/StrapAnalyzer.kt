package app.loop.whoop

import app.loop.analytics.RhythmCheck
import app.loop.analytics.Scoring
import app.loop.analytics.Stats
import app.loop.model.DayRecord
import app.loop.model.Settings
import app.loop.model.Workout
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns raw 1 Hz strap history into the numbers Loop shows: sleep (with stage estimates), overnight HRV,
 * resting heart rate, breathing rate, skin-temperature drift, daily strain and calories, and automatically
 * detected workouts.
 *
 * All of it is a transparent heuristic computed on the phone. Sleep staging in particular is an ESTIMATE
 * from heart rate, heart-rate variability and wrist movement. It is not polysomnography.
 */
object StrapAnalyzer {
    class Output(val days: List<DayRecord>, val workouts: List<Workout>, val nights: Int, val observedMaxHr: Int = 0)

    private class Minute(val min: Long, val hr: Double, val motion: Double, val rr: List<Int>, val skin: Double?, val skinRaw: Double? = null)

    private const val MIN_SAMPLES = 5

    fun analyze(records: List<HistRecord>, s: Settings, restingFallback: Double?, zone: ZoneId = ZoneId.systemDefault()): Output {
        if (records.isEmpty()) return Output(emptyList(), emptyList(), 0)
        val minutes = buildMinutes(records)
        if (minutes.isEmpty()) return Output(emptyList(), emptyList(), 0)

        fun localDate(min: Long): LocalDate = Instant.ofEpochSecond(min * 60).atZone(zone).toLocalDate()
        fun localMinuteOfDay(min: Long): Int = Instant.ofEpochSecond(min * 60).atZone(zone).let { it.hour * 60 + it.minute }

        val days = HashMap<String, DayRecord>()
        fun upd(day: String, f: (DayRecord) -> DayRecord) { days[day] = f(days[day] ?: DayRecord(day)) }

        // ---- sleep: one night per 18:00 -> 14:00 window ----
        val windows = minutes.groupBy { m ->
            val shifted = Instant.ofEpochSecond(m.min * 60).atZone(zone).minusHours(14) // 14:00 -> 00:00 of the wake date's "previous" day boundary
            shifted.toLocalDate().plusDays(1) // wake date
        }
        class Night(val wake: LocalDate, val startMin: Long, val endMin: Long, val rhr: Double?, val skin: Double?, val skinRaw: Double?)
        val nights = ArrayList<Night>()
        val sleepRanges = ArrayList<LongRange>()
        for ((wake, ms) in windows.toSortedMap()) {
            val inWindow = ms.filter { val md = localMinuteOfDay(it.min); md >= 18 * 60 || md < 14 * 60 }
            if (inWindow.size < 240) continue
            val res = detectNight(inWindow) ?: continue
            sleepRanges += res.startMin..res.endMin
            nights += Night(wake, res.startMin, res.endMin, res.rhr, res.skin, res.skinRaw)
            upd(wake.toString()) {
                it.copy(
                    hrv = res.hrv, rhr = res.rhr, resp = res.resp, rhythmIrreg = res.irregPct, sleepMin = res.asleepMin, sleepEff = res.efficiency,
                    deepMin = res.deepMin, remMin = res.remMin, lightMin = res.lightMin, awakeMin = res.awakeMin,
                )
            }
        }
        // skin temperature as deviation from the previous nights' median
        val skins = nights.mapNotNull { n -> n.skin?.let { n.wake to it } }
        for ((i, p) in skins.withIndex()) {
            val prev = skins.subList(max(0, i - 14), i).map { it.second }
            if (prev.size >= 3) upd(p.first.toString()) { it.copy(skinTempDev = p.second - (Stats.median(prev) ?: p.second)) }
        }

        // WHOOP 4.0 only supplies an uncalibrated sensor value, so report a relative shift in standard deviations.
        val raws = nights.filter { it.skin == null && it.skinRaw != null }.map { it.wake to it.skinRaw!! }
        for ((i, p) in raws.withIndex()) {
            val prev = raws.subList(max(0, i - 14), i).map { it.second }
            val sd = Stats.std(prev)
            if (prev.size >= 5 && sd != null && sd > 1e-6) upd(p.first.toString()) { it.copy(skinIdx = (p.second - prev.average()) / sd) }
        }

        // ---- daily strain, calories, workouts ----
        val rhrByDay = nights.associate { it.wake to it.rhr }
        val globalRhr = Stats.median(nights.map { it.rhr }) ?: restingFallback ?: 60.0
        val observedMax = minutes.map { it.hr }.sortedDescending().getOrNull(9)?.takeIf { it in 100.0..215.0 }?.roundToInt() ?: 0
        val maxHr = maxOf(Scoring.maxHr(s), observedMax.toDouble())
        val workouts = ArrayList<Workout>()
        for ((date, ms) in minutes.groupBy { localDate(it.min) }.toSortedMap()) {
            val rhr = rhrByDay[date] ?: globalRhr
            var kcal = 0.0
            var load = 0.0
            for (m in ms) {
                if (Scoring.zone(m.hr, rhr, maxHr) >= 1) kcal += Scoring.kcalPerMin(m.hr, s)
                load += Scoring.trimpMinute(m.hr, rhr, maxHr, s.female)
            }
            // The day's load is stored as TRIMP; strain is worked out from it later, on the person's own scale.
            if (ms.size >= 60) upd(date.toString()) { it.copy(trimp = load, strainRaw = null, kcal = kcal) }
            workouts += detectWorkouts(ms, rhr, maxHr, sleepRanges, s)
        }
        // ---- steps from the strap's pedometer counter ----
        for ((date, n) in stepsByDay(records, zone)) if (n > 0) upd(date.toString()) { it.copy(steps = n.toDouble()) }

        // ---- wear time ----
        for ((date, ms) in minutes.groupBy { localDate(it.min) }) upd(date.toString()) { it.copy(wearMin = ms.size.toDouble()) }

        // ---- daytime stress ----
        val wins = stressWindows(minutes, sleepRanges)
        for ((date, ws) in wins.groupBy { localDate(it.startMin) }) {
            if (ws.size < 6) continue
            upd(date.toString()) { it.copy(dayStress = ws.map { w -> w.stress }.average(), stressHighMin = 5.0 * ws.count { w -> w.stress >= 65 }) }
        }

        // ---- heart-rate recovery after detected workouts ----
        val byMinAll = minutes.associateBy { it.min }
        for ((date, ws) in workouts.groupBy { localDate(it.id / 60) }) {
            val drops = ws.filter { (it.avgHr ?: 0) >= 110 }.mapNotNull { w ->
                val end = w.id / 60 + w.durationMin.toLong() - 1
                val peak = listOfNotNull(byMinAll[end]?.hr, byMinAll[end - 1]?.hr).maxOrNull()
                val after = byMinAll[end + 1]?.hr
                if (peak != null && after != null) (peak - after).takeIf { it in 0.0..80.0 } else null
            }
            if (drops.isNotEmpty()) upd(date.toString()) { it.copy(hrr = drops.max()) }
        }
        return Output(days.values.sortedBy { it.day }, workouts, nights.size, observedMax)
    }

    // ------------------------------------------------------------------ minutes
    private fun buildMinutes(records: List<HistRecord>): List<Minute> {
        val out = ArrayList<Minute>()
        var i = 0
        while (i < records.size) {
            val key = records[i].ts / 60
            var j = i
            while (j < records.size && records[j].ts / 60 == key) j++
            val seg = records.subList(i, j)
            i = j
            val valid = seg.filter { it.hr in 35..220 && it.contact != 0 }
            if (valid.size < MIN_SAMPLES) continue
            var mot = 0.0; var cnt = 0
            for (k in 1 until seg.size) {
                val a = seg[k - 1]; val b = seg[k]
                if (b.ts - a.ts > 3) continue
                mot += sqrt(((b.gx - a.gx) * (b.gx - a.gx) + (b.gy - a.gy) * (b.gy - a.gy) + (b.gz - a.gz) * (b.gz - a.gz)).toDouble()); cnt++
            }
            val skin = seg.mapNotNull { it.skinC?.toDouble() }.takeIf { it.isNotEmpty() }?.average()
            val skinRaw = seg.mapNotNull { it.skinRaw?.toDouble() }.takeIf { it.isNotEmpty() }?.average()
            out += Minute(key, valid.map { it.hr }.average(), if (cnt > 0) mot / cnt else 0.0, valid.flatMap { it.rr.toList() }, skin, skinRaw)
        }
        return out
    }

    // -------------------------------------------------------------------- sleep
    private class Sleep(
        val startMin: Long, val endMin: Long, val asleepMin: Double, val awakeMin: Double, val efficiency: Double,
        val deepMin: Double, val remMin: Double, val lightMin: Double, val hrv: Double?, val rhr: Double?, val resp: Double?, val skin: Double?, val skinRaw: Double? = null,
        val irregPct: Double? = null,
    )

    private fun percentile(v: List<Double>, p: Double): Double {
        val s = v.sorted(); return s[((s.size - 1) * p).roundToInt().coerceIn(0, s.size - 1)]
    }

    private fun detectNight(ms: List<Minute>): Sleep? {
        val motionThr = percentile(ms.map { it.motion }, 0.30)
        val hrThr = percentile(ms.map { it.hr }, 0.55)
        val byMin = ms.associateBy { it.min }
        val first = ms.first().min; val last = ms.last().min
        // candidate: smoothed (+-4 min) movement and heart rate are both low
        val cand = HashSet<Long>()
        for (m in ms) {
            var mo = 0.0; var hr = 0.0; var n = 0
            for (d in -4..4) byMin[m.min + d]?.let { mo += it.motion; hr += it.hr; n++ }
            if (n >= 4 && mo / n <= max(motionThr, 0.002) && hr / n <= hrThr) cand += m.min
        }
        if (cand.size < 150) return null
        // merge candidate minutes into blocks, bridging wake bouts of up to 15 minutes
        val sorted = cand.sorted()
        var bs = sorted[0]; var be = sorted[0]
        var bestS = 0L; var bestE = -1L; var bestCount = 0
        var count = 1
        fun close() { if (be - bs + 1 >= 180 && count > bestCount) { bestS = bs; bestE = be; bestCount = count } }
        for (k in 1 until sorted.size) {
            if (sorted[k] - be <= 15) { be = sorted[k]; count++ } else { close(); bs = sorted[k]; be = sorted[k]; count = 1 }
        }
        close()
        if (bestE < 0 || bestCount < 150) return null

        val inBed = bestE - bestS + 1
        val asleep = bestCount.toDouble()
        val awake = inBed - asleep

        // 5-minute epochs for staging and HRV
        class Ep(val hr: Double, val hrStd: Double, val motion: Double, val rmssd: Double?, val resp: Double?, val t: Double)
        val eps = ArrayList<Ep>()
        val rrStretches = ArrayList<List<Int>>() // every 5-minute stretch's R-R, for the overnight rhythm-regularity share
        var s = bestS
        while (s + 4 <= bestE) {
            val win = (0 until 5).mapNotNull { byMin[s + it] }
            val asleepFrac = (0 until 5).count { (s + it) in cand } / 5.0
            if (win.size >= 4 && asleepFrac >= 0.6) {
                val hrs = win.map { it.hr }
                val rr = win.flatMap { it.rr }
                rrStretches += rr
                eps += Ep(hrs.average(), Stats.std(hrs) ?: 0.0, win.map { it.motion }.average(), rmssd(rr), respRate(rr), (s - bestS).toDouble() / inBed)
            }
            s += 5
        }
        if (eps.size < 6) return null

        fun z(v: List<Double>): List<Double> { val m = v.average(); val sd = (Stats.std(v) ?: 1.0).takeIf { it > 1e-9 } ?: 1.0; return v.map { (it - m) / sd } }
        val zHr = z(eps.map { it.hr }); val zStd = z(eps.map { it.hrStd })
        val rmssdFilled = eps.map { it.rmssd }.let { l -> val med = Stats.median(l) ?: 0.0; l.map { it ?: med } }
        val zRm = z(rmssdFilled)
        val deepScore = eps.indices.map { zRm[it] - zHr[it] - 0.9 * eps[it].t }
        val remScore = eps.indices.map { zHr[it] + zStd[it] + 0.9 * eps[it].t - 0.5 * zRm[it] }
        val motionMed = Stats.median(eps.map { it.motion }) ?: 0.0
        val deepIdx = eps.indices.filter { eps[it].motion <= motionMed }.sortedByDescending { deepScore[it] }.take((eps.size * 0.18).roundToInt()).toSet()
        val remIdx = eps.indices.filter { it !in deepIdx }.sortedByDescending { remScore[it] }.take((eps.size * 0.22).roundToInt()).toSet()
        val n = eps.size.toDouble()
        val deep = asleep * deepIdx.size / n; val rem = asleep * remIdx.size / n; val light = asleep - deep - rem

        val rm = eps.mapNotNull { it.rmssd }
        val lowHr = eps.map { it.hr }.sorted().take(max(3, eps.size / 4))
        val resp = Stats.median(eps.map { it.resp })?.takeIf { it in 8.0..26.0 }
        val skin = (bestS..bestE).mapNotNull { byMin[it]?.skin }.takeIf { it.size > 30 }?.average()
        val skinRaw = (bestS..bestE).mapNotNull { byMin[it]?.skinRaw }.takeIf { it.size > 30 }?.average()
        return Sleep(
            bestS, bestE, asleep, awake, (asleep / inBed * 100).coerceIn(0.0, 100.0), deep, rem, light,
            Stats.median(rm)?.takeIf { rm.size >= 3 }, lowHr.average().takeIf { lowHr.isNotEmpty() }?.roundToInt()?.toDouble(), resp, skin, skinRaw,
            RhythmCheck.irregularShare(rrStretches),
        )
    }

    // ---------------------------------------------------------- HRV / breathing
    /** RMSSD over a run of R-R intervals (ms), ignoring artefacts. Needs enough clean beats to mean anything. */
    fun rmssd(rr: List<Int>): Double? {
        var sum = 0.0; var n = 0
        for (i in 1 until rr.size) {
            val a = rr[i - 1]; val b = rr[i]
            if (a !in 300..2200 || b !in 300..2200) continue
            if (abs(b - a) > 0.2 * a) continue
            sum += (b - a).toDouble() * (b - a); n++
        }
        return if (n >= 40) sqrt(sum / n) else null
    }

    /** Breaths per minute from respiratory sinus arrhythmia: dominant 0.1 to 0.5 Hz component of the R-R series. */
    fun respRate(rr: List<Int>): Double? = respEstimate(rr)?.first

    /**
     * Breathing rate plus how clear it is. The second value is the share of respiratory-band power sitting at the
     * dominant peak (0..1): a clean breathing rhythm is a sharp peak, noise is spread out. Callers should ignore low shares.
     */
    fun respEstimate(rr: List<Int>): Pair<Double, Double>? {
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
        val freqs = ArrayList<Double>(); val pw = ArrayList<Double>()
        var f = 0.10
        while (f <= 0.50) {
            var re = 0.0; var im = 0.0
            val w = 2 * PI * f / fs
            for (k in 0 until n) { re += x[k] * cos(w * k); im -= x[k] * sin(w * k) }
            freqs += f; pw += re * re + im * im
            f += 0.005
        }
        val total = pw.sum()
        if (total <= 0) return null
        val best = pw.indices.maxByOrNull { pw[it] } ?: return null
        val share = (max(0, best - 3)..min(pw.size - 1, best + 3)).sumOf { pw[it] } / total
        return freqs[best] * 60 to share
    }

    // ----------------------------------------------------------------- workouts
    private fun detectWorkouts(ms: List<Minute>, rhr: Double, maxHr: Double, sleep: List<LongRange>, s: Settings): List<Workout> {
        val out = ArrayList<Workout>()
        val active = ms.filter { Scoring.zone(it.hr, rhr, maxHr) >= 1 && sleep.none { r -> it.min in r } }
        if (active.isEmpty()) return out
        var seg = ArrayList<Minute>().also { it += active[0] }
        fun flush() {
            val span = seg.last().min - seg.first().min + 1
            if (span >= 10 && seg.size >= 8) {
                val zm = DoubleArray(5); var kcal = 0.0; var load = 0.0
                for (m in seg) { val z = Scoring.zone(m.hr, rhr, maxHr); if (z >= 1) zm[z - 1] += 1.0; kcal += Scoring.kcalPerMin(m.hr, s); load += Scoring.trimpMinute(m.hr, rhr, maxHr, s.female) }
                out += Workout(
                    id = seg.first().min * 60, sport = "Workout", durationMin = span.toDouble(),
                    avgHr = seg.map { it.hr }.average().roundToInt(), maxHr = seg.maxOf { it.hr }.roundToInt(),
                    strain = Scoring.strainFromTrimp(load), kcal = kcal, zoneMin = zm.toList(), source = "strap-auto",
                )
            }
        }
        for (k in 1 until active.size) {
            if (active[k].min - seg.last().min <= 3) seg.add(active[k]) else { flush(); seg = ArrayList<Minute>().also { it += active[k] } }
        }
        flush()
        return out
    }

    // ------------------------------------------------------------------- steps
    /**
     * Steps from the strap's pedometer counter: positive changes between consecutive records, with u16 wrap-around
     * handled. Gaps longer than five minutes and implausible jumps are ignored rather than guessed. The counter is not
     * documented as equal to WHOOP's own step total, so treat this as an estimate.
     */
    fun stepsByDay(records: List<HistRecord>, zone: ZoneId = ZoneId.systemDefault()): Map<LocalDate, Int> {
        val out = HashMap<LocalDate, Int>()
        var prev: HistRecord? = null
        for (r in records) {
            val c = r.steps ?: continue
            val p = prev
            val ps = p?.steps
            if (p != null && ps != null) {
                val dt = r.ts - p.ts
                if (dt in 1..300) {
                    var d = c - ps
                    if (d < 0) d = if (ps > 60000 && c < 5000) d + 65536 else 0
                    if (d in 1..(5 * dt + 30).toInt()) out.merge(Instant.ofEpochSecond(r.ts).atZone(zone).toLocalDate(), d, Int::plus)
                }
            }
            prev = r
        }
        return out
    }

    // ------------------------------------------------------------------ stress
    private class StressWindow(val startMin: Long, val stress: Double)

    /** A person's own daytime-at-rest norm for heart rate and (log) HRV. Live stress is measured against it. */
    class DaytimeNorm(val hrMed: Double, val hrMad: Double, val lnMed: Double, val lnMad: Double, val windows: Int)

    private class RestWindow(val start: Long, val hr: Double, val ln: Double)

    private fun restWindows(minutes: List<Minute>, sleep: List<LongRange>): List<RestWindow> {
        val awake = minutes.filter { m -> sleep.none { m.min in it } }
        if (awake.size < 60) return emptyList()
        val restThr = percentile(awake.map { it.motion }, 0.5)
        val byMin = awake.associateBy { it.min }
        val ws = ArrayList<RestWindow>()
        for (st in awake.map { it.min / 5 * 5 }.distinct()) {
            val win = (0 until 5).mapNotNull { byMin[st + it] }
            if (win.size < 4 || win.map { it.motion }.average() > max(restThr, 0.0015)) continue
            val r = rmssd(win.flatMap { it.rr }) ?: continue
            ws += RestWindow(st, win.map { it.hr }.average(), kotlin.math.ln(r))
        }
        return if (ws.size < 12) emptyList() else ws
    }

    private fun normOf(ws: List<RestWindow>): DaytimeNorm {
        fun med(v: List<Double>) = Stats.median(v) ?: 0.0
        fun mad(v: List<Double>, m: Double, floor: Double) = max(1.4826 * med(v.map { abs(it - m) }), floor)
        val hrM = med(ws.map { it.hr }); val lnM = med(ws.map { it.ln })
        return DaytimeNorm(hrM, mad(ws.map { it.hr }, hrM, 3.0), lnM, mad(ws.map { it.ln }, lnM, 0.15), ws.size)
    }

    /** 0..100 stress from heart rate and HRV against the personal norm: faster heart and lower HRV score higher. */
    fun stressScore(hr: Double, rmssd: Double, n: DaytimeNorm): Double {
        val z = 0.4 * ((hr - n.hrMed) / n.hrMad) - 0.6 * ((kotlin.math.ln(rmssd) - n.lnMed) / n.lnMad)
        return (50 + 18 * z).coerceIn(0.0, 100.0)
    }

    private fun stressWindows(minutes: List<Minute>, sleep: List<LongRange>): List<StressWindow> {
        val ws = restWindows(minutes, sleep)
        if (ws.isEmpty()) return emptyList()
        val n = normOf(ws)
        return ws.map { w -> StressWindow(w.start, stressScore(w.hr, kotlin.math.exp(w.ln), n)) }
    }

    private fun sleepRangesOf(minutes: List<Minute>, zone: ZoneId): List<LongRange> {
        val out = ArrayList<LongRange>()
        val windows = minutes.groupBy { m -> Instant.ofEpochSecond(m.min * 60).atZone(zone).minusHours(14).toLocalDate().plusDays(1) }
        for ((_, ms) in windows) {
            val inW = ms.filter { val md = Instant.ofEpochSecond(it.min * 60).atZone(zone).let { z -> z.hour * 60 + z.minute }; md >= 18 * 60 || md < 14 * 60 }
            if (inW.size >= 240) detectNight(inW)?.let { out += it.startMin..it.endMin }
        }
        return out
    }

    /** The personal daytime norm from saved history, or null until there is enough still, awake wear time. */
    fun daytimeNorm(records: List<HistRecord>, zone: ZoneId = ZoneId.systemDefault()): DaytimeNorm? {
        val minutes = buildMinutes(records)
        if (minutes.isEmpty()) return null
        val ws = restWindows(minutes, sleepRangesOf(minutes, zone))
        return if (ws.isEmpty()) null else normOf(ws)
    }

    /** (minute of local day, stress 0..100) for one date. Needs a few days of [records] for the personal norm. */
    fun stressTimeline(records: List<HistRecord>, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<Pair<Int, Double>> {
        val minutes = buildMinutes(records)
        if (minutes.isEmpty()) return emptyList()
        return stressWindows(minutes, sleepRangesOf(minutes, zone))
            .filter { Instant.ofEpochSecond(it.startMin * 60).atZone(zone).toLocalDate() == date }
            .map { Instant.ofEpochSecond(it.startMin * 60).atZone(zone).let { z -> z.hour * 60 + z.minute } to it.stress }
    }
}
