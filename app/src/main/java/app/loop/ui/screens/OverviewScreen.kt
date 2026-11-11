package app.loop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.loop.analytics.CategoryOverview
import app.loop.analytics.Grade
import app.loop.analytics.HealthOverviews
import app.loop.analytics.HealthOverview
import app.loop.analytics.MetricOverview
import app.loop.analytics.Period
import app.loop.analytics.Reading
import app.loop.model.Category
import app.loop.ui.AppViewModel
import app.loop.ui.Dashboard
import app.loop.ui.Route
import app.loop.ui.components.DetailHeader
import app.loop.ui.components.EmptyState
import app.loop.ui.components.IconBadge
import app.loop.ui.components.LoopCard
import app.loop.ui.components.Pill
import app.loop.ui.components.RangeText
import app.loop.ui.components.ScoreRing
import app.loop.ui.components.Segmented
import app.loop.ui.components.SectionTitle
import app.loop.ui.components.VerdictPill
import app.loop.ui.components.color
import app.loop.ui.components.format
import app.loop.ui.components.icon
import app.loop.ui.components.unitLabel
import app.loop.ui.interpret
import app.loop.ui.theme.color
import app.loop.ui.theme.good
import app.loop.ui.theme.warn
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt
import app.loop.model.plural

private val tabs = listOf("Now") + Period.entries.map { it.tab }

/** Where the person stands right now, and over the last day, 7 days and 30 days, with what is good and what to work on. */
@Composable
fun OverviewScreen(vm: AppViewModel, dash: Dashboard) {
    val c = MaterialTheme.colorScheme
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val overviews = remember(dash.days, dash.settings) { Period.entries.associateWith { HealthOverviews.build(it, dash.days, dash.settings) } }
    val readings = remember(dash.days, dash.settings) { HealthOverviews.now(dash.days, dash.settings) }

    ScreenList {
        item {
            Column(Modifier.padding(top = 8.dp)) {
                Text("Overview", style = MaterialTheme.typography.headlineLarge, color = c.onSurface)
                Text("Where you stand, and what to do next.", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            }
        }
        item { Segmented(tabs, tab, { tab = it }, c.primary) }
        if (!dash.hasData) {
            item {
                EmptyState(
                    Icons.Rounded.MonitorHeart, "Nothing to show yet",
                    "Once Loop has your heart rate or your history, this page shows how you are doing right now and over the last day, 7 days and 30 days.",
                    c.primary, "Add data", { vm.push(Route.Devices) },
                )
            }
            return@ScreenList
        }
        if (tab == 0) nowItems(vm, dash, readings) else periodItems(vm, dash, overviews.getValue(Period.entries[tab - 1]))
        item { ScoreExplainer() }
    }
}

// =================================================================== right now

private fun LazyListScope.nowItems(vm: AppViewModel, dash: Dashboard, readings: List<Reading>) {
    item { NowHeadline(dash, readings) }
    item { RightNowCard(vm, dash) { vm.push(Route.Live) } }
    if (!dash.log.isEmpty) item { HealthLogCard(vm, dash) }
    dash.tonight?.let { t -> item { TonightCard(t, dash) } }
    item { NeedsAttention(vm, dash, readings) }
    if (readings.isNotEmpty()) item { ReadingsCard(vm, dash, readings) }
}

@Composable
private fun NowHeadline(dash: Dashboard, readings: List<Reading>) {
    val c = MaterialTheme.colorScheme
    val brief = dash.brief
    val graded = readings.filter { it.grade != null }
    val good = graded.count { it.grade == Grade.GOOD }
    val watch = graded.filter { it.grade == Grade.WATCH }
    val accent = when {
        graded.isEmpty() -> c.primary
        watch.size * 2 >= graded.size -> c.warn
        good * 2 >= graded.size -> c.good
        else -> c.primary
    }
    LoopCard(accent = accent) {
        Text("AT A GLANCE", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp), color = c.outline)
        Spacer(Modifier.height(4.dp))
        Text(brief?.headline ?: "Here is where you are", style = MaterialTheme.typography.headlineMedium, color = c.onSurface)
        (brief?.sub ?: "Loop is still learning your normal, so it cannot grade most numbers yet.").let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
        if (graded.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                graded.forEach { Box(Modifier.size(12.dp).clip(CircleShape).background(it.grade!!.color())) }
            }
            Spacer(Modifier.height(8.dp))
            val names = watch.joinToString(", ") { it.def.title.lowercase() }
            Text(
                "$good of ${graded.size} latest readings are in a good range" +
                    if (watch.isEmpty()) "." else ", and ${if (watch.size == 1) "one needs" else "${watch.size} need"} attention: $names.",
                style = MaterialTheme.typography.bodyMedium, color = c.onSurface,
            )
        }
    }
}

