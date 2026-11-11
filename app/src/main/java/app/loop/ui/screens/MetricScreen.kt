package app.loop.ui.screens

import app.loop.ui.theme.good
import app.loop.ui.theme.warn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.loop.analytics.Stats
import app.loop.model.Metrics
import app.loop.model.MetricId
import app.loop.ui.AppViewModel
import app.loop.ui.Dashboard
import app.loop.ui.color
import app.loop.ui.interpret
import app.loop.ui.components.DetailHeader
import app.loop.ui.components.EmptyState
import app.loop.ui.components.InfoButton
import app.loop.ui.components.LoopCard
import app.loop.ui.components.MetricInfoSheet
import app.loop.ui.components.Pill
import app.loop.ui.components.RangeBar
import app.loop.ui.components.RangeLegend
import app.loop.ui.components.RangeText
import app.loop.ui.components.VerdictPill
import app.loop.ui.components.goodRange
import app.loop.ui.components.gradeFor
import app.loop.ui.components.Segmented
import app.loop.ui.components.TrendChart
import app.loop.ui.components.format
import app.loop.ui.components.icon
import app.loop.ui.components.unitLabel
import app.loop.ui.components.short
import app.loop.ui.components.word
import app.loop.ui.theme.color
import java.time.format.DateTimeFormatter

private val ranges = listOf("7 days" to 7, "30 days" to 30, "90 days" to 90)

