package app.loop.analytics

import app.loop.model.DayRecord
import app.loop.model.DoseTaken
import app.loop.model.HealthLog
import app.loop.model.Medicine
import app.loop.model.PainEntry
import app.loop.model.Problem
import app.loop.model.ProblemKind
import app.loop.model.Schedule
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

// ===================================================================== medicines

class MedEffect(val label: String, val unit: String, val delta: Double, val pct: Double?, val confidence: Confidence, val higherIsBetter: Boolean?)

/** What a medicine seems to do to the person's numbers. [enough] is false until there are enough days to compare. */
class MedImpact(val effects: List<MedEffect>, val daysBefore: Int, val daysAfter: Int, val enough: Boolean)

/**
 * Compares the person's numbers before and after a daily medicine started, or on the mornings after a "when needed"
 * dose against other mornings. It needs enough days of both and says "no clear change" otherwise. A pattern in one
 * person's data, never proof of cause.
 */
object MedicineImpacts {
    const val MIN_DAYS = 5
    private const val SETTLE_DAYS = 3

    private class M(val label: String, val unit: String, val better: Boolean?, val read: (DayRecord) -> Double?)

    private val metrics = listOf(
        M("Heart-rate variability", "ms", true) { it.hrv },
        M("Resting heart rate", "bpm", false) { it.rhr },
        M("Breathing rate", "br/min", null) { it.resp },
        M("Sleep", "min", true) { it.sleepMin },
        M("Recovery", "%", true) { it.recovery },
    )

    fun compute(med: Medicine, days: List<DayRecord>, doses: List<DoseTaken>, today: LocalDate = LocalDate.now()): MedImpact {
        val start = runCatching { LocalDate.parse(med.start) }.getOrNull() ?: return MedImpact(emptyList(), 0, 0, false)
        val end = med.end?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today
        val a: List<DayRecord>; val b: List<DayRecord>
        if (med.schedule == Schedule.DAILY) {
            b = days.filter { inRange(it, start.minusDays(28), start.minusDays(1)) }
            a = days.filter { inRange(it, start.plusDays(SETTLE_DAYS.toLong()), end) }
        } else {
            val taken = doses.filter { it.medId == med.id }.map { it.day }.toSet()
            if (taken.isEmpty()) return MedImpact(emptyList(), 0, 0, false)
            val first = taken.minOf { LocalDate.parse(it) }
            val window = days.filter { inRange(it, first, today) }
            // The morning after a dose is when it shows (sleep, resting heart rate, recovery).
            a = window.filter { taken.contains(it.date.minusDays(1).toString()) }
            b = window.filter { !taken.contains(it.date.minusDays(1).toString()) && !taken.contains(it.day) }
        }
        val need = if (med.schedule == Schedule.DAILY) MIN_DAYS else 3
        val out = ArrayList<MedEffect>()
        var enough = false
        for (m in metrics) {
            val after = a.mapNotNull(m.read); val before = b.mapNotNull(m.read)
            if (after.size < need || before.size < MIN_DAYS) continue
            enough = true
            val delta = after.average() - before.average()
            val t = JournalImpact.welch(after, before)
            val conf = when { abs(t) >= 2.0 -> Confidence.CLEAR; abs(t) >= 1.2 -> Confidence.POSSIBLE; else -> Confidence.UNCLEAR }
            val pct = before.average().takeIf { abs(it) > 1e-9 }?.let { delta / it * 100 }
            out += MedEffect(m.label, m.unit, delta, pct, conf, m.better)
        }
        return MedImpact(out.sortedByDescending { it.confidence.ordinal * -1 }.sortedBy { it.confidence.ordinal }, b.size, a.size, enough)
    }

    private fun inRange(d: DayRecord, from: LocalDate, to: LocalDate): Boolean { val x = d.date; return !x.isBefore(from) && !x.isAfter(to) }

