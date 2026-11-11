package app.loop.ui.screens

import app.loop.ui.theme.activity
import app.loop.ui.theme.bad
import app.loop.ui.theme.body
import app.loop.ui.theme.good
import app.loop.ui.theme.mind
import app.loop.ui.theme.sleep
import app.loop.ui.theme.warn
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Sensors
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.loop.analytics.Scoring
import app.loop.model.Category
import app.loop.model.DayRecord
import app.loop.model.MetricDef
import app.loop.model.MetricId
import app.loop.model.Metrics
import app.loop.ui.AppViewModel
import app.loop.ui.Dashboard
import app.loop.ui.Route
import app.loop.ui.color
import app.loop.ui.interpret
import app.loop.ui.components.CategoryLabel
import app.loop.ui.components.DetailHeader
import app.loop.ui.components.EmptyState
import app.loop.ui.components.LoopCard
import app.loop.ui.components.PrimaryButton
import app.loop.ui.components.Pill
import app.loop.ui.components.ScoreRing
import app.loop.ui.components.Segmented
import app.loop.ui.components.SecondaryButton
import app.loop.ui.components.SectionTitle
import app.loop.ui.components.Sparkline
import app.loop.ui.components.StackedBar
import app.loop.ui.components.format
import app.loop.ui.components.icon
import app.loop.ui.components.RangeText
import app.loop.ui.components.VerdictLine
import app.loop.ui.components.goodRange
import app.loop.ui.components.gradeFor
import app.loop.ui.components.unitLabel
import app.loop.ui.components.word
import app.loop.ui.theme.color
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt
import app.loop.model.plural

private val ranges = listOf("7 days" to 7, "30 days" to 30, "90 days" to 90)
private val headlineOf = mapOf(
    Category.RECOVERY to MetricId.RECOVERY, Category.SLEEP to MetricId.SLEEP_SCORE, Category.ACTIVITY to MetricId.STRAIN,
    Category.BODY to MetricId.RESP, Category.MIND to MetricId.STRESS, Category.DAYTIME to MetricId.DT_HRV,
)

@Composable
fun CategoryScreen(vm: AppViewModel, dash: Dashboard, cat: Category) {
    val color = cat.color(); val c = MaterialTheme.colorScheme
    var rangeIdx by rememberSaveable { mutableIntStateOf(1) }
    val n = ranges[rangeIdx].second
    val main = Metrics[headlineOf.getValue(cat)]
    val latest = dash.latestValue(main)
    val cal = rememberCalendarState(dash, cat, main.id)

    ScreenList {
        item { DetailHeader(cat.title, { vm.pop() }, subtitle = cat.tagline) }

        item {
            Box(Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(color.copy(alpha = 0.30f), color.copy(alpha = 0.06f)))).padding(18.dp)) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val v = latest?.second
                        val isScore = main.id == MetricId.RECOVERY || main.id == MetricId.SLEEP_SCORE || main.id == MetricId.STRAIN || main.id == MetricId.STRESS
                        if (isScore) {
                            ScoreRing(v?.let { (it / 100).toFloat() }, color, size = 84.dp, stroke = 9.dp) {
                                Text(v?.roundToInt()?.toString() ?: "–", style = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold), color = c.onSurface)
                            }
                            Spacer(Modifier.width(16.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(main.title, style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                            if (!isScore) Text(main.format(v, dash.settings.imperial) + " " + main.unitLabel(dash.settings.imperial), style = MaterialTheme.typography.headlineMedium, color = c.onSurface)
                            Text(main.word(v) ?: if (v == null) "No data yet" else "", style = MaterialTheme.typography.titleLarge, color = color)
                            v?.let { deltaVs(main, it, dash) }?.let { Text(it.text, style = MaterialTheme.typography.bodySmall, color = it.color()) }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(cat.blurb, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                }
            }
        }

        if (!dash.hasData) {
            item { EmptyState(cat.icon(), "No ${cat.title.lowercase()} data yet", "Connect a device or import your history and this fills in.", color, "Add data", { vm.push(Route.Devices) }) }
            return@ScreenList
        }

        // ---- category-specific extras ----
        when (cat) {
            Category.RECOVERY -> item { DayDots("Recovery, last 14 days", dash.days.takeLast(14), { it.recovery }, { recoveryColor(it) }) }
            Category.SLEEP -> item { LastNightCard(dash) }
            Category.ACTIVITY -> item { ActivityExtras(vm, dash) }
            Category.MIND -> item { MindExtras(vm, dash) }
            Category.BODY -> item { BodyExtras(vm, dash) }
            Category.DAYTIME -> item { DaytimeExtras(vm, dash) }
        }

        item { SectionTitle("Browse by date", subtitle = "Pick any day or a range to see every ${cat.title.lowercase()} metric and what it means") }
        calendarSection(vm, dash, cat, cal)

        item { SectionTitle("Latest ${cat.title.lowercase()} metrics", subtitle = "Tap one for its trend", modifier = Modifier.padding(top = 8.dp)) }
        item { Segmented(ranges.map { it.first }, rangeIdx, { rangeIdx = it }, color) }
        Metrics.listed(cat).forEach { def ->
            item(key = def.id.name) { MetricRow(def, dash, n) { vm.openMetric(def.id) } }
        }
    }
}

