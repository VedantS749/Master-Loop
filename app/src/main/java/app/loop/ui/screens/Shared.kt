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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.sp
import app.loop.model.MetricDef
import app.loop.ui.Dashboard
import app.loop.ui.ScreenPadding
import app.loop.ui.components.LoopCard
import app.loop.ui.components.RangeText
import app.loop.ui.components.Sparkline
import app.loop.ui.components.VerdictLine
import app.loop.ui.components.goodRange
import app.loop.ui.components.gradeFor
import androidx.compose.runtime.remember
import app.loop.ui.components.format
import app.loop.ui.components.short
import app.loop.ui.components.unitLabel
import app.loop.ui.components.word
import app.loop.ui.theme.color
import kotlin.math.abs

/** Standard scrolling screen body. */
@Composable
fun ScreenList(modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    LazyColumn(modifier, contentPadding = ScreenPadding, verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
}

/** Heading that separates night-time, daytime and whole-day metrics. */
@Composable
fun GroupHeader(w: app.loop.model.Window, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(w.title.uppercase(), style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp), color = MaterialTheme.colorScheme.primary)
        Text(w.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Compact dashboard tile: title, big value, plain-language word and a 14-day sparkline. */
@Composable
fun StatTile(def: MetricDef, dash: Dashboard, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = def.category.color()
    val latest = dash.latestValue(def)
    val v = latest?.second
    val imp = dash.settings.imperial
    LoopCard(modifier, onClick, padding = 12.dp) {
        Text(def.short(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(def.format(v, imp), style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold), color = if (v == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface, maxLines = 1)
            val u = def.unitLabel(imp)
            if (u.isNotEmpty() && v != null) { Spacer(Modifier.width(3.dp)); Text(u, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp)) }
        }
        val range = remember(dash.days, dash.settings, def) { dash.goodRange(def) }
        val grade = gradeFor(range, def, v, latest?.first?.day)
        val running = v != null && app.loop.analytics.HealthOverviews.inProgress(def.id, latest?.first?.day)
        if (v == null) Text("No data yet", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1)
        else if (running) Text("So far today", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline, maxLines = 1)
        else VerdictLine(grade, def.word(v), color, compact = true)
        RangeText(range, Modifier.padding(top = 2.dp), maxLines = 2, minLines = 2, compact = true)
        Spacer(Modifier.height(6.dp))
        Sparkline(dash.series(def, 14), color, Modifier.fillMaxWidth().height(26.dp))
    }
}

/** How a value compares with the user's own recent normal: text + whether that's good news. */
data class Delta(val text: String, val good: Boolean?)

fun deltaVs(def: MetricDef, value: Double, dash: Dashboard): Delta? {
    val base = dash.baseline(def) ?: return null
    val diff = value - base.mean
    val rel = if (base.mean != 0.0) abs(diff / base.mean) else 0.0
    if (rel < 0.03) return Delta("In line with your normal", null)
    val pct = (diff / base.mean * 100).toInt()
    val dir = if (diff > 0) "above" else "below"
    val good = when (def.higherIsBetter) { true -> diff > 0; false -> diff < 0; null -> null }
    return Delta("${abs(pct)}% $dir your normal", good)
}

@Composable
fun Delta.color(): Color = when (good) { true -> MaterialTheme.colorScheme.good; false -> MaterialTheme.colorScheme.warn; null -> MaterialTheme.colorScheme.onSurfaceVariant }
