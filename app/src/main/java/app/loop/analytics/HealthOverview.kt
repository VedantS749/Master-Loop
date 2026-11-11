package app.loop.analytics

import app.loop.model.Category
import app.loop.model.DayRecord
import app.loop.model.Families
import app.loop.model.MetricDef
import app.loop.model.MetricId
import app.loop.model.Metrics
import app.loop.model.Settings
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Period(val tab: String, val label: String, val days: Int, val subject: String, val verb: String) {
    DAY("Last day", "Last day", 1, "Your last day", "looks"),
    WEEK("7 days", "Last 7 days", 7, "Your last 7 days", "look"),
    MONTH("30 days", "Last 30 days", 30, "Your last 30 days", "look"),
}

/** One metric across a period: its average, how that grades, and how often it landed in the good range. */
class MetricOverview(
    val def: MetricDef,
    val avg: Double, val min: Double, val max: Double, val n: Int,
    val range: GoodRange?,
    /** Grade of the average. */
    val grade: Grade?,
    /** 0 to 100: how well the individual days graded, on average. Null when the metric is not graded. */
    val score: Double?,
    val goodDays: Int, val gradedDays: Int,
    val prevAvg: Double?,
    val weight: Int,
    /** Only a day still in progress was available, so the value is a total so far and is not graded. */
    val partial: Boolean = false,
) {
    val change: Double? get() = prevAvg?.let { avg - it }

    /** true when the move since the previous period is good news, false when bad, null when neutral or too small to call. */
    val changeGood: Boolean?
        get() {
            val p = prevAvg ?: return null
            val diff = avg - p
            val rel = if (p != 0.0) abs(diff / p) else if (diff == 0.0) 0.0 else 1.0
            if (rel < 0.03) return null
            return when (def.higherIsBetter) { true -> diff > 0; false -> diff < 0; null -> null }
        }
}

class CategoryOverview(val category: Category, val score: Int?, val metrics: List<MetricOverview>) {
    val grade: Grade? get() = score?.let { HealthOverview.gradeFor(it) }
}

data class HealthOverview(
    val period: Period,
    val from: LocalDate?, val to: LocalDate?,
    val daysWithData: Int,
    val score: Int?,
    val prevScore: Int?,
    val categories: List<CategoryOverview>,
    val strengths: List<MetricOverview>,
    val focus: List<MetricOverview>,
) {
    val grade: Grade? get() = score?.let { gradeFor(it) }

    val label: String? get() = score?.let { wordFor(it) }

    val headline: String
        get() {
            val sc = score ?: return "Not enough data yet"
            return when {
                sc >= 85 -> "${period.subject} ${period.verb} excellent"
                sc >= 70 -> "${period.subject} ${period.verb} good"
                sc >= 55 -> "${period.subject} ${period.verb} fair, with room to improve"
                else -> "${period.subject} ${if (period == Period.DAY) "needs" else "need"} attention"
            }
        }

    /** One sentence naming the strongest and the weakest area, when there are at least two. */
    val detail: String?
        get() {
            val scored = categories.filter { it.score != null }
            if (scored.size < 2) return null
            val best = scored.maxByOrNull { it.score!! }!!; val worst = scored.minByOrNull { it.score!! }!!
            if (best === worst || best.score == worst.score) return null
            return "Strongest: ${best.category.title} (${best.score}). Most room to improve: ${worst.category.title} (${worst.score})."
        }

    val change: Int? get() = if (score != null && prevScore != null) score - prevScore else null

    companion object {
        fun gradeFor(score: Int) = when { score >= 70 -> Grade.GOOD; score >= 55 -> Grade.OK; else -> Grade.WATCH }
        fun wordFor(score: Int) = when { score >= 85 -> "Excellent"; score >= 70 -> "Good"; score >= 55 -> "Fair"; else -> "Needs attention" }
    }
}

/** A reading from the latest day, for the "right now" summary. */
class Reading(val def: MetricDef, val value: Double, val range: GoodRange?, val grade: Grade?, val partial: Boolean = false)