@Composable
fun MetricRow(def: MetricDef, dash: Dashboard, n: Int, day: DayRecord? = null, onClick: () -> Unit) {
    val color = def.category.color(); val c = MaterialTheme.colorScheme
    val series = dash.series(def, n)
    val v = if (day != null) def.read(day) else dash.latestValue(def)?.second
    val interp = interpret(def, v, dash, day)
    val range = remember(dash.days, dash.settings, def, day) { dash.goodRange(def, day) }
    val grade = gradeFor(range, def, v, day?.day ?: dash.latestValue(def)?.first?.day)
    LoopCard(onClick = onClick, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(def.title, style = MaterialTheme.typography.titleMedium, color = c.onSurface, modifier = Modifier.weight(1f, fill = false))
                    app.loop.model.Metrics.window(def.id).tag.takeIf { it.isNotEmpty() }?.let { Spacer(Modifier.width(8.dp)); Pill(it, c.outline) }
                }
                Spacer(Modifier.height(2.dp))
                VerdictLine(grade, interp?.headline ?: if (v == null) "No data yet" else "Tap for trend", interp?.tone?.color() ?: c.onSurfaceVariant)
            }
            if (day == null) { Sparkline(series, color, Modifier.width(80.dp).height(34.dp)); Spacer(Modifier.width(14.dp)) }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(76.dp)) {
                Text(def.format(v, dash.settings.imperial), style = MaterialTheme.typography.titleLarge, color = if (v == null) c.outline else c.onSurface, fontWeight = FontWeight.Bold)
                val u = def.unitLabel(dash.settings.imperial)
                if (u.isNotEmpty() && v != null) Text(u, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
            }
        }
        if (range != null) { Spacer(Modifier.height(8.dp)); RangeText(range) }
        if (interp != null) {
            Spacer(Modifier.height(6.dp))
            Text(interp.meaning, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            interp.vsNormal?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = c.outline, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
}

@Composable
private fun recoveryColor(v: Double): Color = when (Scoring.recoveryBand(v)) { Scoring.Band.HIGH -> MaterialTheme.colorScheme.good; Scoring.Band.MID -> MaterialTheme.colorScheme.warn; Scoring.Band.LOW -> MaterialTheme.colorScheme.bad }

/** A row of coloured day dots (e.g. recovery by day) with weekday initials. */
@Composable
private fun DayDots(title: String, days: List<DayRecord>, read: (DayRecord) -> Double?, colorFor: @Composable (Double) -> Color) {
    LoopCard {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            days.forEach { d ->
                val v = read(d)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(16.dp).clip(CircleShape).background(if (v == null) MaterialTheme.colorScheme.outlineVariant else colorFor(v)))
                    Spacer(Modifier.height(4.dp))
                    Text(LocalDate.parse(d.day).dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()), style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp), color = MaterialTheme.colorScheme.outline)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend(MaterialTheme.colorScheme.good, "Push"); Legend(MaterialTheme.colorScheme.warn, "Steady"); Legend(MaterialTheme.colorScheme.bad, "Recharge")
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color)); Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LastNightCard(dash: Dashboard) {
    val c = MaterialTheme.colorScheme
    val d = dash.days.lastOrNull { it.sleepMin != null } ?: return
    val need = dash.settings.sleepNeedMin
    val asleep = d.sleepMin ?: 0.0
    val deepC = c.primary; val remC = c.tertiary; val lightC = c.sleep.copy(alpha = 0.45f); val awakeC = c.outlineVariant
    LoopCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryLabel(Category.SLEEP)
            Spacer(Modifier.weight(1f))
            Text(LocalDate.parse(d.day).format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")), style = MaterialTheme.typography.labelSmall, color = c.outline)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Scoring.hm(asleep), style = MaterialTheme.typography.headlineLarge, color = c.onSurface)
            Text("  of your ${Scoring.hm(need.toDouble())} need", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        }
        Spacer(Modifier.height(10.dp))
        if (d.deepMin != null && d.remMin != null && d.lightMin != null) {
            StackedBar(listOf(d.deepMin to deepC, d.remMin to remC, d.lightMin to lightC, (d.awakeMin ?: 0.0) to awakeC))
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stage("Deep", d.deepMin, asleep, deepC); Stage("REM", d.remMin, asleep, remC); Stage("Light", d.lightMin, asleep, lightC); Stage("Awake", d.awakeMin ?: 0.0, null, c.onSurfaceVariant)
            }
        } else Text("Stage detail isn't available for this night.", style = MaterialTheme.typography.bodySmall, color = c.outline)
        d.sleepDebtMin?.takeIf { it > 30 }?.let {
            Spacer(Modifier.height(12.dp))
            Text("Sleep debt: ${Scoring.hm(it)}. An earlier night will help pay it back.", style = MaterialTheme.typography.bodySmall, color = c.warn)
        }
    }
}