    private val notes: List<Pair<List<String>, String>> = listOf(
        listOf("propranolol", "metoprolol", "atenolol", "bisoprolol", "nebivolol", "carvedilol", "beta") to
            "Beta-blockers usually lower resting and exercise heart rate and can change heart-rate variability, so your zones and recovery may read differently while you take one.",
        listOf("methylphenidate", "ritalin", "concerta", "amphetamine", "adderall", "vyvanse", "modafinil", "armodafinil") to
            "Stimulants can raise resting heart rate and make sleep lighter or shorter.",
        listOf("sertraline", "fluoxetine", "escitalopram", "citalopram", "paroxetine", "venlafaxine", "duloxetine", "amitriptyline", "mirtazapine", "bupropion") to
            "Some antidepressants can lower heart-rate variability and change sleep stages, especially in the first weeks.",
        listOf("diphenhydramine", "benadryl", "chlorphenamine", "promethazine", "doxylamine") to
            "Some antihistamines are sedating and can change how deep your sleep is.",
        listOf("melatonin") to "Melatonin can shift when you fall asleep. Effects on how long you sleep are usually small.",
        listOf("zolpidem", "zopiclone", "clonazepam", "alprazolam", "diazepam", "lorazepam", "temazepam") to
            "Sleep medicines can change sleep stages and often lower the deep and REM sleep Loop estimates.",
        listOf("prednisone", "prednisolone", "dexamethasone", "hydrocortisone", "steroid") to
            "Steroids can raise resting heart rate, disturb sleep and lower heart-rate variability.",
        listOf("pseudoephedrine", "phenylephrine", "decongestant", "sudafed") to "Decongestants can raise resting heart rate and keep you awake.",
        listOf("levothyroxine", "thyroxine", "eltroxin", "liothyronine") to "Thyroid medicine affects resting heart rate, so a change in dose can show up in your numbers.",
        listOf("ibuprofen", "naproxen", "diclofenac", "aspirin", "paracetamol", "acetaminophen") to
            "Pain relievers can ease soreness, which may change how an injury feels more than how fast your body recovers.",
        listOf("amoxicillin", "azithromycin", "ciprofloxacin", "doxycycline", "cefalexin", "antibiotic") to
            "When you are unwell, the illness itself usually shows up in your numbers: a higher resting heart rate and lower HRV until you recover.",
    )

    /** A short, general note about medicines of this kind, or null when Loop has nothing sensible to say. */
    fun noteFor(name: String): String? {
        val n = name.lowercase()
        return notes.firstOrNull { (keys, _) -> keys.any { n.contains(it) } }?.second
    }

    const val DISCLAIMER = "General information, not medical advice. Don't change or stop a medicine because of what Loop shows: talk to your doctor or pharmacist."
}

// ===================================================================== injuries and illness

enum class Readiness(val label: String) {
    REST("Keep resting"), GENTLE("Gentle movement only"), EASING("Ready to ease back in"), RECOVERED("Numbers look back to normal"),
}

enum class PainTrend(val label: String) { IMPROVING("Improving"), STEADY("Steady"), WORSENING("Getting worse") }

class ProblemStatus(
    val problem: Problem,
    val dayCount: Int,
    val painNow: Int,
    val trend: PainTrend,
    val readiness: Readiness,
    val advice: String,
    val warning: String?,
    val painSeries: List<Double?>,
    val recoverySeries: List<Double?>,
)

object RecoveryChecks {
    private const val SERIES_DAYS = 14

