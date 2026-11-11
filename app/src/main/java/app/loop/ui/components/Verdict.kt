package app.loop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.loop.analytics.GoodRange
import app.loop.analytics.GoodRanges
import app.loop.analytics.Grade
import app.loop.analytics.HealthOverviews
import app.loop.model.DayRecord
import app.loop.model.MetricDef
import app.loop.ui.Dashboard
import app.loop.ui.theme.good
import app.loop.ui.theme.warn

/** The range for [def] against this person's own normal. [day] gives same-day context; it defaults to the latest day. */
fun Dashboard.goodRange(def: MetricDef, day: DayRecord? = null): GoodRange? = GoodRanges.of(def, settings, baseline(def), day ?: latest)

/** How [v] grades against [range], or null when there is no value, no graded range, or it is a total still building up today. */
fun gradeFor(range: GoodRange?, def: MetricDef, v: Double?, day: String?): Grade? {
    if (v == null || range == null) return null
    if (HealthOverviews.inProgress(def.id, day)) return null
    return range.gradeOf(v)
}

@Composable
fun Grade.color(): Color = when (this) {
    Grade.GOOD -> MaterialTheme.colorScheme.good
    Grade.OK -> MaterialTheme.colorScheme.primary
    Grade.WATCH -> MaterialTheme.colorScheme.warn
}

/** A word short enough for a tile. */
fun Grade.short(): String = when (this) { Grade.GOOD -> "Good"; Grade.OK -> "Okay"; Grade.WATCH -> "Watch" }

/** A small pill saying whether a value is good, okay or worth watching. */
@Composable
fun VerdictPill(grade: Grade?, modifier: Modifier = Modifier) {
    if (grade == null) return
    Pill(grade.short(), grade.color(), modifier)
}

/** "● Good · Well recovered": the verdict first, then the plain-language headline. */
@Composable
fun VerdictLine(
    grade: Grade?, headline: String?, fallbackColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier: Modifier = Modifier, compact: Boolean = false,
) {
    val col = grade?.color() ?: fallbackColor
    val style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge
    // "Good · Good" says nothing twice.
    val hl = headline?.takeUnless { grade != null && (it.equals(grade.short(), true) || it.equals(grade.word, true)) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (grade != null) {
            Box(Modifier.size(if (compact) 6.dp else 8.dp).clip(CircleShape).background(col))
            Spacer(Modifier.width(if (compact) 4.dp else 6.dp))
            Text(grade.short(), style = style, color = col, fontWeight = FontWeight.Bold, maxLines = 1)
            if (hl != null) Text(" · ", style = style, color = col)
        }
        if (hl != null) Text(hl, style = style, color = col, maxLines = if (compact) 1 else Int.MAX_VALUE, modifier = Modifier.weight(1f, fill = false))
    }
}

/** "Good range 67–100%", or "Guide …" for metrics that have no right answer. */
@Composable
fun RangeText(range: GoodRange?, modifier: Modifier = Modifier, maxLines: Int = 2, minLines: Int = 1, compact: Boolean = false) {
    if (range == null || (compact && !range.graded)) return
    val c = MaterialTheme.colorScheme
    val label = if (!range.graded) "Guide  " else if (compact) "Good  " else "Good range  "
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = if (range.graded) c.good else c.outline, fontWeight = FontWeight.SemiBold)) { append(label) }
            withStyle(SpanStyle(color = c.onSurfaceVariant)) { append(range.good) }
        },
        style = MaterialTheme.typography.labelSmall, modifier = modifier, maxLines = maxLines, minLines = minLines,
    )
}

/** The whole scale with its good, okay and watch stretches shaded, and a marker where [value] sits. */
@Composable
fun RangeBar(range: GoodRange, value: Double?, modifier: Modifier = Modifier) {
    if (!range.graded) return
    val c = MaterialTheme.colorScheme
    val colors = mapOf(Grade.GOOD to c.good, Grade.OK to c.primary, Grade.WATCH to c.warn)
    val ink = c.onSurface; val outline = c.surface
    val span = (range.scaleHi - range.scaleLo).takeIf { it > 0 } ?: 1.0
    Canvas(modifier.fillMaxWidth().height(22.dp)) {
        val barH = 10.dp.toPx(); val top = (size.height - barH) / 2
        range.zones.forEach { z ->
            val x0 = (((z.lo - range.scaleLo) / span).coerceIn(0.0, 1.0) * size.width).toFloat()
            val x1 = (((z.hi - range.scaleLo) / span).coerceIn(0.0, 1.0) * size.width).toFloat()
            if (x1 - x0 > 0.5f) drawRoundRect((colors.getValue(z.grade)).copy(alpha = 0.55f), Offset(x0, top), Size(x1 - x0, barH), CornerRadius(2.dp.toPx()))
        }
        if (value != null) {
            val x = (((value - range.scaleLo) / span).coerceIn(0.0, 1.0) * size.width).toFloat()
            drawCircle(outline, 9.dp.toPx(), Offset(x, size.height / 2))
            drawCircle(ink, 6.dp.toPx(), Offset(x, size.height / 2))
        }
    }
}

/** The legend under a [RangeBar]: what counts as good and okay. */
@Composable
fun RangeLegend(range: GoodRange, modifier: Modifier = Modifier) {
    if (!range.graded) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        // Only the stretches that actually exist on this scale are listed.
        val hasOk = range.zone(Grade.OK) != null
        LegendRow(Grade.GOOD, range.good)
        if (hasOk) LegendRow(Grade.OK, range.okay ?: "other values")
        if (range.zone(Grade.WATCH) != null) LegendRow(Grade.WATCH, if (hasOk) "outside both" else "outside the good range")
    }
}

@Composable
private fun LegendRow(g: Grade, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(g.color()))
        Spacer(Modifier.width(8.dp))
        Text(g.word, style = MaterialTheme.typography.labelMedium, color = g.color(), fontWeight = FontWeight.SemiBold, modifier = Modifier.width(112.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