@Composable
private fun Stage(label: String, min: Double, of: Double?, color: Color) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color)); Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(Scoring.hm(min), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        if (of != null && of > 0) Text("${(min / of * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun ActivityExtras(vm: AppViewModel, dash: Dashboard) {
    val c = MaterialTheme.colorScheme
    val weekAgo = java.time.Instant.now().epochSecond - 7 * 86400
    val week = dash.workouts.filter { it.id >= weekAgo }
    val zones = DoubleArray(5).also { z -> week.forEach { w -> w.zoneMin.forEachIndexed { i, m -> z[i] += m } } }
    val zoneColors = listOf(c.sleep.copy(alpha = 0.5f), c.secondary, c.warn, c.activity, c.bad)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LoopCard {
            Text("This week", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Big("${week.size}", "workouts"); Big(Scoring.hm(week.sumOf { it.durationMin }), "active time"); Big("${week.sumOf { it.kcal ?: 0.0 }.roundToInt()}", "kcal")
            }
            if (zones.sum() > 0) {
                Spacer(Modifier.height(14.dp))
                StackedBar(zones.mapIndexed { i, m -> m to zoneColors[i] })
                Spacer(Modifier.height(6.dp))
                Text("Time in each heart-rate zone, warm-up to peak", style = MaterialTheme.typography.labelSmall, color = c.outline)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { PrimaryButton("Record live", { vm.push(Route.Live) }, icon = Icons.Rounded.Sensors, color = c.activity) }
            Box(Modifier.weight(1f)) { SecondaryButton("Log workout", { vm.push(Route.LogWorkout) }, icon = Icons.Rounded.Add) }
        }
        LoopCard(onClick = { vm.push(Route.Strength) }) {
            Text("Strength log", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
            Text("${plural(dash.strength.size, "session")} · sets, reps, estimated one-rep max", style = MaterialTheme.typography.bodySmall, color = c.activity)
        }
        if (dash.workouts.isNotEmpty()) {
            LoopCard(onClick = { vm.push(Route.Workouts) }) {
                Text("Latest: ${dash.workouts.first().sport}, ${Scoring.hm(dash.workouts.first().durationMin)}", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                Text("See all ${plural(dash.workouts.size, "workout")}", style = MaterialTheme.typography.bodySmall, color = c.activity)
            }
        }
    }
}

@Composable
private fun Big(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MindExtras(vm: AppViewModel, dash: Dashboard) {
    val c = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DayDots("Mood, last 14 days", dash.days.takeLast(14), { it.mood }, { v -> if (v >= 4) c.good else if (v >= 2.5) c.warn else c.bad })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { PrimaryButton("Check in", { vm.push(Route.MoodCheckIn) }, color = c.mind) }
            Box(Modifier.weight(1f)) { SecondaryButton("Breathe", { vm.push(Route.Breathe) }, icon = Icons.Rounded.Air) }
        }
        LoopCard(onClick = { vm.push(Route.Journal) }, accent = c.mind) {
            Text("Journal", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
            Text(
                if (dash.journalEffects.isEmpty()) "Log caffeine, alcohol, late meals and more to see what really affects you"
                else dash.journalEffects.count { it.confidence != app.loop.analytics.Confidence.UNCLEAR }.let { n -> "${plural(n, "factor")} ${if (n == 1) "shows" else "show"} an effect on your recovery" },
                style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DaytimeExtras(vm: AppViewModel, dash: Dashboard) {
    val c = MaterialTheme.colorScheme
    val today = dash.days.lastOrNull { it.day == java.time.LocalDate.now().toString() }
    LoopCard(onClick = { vm.push(Route.Live) }, accent = c.warn) {
        Text("See it live", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
        Text(
            if (today?.dtMin != null) "Today so far: ${Scoring.hm(today.dtMin)} logged. Open the live view for your numbers right now."
            else "Connect your strap and Loop works these out live, then logs them every minute.",
            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
        )
    }
    Text("Daytime metrics are estimates, logged separately from your overnight scores.", style = MaterialTheme.typography.labelSmall, color = c.outline, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun BodyExtras(vm: AppViewModel, dash: Dashboard) {
    if (!dash.settings.female) return
    val c = MaterialTheme.colorScheme
    val info = app.loop.analytics.CycleEngine.info(java.time.LocalDate.now(), dash.cycleStarts)
    LoopCard(onClick = { vm.push(Route.Cycle) }, accent = c.body) {
        Text("Cycle tracking", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
        Text(
            when {
                !dash.settings.cycleTracking -> "Optional. See how your cycle shapes recovery, HRV and temperature."
                info == null -> "Log your last period to see your phase."
                else -> "Day ${info.dayOfCycle}, ${info.phase.label} phase"
            },
            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
        )
    }
}