object HealthOverviews {
    /** Metrics that count double in a category score because they carry the most meaning. */
    private val KEY = setOf(MetricId.RECOVERY, MetricId.HRV, MetricId.RHR, MetricId.SLEEP_SCORE, MetricId.SLEEP_DURATION, MetricId.STRESS)

    /** Metrics that say how much data Loop has, not how healthy someone is. They are shown and graded but never scored. */
    private val COVERAGE = setOf(MetricId.WEAR_TIME, MetricId.DT_LOGGED)

    /** Totals that build up through the day, so a day still in progress is not judged against them anywhere in the app. */
    val RUNNING_TOTALS = setOf(MetricId.STEPS, MetricId.STRESS_HIGH, MetricId.CALORIES, MetricId.DT_KCAL, MetricId.WEAR_TIME, MetricId.DT_LOGGED)

    /** Summaries also hold back today's strain, which is only judged against the day's target once it is over. */
    private val CUMULATIVE = RUNNING_TOTALS + MetricId.STRAIN

    /** True when [id] is a running total and [day] is still today, so it is not final. */
    fun inProgress(id: MetricId, day: String?, today: LocalDate = LocalDate.now()): Boolean = id in RUNNING_TOTALS && day == today.toString()

    private val CATEGORY_WEIGHT = mapOf(
        Category.RECOVERY to 0.25, Category.SLEEP to 0.25, Category.ACTIVITY to 0.20,
        Category.MIND to 0.15, Category.BODY to 0.15, Category.DAYTIME to 0.10,
    )

    /** The metrics shown in the "right now" summary, in order. */
    val NOW = listOf(
        MetricId.RECOVERY, MetricId.SLEEP_SCORE, MetricId.SLEEP_DURATION, MetricId.HRV, MetricId.RHR,
        MetricId.STRAIN, MetricId.STRESS, MetricId.STEPS, MetricId.RESP,
    )

    private fun hasData(d: DayRecord) = d.hasAnything || d.recovery != null || d.sleepScore != null

    /** The day a summary is anchored to: the latest day with data, but never in the future. */
    fun anchor(days: List<DayRecord>, today: LocalDate = LocalDate.now()): LocalDate? {
        val last = days.lastOrNull { hasData(it) }?.date ?: return null
        return if (last.isAfter(today)) today else last
    }

    /** The person's recent normal for a metric: the 28 days before the latest one. */
    fun baselineFor(days: List<DayRecord>, def: MetricDef): Stats.Baseline? =
        Stats.baseline(days.takeLast(29).dropLast(1).map(def.read), minN = 4)

    fun build(period: Period, days: List<DayRecord>, s: Settings, today: LocalDate = LocalDate.now()): HealthOverview {
        val anchor = anchor(days, today) ?: return HealthOverview(period, null, null, 0, null, null, emptyList(), emptyList(), emptyList())
        val cur = compute(period, days, s, anchor, 0, today)
        val prev = compute(period, days, s, anchor, 1, today)
        return cur.copy(prevScore = prev.score)
    }