    fun status(p: Problem, pain: List<PainEntry>, days: List<DayRecord>, today: LocalDate = LocalDate.now()): ProblemStatus {
        val start = LocalDate.parse(p.start)
        val mine = pain.filter { it.problemId == p.id }.sortedBy { it.day }
        val now = mine.lastOrNull()?.level ?: p.severity
        val trend = trendOf(mine, p.severity)
        val dayCount = (ChronoUnit.DAYS.between(start, p.end?.let { LocalDate.parse(it) } ?: today).toInt() + 1).coerceAtLeast(1)

        val recent = days.filter { !it.date.isAfter(today) }.takeLast(3)
        val recAvg = recent.mapNotNull { it.recovery }.takeIf { it.isNotEmpty() }?.average()
        val before = days.filter { it.date.isBefore(start) }.takeLast(28)
        val rhrB = Stats.baseline(before.map { it.rhr }, minN = 4)?.mean
        val respB = Stats.baseline(before.map { it.resp }, minN = 4)?.mean
        val rhrNow = recent.mapNotNull { it.rhr }.lastOrNull()
        val respNow = recent.mapNotNull { it.resp }.lastOrNull()
        val rhrUp = if (rhrB != null && rhrNow != null) rhrNow - rhrB else null
        val respUp = if (respB != null && respNow != null) respNow - respB else null

        val readiness: Readiness
        val advice: String
        if (p.kind == ProblemKind.INJURY) {
            readiness = when {
                now >= 6 || (trend == PainTrend.WORSENING && now >= 4) -> Readiness.REST
                now >= 3 -> Readiness.GENTLE
                (recAvg == null || recAvg >= 50) && (rhrUp == null || rhrUp <= 4) -> Readiness.EASING
                else -> Readiness.GENTLE
            }
            advice = when (readiness) {
                Readiness.REST -> "Pain is still high. Rest the area and avoid anything that hurts. See a clinician if it isn't settling or it got worse after a fall or knock."
                Readiness.GENTLE -> "Gentle movement that doesn't hurt is fine. Stop if the pain rises, and keep hard training off this area for now."
                else -> "Pain is low and your body looks recovered enough. Restart at about half your usual load and build up over a week."
            }
        } else {
            val back = (rhrUp == null || rhrUp <= 2) && (respUp == null || respUp <= 0.8) && (recAvg == null || recAvg >= 50) && now <= 2
            readiness = when { now >= 6 -> Readiness.REST; back -> Readiness.RECOVERED; else -> Readiness.GENTLE }
            advice = when (readiness) {
                Readiness.REST -> "You are feeling quite unwell. Rest, drink plenty and don't train. See a clinician if it lasts more than a few days or gets worse."
                Readiness.RECOVERED -> "Your resting heart rate and breathing are back near normal. Ease back in rather than going straight to a hard session."
                else -> "Your numbers are still off from your normal, which is typical while recovering. Keep it light and let sleep do its work."
            }
        }

        val strainLately = days.takeLast(2).mapNotNull { it.strain }.maxOrNull()
        val load = days.lastOrNull()?.loadRatio
        val warning = if (p.active && ((strainLately ?: 0.0) >= 60 || (load ?: 0.0) > 1.3)) {
            if (p.kind == ProblemKind.INJURY) "Your training load is high while this is healing. Easing off protects it."
            else "Hard training while you are unwell can slow recovery. Take it easy until you feel better."
        } else null

        val byDay = days.associateBy { it.day }
        val last14 = (SERIES_DAYS - 1 downTo 0).map { today.minusDays(it.toLong()) }
        val painByDay = mine.associate { it.day to it.level.toDouble() }
        return ProblemStatus(
            p, dayCount, now, trend, readiness, advice, warning,
            last14.map { painByDay[it.toString()] },
            last14.map { byDay[it.toString()]?.recovery },
        )
    }

    private fun trendOf(entries: List<PainEntry>, startSeverity: Int): PainTrend {
        val levels = listOf(startSeverity) + entries.map { it.level }
        if (levels.size < 3) return PainTrend.STEADY
        val recent = levels.takeLast(2).average()
        val earlier = levels.dropLast(2).takeLast(3).average()
        return when { recent - earlier <= -1.0 -> PainTrend.IMPROVING; recent - earlier >= 1.0 -> PainTrend.WORSENING; else -> PainTrend.STEADY }
    }
}

object HealthLogs {
    /**
     * Days that would distort the person's normal, so baselines leave them out: any illness, and injuries that began
     * at a severity of 7 or more. Open-ended ones are capped at three weeks.
     */
    fun offDays(log: HealthLog, today: LocalDate = LocalDate.now()): Set<String> {
        val out = HashSet<String>()
        for (p in log.problems) {
            if (p.kind == ProblemKind.INJURY && p.severity < 7) continue
            val s = runCatching { LocalDate.parse(p.start) }.getOrNull() ?: continue
            val e = p.end?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: minOf(today, s.plusDays(21))
            var d = s
            while (!d.isAfter(e)) { out += d.toString(); d = d.plusDays(1) }
        }
        return out
    }

    /** Dated events in a window, newest first, for notes under a chart. */
    class Event(val day: LocalDate, val text: String)

    fun events(log: HealthLog, from: LocalDate, to: LocalDate): List<Event> {
        val out = ArrayList<Event>()
        fun add(day: String?, text: String) {
            val d = day?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return
            if (!d.isBefore(from) && !d.isAfter(to)) out += Event(d, text)
        }
        log.medicines.forEach { add(it.start, "Started ${it.name}"); add(it.end, "Stopped ${it.name}") }
        log.problems.forEach { add(it.start, "${it.title} began"); add(it.end, "${it.title} healed") }
        return out.sortedByDescending { it.day }
    }
}