@Composable
fun MetricScreen(vm: AppViewModel, dash: Dashboard, id: MetricId) {
    val def = Metrics[id]
    val color = def.category.color(); val c = MaterialTheme.colorScheme
    val imp = dash.settings.imperial
    var rangeIdx by rememberSaveable { mutableIntStateOf(1) }
    var showInfo by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Int?>(null) }
    val n = ranges[rangeIdx].second
    val series = dash.series(def, n)
    val dates = dash.dates(n)
    val latest = dash.latestValue(def)
    val fixedPercent = id == MetricId.RECOVERY || id == MetricId.SLEEP_SCORE || id == MetricId.STRAIN || id == MetricId.STRESS || id == MetricId.SLEEP_EFFICIENCY

    val selIdx = selected?.takeIf { it in series.indices && series[it] != null }
    val shownValue = if (selIdx != null) series[selIdx] else latest?.second
    val shownDate = if (selIdx != null) dates[selIdx] else latest?.first?.date
    val delta = shownValue?.let { deltaVs(def, it, dash) }
    val ctxDay = selIdx?.let { i -> dash.days.firstOrNull { it.day == dates[i].toString() } } ?: latest?.first
    val range = remember(dash.days, dash.settings, def, ctxDay) { dash.goodRange(def, ctxDay) }
    val shownGrade = gradeFor(range, def, shownValue, ctxDay?.day)

    ScreenList {
        item {
            DetailHeader(def.title, { vm.pop() }, subtitle = def.category.title, trailing = { InfoButton({ showInfo = true }, tint = c.onSurfaceVariant) })
        }

        // The same measure at other times of day (or in another form) is a toggle here, not a separate metric.
        app.loop.model.Families.familyOf(id)?.takeIf { it.facets.size > 1 }?.let { fam ->
            item {
                Segmented(
                    fam.facets.map { it.label }, fam.facets.indexOfFirst { it.id == id },
                    { i -> val target = fam.facets[i].id; if (target != id) { vm.pop(); vm.openMetric(target) } }, color,
                )
            }
        }

        if (latest == null) {
            item { EmptyState(def.category.icon(), "No ${def.title.lowercase()} yet", "Loop hasn't seen any ${def.title.lowercase()} data. ${def.what}", color) }
        } else {
            item {
                Column {
                    Text(shownDate?.format(DateTimeFormatter.ofPattern("EEEE d MMM"))?.uppercase() ?: "", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp), color = c.outline)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(def.format(shownValue, imp), style = MaterialTheme.typography.displayLarge, color = c.onSurface)
                        val u = def.unitLabel(imp)
                        if (u.isNotEmpty()) Text(" $u", style = MaterialTheme.typography.titleMedium, color = c.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        VerdictPill(shownGrade)
                        def.word(shownValue)?.takeUnless { w -> shownGrade != null && (w.equals(shownGrade.short(), true) || w.equals(shownGrade.word, true)) }?.let { Pill(it, color) }
                        delta?.let { Text(it.text, style = MaterialTheme.typography.bodySmall, color = it.color()) }
                    }
                    if (range != null) RangeText(range, Modifier.padding(top = 8.dp), maxLines = 3)
                }
            }

            val selRec = selIdx?.let { i -> dash.days.firstOrNull { it.day == dates[i].toString() } }
            interpret(def, shownValue, dash, selRec)?.let { ip ->
                item {
                    LoopCard(accent = ip.tone.color()) {
                        Text("What this means for you", style = MaterialTheme.typography.labelSmall, color = c.outline)
                        Spacer(Modifier.height(4.dp))
                        Text(ip.headline, style = MaterialTheme.typography.titleLarge, color = ip.tone.color())
                        Spacer(Modifier.height(6.dp))
                        Text(ip.meaning, style = MaterialTheme.typography.bodyMedium, color = c.onSurface)
                        ip.vsNormal?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)) }
                        if (def.id == MetricId.RECOVERY) ctxDay?.takeIf { (it.recoveryConf ?: 2) < 2 }?.let { d ->
                            Text(
                                (if (d.recoveryConf == 0) "Low confidence. " else "Medium confidence. ") + (d.recoveryConfNote ?: "") + ".",
                                style = MaterialTheme.typography.labelMedium, color = c.warn, modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        if (range != null) {
                            Spacer(Modifier.height(12.dp))
                            Text(if (range.graded) "Where you sit" else "Guide", style = MaterialTheme.typography.labelSmall, color = c.outline)
                            if (range.graded) {
                                Spacer(Modifier.height(4.dp))
                                RangeBar(range, shownValue)
                                Spacer(Modifier.height(6.dp))
                                RangeLegend(range)
                            } else Text(range.good, style = MaterialTheme.typography.bodyMedium, color = c.onSurface)
                            Text(range.basis, style = MaterialTheme.typography.labelSmall, color = c.outline, modifier = Modifier.padding(top = 6.dp))
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("Why it matters", style = MaterialTheme.typography.labelSmall, color = c.outline)
                        Text(def.why, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    }
                }
            }

            item { Segmented(ranges.map { it.first }, rangeIdx, { rangeIdx = it; selected = null }, color) }

            item {
                LoopCard(padding = 12.dp) {
                    TrendChart(
                        series, color, height = 200.dp, yMin = if (fixedPercent && id != MetricId.SLEEP_EFFICIENCY) 0.0 else null, yMax = if (fixedPercent && id != MetricId.SLEEP_EFFICIENCY) 100.0 else null,
                        onSelect = { selected = it },
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        val f = DateTimeFormatter.ofPattern("d MMM")
                        Text(dates.first().format(f), style = MaterialTheme.typography.labelSmall, color = c.outline)
                        Text(dates[dates.size / 2].format(f), style = MaterialTheme.typography.labelSmall, color = c.outline)
                        Text("Today", style = MaterialTheme.typography.labelSmall, color = c.outline)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Shaded band = your usual range. Touch or drag the chart to read any day.", style = MaterialTheme.typography.labelSmall, color = c.outline)
                }
            }

            val events = app.loop.analytics.HealthLogs.events(dash.log, dates.first(), dates.last())
            if (events.isNotEmpty()) item {
                LoopCard(padding = 12.dp) {
                    Text("Around this chart", style = MaterialTheme.typography.labelMedium, color = c.outline)
                    events.take(4).forEach { e ->
                        Text("${e.day.format(DateTimeFormatter.ofPattern("d MMM"))}  ·  ${e.text}", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }

            item {
                val avg = Stats.mean(series); val hi = Stats.max(series); val lo = Stats.min(series)
                val best = if (def.higherIsBetter == false) lo else hi
                val worst = if (def.higherIsBetter == false) hi else lo
                LoopCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Stat("Average", def.format(avg, imp), c.onSurface)
                        if (def.higherIsBetter == null) { Stat("Highest", def.format(hi, imp), c.onSurface); Stat("Lowest", def.format(lo, imp), c.onSurface) }
                        else { Stat("Best", def.format(best, imp), c.good); Stat("Toughest", def.format(worst, imp), c.warn) }
                        Stat("Days", series.count { it != null }.toString(), c.onSurfaceVariant)
                    }
                }
            }

            item { Trend(def, series, color) }
        }

        item {
            LoopCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Info, null, tint = color, modifier = Modifier.width(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("About ${def.title}", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                }
                Spacer(Modifier.height(10.dp))
                Text(def.what, style = MaterialTheme.typography.bodyMedium, color = c.onSurface)
                Spacer(Modifier.height(8.dp))
                Text(def.why + (if (dash.settings.female) app.loop.model.Metrics.femaleNote(def.id)?.let { " $it" }.orEmpty() else ""), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(def.good, style = MaterialTheme.typography.bodyMedium, color = color)
            }
        }

        item {
            LoopCard {
                Text("Glossary", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                Spacer(Modifier.height(10.dp))
                Text("HOW LOOP WORKS IT OUT", style = MaterialTheme.typography.labelSmall, color = color)
                Spacer(Modifier.height(4.dp))
                Text(app.loop.model.Glossary.method(id), style = MaterialTheme.typography.bodyMedium, color = c.onSurface)
                Spacer(Modifier.height(12.dp))
                val win = Metrics.window(id)
                Text("PART OF THE DAY", style = MaterialTheme.typography.labelSmall, color = color)
                Spacer(Modifier.height(4.dp))
                Text(win.title + ". " + win.blurb + ".", style = MaterialTheme.typography.bodyMedium, color = c.onSurface)
                val terms = app.loop.model.Glossary.termsFor(id)
                if (terms.isNotEmpty()) { Spacer(Modifier.height(12.dp)); app.loop.ui.components.GlossaryTerms(terms, color) }
            }
        }

        val related = Metrics.listed(def.category).filter { it.id != id }
        if (related.isNotEmpty()) {
            item { Text("Related in ${def.category.title}", style = MaterialTheme.typography.titleMedium, color = c.onSurface, modifier = Modifier.padding(top = 4.dp)) }
            items(related, key = { it.id.name }) { r -> MetricRow(r, dash, n) { vm.openMetric(r.id) } }
        }
    }

    if (showInfo) MetricInfoSheet(def, { showInfo = false }, dash.settings.female)
}

@Composable
private fun Trend(def: app.loop.model.MetricDef, series: List<Double?>, color: androidx.compose.ui.graphics.Color) {
    val slope = Stats.slope(series) ?: return
    val mean = Stats.mean(series) ?: return
    if (mean == 0.0) return
    val total = slope * (series.size - 1)
    val pct = total / kotlin.math.abs(mean) * 100
    if (kotlin.math.abs(pct) < 4) return
    val up = pct > 0
    val good = when (def.higherIsBetter) { true -> up; false -> !up; null -> null }
    val c = MaterialTheme.colorScheme
    LoopCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(if (up) "Trending up" else "Trending down", when (good) { true -> c.good; false -> c.warn; null -> color })
            Spacer(Modifier.width(10.dp))
            Text("About ${kotlin.math.abs(pct).toInt()}% over this period", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = color)
    }
}
