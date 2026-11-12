package app.loop.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.loop.model.DayRecord
import app.loop.model.Workout
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.zip.ZipInputStream

/**
 * Imports a WHOOP data export (the .zip, or the loose CSVs inside it) entirely on-device.
 * Understands physiological_cycles.csv, sleeps.csv and workouts.csv. Files are matched by header
 * content, so renamed or localised exports still work.
 */
object WhoopImporter {
    data class Result(val days: List<DayRecord>, val workouts: List<Workout>, val files: List<String>, val note: String?)

    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024

    fun readFiles(context: Context, uris: List<Uri>): Result {
        val named = ArrayList<Pair<String, ByteArray>>()
        for (uri in uris) {
            val name = displayName(context, uri) ?: "file"
            val bytes = context.contentResolver.openInputStream(uri)?.use { readCapped(it) } ?: continue
            if (bytes.size > 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
                ZipInputStream(bytes.inputStream()).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        if (e.isDirectory || !e.name.lowercase().endsWith(".csv")) continue
                        named += e.name to readCapped(zip)
                    }
                }
            } else named += name to bytes
        }
        return parse(named)
    }

    fun parse(files: List<Pair<String, ByteArray>>): Result {
        var cycles: List<Map<String, String>> = emptyList()
        var sleeps: List<Map<String, String>> = emptyList()
        var workoutsRows: List<Map<String, String>> = emptyList()
        val used = ArrayList<String>()
        for ((name, bytes) in files) {
            val table = table(bytes.toString(Charsets.UTF_8))
            if (table.isEmpty()) continue
            val h = table.first().keys
            when {
                "activity_name" in h || "workout_start_time" in h -> { workoutsRows = workoutsRows + table; used += name }
                "nap" in h && ("sleep_onset" in h || "wake_onset" in h) -> { sleeps = sleeps + table; used += name }
                "cycle_start_time" in h || "recovery_score_pct" in h || "day_strain" in h -> { cycles = cycles + table; used += name }
            }
        }
        if (used.isEmpty()) return Result(emptyList(), emptyList(), emptyList(), "No WHOOP files were found. Pick the export .zip, or physiological_cycles.csv, sleeps.csv and workouts.csv.")

        val days = HashMap<String, DayRecord>()
        for (r in cycles) {
            val day = dayOf(r["wake_onset"] ?: r["cycle_end_time"] ?: r["cycle_start_time"]) ?: continue
            val prev = days[day] ?: DayRecord(day)
            days[day] = prev.copy(
                recoveryRaw = r.num("recovery_score_pct") ?: prev.recoveryRaw,
                rhr = r.num("resting_heart_rate_bpm", "resting_heart_rate") ?: prev.rhr,
                hrv = r.num("heart_rate_variability_ms", "heart_rate_variability_rmssd_ms") ?: prev.hrv,
                skinTempDev = r.num("skin_temp_celsius") ?: r.num("skin_temp_f")?.let { (it - 32) * 5 / 9 } ?: prev.skinTempDev,
                strainRaw = r.num("day_strain")?.let { (it * 100.0 / 21.0).coerceIn(0.0, 100.0) } ?: prev.strainRaw,
                resp = r.num("respiratory_rate_rpm", "respiratory_rate") ?: prev.resp,
                sleepMin = r.num("asleep_duration_min") ?: prev.sleepMin,
                lightMin = r.num("light_sleep_duration_min") ?: prev.lightMin,
                deepMin = r.num("deep_sws_duration_min", "deep_sleep_duration_min") ?: prev.deepMin,
                remMin = r.num("rem_duration_min") ?: prev.remMin,
                awakeMin = r.num("awake_duration_min") ?: prev.awakeMin,
                sleepEff = r.num("sleep_efficiency_pct") ?: prev.sleepEff,
                sleepPerf = r.num("sleep_performance_pct") ?: prev.sleepPerf,
                sleepConsistency = r.num("sleep_consistency_pct") ?: prev.sleepConsistency,
            )
        }
        // Detailed sleeps: keep the longest non-nap sleep of each wake day.
        val best = HashMap<String, Map<String, String>>()
        for (r in sleeps) {
            if (r["nap"]?.trim()?.lowercase() == "true") continue
            val day = dayOf(r["wake_onset"] ?: r["sleep_onset"]) ?: continue
            val len = r.num("asleep_duration_min") ?: 0.0
            if (len > (best[day]?.num("asleep_duration_min") ?: -1.0)) best[day] = r
        }
        for ((day, r) in best) {
            val prev = days[day] ?: DayRecord(day)
            days[day] = prev.copy(
                sleepMin = r.num("asleep_duration_min") ?: prev.sleepMin,
                lightMin = r.num("light_sleep_duration_min") ?: prev.lightMin,
                deepMin = r.num("deep_sws_duration_min", "deep_sleep_duration_min") ?: prev.deepMin,
                remMin = r.num("rem_duration_min") ?: prev.remMin,
                awakeMin = r.num("awake_duration_min") ?: prev.awakeMin,
                sleepEff = r.num("sleep_efficiency_pct") ?: prev.sleepEff,
                sleepPerf = r.num("sleep_performance_pct") ?: prev.sleepPerf,
                sleepConsistency = r.num("sleep_consistency_pct") ?: prev.sleepConsistency,
                resp = r.num("respiratory_rate_rpm") ?: prev.resp,
            )
        }

        val workouts = ArrayList<Workout>()
        for (r in workoutsRows) {
            val start = epoch(r["workout_start_time"] ?: r["cycle_start_time"], r["cycle_timezone"]) ?: continue
            val end = epoch(r["workout_end_time"], r["cycle_timezone"])
            val dur = r.num("duration_min") ?: if (end != null && end >= start) (end - start) / 60.0 else 0.0
            val zones = (1..5).map { z -> (r.num("hr_zone_${z}_pct", "zone_${z}_pct") ?: 0.0) * dur / 100.0 }
            workouts += Workout(
                id = start, sport = r["activity_name"]?.ifBlank { null } ?: "Workout", durationMin = dur,
                avgHr = r.num("average_hr_bpm", "average_heart_rate_bpm")?.toInt(), maxHr = r.num("max_hr_bpm", "max_heart_rate_bpm")?.toInt(),
                strain = ((r.num("activity_strain") ?: 0.0) * 100.0 / 21.0).coerceIn(0.0, 100.0),
                kcal = r.num("energy_burned_cal"), zoneMin = zones, source = "import",
            )
        }
        return Result(days.values.sortedBy { it.day }, workouts.sortedBy { it.id }, used, null)
    }

    /** Imported values win over existing ones; existing values survive where the import is silent. */
    fun merge(existing: LoopData, imported: Result): LoopData {
        val byDay = existing.days.associateBy { it.day }.toMutableMap()
        for (n in imported.days) {
            val o = byDay[n.day]
            byDay[n.day] = if (o == null) n else n.copy(
                hrv = n.hrv ?: o.hrv, rhr = n.rhr ?: o.rhr, resp = n.resp ?: o.resp,
                skinTempDev = n.skinTempDev ?: o.skinTempDev, sleepMin = n.sleepMin ?: o.sleepMin, sleepEff = n.sleepEff ?: o.sleepEff,
                deepMin = n.deepMin ?: o.deepMin, remMin = n.remMin ?: o.remMin, lightMin = n.lightMin ?: o.lightMin,
                awakeMin = n.awakeMin ?: o.awakeMin, sleepPerf = n.sleepPerf ?: o.sleepPerf,
                sleepConsistency = n.sleepConsistency ?: o.sleepConsistency, kcal = n.kcal ?: o.kcal, steps = n.steps ?: o.steps,
                weightKg = n.weightKg ?: o.weightKg, recoveryRaw = n.recoveryRaw ?: o.recoveryRaw, strainRaw = n.strainRaw ?: o.strainRaw,
                wearMin = n.wearMin ?: o.wearMin, skinIdx = n.skinIdx ?: o.skinIdx, hrr = n.hrr ?: o.hrr, trimp = n.trimp ?: o.trimp, napMin = n.napMin ?: o.napMin, noSleep = n.noSleep ?: o.noSleep,
                rhythmIrreg = n.rhythmIrreg ?: o.rhythmIrreg, dayStress = n.dayStress ?: o.dayStress, stressHighMin = n.stressHighMin ?: o.stressHighMin,
                dtHr = n.dtHr ?: o.dtHr, dtHrLow = n.dtHrLow ?: o.dtHrLow, dtHrv = n.dtHrv ?: o.dtHrv, dtSdnn = n.dtSdnn ?: o.dtSdnn,
                dtBreath = n.dtBreath ?: o.dtBreath, dtStress = n.dtStress ?: o.dtStress, dtCoherence = n.dtCoherence ?: o.dtCoherence,
                dtLfHf = n.dtLfHf ?: o.dtLfHf, dtKcal = n.dtKcal ?: o.dtKcal, dtMin = n.dtMin ?: o.dtMin,
            )
        }
        val wk = (existing.workouts + imported.workouts).associateBy { it.id }.values.sortedBy { it.id }
        return existing.copy(days = byDay.values.sortedBy { it.day }, workouts = wk)
    }

    // ----- helpers -----
    private fun Map<String, String>.num(vararg keys: String): Double? {
        for (k in keys) { val v = this[k]?.trim(); if (!v.isNullOrEmpty()) v.toDoubleOrNull()?.let { return it } }
        return null
    }

    private fun dayOf(ts: String?): String? = ts?.trim()?.takeIf { it.length >= 10 && it[4] == '-' && it[7] == '-' }?.substring(0, 10)

    /** "2024-03-02 23:41:12" + "UTC-05:00" -> epoch seconds. */
    private fun epoch(ts: String?, tz: String?): Long? {
        val t = ts?.trim()?.takeIf { it.length >= 19 } ?: return null
        val local = runCatching { LocalDateTime.parse(t.substring(0, 19).replace(' ', 'T')) }.getOrNull() ?: return null
        var offset = 0
        val m = Regex("([+-])(\\d{2}):?(\\d{2})").find(tz ?: "")
        if (m != null) offset = (m.groupValues[2].toInt() * 3600 + m.groupValues[3].toInt() * 60) * if (m.groupValues[1] == "-") -1 else 1
        return local.toEpochSecond(ZoneOffset.UTC) - offset
    }

    private fun table(text: String): List<Map<String, String>> {
        val rows = parseCsv(text.removePrefix("﻿"))
        if (rows.size < 2) return emptyList()
        val head = rows[0].map { normalise(it) }
        return rows.drop(1).filter { it.any { c -> c.isNotBlank() } }.map { r -> head.indices.associate { i -> head[i] to (r.getOrNull(i) ?: "") } }
    }

    fun normalise(h: String): String {
        val s = java.text.Normalizer.normalize(h.lowercase().trim(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").replace("%", "pct")
        return s.replace(Regex("[^a-z0-9]+"), "_").trim('_')
    }

    fun parseCsv(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>(); var row = ArrayList<String>(); val cell = StringBuilder()
        var inQ = false; var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQ) {
                if (c == '"') { if (i + 1 < text.length && text[i + 1] == '"') { cell.append('"'); i++ } else inQ = false } else cell.append(c)
            } else when (c) {
                '"' -> inQ = true
                ',' -> { row += cell.toString(); cell.clear() }
                '\n' -> { row += cell.toString(); cell.clear(); rows += row; row = ArrayList() }
                '\r' -> {}
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); rows += row }
        return rows
    }

    private fun readCapped(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream(); val buf = ByteArray(16 * 1024); var total = 0L
        while (true) {
            val n = input.read(buf); if (n < 0) break
            total += n; if (total > MAX_ENTRY_BYTES) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    }.getOrNull() ?: uri.lastPathSegment
}

