package app.loop.data

import android.content.Context
import app.loop.model.DayRecord
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One logged minute of live daytime estimates. Null means "not measurable that minute". */
class DayLogRow(
    val ts: Long, val hr: Double, val steady: Boolean,
    val rmssd: Double?, val sdnn: Double?, val pnn50: Double?, val breath: Double?,
    val stress: Double?, val coherence: Double?, val lfHf: Double?, val kcal: Double,
)

/**
 * Append-only text log of live daytime metrics, one row per minute while the strap is connected. Kept apart from
 * the night-time data: these are estimates from live heartbeat timing, not the overnight scores.
 *
 * Line: unixSeconds,hr,steady(0/1),rmssd,sdnn,pnn50,breath,stress,coherence,lfhf,kcal   (blank = unavailable)
 */
class DaytimeStore(context: Context) {
    private val file = File(context.filesDir, "daytime_log.csv")
    private val lock = Any()

    fun append(r: DayLogRow) = synchronized(lock) {
        fun f(v: Double?) = v?.let { "%.2f".format(java.util.Locale.US, it) } ?: ""
        runCatching {
            file.appendText("${r.ts},${f(r.hr)},${if (r.steady) 1 else 0},${f(r.rmssd)},${f(r.sdnn)},${f(r.pnn50)},${f(r.breath)},${f(r.stress)},${f(r.coherence)},${f(r.lfHf)},${f(r.kcal)}\n")
        }
        Unit
    }

    fun readSince(sinceTs: Long): List<DayLogRow> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        val out = ArrayList<DayLogRow>()
        runCatching {
            file.forEachLine { line ->
                val p = line.split(',')
                if (p.size < 11) return@forEachLine
                val ts = p[0].toLongOrNull() ?: return@forEachLine
                if (ts < sinceTs) return@forEachLine
                fun d(i: Int) = p[i].toDoubleOrNull()
                out += DayLogRow(ts, d(1) ?: return@forEachLine, p[2] == "1", d(3), d(4), d(5), d(6), d(7), d(8), d(9), d(10) ?: 0.0)
            }
        }
        out
    }

    fun prune(beforeTs: Long) = synchronized(lock) {
        if (!file.exists() || file.length() < 4L * 1024 * 1024) return@synchronized
        val keep = file.readLines().filter { (it.substringBefore(',').toLongOrNull() ?: 0) >= beforeTs }
        runCatching { file.writeText(keep.joinToString("\n", postfix = "\n")) }
        Unit
    }

    fun clear() = synchronized(lock) { file.delete(); Unit }

    companion object {
        /** Daily roll-ups of the live log, as partial day records ready to merge into the main store. */
        fun rollup(rows: List<DayLogRow>, zone: ZoneId = ZoneId.systemDefault()): List<DayRecord> =
            rows.groupBy { Instant.ofEpochSecond(it.ts).atZone(zone).toLocalDate() }.map { (date, rs) -> day(date, rs) }.sortedBy { it.day }

        private fun day(date: LocalDate, rs: List<DayLogRow>): DayRecord {
            fun mean(sel: (DayLogRow) -> Double?) = rs.mapNotNull(sel).takeIf { it.isNotEmpty() }?.average()
            return DayRecord(
                day = date.toString(),
                dtHr = mean { it.hr },
                dtHrLow = rs.filter { it.steady }.minOfOrNull { it.hr },
                dtHrv = mean { it.rmssd }, dtSdnn = mean { it.sdnn }, dtBreath = mean { it.breath },
                dtStress = mean { it.stress }, dtCoherence = mean { it.coherence }, dtLfHf = mean { it.lfHf },
                dtKcal = rs.sumOf { it.kcal }, dtMin = rs.size.toDouble(),
            )
        }
    }
}
