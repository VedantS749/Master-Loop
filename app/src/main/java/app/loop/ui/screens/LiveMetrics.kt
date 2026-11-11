package app.loop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.loop.analytics.Scoring
import app.loop.whoop.StrapAnalyzer
import app.loop.ble.HeartRateMonitor
import app.loop.analytics.Grade
import app.loop.model.MetricId
import app.loop.model.Metrics
import app.loop.ui.AppViewModel
import app.loop.ui.components.VerdictLine
import app.loop.ui.components.goodRange
import app.loop.ui.Dashboard
import app.loop.ui.components.LoopCard
import app.loop.ui.components.Pill
import app.loop.ui.theme.good
import app.loop.ui.theme.warn
import java.time.LocalDate
import kotlin.math.roundToInt

/** One live number. [solid] is the overnight or strap-history value for the same thing, shown beside it when it exists. */
private class Tile(
    val title: String, val value: String?, val unit: String, val estimate: Boolean,
    val hint: String, val solidLabel: String? = null, val solid: String? = null,
    /** How the live value grades against its good range, and that range, when Loop has one. */
    val grade: Grade? = null, val good: String? = null,
)

private fun fmt1(v: Double) = "%.1f".format(v)

/** Builds the live tiles from the monitor state, the person's norm and the solid values already in their data. */
@Composable
private fun liveTiles(vm: AppViewModel, dash: Dashboard, st: HeartRateMonitor.State, brief: Boolean): List<Tile> {
    val norm by vm.liveNorm.collectAsStateWithLifecycle()
    val s = dash.settings
    val today = dash.days.lastOrNull { it.day == LocalDate.now().toString() }
    val last = dash.latest
    val rhr = last?.rhr ?: dash.days.takeLast(14).mapNotNull { it.rhr }.takeIf { it.isNotEmpty() }?.average() ?: 60.0
    val maxHr = Scoring.maxHr(s)
    val bpm = st.bpm
    val zone = bpm?.let { Scoring.zone(it.toDouble(), rhr, maxHr) } ?: 0
    val reserve = bpm?.let { ((it - rhr) / (maxHr - rhr) * 100).coerceIn(0.0, 100.0) }
    val stress = if (bpm != null && st.rmssd != null && st.liveSteady && norm != null) StrapAnalyzer.stressScore(bpm.toDouble(), st.rmssd, norm!!) else null
    val steadyHint = if (st.liveSteady) "Collecting a few minutes of beats…" else "Sit still for a couple of minutes"
    val ctx = today ?: last
    fun rng(id: MetricId) = dash.goodRange(Metrics[id], ctx)
    fun g(id: MetricId, v: Double?): Grade? = v?.let { rng(id)?.gradeOf(it) }
    fun gt(id: MetricId): String? = rng(id)?.takeIf { it.graded }?.good
    val quality = st.liveQuality?.let { it * 100 }

    val all = listOf(
        Tile("HRV (RMSSD)", st.rmssd?.roundToInt()?.toString(), "ms", true, if (st.liveBeats < 20) "Warming up…" else "Needs more beats", "Last night", last?.hrv?.let { "${it.roundToInt()} ms" }, g(MetricId.DT_HRV, st.rmssd), gt(MetricId.DT_HRV)),
        Tile("Breathing", st.liveBreath?.let { fmt1(it) }, "br/min", true, steadyHint, "Last night", last?.resp?.let { "${fmt1(it)} br/min" }, g(MetricId.DT_BREATH, st.liveBreath), gt(MetricId.DT_BREATH)),
        Tile(
            "Stress", stress?.roundToInt()?.toString(), "/100", true,
            when { norm == null -> "Learning your daytime norm from strap history"; !st.liveSteady -> "Hold still for a minute"; else -> "Working it out…" },
            "Today so far", (today?.dayStress ?: today?.dtStress)?.let { "${it.roundToInt()} avg" },
            g(MetricId.DT_STRESS, stress), gt(MetricId.DT_STRESS),
        ),
        Tile("HRV (SDNN)", st.liveSdnn?.roundToInt()?.toString(), "ms", true, "Warming up…", "Today avg", today?.dtSdnn?.let { "${it.roundToInt()} ms" }, g(MetricId.DT_SDNN, st.liveSdnn), gt(MetricId.DT_SDNN)),
        Tile("pNN50", st.livePnn50?.let { fmt1(it) }, "%", true, "Warming up…", null, null),
        Tile("Rhythm coherence", st.liveCoherence?.roundToInt()?.toString(), "%", true, steadyHint, "Today avg", today?.dtCoherence?.let { "${it.roundToInt()}%" }, g(MetricId.DT_COHERENCE, st.liveCoherence), gt(MetricId.DT_COHERENCE)),
        Tile("Autonomic balance", st.liveLfHf?.let { fmt1(it) }, "LF/HF", true, steadyHint, "Today avg", today?.dtLfHf?.let { fmt1(it) }, g(MetricId.DT_LFHF, st.liveLfHf), gt(MetricId.DT_LFHF)),
        Tile("Effort", reserve?.roundToInt()?.toString(), "% reserve", true, "Waiting for heart rate", if (zone >= 1) "Zone" else null, if (zone >= 1) "$zone · ${Scoring.zoneNames[zone - 1]}" else null),
        Tile("Energy burn", bpm?.let { if (zone >= 1) fmt1(Scoring.kcalPerMin(it.toDouble(), s)) else "0" }, "kcal/min", true, "Waiting for heart rate", "Today est.", today?.dtKcal?.let { "${it.roundToInt()} kcal" }),
        Tile("Heart-rate trend", st.liveTrend?.let { "%+.0f".format(it) }, "bpm/min", false, "Needs a minute of data", "Resting (night)", last?.rhr?.let { "${it.roundToInt()} bpm" }),
        Tile("Beat interval", st.liveMeanRr?.roundToInt()?.toString(), "ms", false, "Warming up…"),
        Tile(
            "Signal quality", st.liveQuality?.let { (it * 100).roundToInt().toString() }, "% clean", false, "Waiting for beats", null, null,
            quality?.let { if (it >= 80) Grade.GOOD else if (it >= 50) Grade.OK else Grade.WATCH }, "80% or more",
        ),
    )
    return if (brief) listOf(all[0], all[1], all[2]) else all
}

