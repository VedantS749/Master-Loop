package app.loop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.loop.analytics.Scoring
import app.loop.analytics.Stats
import app.loop.model.Category
import app.loop.model.DayRecord
import app.loop.model.MetricDef
import app.loop.model.MetricId
import app.loop.model.Metrics
import app.loop.ui.AppViewModel
import app.loop.ui.Dashboard
import app.loop.ui.color
import app.loop.ui.components.LoopCard
import app.loop.ui.components.Segmented
import app.loop.ui.components.format
import app.loop.ui.components.short
import app.loop.ui.components.unitLabel
import app.loop.ui.interpret
import app.loop.ui.theme.bad
import app.loop.ui.theme.color
import app.loop.ui.theme.good
import app.loop.ui.theme.warn
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Selection state for the calendar. Lives for as long as the category screen is open. */
class CalendarState(initial: LocalDate, val shade: MetricId) {
    var month by mutableStateOf(YearMonth.from(initial))
    var shadeId by mutableStateOf(shade)
    var rangeMode by mutableStateOf(false)
    var day by mutableStateOf<LocalDate?>(initial)
    var from by mutableStateOf<LocalDate?>(null)
    var to by mutableStateOf<LocalDate?>(null)

    fun tap(d: LocalDate) {
        if (!rangeMode) { day = d; return }
        val f = from; val t = to
        when {
            f == null || t != null -> { from = d; to = null }
            d.isBefore(f) -> { to = f; from = d }
            else -> to = d
        }
    }
}

@Composable
fun rememberCalendarState(dash: Dashboard, cat: Category, shade: MetricId): CalendarState {
    val latest = dash.latest?.let { runCatching { LocalDate.parse(it.day) }.getOrNull() } ?: LocalDate.now()
    return remember(cat) { CalendarState(latest, shade) }
}

