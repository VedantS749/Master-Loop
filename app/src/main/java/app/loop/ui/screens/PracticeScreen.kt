package app.loop.ui.screens

import app.loop.ui.theme.activity
import app.loop.ui.theme.bad
import app.loop.ui.theme.mind
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.loop.ui.theme.sleep
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.loop.ble.HeartRateMonitor
import app.loop.ui.AppViewModel
import app.loop.ui.Dashboard
import app.loop.ui.Route
import app.loop.ui.components.IconBadge
import app.loop.ui.components.LoopCard

/** The "do something" tab: live heart rate, workouts and calming tools. */
@Composable
fun PracticeScreen(vm: AppViewModel, dash: Dashboard) {
    val c = MaterialTheme.colorScheme
    val st by vm.monitor.state.collectAsStateWithLifecycle()
    val rec by vm.recording.collectAsStateWithLifecycle()
    val connected = st.phase == HeartRateMonitor.Phase.CONNECTED

    ScreenList {
        item {
            Column(Modifier.padding(top = 8.dp)) {
                Text("Practice", style = MaterialTheme.typography.headlineLarge, color = c.onSurface)
                Text("Tools you use in the moment.", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            }
        }
        item {
            ToolCard(
                Icons.Rounded.Favorite, if (connected) "Live heart rate" else "Connect a device",
                when {
                    rec.active -> "Recording ${rec.sport} · tap to view"
                    connected -> "${st.device?.name ?: "Connected"} · ${st.bpm ?: "--"} bpm"
                    else -> "Pair a strap or band for live heart rate and workouts"
                },
                c.bad, badge = if (connected) "LIVE" else null,
            ) { vm.push(Route.Live) }
        }
        item { SectionLabel("Train") }
        item { ToolCard(Icons.Rounded.Sensors, "Record a workout", "Zones, strain and calories from your heart rate", c.activity) { vm.push(Route.Live) } }
        item { ToolCard(Icons.Rounded.Edit, "Log a workout by hand", "No strap? Add sport, time and how hard it felt", c.activity) { vm.push(Route.LogWorkout) } }
        item { ToolCard(Icons.Rounded.Timer, "Interval timer", "Work and rest rounds with haptic cues", c.activity) { vm.push(Route.Intervals) } }
        item { ToolCard(Icons.Rounded.FitnessCenter, "Strength log", "Sets, reps and weight, with estimated one-rep max and personal bests", c.activity) { vm.push(Route.Strength) } }
        item { SectionLabel("Track") }
        item { ToolCard(Icons.Rounded.EditNote, "Journal", "Log caffeine, alcohol, late meals and more, and see what they do to recovery", c.mind) { vm.push(Route.Journal) } }
        item { SectionLabel("Sleep") }
        item { ToolCard(Icons.Rounded.Alarm, "Strap wake alarm", "Your strap buzzes you awake, even with the phone away", c.sleep) { vm.push(Route.Alarm) } }
        item { SectionLabel("Calm") }
        item { ToolCard(Icons.Rounded.Air, "Guided breathing", "Slow your breath to lower stress. 1 to 5 minutes.", c.mind) { vm.push(Route.Breathe) } }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp), color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun ToolCard(icon: ImageVector, title: String, subtitle: String, color: Color, badge: String? = null, onClick: () -> Unit) {
    LoopCard(onClick = onClick, accent = if (badge != null) color else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, color, 46.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    if (badge != null) { Spacer(Modifier.width(8.dp)); app.loop.ui.components.Pill(badge, color, filled = true) }
                }
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = color)
        }
    }
}
