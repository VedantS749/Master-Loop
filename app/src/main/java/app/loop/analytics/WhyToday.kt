package app.loop.analytics

import app.loop.model.Category
import app.loop.model.DayRecord
import app.loop.model.Settings
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Effect { LIFTING, DRAGGING, STEADY }

/** One thing behind today's recovery: what it is, the number against the person's usual, and which way it pushes. */
class Factor(
    val category: Category,
    val title: String,
    /** The headline number, such as "52 ms". */
    val value: String,
    /** The comparison in plain words, such as "15% below your usual 61 ms". */
    val detail: String,
    val effect: Effect,
    /** Rough size of the effect on recovery, used to rank. */
    val weight: Double,
)

class WhyToday(val factors: List<Factor>, val bottomLine: String?)

/**
 * Explains the day in terms of what is pulling recovery up or down, biggest first, each with the number and the person's
 * usual beside it. Replaces a loose list of sentences, several of which said the same thing.
 */
object WhyTodays {
    fun build(days: List<DayRecord>, s: Settings): WhyToday? {
        val latest = days.lastOrNull { it.recovery != null || it.sleepScore != null || it.hrv != null || it.noSleep == true } ?: return null
        val before = days.takeWhile { it.day < latest.day }.takeLast(28)
        val out = ArrayList<Factor>()

        val hb = Stats.baseline(before.map { it.hrv })
        if (latest.hrv != null && hb != null) {
            val pct = ((latest.hrv - hb.mean) / hb.mean * 100)
            out += Factor(
                Category.RECOVERY, "Heart-rate variability", "${latest.hrv.roundToInt()} ms",
                compare(pct, "${hb.mean.roundToInt()} ms"), effectOf(pct, 3.0), 0.55 * abs(pct) / 10,
            )
        }
        val rb = Stats.baseline(before.map { it.rhr })
        if (latest.rhr != null && rb != null) {
            val d = latest.rhr - rb.mean
            out += Factor(
                Category.RECOVERY, "Resting heart rate", "${latest.rhr.roundToInt()} bpm",
                when {
                    abs(d) < 1.5 -> "In line with your usual ${rb.mean.roundToInt()} bpm"
                    d > 0 -> "${d.roundToInt()} bpm above your usual ${rb.mean.roundToInt()} bpm"
                    else -> "${(-d).roundToInt()} bpm below your usual ${rb.mean.roundToInt()} bpm"
                },
                if (d >= 1.5) Effect.DRAGGING else if (d <= -1.5) Effect.LIFTING else Effect.STEADY, 0.20 * abs(d) / 3,
            )
        }
        if (latest.noSleep == true) {
            val need = s.sleepNeedMin.toDouble()
            out += Factor(Category.SLEEP, "Sleep", "None", "No sleep was detected, ${Scoring.hm(need)} short of your need", Effect.DRAGGING, 2.0)
        }
        latest.sleepMin?.let { asleep ->
            val need = s.sleepNeedMin.toDouble()
            val gap = asleep - need
            val debt = latest.sleepDebtMin?.takeIf { it >= 90 }
            val detail = buildString {
                append(if (gap >= -20) "Enough for your ${Scoring.hm(need)} need" else "${Scoring.hm(-gap)} short of your ${Scoring.hm(need)} need")
                if (debt != null) append(", and about ${Scoring.hm(debt)} behind over recent nights")
            }
            out += Factor(
                Category.SLEEP, "Sleep", Scoring.hm(asleep), detail,
                if (gap >= -20) Effect.LIFTING else Effect.DRAGGING, 0.15 * abs(gap) / 30 + (debt ?: 0.0) / 600,
            )
        }
        val respB = Stats.baseline(before.map { it.resp })
        if (latest.resp != null && respB != null && abs(latest.resp - respB.mean) >= 0.8) {
            val up = latest.resp > respB.mean
            out += Factor(
                Category.BODY, "Breathing rate", "${"%.1f".format(latest.resp)} br/min",
                "${"%.1f".format(abs(latest.resp - respB.mean))} ${if (up) "above" else "below"} your usual ${"%.1f".format(respB.mean)}",
                if (up) Effect.DRAGGING else Effect.LIFTING, 0.05 * abs(latest.resp - respB.mean),
            )
        }
        days.getOrNull(days.indexOf(latest) - 1)?.strain?.takeIf { it >= 70 }?.let {
            out += Factor(Category.ACTIVITY, "Yesterday's effort", "Strain ${it.roundToInt()}", "A hard day, which usually costs some recovery the morning after", Effect.DRAGGING, 0.3)
        }
        if (out.isEmpty()) return null

        val ranked = out.sortedByDescending { it.weight }
        // Show what matters: everything that moves recovery, plus a steady factor only if little else is available.
        val moving = ranked.filter { it.effect != Effect.STEADY }
        val shown = (if (moving.size >= 2) moving else ranked).take(4)
        return WhyToday(shown, bottomLine(ranked, latest.recovery, latest.noSleep == true))
    }

    private fun compare(pct: Double, usual: String): String = when {
        abs(pct) < 3 -> "In line with your usual $usual"
        pct > 0 -> "${pct.roundToInt()}% above your usual $usual"
        else -> "${(-pct).roundToInt()}% below your usual $usual"
    }

    private fun effectOf(pct: Double, threshold: Double) = if (pct >= threshold) Effect.LIFTING else if (pct <= -threshold) Effect.DRAGGING else Effect.STEADY

    /** One sentence on what matters most, without repeating the strain target the day's headline already gives. */
    private fun bottomLine(ranked: List<Factor>, recovery: Double?, sleepless: Boolean = false): String? {
        val drags = ranked.filter { it.effect == Effect.DRAGGING }
        val lifts = ranked.filter { it.effect == Effect.LIFTING }
        fun names(f: List<Factor>) = f.take(2).joinToString(" and ") { it.title.lowercase() }
        return when {
            drags.isNotEmpty() -> {
                val top = drags.first()
                val fix = when (top.title) {
                    "Sleep" -> if (sleepless) "A 20 to 30 minute nap before mid-afternoon and an early night will help most." else "An earlier night is the biggest thing you can do for tomorrow."
                    "Heart-rate variability" -> "Stress, alcohol, illness and late nights often lower it, so think about what changed yesterday."
                    "Resting heart rate" -> "A raised resting heart rate can follow poor sleep, dehydration or a hard day. Rest, water and an early night help."
                    "Yesterday's effort" -> "Keep today lighter and the effect should fade by tomorrow."
                    else -> "Keep an eye on it over the next few days."
                }
                "Mostly ${names(drags)} pulling recovery down. $fix"
            }
            lifts.isNotEmpty() -> "Your ${names(lifts)} ${if (lifts.size == 1) "is" else "are"} working in your favour. Keep doing what you did yesterday."
            recovery != null -> "Everything is close to your normal today."
            else -> null
        }
    }
}
