package app.loop.analytics

import app.loop.model.Category
import app.loop.model.DayRecord
import app.loop.model.Settings
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

data class Driver(val category: Category, val text: String, val positive: Boolean?)

data class Brief(
    val headline: String,
    val sub: String,
    val band: Scoring.Band?,
    val target: IntRange?,
    val drivers: List<Driver>,
    /** Non-null while we are still learning the user's baseline (days collected so far, goal). */
    val learning: Pair<Int, Int>?,
)

data class Insight(val category: Category, val title: String, val body: String, val strength: Double)

object Insights {
    const val BASELINE_GOAL = 5

    fun brief(days: List<DayRecord>, s: Settings): Brief? {
        val latest = days.lastOrNull { it.recovery != null || it.sleepScore != null || it.noSleep == true } ?: return null
        val withHrv = days.count { it.hrv != null }
        val rec = latest.recovery
        val drivers = ArrayList<Driver>()

        val hrvBase = Stats.baseline(days.takeWhile { it.day < latest.day }.takeLast(28).map { it.hrv })
        val rhrBase = Stats.baseline(days.takeWhile { it.day < latest.day }.takeLast(28).map { it.rhr })
        if (latest.hrv != null && hrvBase != null) {
            val pct = ((latest.hrv - hrvBase.mean) / hrvBase.mean * 100).roundToInt()
            drivers += Driver(
                Category.RECOVERY,
                when { pct >= 3 -> "Heart-rate variability is $pct% above your normal"; pct <= -3 -> "Heart-rate variability is ${-pct}% below your normal"; else -> "Heart-rate variability is right at your normal" },
                if (pct >= 3) true else if (pct <= -3) false else null,
            )
        }
        if (latest.rhr != null && rhrBase != null) {
            val d = (latest.rhr - rhrBase.mean).roundToInt()
            drivers += Driver(
                Category.RECOVERY,
                when { d >= 2 -> "Resting heart rate is $d bpm higher than usual"; d <= -2 -> "Resting heart rate is ${-d} bpm lower than usual"; else -> "Resting heart rate is steady" },
                if (d >= 2) false else if (d <= -2) true else null,
            )
        }
        if (latest.noSleep == true) drivers += Driver(Category.SLEEP, "No sleep was detected overnight", false)
        latest.sleepMin?.let {
            val need = s.sleepNeedMin
            val diff = it - need
            drivers += Driver(
                Category.SLEEP,
                if (diff >= -20) "You slept ${Scoring.hm(it)}, enough for your need"
                else "You slept ${Scoring.hm(it)}, ${Scoring.hm(-diff)} short of your need",
                diff >= -20,
            )
        }
        latest.sleepDebtMin?.takeIf { it >= 90 }?.let {
            drivers += Driver(Category.SLEEP, "You owe about ${Scoring.hm(it)} of sleep from recent nights", false)
        }
        val prev = days.getOrNull(days.indexOf(latest) - 1)
        prev?.strain?.takeIf { it >= 70 }?.let {
            drivers += Driver(Category.ACTIVITY, "Yesterday was a hard day (strain ${it.roundToInt()})", null)
        }

        if (latest.noSleep == true && rec == null) {
            val need = s.sleepNeedMin
            val nap = latest.napMin?.takeIf { it >= 20 }?.let { " You did get about ${Scoring.hm(it)} of naps." }.orEmpty()
            return Brief(
                headline = "You went without sleep",
                sub = "No sleep was found overnight, so recovery can't be scored today.$nap Keep effort light, a 20 to 30 minute nap before mid-afternoon helps, and aim for an early night: your body is about ${Scoring.hm(need.toDouble())} behind.",
                band = null, target = 5..30, drivers = drivers, learning = null,
            )
        }
        if (rec == null) {
            return Brief(
                headline = "Learning your normal",
                sub = "Loop needs a few nights of heart-rate data to know what is normal for you. " +
                    "Sleep scores and trends are already available.",
                band = null, target = null, drivers = drivers,
                learning = withHrv.coerceAtMost(BASELINE_GOAL) to BASELINE_GOAL,
            )
        }
        val band = Scoring.recoveryBand(rec)
        val target = Scoring.strainTarget(rec)
        val (headline, sub) = when (band) {
            Scoring.Band.HIGH -> "You're primed" to "Your body has bounced back well. It's a good day to push, so aim for strain ${target.first} to ${target.last}."
            Scoring.Band.MID -> "Steady day" to "You're partly recharged. Keep effort moderate today, and aim for strain ${target.first} to ${target.last}."
            Scoring.Band.LOW -> "Time to recharge" to "Your body is asking for rest. Keep today light (strain under ${target.last}) and protect tonight's sleep."
        }
        return Brief(headline, sub, band, target, drivers, null)
    }

