package app.loop.ui.screens

import app.loop.ui.theme.activity
import app.loop.ui.theme.good
import app.loop.ui.theme.mind
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.loop.ui.AppViewModel
import app.loop.ui.Dashboard
import app.loop.ui.brand.LoopMark
import app.loop.ui.components.DetailHeader
import app.loop.ui.components.LoopCard
import app.loop.ui.components.PrimaryButton
import app.loop.ui.components.Segmented
import app.loop.ui.components.SecondaryButton
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import app.loop.model.plural

// ============================================================ Breathe

private data class Pattern(val name: String, val blurb: String, val phases: List<Pair<String, Double>>)

private val patterns = listOf(
    Pattern("Relax 4-6", "Longer exhale to switch on your calm response. Best for winding down.", listOf("Breathe in" to 4.0, "Breathe out" to 6.0)),
    Pattern("Coherence", "Even 5.5-second breaths. Syncs heart rate and breathing to lift HRV.", listOf("Breathe in" to 5.5, "Breathe out" to 5.5)),
    Pattern("Box 4-4", "In, hold, out, hold. Steady focus under pressure.", listOf("Breathe in" to 4.0, "Hold" to 4.0, "Breathe out" to 4.0, "Hold" to 4.0)),
)

@Composable
fun BreatheScreen(vm: AppViewModel, dash: Dashboard) {
    val c = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val live by vm.monitor.state.collectAsStateWithLifecycle()
    var patIdx by remember { mutableIntStateOf(0) }
    var minutesIdx by remember { mutableIntStateOf(1) }
    var running by remember { mutableStateOf(false) }
    var elapsed by remember { mutableStateOf(0.0) }
    var done by remember { mutableStateOf(false) }
    var rmssdStart by remember { mutableStateOf<Double?>(null) }
    var rmssdEnd by remember { mutableStateOf<Double?>(null) }
    val minutes = listOf(1, 2, 5)[minutesIdx]
    val pat = patterns[patIdx]
    val cycle = pat.phases.sumOf { it.second }

    val view = LocalView.current
    DisposableEffect(running) { view.keepScreenOn = running; onDispose { view.keepScreenOn = false } }

    // Phase maths from elapsed time
    var t = elapsed % cycle
    var phaseIdx = 0
    for ((i, p) in pat.phases.withIndex()) { if (t < p.second) { phaseIdx = i; break }; t -= p.second; phaseIdx = i }
    val phase = pat.phases[phaseIdx]
    val frac = (t / phase.second).coerceIn(0.0, 1.0)
    val isIn = phase.first == "Breathe in"; val isOut = phase.first == "Breathe out"
    val prevHold = phaseIdx > 0 && pat.phases[phaseIdx - 1].first == "Breathe in" || (phaseIdx == 0 && pat.phases.last().first == "Breathe in")
    val ease = (1 - cos(frac * PI)) / 2
    val scale = when {
        !running -> 0.72
        isIn -> 0.62 + 0.38 * ease
        isOut -> 1.0 - 0.38 * ease
        prevHold -> 1.0
        else -> 0.62
    }.toFloat()

    LaunchedEffect(running, patIdx, minutesIdx) {
        if (!running) return@LaunchedEffect
        var last = System.nanoTime(); var lastPhase = -1
        elapsed = 0.0
        while (running && elapsed < minutes * 60.0) {
            delay(33)
            val now = System.nanoTime(); elapsed += (now - last) / 1e9; last = now
            var tt = elapsed % cycle; var idx = 0
            for ((i, p) in pat.phases.withIndex()) { if (tt < p.second) { idx = i; break }; tt -= p.second; idx = i }
            if (idx != lastPhase) { lastPhase = idx; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
        }
        if (running) { running = false; done = true; rmssdEnd = vm.monitor.state.value.rmssd; haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    }

    ScreenList {
        item { DetailHeader("Breathe", { running = false; vm.pop() }, subtitle = "Guided breathing") }

        item {
            Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(280.dp)) {
                    val r = size.minDimension / 2 * scale
                    drawCircle(c.mind.copy(alpha = 0.10f), r * 1.12f, Offset(size.width / 2, size.height / 2))
                    drawCircle(c.mind.copy(alpha = 0.30f), size.minDimension / 2, Offset(size.width / 2, size.height / 2), style = Stroke(1.5.dp.toPx()))
                }
                LoopMark(size = 210.dp * scale, animate = running)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (running) {
                        Text(phase.first, style = MaterialTheme.typography.titleLarge, color = c.onSurface, fontWeight = FontWeight.Bold)
                        Text("${(phase.second - t).toInt() + 1}", style = MaterialTheme.typography.headlineMedium, color = c.onSurface)
                    }
                }
            }
        }

        if (running) {
            item {
                Text("%d:%02d left".format(((minutes * 60 - elapsed).toInt().coerceAtLeast(0)) / 60, ((minutes * 60 - elapsed).toInt().coerceAtLeast(0)) % 60), style = MaterialTheme.typography.titleMedium, color = c.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            item { SecondaryButton("End session", { running = false }) }
        } else if (done) {
            item {
                LoopCard(accent = c.mind) {
                    Text("Session complete", style = MaterialTheme.typography.titleLarge, color = c.onSurface)
                    Spacer(Modifier.height(4.dp))
                    Text("${(minutes * 60 / cycle).roundToInt()} breaths over $minutes ${if (minutes == 1) "minute" else "minutes"}.", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                    if (rmssdStart != null && rmssdEnd != null) {
                        Spacer(Modifier.height(8.dp))
                        Text("Live HRV: ${rmssdStart!!.roundToInt()} ms before, ${rmssdEnd!!.roundToInt()} ms after.", style = MaterialTheme.typography.bodyMedium, color = c.onSurface)
                    }
                }
            }
            item { PrimaryButton("Go again", { done = false; rmssdStart = live.rmssd; rmssdEnd = null; running = true }, color = c.mind) }
        } else {
            item { Segmented(patterns.map { it.name }, patIdx, { patIdx = it }, c.mind) }
            item { Text(pat.blurb, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant) }
            item { Segmented(listOf("1 min", "2 min", "5 min"), minutesIdx, { minutesIdx = it }, c.mind) }
            item { PrimaryButton("Start", { rmssdStart = live.rmssd; rmssdEnd = null; done = false; running = true }, icon = Icons.Rounded.PlayArrow, color = c.mind) }
            item { Text("Follow the shape as it grows and shrinks. You'll feel a soft tap at every change. With a heart-rate device connected, Loop also shows your HRV before and after.", style = MaterialTheme.typography.bodySmall, color = c.outline) }
        }
    }
}

// ============================================================ Intervals

@Composable
fun IntervalsScreen(vm: AppViewModel) {
    val c = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    var work by remember { mutableIntStateOf(30) }
    var rest by remember { mutableIntStateOf(15) }
    var rounds by remember { mutableIntStateOf(8) }
    var running by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var round by remember { mutableIntStateOf(1) }
    var isWork by remember { mutableStateOf(true) }
    var left by remember { mutableStateOf(0.0) }
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull() }
    DisposableEffect(Unit) { onDispose { tone?.release() } }
    val view = LocalView.current
    DisposableEffect(running) { view.keepScreenOn = running; onDispose { view.keepScreenOn = false } }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        round = 1; isWork = true; left = work.toDouble(); finished = false
        haptic.performHapticFeedback(HapticFeedbackType.LongPress); tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
        var last = System.nanoTime(); var lastWhole = left.toInt()
        while (running) {
            delay(50)
            val now = System.nanoTime(); left -= (now - last) / 1e9; last = now
            val whole = kotlin.math.ceil(left).toInt()
            if (whole != lastWhole && whole in 1..3) { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 80); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
            lastWhole = whole
            if (left <= 0) {
                if (isWork) {
                    if (round >= rounds) { running = false; finished = true; haptic.performHapticFeedback(HapticFeedbackType.LongPress); tone?.startTone(ToneGenerator.TONE_PROP_ACK, 600); break }
                    if (rest > 0) { isWork = false; left = rest.toDouble() } else { round++; left = work.toDouble() }
                } else { isWork = true; round++; left = work.toDouble() }
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                tone?.startTone(if (isWork) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_PROMPT, 250)
                lastWhole = kotlin.math.ceil(left).toInt()
            }
        }
    }

    ScreenList {
        item { DetailHeader("Interval timer", { running = false; vm.pop() }, subtitle = "Work, rest, repeat") }
        if (running) {
            val total = (if (isWork) work else rest).toDouble().coerceAtLeast(1.0)
            val col = if (isWork) c.activity else c.secondary
            item {
                Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(260.dp)) {
                        val sw = 16.dp.toPx()
                        drawArc(c.outlineVariant.copy(alpha = 0.5f), 0f, 360f, false, topLeft = Offset(sw / 2, sw / 2), size = androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw), style = Stroke(sw, cap = StrokeCap.Round))
                        drawArc(col, -90f, (360f * (left / total)).toFloat().coerceIn(0f, 360f), false, topLeft = Offset(sw / 2, sw / 2), size = androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw), style = Stroke(sw, cap = StrokeCap.Round))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (isWork) "WORK" else "REST", style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 3.sp), color = col, fontWeight = FontWeight.Bold)
                        Text("${kotlin.math.ceil(left).toInt().coerceAtLeast(0)}", style = MaterialTheme.typography.displayLarge.copy(fontSize = 84.sp), color = c.onSurface)
                        Text("Round $round of $rounds", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                    }
                }
            }
            item { SecondaryButton("Stop", { running = false }) }
        } else {
            if (finished) item {
                LoopCard(accent = c.good) {
                    Text("Done. ${plural(rounds, "round")} complete.", style = MaterialTheme.typography.titleLarge, color = c.onSurface)
                    Text("Nice work. Log it from Practice if you want it in your workouts.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
            }
            item {
                LoopCard {
                    Stepper("Work", "$work s", { work = (work - 5).coerceAtLeast(5) }, { work = (work + 5).coerceAtMost(300) })
                    Stepper("Rest", "$rest s", { rest = (rest - 5).coerceAtLeast(0) }, { rest = (rest + 5).coerceAtMost(300) })
                    Stepper("Rounds", "$rounds", { rounds = (rounds - 1).coerceAtLeast(1) }, { rounds = (rounds + 1).coerceAtMost(50) })
                    Spacer(Modifier.height(8.dp))
                    val totalSec = rounds * work + (rounds - 1).coerceAtLeast(0) * rest
                    Text("Total: ${totalSec / 60}:%02d".format(totalSec % 60), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
            }
            item { PrimaryButton("Start", { running = true }, icon = Icons.Rounded.PlayArrow, color = c.activity) }
            item { Text("You'll feel a strong tap when work starts, a different cue for rest, and three short beeps before each change. Keep your phone volume up for the beeps.", style = MaterialTheme.typography.bodySmall, color = c.outline) }
        }
    }
}

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        IconButton(onMinus) { Icon(Icons.Rounded.Remove, "Less", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.widthIn(min = 64.dp), textAlign = TextAlign.Center)
        IconButton(onPlus) { Icon(Icons.Rounded.Add, "More", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