@Composable
private fun NeedsAttention(vm: AppViewModel, dash: Dashboard, readings: List<Reading>) {
    val c = MaterialTheme.colorScheme
    val watch = readings.filter { it.grade == Grade.WATCH }
    LoopCard {
        if (readings.none { it.grade != null }) {
            Text("Not enough to judge yet", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
            Text(
                "Recovery, sleep and heart-rate variability appear after your first full night with the strap on. Until then, today's totals so far are listed below and are not graded.",
                style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
            )
        } else if (watch.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, null, tint = c.good, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Nothing needs attention", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                    Text("None of your latest readings is outside its range.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
            }
        } else {
            Text("Needs attention now", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
            watch.forEachIndexed { i, r ->
                Column(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(10.dp)).clickable { vm.openMetric(r.def.id) }.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.def.title, style = MaterialTheme.typography.titleSmall, color = c.onSurface, modifier = Modifier.weight(1f))
                        Text(valueText(r.def, r.value, dash), style = MaterialTheme.typography.titleSmall, color = Grade.WATCH.color(), fontWeight = FontWeight.Bold)
                    }
                    RangeText(r.range)
                    interpret(r.def, r.value, dash, dash.latest)?.meaning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ReadingsCard(vm: AppViewModel, dash: Dashboard, readings: List<Reading>) {
    val c = MaterialTheme.colorScheme
    val day = dash.latest?.day?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val label = when (day) { null -> ""; LocalDate.now() -> "Today"; else -> day.format(DateTimeFormatter.ofPattern("EEEE d MMM")) }
    LoopCard {
        Text("Your latest readings", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
        if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        readings.forEachIndexed { i, r ->
            if (i > 0) Spacer(Modifier.height(2.dp))
            Column(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(10.dp)).clickable { vm.openMetric(r.def.id) }.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.def.title, style = MaterialTheme.typography.titleSmall, color = c.onSurface, modifier = Modifier.weight(1f))
                    Text(valueText(r.def, r.value, dash), style = MaterialTheme.typography.titleSmall, color = c.onSurface, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    if (r.partial) Text("so far today", style = MaterialTheme.typography.labelMedium, color = c.outline) else VerdictPill(r.grade)
                }
                RangeText(r.range)
            }
        }
    }
}

// =================================================================== a period

private fun LazyListScope.periodItems(vm: AppViewModel, dash: Dashboard, sum: HealthOverview) {
    item { PeriodHeader(sum) }
    if (sum.strengths.isNotEmpty()) item { Highlights("What is going well", sum, sum.strengths, dash, vm, positive = true) }
    if (sum.focus.isNotEmpty()) item { Highlights("Worth working on", sum, sum.focus, dash, vm, positive = false) }
    if (sum.categories.isEmpty()) return
    item { SectionTitle("By area", subtitle = "Tap an area, or any number, for the detail", modifier = Modifier.padding(top = 8.dp)) }
    sum.categories.forEach { cs -> item(key = "area_${cs.category.name}") { AreaCard(vm, dash, sum, cs) } }
}

private fun dateText(sum: HealthOverview): String {
    val f = sum.from ?: return ""; val t = sum.to ?: return ""
    val d = DateTimeFormatter.ofPattern("d MMM")
    return if (sum.period == Period.DAY) t.format(DateTimeFormatter.ofPattern("EEEE d MMM")) else "${f.format(d)} to ${t.format(d)}"
}

private fun prevName(p: Period) = if (p == Period.DAY) "the day before" else "the previous ${p.days} days"

@Composable
private fun PeriodHeader(sum: HealthOverview) {
    val c = MaterialTheme.colorScheme
    val grade = sum.grade
    val col = grade?.color() ?: c.primary
    LoopCard(accent = grade?.color()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScoreRing(sum.score?.let { it / 100f }, col, size = 96.dp, stroke = 10.dp) {
                Text(sum.score?.toString() ?: "–", style = MaterialTheme.typography.titleLarge.copy(fontSize = 30.sp, fontWeight = FontWeight.Bold), color = if (sum.score == null) c.outline else c.onSurface)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(sum.period.label.uppercase(), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp), color = c.outline)
                Text(sum.headline, style = MaterialTheme.typography.titleLarge, color = c.onSurface)
                sum.label?.let { Pill(it, col, Modifier.padding(top = 6.dp)) }
            }
        }
        sum.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.onSurface, modifier = Modifier.padding(top = 14.dp)) }
        sum.change?.let { ch ->
            val txt = when { ch == 0 -> "Same as ${prevName(sum.period)}"; ch > 0 -> "▲ $ch points vs ${prevName(sum.period)}"; else -> "▼ ${-ch} points vs ${prevName(sum.period)}" }
            Text(txt, style = MaterialTheme.typography.labelLarge, color = if (ch > 0) c.good else if (ch < 0) c.warn else c.outline, modifier = Modifier.padding(top = 8.dp))
        }
        val cov = buildString {
            append(dateText(sum))
            if (sum.period != Period.DAY) append("  ·  ${sum.daysWithData} of ${plural(sum.period.days, "day")} with data")
        }
        if (cov.isNotEmpty()) Text(cov, style = MaterialTheme.typography.labelMedium, color = c.outline, modifier = Modifier.padding(top = 6.dp))
        if (sum.score == null) {
            Text(
                "Loop needs a few graded numbers, such as recovery, sleep or steps, before it can score ${sum.period.label.lowercase()}. Keep wearing your strap and the score fills in.",
                style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun Highlights(title: String, sum: HealthOverview, items: List<MetricOverview>, dash: Dashboard, vm: AppViewModel, positive: Boolean) {
    val c = MaterialTheme.colorScheme
    val accent = if (positive) c.good else c.warn
    LoopCard(accent = accent) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = accent)
        items.forEach { ms ->
            Column(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(10.dp)).clickable { vm.openMetric(ms.def.id) }.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ms.def.title, style = MaterialTheme.typography.titleSmall, color = c.onSurface, modifier = Modifier.weight(1f))
                    Text(valueText(ms.def, ms.avg, dash), style = MaterialTheme.typography.titleSmall, color = c.onSurface, fontWeight = FontWeight.Bold)
                }
                RangeText(ms.range)
                if (sum.period != Period.DAY && ms.gradedDays > 0) {
                    Text("In the good range on ${ms.goodDays} of ${plural(ms.gradedDays, "day")}", style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                }
                if (!positive) {
                    // Advice only fits when the average itself is the problem; otherwise say why the number matters.
                    val note = if (ms.grade == Grade.WATCH) interpret(ms.def, ms.avg, dash, null)?.meaning else ms.def.why
                    note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
                }
            }
        }
    }
}

