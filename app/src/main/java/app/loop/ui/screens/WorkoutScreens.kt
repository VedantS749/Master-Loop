package app.loop.ui.screens

import app.loop.ui.theme.activity
import app.loop.ui.theme.bad
import app.loop.ui.theme.sleep
import app.loop.ui.theme.warn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DirectionsBike
import androidx.compose.material.icons.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Pool
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.SportsGymnastics
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.loop.analytics.Scoring
import app.loop.model.Category
import app.loop.model.Workout
import app.loop.ui.AppViewModel
import app.loop.ui.Dashboard
import app.loop.ui.Route
import app.loop.ui.components.DetailHeader
import app.loop.ui.components.EmptyState
import app.loop.ui.components.IconBadge
import app.loop.ui.components.LoopCard
import app.loop.ui.components.Pill
import app.loop.ui.components.PrimaryButton
import app.loop.ui.components.SecondaryButton
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

fun sportIcon(sport: String): ImageVector = when {
    sport.contains("run", true) -> Icons.Rounded.DirectionsRun
    sport.contains("cycl", true) || sport.contains("bike", true) -> Icons.Rounded.DirectionsBike
    sport.contains("walk", true) || sport.contains("hik", true) -> Icons.Rounded.DirectionsWalk
    sport.contains("swim", true) -> Icons.Rounded.Pool
    sport.contains("strength", true) || sport.contains("weight", true) || sport.contains("lift", true) -> Icons.Rounded.FitnessCenter
    else -> Icons.Rounded.SportsGymnastics
}

private fun fmtWhen(w: Workout): String =
    Instant.ofEpochSecond(w.id).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE d MMM, HH:mm"))

@Composable
fun WorkoutsScreen(vm: AppViewModel, dash: Dashboard) {
    val c = MaterialTheme.colorScheme
    ScreenList {
        item { DetailHeader("Workouts", { vm.pop() }, subtitle = "${dash.workouts.size} logged") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { PrimaryButton("Record live", { vm.push(Route.Live) }, icon = Icons.Rounded.Sensors, color = c.activity) }
                Box(Modifier.weight(1f)) { SecondaryButton("Log workout", { vm.push(Route.LogWorkout) }, icon = Icons.Rounded.Add) }
            }
        }
        if (dash.workouts.isEmpty()) item {
            EmptyState(Icons.Rounded.FitnessCenter, "No workouts yet", "Record one live with a heart-rate strap, log one by hand, or import your history.", c.activity)
        }
        items(dash.workouts, key = { it.id }) { w ->
            LoopCard(onClick = { vm.push(Route.WorkoutScreen(w.id)) }, padding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(sportIcon(w.sport), c.activity, 44.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(w.sport, style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                        Text("${fmtWhen(w)}  ·  ${Scoring.hm(w.durationMin)}", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    }
                    Pill("Strain ${w.strain.roundToInt()}", c.activity)
                }
            }
        }
    }
}

@Composable
fun WorkoutDetailScreen(vm: AppViewModel, dash: Dashboard, id: Long) {
    val c = MaterialTheme.colorScheme
    val w = dash.workouts.firstOrNull { it.id == id }
    if (w == null) { ScreenList { item { DetailHeader("Workout", { vm.pop() }) }; item { Text("This workout no longer exists.", color = c.onSurfaceVariant) } }; return }
    val zoneColors = listOf(c.sleep.copy(alpha = 0.55f), c.secondary, c.warn, c.activity, c.bad)
    val total = w.zoneMin.sum().takeIf { it > 0 }
    ScreenList {
        item { DetailHeader(w.sport, { vm.pop() }, subtitle = fmtWhen(w)) }
        item {
            LoopCard(accent = c.activity) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Big2("Strain", w.strain.roundToInt().toString(), Scoring.strainWord(w.strain))
                    Big2("Duration", Scoring.hm(w.durationMin), null)
                    Big2("Calories", w.kcal?.roundToInt()?.toString() ?: "–", "kcal")
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Big2("Average HR", w.avgHr?.toString() ?: "–", "bpm")
                    Big2("Max HR", w.maxHr?.toString() ?: "–", "bpm")
                    Spacer(Modifier.width(64.dp))
                }
            }
        }
        if (total != null) item {
            LoopCard {
                Text("Time in heart-rate zones", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                Spacer(Modifier.height(4.dp))
                Text("Higher zones work your heart harder and add strain faster.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                Spacer(Modifier.height(14.dp))
                w.zoneMin.forEachIndexed { i, m ->
                    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Z${i + 1}", style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant, modifier = Modifier.width(28.dp))
                        Canvas(Modifier.weight(1f).height(12.dp)) {
                            drawRoundRect(c.outlineVariant.copy(alpha = 0.5f), Offset.Zero, size, CornerRadius(size.height / 2))
                            val frac = (m / total).toFloat()
                            if (frac > 0) drawRoundRect(zoneColors[i], Offset.Zero, Size((size.width * frac).coerceAtLeast(size.height), size.height), CornerRadius(size.height / 2))
                        }
                        Text("${Scoring.hm(m)}", style = MaterialTheme.typography.labelMedium, color = c.onSurface, modifier = Modifier.width(64.dp).padding(start = 10.dp))
                    }
                    Text(Scoring.zoneNames[i], style = MaterialTheme.typography.labelSmall, color = c.outline, modifier = Modifier.padding(start = 28.dp))
                }
            }
        }
        item {
            Text(
                when (w.source) { "strap" -> "Recorded live with a heart-rate device."; "manual" -> "Logged by hand. Heart-rate zones are estimated from your effort rating."; "import" -> "Imported from your WHOOP export."; else -> "Sample workout." },
                style = MaterialTheme.typography.bodySmall, color = c.outline,
            )
        }
        item { TextButton({ vm.deleteWorkout(w.id); vm.pop() }) { androidx.compose.material3.Icon(Icons.Rounded.DeleteOutline, null, tint = c.bad); Spacer(Modifier.width(6.dp)); Text("Delete workout", color = c.bad) } }
    }
}

@Composable
private fun Big2(label: String, value: String, sub: String?) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
        if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.activity)
    }
}