    private fun compute(period: Period, days: List<DayRecord>, s: Settings, anchor: LocalDate, offset: Int, today: LocalDate): HealthOverview {
        val n = period.days
        val end = anchor.minusDays((offset * n).toLong())
        val start = end.minusDays((n - 1).toLong())
        fun window(a: LocalDate, b: LocalDate) = days.filter { it.day >= a.toString() && it.day <= b.toString() }
        val inWin = window(start, end)
        val prevWin = window(start.minusDays(n.toLong()), start.minusDays(1))
        val withData = inWin.filter { hasData(it) }

        // Context for ranges that depend on the day: for one day it is that day, otherwise the period's averages.
        val ref = if (period == Period.DAY) inWin.lastOrNull() else DayRecord(
            end.toString(),
            recovery = Stats.mean(inWin.map { it.recovery }), sleepMin = Stats.mean(inWin.map { it.sleepMin }), rhr = Stats.mean(inWin.map { it.rhr }),
        )

        val metrics = Metrics.all.filter { Families.isListed(it.id) && Metrics.window(it.id) != app.loop.model.Window.DAY }.mapNotNull { def ->
            val all = inWin.mapNotNull { d -> def.read(d)?.let { d to it } }
            if (all.isEmpty()) return@mapNotNull null
            // A steps counter that reads zero every day is a sensor gap, not a sedentary person.
            if (def.id == MetricId.STEPS && all.all { it.second == 0.0 }) return@mapNotNull null
            // Totals are judged on finished days only; today's total so far is shown but not graded.
            val finished = all.filter { it.first.day != today.toString() }
            val partial = def.id in CUMULATIVE && finished.isEmpty()
            val vals = if (def.id in CUMULATIVE && finished.isNotEmpty()) finished else all
            val base = baselineFor(days, def)
            val values = vals.map { it.second }
            val avg = values.average()
            val range = GoodRanges.of(def, s, base, ref)
            var pts = 0; var graded = 0; var good = 0
            if (!partial) vals.forEach { (d, v) ->
                val g = GoodRanges.of(def, s, base, d)?.gradeOf(v)
                if (g != null) { graded++; pts += g.points; if (g == Grade.GOOD) good++ }
            }
            MetricOverview(
                def, avg, values.min(), values.max(), values.size, range, if (partial) null else range?.gradeOf(avg),
                if (graded > 0 && def.id !in COVERAGE) pts.toDouble() / graded else null, good, graded,
                prevWin.mapNotNull { def.read(it) }.takeIf { it.isNotEmpty() }?.average(),
                if (def.id in KEY) 2 else 1, partial,
            )
        }

        val categories = Category.entries.map { cat ->
            val ms = metrics.filter { it.def.category == cat }
            val scored = ms.filter { it.score != null }
            val score = if (scored.isEmpty()) null else (scored.sumOf { it.score!! * it.weight } / scored.sumOf { it.weight }).roundToInt()
            CategoryOverview(cat, score, ms)
        }.filter { it.metrics.isNotEmpty() }

        val gradedCount = metrics.count { it.score != null }
        val parts = categories.filter { it.score != null }
        // An overall score needs real substance: graded numbers from at least two areas other than live daytime estimates.
        val score = if (gradedCount < 3 || parts.count { it.category != Category.DAYTIME } < 2) null else {
            val w = parts.sumOf { CATEGORY_WEIGHT.getValue(it.category) }
            (parts.sumOf { it.score!! * CATEGORY_WEIGHT.getValue(it.category) } / w).roundToInt()
        }

        val scored = metrics.filter { it.score != null }
        val strengths = scored.filter { it.score!! >= 80 }.sortedWith(compareByDescending<MetricOverview> { it.weight }.thenByDescending { it.score }).take(3)
        val focus = scored.filter { it.score!! < 60 }.sortedWith(compareBy<MetricOverview> { it.score }.thenByDescending { it.weight }).take(3)

        return HealthOverview(period, start, end, withData.size, score, null, categories, strengths, focus)
    }

    /** The latest day's key readings, each graded, for the "right now" summary. */
    fun now(days: List<DayRecord>, s: Settings, today: LocalDate = LocalDate.now()): List<Reading> {
        val d = days.lastOrNull { hasData(it) } ?: return emptyList()
        return NOW.mapNotNull { id ->
            val def = Metrics[id]
            val v = def.read(d) ?: return@mapNotNull null
            if (id == MetricId.STEPS && v == 0.0) return@mapNotNull null // a counter stuck at zero is a sensor gap
            val range = GoodRanges.of(def, s, baselineFor(days, def), d)
            // A total still building up today is shown against its range but not judged.
            val inProgress = id in CUMULATIVE && d.day == today.toString()
            Reading(def, v, range, if (inProgress) null else range?.gradeOf(v), inProgress)
        }
    }
}
