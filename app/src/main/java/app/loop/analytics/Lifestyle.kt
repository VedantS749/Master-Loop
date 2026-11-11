package app.loop.analytics

import app.loop.model.DayRecord
import app.loop.model.JournalEntry
import app.loop.model.StrengthExercise
import app.loop.model.StrengthSession
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.sqrt

// ===================================================================== Journal

object JournalCatalog {
    class Factor(val id: String, val label: String, val hint: String)

    val factors = listOf(
        Factor("caffeine_late", "Caffeine after 2pm", "Coffee, tea or energy drinks late in the day"),
        Factor("alcohol", "Alcohol", "Any drinks"),
        Factor("late_meal", "Late or heavy meal", "Eating within 3 hours of bed"),
        Factor("screens_bed", "Screens in bed", "Phone or TV right before sleep"),
        Factor("late_workout", "Late hard workout", "Intense training in the evening"),
        Factor("meditation", "Meditation or breathwork", "Any calming practice"),
        Factor("stretching", "Stretching or mobility", "Yoga, foam rolling, stretching"),
        Factor("sauna_cold", "Sauna or cold exposure", "Heat or cold therapy"),
        Factor("supplements", "Supplements", "Magnesium, creatine, vitamins, etc."),
        Factor("stressful_day", "Stressful day", "Work, family or other pressure"),
        Factor("felt_sick", "Felt unwell", "Cold, fever or low energy"),
        Factor("travel", "Travel", "Flights, long drives or time-zone changes"),
        Factor("nap", "Daytime nap", "Any sleep outside the main night"),
        Factor("hydrated", "Well hydrated", "Drank plenty of water"),
    )

    fun label(id: String) = factors.firstOrNull { it.id == id }?.label ?: id
}

enum class Confidence(val label: String) { CLEAR("Clear effect"), POSSIBLE("Possible effect"), UNCLEAR("No clear effect yet") }

class FactorEffect(
    val factorId: String, val withDays: Int, val withoutDays: Int,
    val recoveryDelta: Double?, val hrvDelta: Double?, val sleepDelta: Double?, val confidence: Confidence,
)

/**
 * Measures what each journal factor does to the NEXT morning: recovery, heart-rate variability and sleep score on
 * days after the factor was logged versus other journaled days. Needs enough days of both kinds before it says anything.
 * This is a correlation in one person's own data, not proof of cause.
 */
object JournalImpact {
    const val MIN_DAYS = 4

    fun compute(days: List<DayRecord>, entries: List<JournalEntry>): List<FactorEffect> {
        val byDay = days.associateBy { it.day }
        val next = entries.associate { e -> e.day to byDay[LocalDate.parse(e.day).plusDays(1).toString()] }
        val out = ArrayList<FactorEffect>()
        for (f in JournalCatalog.factors) {
            val withE = entries.filter { f.id in it.factors }
            val withoutE = entries.filter { f.id !in it.factors }
            if (withE.size < MIN_DAYS || withoutE.size < MIN_DAYS) continue
            fun vals(es: List<JournalEntry>, read: (DayRecord) -> Double?) = es.mapNotNull { e -> next[e.day]?.let(read) }
            val rw = vals(withE) { it.recovery }; val ro = vals(withoutE) { it.recovery }
            val hw = vals(withE) { it.hrv }; val ho = vals(withoutE) { it.hrv }
            val sw = vals(withE) { it.sleepScore }; val so = vals(withoutE) { it.sleepScore }
            fun delta(a: List<Double>, b: List<Double>) = if (a.size >= MIN_DAYS && b.size >= MIN_DAYS) a.average() - b.average() else null
            val rd = delta(rw, ro)
            val conf = if (rd == null) Confidence.UNCLEAR else when {
                abs(welch(rw, ro)) >= 2.0 -> Confidence.CLEAR
                abs(welch(rw, ro)) >= 1.2 -> Confidence.POSSIBLE
                else -> Confidence.UNCLEAR
            }
            out += FactorEffect(f.id, withE.size, withoutE.size, rd, delta(hw, ho), delta(sw, so), conf)
        }
        return out.sortedByDescending { abs(it.recoveryDelta ?: 0.0) }
    }

    /** Welch's t statistic for the difference of two means. */
    fun welch(a: List<Double>, b: List<Double>): Double {
        if (a.size < 2 || b.size < 2) return 0.0
        val va = (Stats.std(a) ?: 0.0).let { it * it } / a.size
        val vb = (Stats.std(b) ?: 0.0).let { it * it } / b.size
        val se = sqrt(va + vb)
        return if (se < 1e-9) 0.0 else (a.average() - b.average()) / se
    }
}

// ======================================================================= Cycle

enum class CyclePhase(val label: String, val blurb: String) {
    MENSTRUAL("Menstrual", "Bleeding days. Energy and recovery can dip, and iron-rich food and rest help."),
    FOLLICULAR("Follicular", "Energy usually builds. Many people recover well and tolerate harder training."),
    OVULATORY("Ovulatory", "Peak energy for many, with resting heart rate often at its lowest. Warm-up carefully, as ligaments can be laxer."),
    LUTEAL("Luteal", "Body temperature and resting heart rate tend to rise and HRV to dip. Sleep and recovery may need extra attention."),
}