/** Calendar plus day or range detail for a category, emitted as list items. */
fun LazyListScope.calendarSection(vm: AppViewModel, dash: Dashboard, cat: Category, st: CalendarState) {
    item(key = "cal") { CalendarCard(dash, cat, st) }
    val defs = Metrics.listed(cat)
    if (!st.rangeMode) {
        val d = st.day
        if (d != null) {
            val rec = dash.days.firstOrNull { it.day == d.toString() }
            item(key = "dayhead") {
                Column(Modifier.padding(top = 4.dp)) {
                    Text(d.format(DateTimeFormatter.ofPattern("EEEE d MMMM")), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text("Every ${cat.title.lowercase()} metric for this day, and what it means", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (cat == Category.MIND) item(key = "stressline") { StressTimelineCard(vm, d) }
            if (rec == null || defs.all { it.read(rec) == null }) {
                item(key = "dayempty") {
                    LoopCard { Text("No ${cat.title.lowercase()} data was recorded for this day.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            } else defs.forEach { def -> item(key = "d_${def.id}") { MetricRow(def, dash, 30, day = rec) { vm.openMetric(def.id) } } }
        }
    } else {
        val f = st.from; val t = st.to
        item(key = "rangehead") {
            Column(Modifier.padding(top = 4.dp)) {
                val fmt = DateTimeFormatter.ofPattern("d MMM")
                Text(
                    when { f == null -> "Pick a start date"; t == null -> "From ${f.format(fmt)}. Now pick an end date"; else -> "${f.format(fmt)} to ${t.format(fmt)}" },
                    style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface,
                )
                Text("Day-by-day values, averages and what the averages mean", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (f != null && t != null) item(key = "rangetable") { RangeTable(vm, dash, cat, f, t) }
    }
}

@Composable
private fun CalendarCard(dash: Dashboard, cat: Category, st: CalendarState) {
    val c = MaterialTheme.colorScheme
    val accent = cat.color()
    val defs = Metrics.listed(cat)
    val shade = Metrics[st.shadeId]
    val byDay = remember(dash.days) { dash.days.associateBy { it.day } }
    val values = remember(dash.days, st.shadeId) { dash.days.mapNotNull { shade.read(it) } }
    val lo = values.minOrNull() ?: 0.0; val hi = values.maxOrNull() ?: 1.0
    val fixed100 = shade.id in setOf(MetricId.RECOVERY, MetricId.SLEEP_SCORE, MetricId.STRAIN, MetricId.STRESS, MetricId.SLEEP_EFFICIENCY)
    val today = LocalDate.now()
    val first = dash.days.firstOrNull()?.let { runCatching { LocalDate.parse(it.day) }.getOrNull() }

    LoopCard {
        Segmented(listOf("Single day", "Date range"), if (st.rangeMode) 1 else 0, { st.rangeMode = it == 1; if (it == 1) { st.from = null; st.to = null } }, accent)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ st.month = st.month.minusMonths(1) }, enabled = first == null || st.month.atDay(1).isAfter(first.withDayOfMonth(1))) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous month") }
            Text(st.month.format(DateTimeFormatter.ofPattern("MMMM yyyy")), style = MaterialTheme.typography.titleMedium, color = c.onSurface, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            IconButton({ st.month = st.month.plusMonths(1) }, enabled = st.month.isBefore(YearMonth.from(today))) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next month") }
        }
        Row(Modifier.fillMaxWidth()) {
            for (i in 0 until 7) {
                val dow = DayOfWeek.MONDAY.plus(i.toLong())
                Text(dow.getDisplayName(TextStyle.NARROW, Locale.getDefault()), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = c.outline)
            }
        }
        Spacer(Modifier.height(4.dp))
        val firstOfMonth = st.month.atDay(1)
        val lead = firstOfMonth.dayOfWeek.value - 1
        val total = lead + st.month.lengthOfMonth()
        val rows = (total + 6) / 7
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val idx = r * 7 + col
                    val dayNum = idx - lead + 1
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                        if (dayNum in 1..st.month.lengthOfMonth()) {
                            val date = st.month.atDay(dayNum)
                            val rec = byDay[date.toString()]
                            val v = rec?.let { shade.read(it) }
                            val future = date.isAfter(today)
                            val inRange = st.rangeMode && st.from != null && ((st.to == null && date == st.from) || (st.to != null && !date.isBefore(st.from) && !date.isAfter(st.to)))
                            val selected = if (st.rangeMode) inRange else st.day == date
                            val fill = when {
                                v == null -> Color.Transparent
                                shade.id == MetricId.RECOVERY -> when (Scoring.recoveryBand(v)) { Scoring.Band.HIGH -> c.good; Scoring.Band.MID -> c.warn; Scoring.Band.LOW -> c.bad }.copy(alpha = 0.75f)
                                else -> {
                                    val frac = if (fixed100) v / 100.0 else if (hi > lo) (v - lo) / (hi - lo) else 0.5
                                    accent.copy(alpha = (0.18 + 0.72 * frac.coerceIn(0.0, 1.0)).toFloat())
                                }
                            }
                            Box(
                                Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape).background(fill)
                                    .then(if (v == null) Modifier.border(1.dp, c.outlineVariant, CircleShape) else Modifier)
                                    .then(if (selected) Modifier.border(2.5.dp, c.onSurface, CircleShape) else Modifier)
                                    .clickable(enabled = !future) { st.tap(date) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "$dayNum", style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium),
                                    color = when { future -> c.outlineVariant; v == null -> c.onSurfaceVariant; else -> c.onSurface },
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("Shade days by", style = MaterialTheme.typography.labelSmall, color = c.outline)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            defs.forEach { d -> FilterChip(selected = d.id == st.shadeId, onClick = { st.shadeId = d.id }, label = { Text(d.short()) }) }
        }
        Text("Darker means a higher value. Empty rings are days without data.", style = MaterialTheme.typography.labelSmall, color = c.outline, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun RangeTable(vm: AppViewModel, dash: Dashboard, cat: Category, from: LocalDate, to: LocalDate) {
    val c = MaterialTheme.colorScheme
    val defs = Metrics.listed(cat)
    val imp = dash.settings.imperial
    val byDay = remember(dash.days) { dash.days.associateBy { it.day } }
    val days = remember(from, to) { generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.take(62).toList() }
    val recs: List<DayRecord?> = days.map { byDay[it.toString()] }
    val colW = 86.dp; val rowH = 38.dp
    val dFmt = DateTimeFormatter.ofPattern("EEE d")

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LoopCard(padding = 12.dp) {
            Row {
                Column {
                    Box(Modifier.height(rowH).width(64.dp), contentAlignment = Alignment.CenterStart) { Text("Day", style = MaterialTheme.typography.labelMedium, color = c.outline) }
                    days.forEach { Box(Modifier.height(rowH).width(64.dp), contentAlignment = Alignment.CenterStart) { Text(it.format(dFmt), style = MaterialTheme.typography.labelMedium, color = c.onSurface) } }
                    Box(Modifier.height(rowH).width(64.dp), contentAlignment = Alignment.CenterStart) { Text("Average", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = cat.color()) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    defs.forEach { def ->
                        Column {
                            Box(Modifier.height(rowH).width(colW), contentAlignment = Alignment.CenterEnd) {
                                Text(def.short() + def.unitLabel(imp).let { if (it.isEmpty()) "" else " ($it)" }, style = MaterialTheme.typography.labelSmall, color = c.outline, textAlign = TextAlign.End, maxLines = 2)
                            }
                            recs.forEach { r ->
                                val v = r?.let { def.read(it) }
                                Box(Modifier.height(rowH).width(colW), contentAlignment = Alignment.CenterEnd) {
                                    Text(def.format(v, imp), style = MaterialTheme.typography.bodyMedium, color = if (v == null) c.outlineVariant else c.onSurface)
                                }
                            }
                            val avg = Stats.mean(recs.map { r -> r?.let { def.read(it) } })
                            Box(Modifier.height(rowH).width(colW), contentAlignment = Alignment.CenterEnd) {
                                Text(def.format(avg, imp), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = cat.color())
                            }
                        }
                    }
                }
            }
        }
        Text("What the averages mean", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
        defs.forEach { def ->
            val avg = Stats.mean(recs.map { r -> r?.let { def.read(it) } })
            val ip = interpret(def, avg, dash, null)
            LoopCard(onClick = { vm.openMetric(def.id) }, padding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(def.title, style = MaterialTheme.typography.titleMedium, color = c.onSurface, modifier = Modifier.weight(1f))
                    Text(def.format(avg, imp) + def.unitLabel(imp).let { if (it.isEmpty()) "" else " $it" }, style = MaterialTheme.typography.titleMedium, color = if (avg == null) c.outline else c.onSurface)
                }
                if (ip != null) {
                    Text(ip.headline, style = MaterialTheme.typography.labelLarge, color = ip.tone.let { t -> when (t) { app.loop.ui.Tone.GOOD -> c.good; app.loop.ui.Tone.WARN -> c.warn; app.loop.ui.Tone.OK -> c.primary; else -> c.onSurfaceVariant } })
                    Text(ip.meaning, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                } else Text("No data in this range.", style = MaterialTheme.typography.bodySmall, color = c.outline)
            }
        }
    }
}