@Composable
private fun AreaCard(vm: AppViewModel, dash: Dashboard, sum: HealthOverview, cs: CategoryOverview) {
    val c = MaterialTheme.colorScheme
    val cat = cs.category
    LoopCard {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { vm.openCategory(cat) }.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(cat.icon(), cat.color(), 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(cat.title, style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                Text(if (cat == Category.DAYTIME) "Live estimates" else cat.tagline, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
            cs.score?.let { s -> Pill("$s", cs.grade!!.color()) }
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = cat.color(), modifier = Modifier.padding(start = 8.dp).size(16.dp))
        }
        cs.metrics.forEach { ms -> MetricLine(vm, dash, sum, ms) }
    }
}

@Composable
private fun MetricLine(vm: AppViewModel, dash: Dashboard, sum: HealthOverview, ms: MetricOverview) {
    val c = MaterialTheme.colorScheme
    val multi = sum.period != Period.DAY
    Column(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(10.dp)).clickable { vm.openMetric(ms.def.id) }.padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ms.def.title, style = MaterialTheme.typography.titleSmall, color = c.onSurface, modifier = Modifier.weight(1f))
            Text(valueText(ms.def, ms.avg, dash), style = MaterialTheme.typography.titleSmall, color = c.onSurface, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            VerdictPill(ms.grade)
        }
        RangeText(ms.range)
        val detail = buildList {
            if (ms.partial) add("So far today, not graded until the day is over")
            else if (multi) add("Average")
            if (multi && ms.n > 1) add("ranged ${ms.def.format(ms.min, dash.settings.imperial)} to ${ms.def.format(ms.max, dash.settings.imperial)}")
            if (multi && ms.gradedDays > 0) add("good on ${ms.goodDays} of ${plural(ms.gradedDays, "day")}")
        }.joinToString(" · ")
        if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.labelSmall, color = c.outline)
        trendText(ms, sum.period)?.let { (txt, good) ->
            Text(txt, style = MaterialTheme.typography.labelSmall, color = when (good) { true -> c.good; false -> c.warn; null -> c.outline })
        }
    }
}