class CycleInfo(val dayOfCycle: Int, val phase: CyclePhase, val cycleLength: Int, val nextStart: LocalDate, val daysUntilNext: Int)

class PhaseStat(val phase: CyclePhase, val days: Int, val recovery: Double?, val hrv: Double?, val rhr: Double?, val skinDev: Double?, val sleepScore: Double?)

object CycleEngine {
    fun starts(iso: List<String>): List<LocalDate> = iso.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.distinct().sorted()

    /** Typical cycle length: median of recent plausible gaps between period starts, else 28. */
    fun cycleLength(starts: List<LocalDate>): Int {
        val gaps = starts.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b).toInt() }.filter { it in 20..45 }.takeLast(6)
        return if (gaps.isEmpty()) 28 else gaps.sorted()[gaps.size / 2]
    }

    fun phaseOn(date: LocalDate, starts: List<LocalDate>): Pair<Int, CyclePhase>? {
        val start = starts.lastOrNull { !it.isAfter(date) } ?: return null
        val day = ChronoUnit.DAYS.between(start, date).toInt() + 1
        if (day > 60) return null
        val len = cycleLength(starts)
        val ov = len - 14
        val phase = when {
            day <= 5 -> CyclePhase.MENSTRUAL
            day < ov - 1 -> CyclePhase.FOLLICULAR
            day <= ov + 1 -> CyclePhase.OVULATORY
            else -> CyclePhase.LUTEAL
        }
        return day to phase
    }

    fun info(today: LocalDate, starts: List<LocalDate>): CycleInfo? {
        val (day, phase) = phaseOn(today, starts) ?: return null
        val len = cycleLength(starts)
        val last = starts.last { !it.isAfter(today) }
        val next = last.plusDays(len.toLong())
        return CycleInfo(day, phase, len, next, ChronoUnit.DAYS.between(today, next).toInt())
    }

    /** Average measures in each phase across logged cycles. */
    fun phaseStats(days: List<DayRecord>, starts: List<LocalDate>): List<PhaseStat> {
        val buckets = CyclePhase.entries.associateWith { ArrayList<DayRecord>() }
        for (d in days) {
            val (_, ph) = phaseOn(d.date, starts) ?: continue
            buckets.getValue(ph).add(d)
        }
        return CyclePhase.entries.map { ph ->
            val l = buckets.getValue(ph)
            PhaseStat(ph, l.size, Stats.mean(l.map { it.recovery }), Stats.mean(l.map { it.hrv }), Stats.mean(l.map { it.rhr }), Stats.mean(l.map { it.skinTempDev }), Stats.mean(l.map { it.sleepScore }))
        }
    }
}

// ===================================================================== Strength

object StrengthMath {
    val commonExercises = listOf(
        "Back squat", "Front squat", "Deadlift", "Romanian deadlift", "Bench press", "Incline press", "Overhead press", "Barbell row",
        "Pull-up", "Lat pulldown", "Dumbbell row", "Lunge", "Leg press", "Hip thrust", "Dip", "Biceps curl", "Triceps extension", "Lateral raise", "Plank",
    )
    val templates = listOf("Push", "Pull", "Legs", "Upper", "Lower", "Full body", "Custom")

    /** Epley estimate of a one-rep max. Sets above 12 reps are too noisy to estimate from. */
    fun e1rm(kg: Double, reps: Int): Double = when {
        kg <= 0 || reps <= 0 -> 0.0
        reps == 1 -> kg
        reps > 12 -> 0.0
        else -> kg * (1 + reps / 30.0)
    }

    fun best(e: StrengthExercise): Double = e.sets.maxOfOrNull { e1rm(it.kg, it.reps) } ?: 0.0

    /** Best estimated 1RM per session for one exercise, oldest first. */
    fun progress(sessions: List<StrengthSession>, name: String): List<Pair<Long, Double>> =
        sessions.sortedBy { it.id }.mapNotNull { s ->
            val b = s.exercises.filter { it.name.equals(name, true) }.maxOfOrNull { best(it) } ?: 0.0
            if (b > 0) s.id to b else null
        }

    fun exerciseNames(sessions: List<StrengthSession>): List<String> =
        sessions.flatMap { s -> s.exercises.map { it.name } }.groupingBy { it.lowercase() }.eachCount().entries.sortedByDescending { it.value }
            .map { e -> sessions.flatMap { it.exercises }.first { it.name.lowercase() == e.key }.name }
}

object AlarmTime {
    /** Next future instant at local [hour]:[minute]: today if still ahead, otherwise tomorrow. */
    fun nextOccurrenceMs(hour: Int, minute: Int, nowMs: Long, zone: java.time.ZoneId): Long {
        val now = java.time.Instant.ofEpochMilli(nowMs).atZone(zone)
        val c = now.with(java.time.LocalTime.of(hour, minute, 0, 0))
        return (if (c.toInstant().toEpochMilli() > nowMs + 30_000) c else c.plusDays(1)).toInstant().toEpochMilli()
    }
}
