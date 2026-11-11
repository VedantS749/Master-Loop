package app.loop.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.loop.analytics.Stats

/** Circular progress score. [fraction] is 0..1; [center] is drawn in the middle. */
@Composable
fun ScoreRing(
    fraction: Float?,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    stroke: Dp = 10.dp,
    center: @Composable BoxScope.() -> Unit = {},
) {
    val anim by animateFloatAsState((fraction ?: 0f).coerceIn(0f, 1f), tween(900, easing = FastOutSlowInEasing), label = "ring")
    val track = MaterialTheme.colorScheme.outlineVariant
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = stroke.toPx()
            val inset = sw / 2
            val arcSize = Size(this.size.width - sw, this.size.height - sw)
            drawArc(track.copy(alpha = 0.55f), -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(sw, cap = StrokeCap.Round))
            if (fraction != null && anim > 0.001f) {
                drawArc(
                    Brush.sweepGradient(listOf(color.copy(alpha = 0.55f), color, color.copy(alpha = 0.55f))),
                    -90f, 360f * anim, false, Offset(inset, inset), arcSize, style = Stroke(sw, cap = StrokeCap.Round),
                )
            }
        }
        center()
    }
}

/** Tiny trend line, no axes. Null values are skipped. */
@Composable
fun Sparkline(values: List<Double?>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val pts = values.mapIndexedNotNull { i, v -> if (v == null) null else i to v }
        if (pts.size < 2) return@Canvas
        val lo = pts.minOf { it.second }; val hi = pts.maxOf { it.second }
        val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0
        val n = (values.size - 1).coerceAtLeast(1)
        val pad = 3.dp.toPx()
        fun x(i: Int) = pad + (size.width - 2 * pad) * i / n
        fun y(v: Double) = (size.height - pad) - (size.height - 2 * pad) * ((v - lo) / span).toFloat()
        val line = Path(); val fill = Path()
        pts.forEachIndexed { k, (i, v) ->
            if (k == 0) { line.moveTo(x(i), y(v)); fill.moveTo(x(i), size.height); fill.lineTo(x(i), y(v)) } else { line.lineTo(x(i), y(v)); fill.lineTo(x(i), y(v)) }
        }
        fill.lineTo(x(pts.last().first), size.height); fill.close()
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.25f), Color.Transparent)))
        drawPath(line, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(color, 3.dp.toPx(), Offset(x(pts.last().first), y(pts.last().second)))
    }
}

/**
 * Interactive trend chart. Touch or drag to read any day. Shades your personal normal range
 * (mean +/- one standard deviation) so "is this unusual for me?" is answered at a glance.
 */
@Composable
fun TrendChart(
    values: List<Double?>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    yMin: Double? = null,
    yMax: Double? = null,
    onSelect: (Int?) -> Unit = {},
) {
    var selected by remember(values) { mutableStateOf<Int?>(null) }
    val data = values.filterNotNull()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val textDim = MaterialTheme.colorScheme.outline
    val mean = Stats.mean(values); val sd = Stats.std(values)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(values) {
                detectTapGestures { o -> val i = idx(o.x, size.width.toFloat(), values.size); selected = i; onSelect(i) }
            }
            .pointerInput(values) {
                detectDragGestures(
                    onDragEnd = { }, onDragCancel = { },
                ) { change, _ -> val i = idx(change.position.x, size.width.toFloat(), values.size); selected = i; onSelect(i) }
            },
    ) {
        if (data.size < 2) return@Canvas
        val lo = yMin ?: (data.min() - (data.max() - data.min()) * 0.12).let { if (data.min() >= 0 && it < 0) 0.0 else it }
        val hi = yMax ?: (data.max() + (data.max() - data.min()) * 0.12)
        val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0
        val padL = 4.dp.toPx(); val padR = 4.dp.toPx(); val padT = 10.dp.toPx(); val padB = 10.dp.toPx()
        val w = size.width - padL - padR; val h = size.height - padT - padB
        val n = (values.size - 1).coerceAtLeast(1)
        fun x(i: Int) = padL + w * i / n
        fun y(v: Double) = padT + h - h * ((v - lo) / span).toFloat()

        // grid
        for (g in 0..3) {
            val gy = padT + h * g / 3f
            drawLine(grid.copy(alpha = 0.5f), Offset(padL, gy), Offset(padL + w, gy), strokeWidth = 1f)
        }
        // normal band
        if (mean != null && sd != null) {
            val top = y((mean + sd).coerceAtMost(hi)); val bot = y((mean - sd).coerceAtLeast(lo))
            drawRect(color.copy(alpha = 0.10f), Offset(padL, top), Size(w, bot - top))
            drawLine(color.copy(alpha = 0.35f), Offset(padL, y(mean)), Offset(padL + w, y(mean)), strokeWidth = 1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
        }
        val line = Path(); val fill = Path(); var started = false; var lastI = 0
        values.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            if (!started) { line.moveTo(x(i), y(v)); fill.moveTo(x(i), padT + h); fill.lineTo(x(i), y(v)); started = true } else { line.lineTo(x(i), y(v)); fill.lineTo(x(i), y(v)) }
            lastI = i
        }
        fill.lineTo(x(lastI), padT + h); fill.close()
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), Color.Transparent), startY = padT, endY = padT + h))
        drawPath(line, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        if (values.size <= 40) values.forEachIndexed { i, v -> if (v != null) drawCircle(color, 2.5.dp.toPx(), Offset(x(i), y(v))) }

        selected?.let { si ->
            val sv = values.getOrNull(si)
            if (sv != null) {
                drawLine(textDim.copy(alpha = 0.6f), Offset(x(si), padT), Offset(x(si), padT + h), strokeWidth = 1.dp.toPx())
                drawCircle(Color.White, 6.dp.toPx(), Offset(x(si), y(sv)))
                drawCircle(color, 4.dp.toPx(), Offset(x(si), y(sv)))
            }
        }
    }
}

private fun idx(x: Float, width: Float, n: Int): Int = ((x / width) * (n - 1)).let { Math.round(it) }.coerceIn(0, (n - 1).coerceAtLeast(0))

/** Horizontal stacked bar for sleep stages (or anything with weighted parts). */
@Composable
fun StackedBar(parts: List<Pair<Double, Color>>, modifier: Modifier = Modifier, height: Dp = 14.dp) {
    val total = parts.sumOf { it.first }.takeIf { it > 0 } ?: return
    Canvas(modifier.fillMaxWidth().height(height)) {
        var x = 0f
        val gap = 3.dp.toPx()
        val r = size.height / 2
        parts.forEachIndexed { i, (v, c) ->
            val w = (size.width * (v / total)).toFloat()
            if (w <= 0f) return@forEachIndexed
            drawRoundRect(c, Offset(x, 0f), Size((w - if (i < parts.lastIndex) gap else 0f).coerceAtLeast(2f), size.height), androidx.compose.ui.geometry.CornerRadius(r, r))
            x += w
        }
    }
}