/** Plain CSV of the user's own scored days, so their data is never locked in. */
object LoopCsv {
    fun export(days: List<DayRecord>): String {
        val sb = StringBuilder("date,recovery_pct,hrv_ms,resting_hr_bpm,resp_rate,skin_temp_dev_c,sleep_score,sleep_min,sleep_efficiency_pct,deep_min,rem_min,light_min,strain,calories_kcal,steps,weight_kg,stress,mood,daytime_stress,high_stress_min,vo2max_est,load_ratio,hr_recovery,wear_min,dt_hr,dt_hr_low,dt_hrv_rmssd,dt_hrv_sdnn,dt_breath,dt_stress,dt_coherence,dt_lfhf,dt_kcal,dt_logged_min\n")
        fun f(v: Double?) = v?.let { "%.2f".format(java.util.Locale.US, it) } ?: ""
        for (d in days) {
            sb.append(d.day).append(',')
            sb.append(listOf(d.recovery, d.hrv, d.rhr, d.resp, d.skinTempDev, d.sleepScore, d.sleepMin, d.sleepEff, d.deepMin, d.remMin, d.lightMin, d.strain, d.kcal, d.steps, d.weightKg, d.stress, d.mood, d.dayStress, d.stressHighMin, d.vo2max, d.loadRatio, d.hrr, d.wearMin, d.dtHr, d.dtHrLow, d.dtHrv, d.dtSdnn, d.dtBreath, d.dtStress, d.dtCoherence, d.dtLfHf, d.dtKcal, d.dtMin).joinToString(",") { f(it) })
            sb.append('\n')
        }
        return sb.toString()
    }
}