@Composable
private fun TileView(t: Tile, modifier: Modifier = Modifier, showPill: Boolean = true, onClick: () -> Unit = {}) {
    val c = MaterialTheme.colorScheme
    Column(modifier.fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(c.surfaceContainerHigh).clickable(onClick = onClick).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.title, style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant, modifier = Modifier.weight(1f).padding(end = 6.dp), maxLines = 2)
            if (showPill) Pill(if (t.estimate) "est." else "live", c.outline)
        }
        Spacer(Modifier.height(6.dp))
        if (t.value != null) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(t.value, style = MaterialTheme.typography.headlineMedium.copy(fontSize = 26.sp, fontWeight = FontWeight.Bold), color = c.onSurface)
                Spacer(Modifier.width(4.dp))
                Text(t.unit, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            }
        } else Text(t.hint, style = MaterialTheme.typography.bodySmall, color = c.outline, modifier = Modifier.padding(vertical = 4.dp))
        if (t.solid != null) {
            Spacer(Modifier.height(6.dp))
            Text("${t.solidLabel}: ${t.solid}", style = MaterialTheme.typography.labelSmall, color = c.good)
        }
        if (t.value != null && t.grade != null) VerdictLine(t.grade, null, compact = true, modifier = Modifier.padding(top = 4.dp))
        if (t.good != null) Text("Good range  ${t.good}", style = MaterialTheme.typography.labelSmall, color = c.outline, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Which glossary entry a live tile opens: a stored metric when one matches, otherwise a live-only entry. */
private fun glossaryFor(title: String): Pair<MetricId?, String?> = when (title) {
    "HRV (RMSSD)" -> MetricId.DT_HRV to null
    "Breathing" -> MetricId.DT_BREATH to null
    "Stress" -> MetricId.DT_STRESS to null
    "HRV (SDNN)" -> MetricId.DT_SDNN to null
    "pNN50" -> null to "pnn50"
    "Rhythm coherence" -> MetricId.DT_COHERENCE to null
    "Autonomic balance" -> MetricId.DT_LFHF to null
    "Effort" -> null to "effort"
    "Energy burn" -> MetricId.DT_KCAL to null
    "Heart-rate trend" -> null to "trend"
    "Beat interval" -> null to "beat"
    else -> null to "quality"
}

@Composable
private fun GlossaryFor(title: String?, onDismiss: () -> Unit) {
    if (title == null) return
    val (metric, key) = glossaryFor(title)
    if (metric != null) app.loop.ui.components.MetricInfoSheet(app.loop.model.Metrics[metric], onDismiss)
    else app.loop.model.Glossary.live[key]?.let { app.loop.ui.components.LiveEntrySheet(it, MaterialTheme.colorScheme.warn, onDismiss) }
}

/** Every live estimate, each labelled as an estimate, with the solid (overnight or history) value beside it once it exists. */
@Composable
fun LiveMetricsCard(vm: AppViewModel, dash: Dashboard, st: HeartRateMonitor.State) {
    val c = MaterialTheme.colorScheme
    val allTiles = liveTiles(vm, dash, st, brief = false)
    var more by remember { mutableStateOf(false) }
    val essentials = setOf("HRV (RMSSD)", "Breathing", "Stress", "Effort")
    val tiles = if (more) allTiles else allTiles.filter { it.title in essentials }
    var open by remember { mutableStateOf<String?>(null) }
    GlossaryFor(open) { open = null }
    LoopCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Live estimates", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                Text("Worked out right now from your heartbeat timing", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
            Pill("estimates", c.outline)
        }
        Spacer(Modifier.height(12.dp))
        tiles.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { t -> TileView(t, Modifier.weight(1f)) { open = t.title } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        androidx.compose.material3.TextButton({ more = !more }, Modifier.fillMaxWidth()) { Text(if (more) "Show fewer numbers" else "Show more numbers") }
        Text(
            "Live estimates are rougher than your overnight scores, and need you to be still. Green lines show the overnight value once Loop has it.",
            style = MaterialTheme.typography.labelSmall, color = c.outline,
        )
    }
}

