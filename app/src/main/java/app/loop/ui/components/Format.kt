package app.loop.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector
import app.loop.analytics.Scoring
import app.loop.model.Category
import app.loop.model.Fmt
import app.loop.model.MetricDef
import app.loop.model.MetricId
import kotlin.math.roundToInt

fun Category.icon(): ImageVector = when (this) {
    Category.RECOVERY -> Icons.Rounded.Spa
    Category.SLEEP -> Icons.Rounded.Bedtime
    Category.DAYTIME -> Icons.Rounded.WbSunny
    Category.ACTIVITY -> Icons.Rounded.LocalFireDepartment
    Category.BODY -> Icons.Rounded.MonitorHeart
    Category.MIND -> Icons.Rounded.Psychology
}

/** Value text for a metric, honouring the user's unit choice. */
fun MetricDef.format(v: Double?, imperial: Boolean = false): String {
    if (v == null || v.isNaN()) return "–"
    return when {
        id == MetricId.WEIGHT -> "%.1f".format(if (imperial) v * 2.20462 else v)
        fmt == Fmt.INT -> if (id == MetricId.STEPS) "%,d".format(v.roundToInt()) else v.roundToInt().toString()
        fmt == Fmt.ONE_DECIMAL -> "%.1f".format(v)
        fmt == Fmt.DURATION -> Scoring.hm(v)
        fmt == Fmt.PERCENT -> "${v.roundToInt()}%"
        // A change too small to show reads "0.0", never "-0.0".
        fmt == Fmt.TEMP_DELTA -> (if (imperial) v * 1.8 else v).let { t -> if ((t * 10).roundToInt() == 0) "0.0" else "%+.1f".format(t) }
        fmt == Fmt.MOOD -> "%.1f".format(v)
        else -> v.toString()
    }
}

fun MetricDef.unitLabel(imperial: Boolean = false): String = when {
    id == MetricId.WEIGHT -> if (imperial) "lb" else "kg"
    fmt == Fmt.TEMP_DELTA -> if (imperial) "°F" else "°C"
    fmt == Fmt.DURATION -> ""
    else -> unit
}

/** Plain-language one-liner for a value, e.g. "Primed" or "Excellent". Null when there's nothing useful to add. */
fun MetricDef.word(v: Double?): String? {
    if (v == null) return null
    return when (id) {
        MetricId.RECOVERY -> Scoring.recoveryWord(v)
        MetricId.SLEEP_SCORE -> Scoring.sleepWord(v)
        MetricId.STRAIN -> Scoring.strainWord(v)
        MetricId.STRESS -> Scoring.stressWord(v)
        MetricId.MOOD -> listOf("", "Low", "Meh", "Okay", "Good", "Great")[v.roundToInt().coerceIn(1, 5)]
        else -> null
    }
}

/** Compact title for tight spaces such as dashboard tiles. */
fun MetricDef.short(): String = when (id) {
    MetricId.HRV -> "HRV"
    MetricId.RHR -> "Resting HR"
    MetricId.SLEEP_SCORE -> "Sleep score"
    MetricId.SLEEP_DURATION -> "Asleep"
    MetricId.SLEEP_EFFICIENCY -> "Efficiency"
    MetricId.SLEEP_DEBT -> "Sleep debt"
    MetricId.CALORIES -> "Calories"
    MetricId.RESP -> "Breathing"
    MetricId.SKIN_TEMP -> "Skin temp"
    MetricId.RHYTHM_IRREG -> "Rhythm"
    MetricId.STRESS -> "Stress"
    else -> title
}
