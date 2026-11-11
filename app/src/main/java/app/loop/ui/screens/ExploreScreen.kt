package app.loop.ui.screens

import app.loop.ui.theme.activity
import app.loop.ui.theme.warn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.loop.model.Category
import app.loop.model.MetricId
import app.loop.model.window
import app.loop.model.Metrics
import app.loop.ui.AppViewModel
import app.loop.ui.Dashboard
import app.loop.ui.Route
import app.loop.ui.components.EmptyState
import app.loop.ui.components.IconBadge
import app.loop.ui.components.LoopCard
import app.loop.ui.components.Sparkline
import app.loop.ui.components.format
import app.loop.ui.components.icon
import app.loop.ui.components.unitLabel
import app.loop.ui.components.word
import app.loop.ui.theme.color

private val headline = mapOf(
    Category.RECOVERY to MetricId.RECOVERY, Category.SLEEP to MetricId.SLEEP_SCORE, Category.ACTIVITY to MetricId.STRAIN,
    Category.BODY to MetricId.RESP, Category.MIND to MetricId.STRESS, Category.DAYTIME to MetricId.DT_HRV,
)

@Composable
fun ExploreScreen(vm: AppViewModel, dash: Dashboard) {
    val c = MaterialTheme.colorScheme
    ScreenList {
        item {
            Column(Modifier.padding(top = 8.dp)) {
                Text("Explore", style = MaterialTheme.typography.headlineLarge, color = c.onSurface)
                Text("Every number Loop tracks, sorted by what it tells you.", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            }
        }

        item {
            Text("What we're noticing", style = MaterialTheme.typography.titleLarge, color = c.onSurface)
            Spacer(Modifier.height(10.dp))
            if (dash.insights.isEmpty()) {
                LoopCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Rounded.Lightbulb, c.warn, 40.dp)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("Insights unlock after about 10 days", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                            Text("Loop looks for links between your sleep, training, mood and recovery.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                        }
                    }
                }
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    dash.insights.forEach { InsightCard(it, Modifier.width(290.dp)) }
                }
            }
        }

        item { Text("By category", style = MaterialTheme.typography.titleLarge, color = c.onSurface, modifier = Modifier.padding(top = 4.dp)) }

        if (!dash.hasData) item {
            EmptyState(Icons.Rounded.Lightbulb, "Nothing to explore yet", "Add data from You, or try sample data, and every category fills in.", c.primary, "Add data", { vm.push(Route.Devices) })
        }

        run {
        app.loop.model.Families.categories.forEach { cat ->
            item(key = cat.name) {
                val def = Metrics[headline.getValue(cat)]
                val latest = dash.latestValue(def)
                val color = cat.color()
                LoopCard(onClick = { vm.openCategory(cat) }, accent = color) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(cat.icon(), color, 44.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(cat.title, style = MaterialTheme.typography.titleLarge, color = c.onSurface)
                            Text(cat.tagline, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                        }
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = color)
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            Text(def.short2(), style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(def.format(latest?.second, dash.settings.imperial), style = MaterialTheme.typography.headlineMedium.copy(fontSize = 30.sp), color = if (latest == null) c.outline else c.onSurface)
                                val u = def.unitLabel(dash.settings.imperial)
                                if (u.isNotEmpty() && latest != null) Text(" $u", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(bottom = 5.dp))
                            }
                            Text(def.word(latest?.second) ?: "${Metrics.listed(cat).size} metrics inside", style = MaterialTheme.typography.labelMedium, color = color)
                        }
                        Sparkline(dash.series(def, 30), color, Modifier.width(120.dp).height(46.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(Metrics.listed(cat).joinToString("  ·  ") { it.title }, style = MaterialTheme.typography.labelSmall, color = c.outline, maxLines = 2)
                }
            }
        }
        }

        item { AdvancedNumbers(vm, dash) }

        item {
            LoopCard(onClick = { vm.push(Route.Workouts) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.FitnessCenter, c.activity, 44.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Workouts", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                        Text("${dash.workouts.size} logged · zones, strain and heart rate", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = c.activity)
                }
            }
        }
    }
}

private fun app.loop.model.MetricDef.short2() = when (id) {
    MetricId.RECOVERY -> "Recovery today"
    MetricId.SLEEP_SCORE -> "Last night's sleep"
    MetricId.STRAIN -> "Strain today"
    MetricId.RESP -> "Breathing rate"
    MetricId.STRESS -> "Body stress"
    else -> title
}

/** Specialist and data-quality numbers, kept out of everyday lists but one tap away. */
@Composable
private fun AdvancedNumbers(vm: AppViewModel, dash: Dashboard) {
    val c = MaterialTheme.colorScheme
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    LoopCard(onClick = { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Advanced numbers", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                Text("Variability detail, autonomic balance, coherence and data coverage", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
            Text(if (open) "Hide" else "Show", style = MaterialTheme.typography.labelLarge, color = c.primary)
        }
        if (open) {
            Metrics.all.filter { it.id in app.loop.model.Families.advanced }.forEach { def ->
                val v = dash.latestValue(def)?.second
                Row(Modifier.fillMaxWidth().padding(top = 12.dp).clickable { vm.openMetric(def.id) }, verticalAlignment = Alignment.CenterVertically) {
                    Text(def.title, style = MaterialTheme.typography.bodyLarge, color = c.onSurface, modifier = Modifier.weight(1f))
                    Text(def.format(v, dash.settings.imperial) + def.unitLabel(dash.settings.imperial).let { if (it.isEmpty() || v == null) "" else " $it" }, style = MaterialTheme.typography.titleSmall, color = if (v == null) c.outline else c.onSurface)
                }
            }
        }
    }
}