/** "▲ 6% on the previous 7 days", with whether that is good news. Null when there is nothing to compare. */
internal fun trendText(ms: MetricOverview, p: Period): Pair<String, Boolean?>? {
    val prev = ms.prevAvg ?: return null
    val diff = ms.avg - prev
    val rel = if (prev != 0.0) abs(diff / prev) else if (diff == 0.0) 0.0 else 1.0
    if (rel < 0.03) return "Steady vs ${prevName(p)}" to null
    val arrow = if (diff > 0) "▲" else "▼"
    return "$arrow ${(rel * 100).roundToInt()}% vs ${prevName(p)}" to ms.changeGood
}

@Composable
private fun ScoreExplainer() {
    val c = MaterialTheme.colorScheme
    LoopCard {
        Text("How the score works", style = MaterialTheme.typography.titleSmall, color = c.onSurface)
        Text(
            "Loop grades every number you have against its good range, the same ranges shown next to each metric: good counts 100, okay 60, needs attention 20. " +
                "Each day is graded, then averaged. Recovery, heart-rate variability, resting heart rate, sleep and stress count double. " +
                "Areas are weighted: Recovery 25%, Sleep 25%, Activity 20%, Mind 15%, Body 15% and Daytime 10%. " +
                "Ranges for heart-rate variability, resting heart rate and breathing are built around your own normal, not a population average.",
            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
        )
        Text("Loop's numbers are estimates for general wellness, not medical advice.", style = MaterialTheme.typography.labelSmall, color = c.outline, modifier = Modifier.padding(top = 8.dp))
    }
}

private fun valueText(def: app.loop.model.MetricDef, v: Double, dash: Dashboard): String {
    val u = def.unitLabel(dash.settings.imperial)
    return def.format(v, dash.settings.imperial) + if (u.isNotEmpty()) " $u" else ""
}

/** A short look at what is being tracked in the health log: how healing is going, and what a medicine seems to do. */
@Composable
private fun HealthLogCard(vm: AppViewModel, dash: Dashboard) {
    val c = MaterialTheme.colorScheme
    val problems = dash.log.problems.filter { it.active }
    val statuses = androidx.compose.runtime.remember(dash.log, dash.days) { problems.map { app.loop.analytics.RecoveryChecks.status(it, dash.log.pain, dash.days) } }
    val meds = dash.log.medicines.filter { it.active }
    LoopCard(onClick = { vm.selectTab(app.loop.ui.Tab.LOG) }) {
        Text("Your health log", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
        statuses.forEach { st ->
            Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(st.problem.title, style = MaterialTheme.typography.titleSmall, color = c.onSurface)
                    Text("Day ${st.dayCount}  ·  ${st.trend.label.lowercase()}", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
                Text(st.readiness.label, style = MaterialTheme.typography.labelLarge, color = when (st.readiness) { app.loop.analytics.Readiness.REST -> c.warn; app.loop.analytics.Readiness.GENTLE -> c.primary; else -> c.good })
            }
            st.warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.warn, modifier = Modifier.padding(top = 4.dp)) }
        }
        if (meds.isNotEmpty()) {
            Text(
                "Taking " + meds.joinToString(", ") { it.name },
                style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/** How long to sleep tonight, and why that number. */
@Composable
private fun TonightCard(t: app.loop.analytics.TonightAdvice, dash: Dashboard) {
    val c = MaterialTheme.colorScheme
    LoopCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Tonight", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp), color = c.outline)
                Text("Aim for ${app.loop.analytics.SleepCoach.hm(t.targetMin)} of sleep", style = MaterialTheme.typography.titleLarge, color = c.onSurface)
            }
        }
        Text(
            if (t.reasons.isEmpty()) "That is your usual need of ${app.loop.analytics.SleepCoach.hm(t.baseMin)}." else "Your usual need is ${app.loop.analytics.SleepCoach.hm(t.baseMin)}, plus:",
            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
        )
        t.reasons.forEach { Text("•  $it", style = MaterialTheme.typography.bodySmall, color = c.onSurface, modifier = Modifier.padding(top = 2.dp)) }
        dash.sleepAdvice?.takeIf { it.personal }?.let { Text(it.basis, style = MaterialTheme.typography.labelSmall, color = c.outline, modifier = Modifier.padding(top = 8.dp)) }
    }
}