    fun insights(days: List<DayRecord>, s: Settings): List<Insight> {
        val out = ArrayList<Insight>()
        if (days.size < 10) return out

        // 1) Longer nights -> higher recovery
        val dur = days.map { it.sleepMin }
        val rec = days.map { it.recovery }
        Stats.correlation(dur, rec)?.let { r ->
            if (r > 0.3) {
                val long = days.filter { (it.sleepMin ?: 0.0) >= s.sleepNeedMin * 0.95 }.mapNotNull { it.recovery }
                val short = days.filter { it.sleepMin != null && it.sleepMin < s.sleepNeedMin * 0.8 }.mapNotNull { it.recovery }
                val detail = if (long.size >= 3 && short.size >= 3)
                    " Full nights average ${long.average().roundToInt()}% recovery versus ${short.average().roundToInt()}% after short ones."
                else ""
                out += Insight(Category.SLEEP, "Sleep is your recovery lever", "Your recovery rises when you sleep longer.$detail", abs(r))
            }
        }

        // 2) Hard days cost tomorrow
        val pairsStrain = ArrayList<Double?>(); val pairsNext = ArrayList<Double?>()
        for (i in 0 until days.size - 1) { pairsStrain += days[i].strain; pairsNext += days[i + 1].recovery }
        Stats.correlation(pairsStrain, pairsNext)?.let { r ->
            if (r < -0.25) {
                val hi = pairsStrain.indices.filter { (pairsStrain[it] ?: 0.0) >= 65 }.mapNotNull { pairsNext[it] }
                val lo = pairsStrain.indices.filter { pairsStrain[it] != null && pairsStrain[it]!! < 40 }.mapNotNull { pairsNext[it] }
                val detail = if (hi.size >= 3 && lo.size >= 3)
                    " After high-strain days your next-morning recovery averages ${hi.average().roundToInt()}%, after light days ${lo.average().roundToInt()}%."
                else ""
                out += Insight(Category.ACTIVITY, "Hard days show up tomorrow", "Heavy training pulls down the next day's recovery.$detail", abs(r))
            }
        }

        // 3) Mood tracks recovery
        Stats.correlation(days.map { it.mood }, rec, minPairs = 8)?.let { r ->
            if (r > 0.25) out += Insight(Category.MIND, "You feel better when recovered", "Your mood check-ins track your recovery, so rest is also mood care.", abs(r))
            else if (r < -0.25) out += Insight(Category.MIND, "Mood and recovery pull apart", "Your mood is not following your recovery lately. Something besides physical load may be driving how you feel.", abs(r))
        }

        // 4) HRV trend, 7 vs 28 days
        val hrvs = days.map { it.hrv }
        val last7 = Stats.mean(hrvs.takeLast(7)); val last28 = Stats.mean(hrvs.takeLast(28))
        if (last7 != null && last28 != null && last28 > 0) {
            val pct = (last7 - last28) / last28 * 100
            if (pct >= 8) out += Insight(Category.RECOVERY, "HRV is trending up", "Your 7-day heart-rate variability is ${pct.roundToInt()}% above your 28-day average, a sign your fitness or recovery habits are working.", pct / 100)
            else if (pct <= -8) out += Insight(Category.RECOVERY, "HRV is trending down", "Your 7-day heart-rate variability is ${(-pct).roundToInt()}% below your 28-day average. Check stress, alcohol, sleep and training load.", -pct / 100)
        }

        // 5) Possible illness signature
        val rhrB = Stats.mean(days.dropLast(3).takeLast(28).map { it.rhr })
        val rhr3 = Stats.mean(days.takeLast(3).map { it.rhr })
        val resp3 = Stats.mean(days.takeLast(3).map { it.resp }); val respB = Stats.mean(days.dropLast(3).takeLast(28).map { it.resp })
        if (rhrB != null && rhr3 != null && rhr3 - rhrB >= 4 && resp3 != null && respB != null && resp3 - respB >= 0.6) {
            out += Insight(Category.BODY, "Your body may be fighting something", "Resting heart rate is up ${(rhr3 - rhrB).roundToInt()} bpm and breathing rate is elevated. Take it easy and watch for symptoms. This is not a diagnosis.", 1.0)
        }

        // 6) Best weekday
        val byDow = days.filter { it.recovery != null }.groupBy { DayOfWeek.from(it.date) }
        if (byDow.size >= 5 && days.size >= 28) {
            val means = byDow.mapValues { (_, v) -> v.mapNotNull { it.recovery }.average() }
            val best = means.maxByOrNull { it.value }!!; val worst = means.minByOrNull { it.value }!!
            if (best.value - worst.value >= 8) {
                out += Insight(
                    Category.RECOVERY, "${best.key.getDisplayName(TextStyle.FULL, Locale.getDefault())}s are your best days",
                    "You average ${best.value.roundToInt()}% recovery on ${best.key.getDisplayName(TextStyle.FULL, Locale.getDefault())}s and ${worst.value.roundToInt()}% on ${worst.key.getDisplayName(TextStyle.FULL, Locale.getDefault())}s.",
                    (best.value - worst.value) / 100,
                )
            }
        }

        // 6b) Training load spike
        days.lastOrNull()?.loadRatio?.let { r ->
            if (r > 1.5) out += Insight(Category.ACTIVITY, "Training load is spiking", "Your last 7 days are ${"%.1f".format(r)}x your 4-week norm. Sudden jumps like this are when injuries and illness appear, so ease off for a few days.", 0.9)
            else if (r < 0.7) out += Insight(Category.ACTIVITY, "Training has dropped off", "You've done much less than your 4-week norm lately. Recovery is up, but fitness slips if it continues.", 0.4)
        }

        // 7) Sleep streak
        var streak = 0
        for (d in days.reversed()) { if ((d.sleepMin ?: 0.0) >= s.sleepNeedMin * 0.9) streak++ else break }
        if (streak >= 3) out += Insight(Category.SLEEP, "$streak good nights in a row", "You have hit at least 90% of your sleep need for $streak nights straight. Keep the streak alive.", 0.3 + streak / 100.0)

        return out.sortedByDescending { it.strength }.take(6)
    }
}