/** A calm "right now" card: the live heart rate, and three estimates with a quiet status each. */
@Composable
fun RightNowCard(vm: AppViewModel, dash: Dashboard, onOpen: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val st by vm.monitor.state.collectAsStateWithLifecycle()
    if (st.phase != HeartRateMonitor.Phase.CONNECTED || st.bpm == null) return
    val tiles = liveTiles(vm, dash, st, brief = true)
    var open by remember { mutableStateOf<String?>(null) }
    GlossaryFor(open) { open = null }
    LoopCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Favorite, null, tint = c.primary, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
            Text("${st.bpm}", style = MaterialTheme.typography.headlineLarge, color = c.onSurface)
            Text(" bpm", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.weight(1f))
            Text("Right now", style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant)
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            tiles.forEach { t -> MiniStat(t, Modifier.weight(1f)) { open = t.title } }
        }
        Spacer(Modifier.height(12.dp))
        Text("Live estimates. Tap a number to learn what it means.", style = MaterialTheme.typography.labelSmall, color = c.outline)
    }
}

/** One quiet number: label, value and a small status. No boxes, no badges. */
@Composable
private fun MiniStat(t: Tile, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(t.title.substringBefore(" ("), style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(t.value ?: "–", style = MaterialTheme.typography.titleLarge.copy(fontSize = 26.sp, fontWeight = FontWeight.Bold), color = if (t.value == null) c.outline else c.onSurface)
            if (t.value != null) { Spacer(Modifier.width(3.dp)); Text(t.unit.removePrefix("/"), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp), maxLines = 1) }
        }
        if (t.value != null && t.grade != null) VerdictLine(t.grade, null, compact = true)
        else if (t.value == null) Text("warming up", style = MaterialTheme.typography.labelSmall, color = c.outline)
    }
}
