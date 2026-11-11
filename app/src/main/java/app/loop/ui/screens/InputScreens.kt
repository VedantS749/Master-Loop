package app.loop.ui.screens

import app.loop.ui.theme.activity
import app.loop.ui.theme.mind
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SentimentDissatisfied
import androidx.compose.material.icons.rounded.SentimentNeutral
import androidx.compose.material.icons.rounded.SentimentSatisfied
import androidx.compose.material.icons.rounded.SentimentVeryDissatisfied
import androidx.compose.material.icons.rounded.SentimentVerySatisfied
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.loop.analytics.Scoring
import app.loop.ui.AppViewModel
import app.loop.ui.Dashboard
import app.loop.ui.components.DetailHeader
import app.loop.ui.components.LoopCard
import app.loop.ui.components.PrimaryButton
import app.loop.ui.components.Segmented
import kotlin.math.roundToInt

private val moodIcons = listOf(
    Icons.Rounded.SentimentVeryDissatisfied, Icons.Rounded.SentimentDissatisfied, Icons.Rounded.SentimentNeutral,
    Icons.Rounded.SentimentSatisfied, Icons.Rounded.SentimentVerySatisfied,
)
private val moodWords = listOf("Low", "Meh", "Okay", "Good", "Great")
private val moodTags = listOf("Calm", "Energised", "Focused", "Happy", "Tired", "Anxious", "Irritable", "Low", "Social", "Alone", "Busy", "Relaxed")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MoodCheckInScreen(vm: AppViewModel, dash: Dashboard) {
    val c = MaterialTheme.colorScheme
    val existing = dash.moods.firstOrNull { it.day == java.time.LocalDate.now().toString() }
    var level by remember { mutableIntStateOf(existing?.level ?: 0) }
    var tags by remember { mutableStateOf(existing?.tags?.toSet() ?: emptySet()) }
    var note by remember { mutableStateOf(existing?.note ?: "") }

    ScreenList {
        item { DetailHeader("Mood check-in", { vm.pop() }, subtitle = "30 seconds, once a day") }
        item {
            LoopCard {
                Text("How do you feel right now?", style = MaterialTheme.typography.titleLarge, color = c.onSurface)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    moodIcons.forEachIndexed { i, icon ->
                        val on = level == i + 1
                        Column(
                            Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) c.mind.copy(alpha = 0.22f) else Color.Transparent).clickable { level = i + 1 }.padding(horizontal = 6.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(icon, moodWords[i], tint = if (on) c.mind else c.outline, modifier = Modifier.size(40.dp))
                            Text(moodWords[i], style = MaterialTheme.typography.labelSmall, color = if (on) c.onSurface else c.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item {
            LoopCard {
                Text("What's behind it? (optional)", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    moodTags.forEach { t ->
                        val on = t in tags
                        Box(Modifier.clip(RoundedCornerShape(50)).background(if (on) c.mind else c.surfaceContainerHigh).clickable { tags = if (on) tags - t else tags + t }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                            Text(t, style = MaterialTheme.typography.labelLarge, color = if (on) Color.White else c.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    note, { note = it.take(240) }, Modifier.fillMaxWidth(), placeholder = { Text("A note to your future self") }, minLines = 2,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.mind, unfocusedBorderColor = c.outlineVariant, focusedTextColor = c.onSurface, unfocusedTextColor = c.onSurface),
                    shape = RoundedCornerShape(14.dp),
                )
            }
        }
        item { PrimaryButton("Save check-in", { vm.addMood(level, tags.toList(), note); vm.pop() }, enabled = level > 0, color = c.mind) }
    }
}

private val sportsList = listOf("Run", "Cycle", "Strength", "HIIT", "Swim", "Walk", "Yoga", "Sport", "Other")
private val effortWords = listOf("", "Very easy", "Easy", "Light", "Moderate", "Somewhat hard", "Hard", "Very hard", "Extremely hard", "Near max", "All-out")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogWorkoutScreen(vm: AppViewModel) {
    val c = MaterialTheme.colorScheme
    var sport by remember { mutableStateOf("Run") }
    var minutes by remember { mutableFloatStateOf(40f) }
    var effort by remember { mutableFloatStateOf(6f) }
    val sliderColors = SliderDefaults.colors(thumbColor = c.activity, activeTrackColor = c.activity, inactiveTrackColor = c.outlineVariant)
    ScreenList {
        item { DetailHeader("Log a workout", { vm.pop() }, subtitle = "For sessions without a heart-rate device") }
        item {
            LoopCard {
                Text("Sport", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    sportsList.forEach { s ->
                        val on = s == sport
                        Box(Modifier.clip(RoundedCornerShape(50)).background(if (on) c.activity else c.surfaceContainerHigh).clickable { sport = s }.padding(horizontal = 16.dp, vertical = 9.dp)) {
                            Text(s, style = MaterialTheme.typography.labelLarge, color = if (on) Color.White else c.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item {
            LoopCard {
                Row { Text("Duration", style = MaterialTheme.typography.titleMedium, color = c.onSurface, modifier = Modifier.weight(1f)); Text(Scoring.hm(minutes.toDouble()), style = MaterialTheme.typography.titleMedium, color = c.activity) }
                Slider(minutes, { minutes = (it / 5).roundToInt() * 5f }, valueRange = 5f..180f, colors = sliderColors)
                Spacer(Modifier.height(8.dp))
                Row { Text("How hard did it feel?", style = MaterialTheme.typography.titleMedium, color = c.onSurface, modifier = Modifier.weight(1f)); Text(effortWords[effort.roundToInt()], style = MaterialTheme.typography.titleMedium, color = c.activity) }
                Slider(effort, { effort = it.roundToInt().toFloat() }, valueRange = 1f..10f, steps = 8, colors = sliderColors)
                Text("1 is a gentle stroll, 10 is the hardest you could go.", style = MaterialTheme.typography.bodySmall, color = c.outline)
            }
        }
        item {
            PrimaryButton("Save workout", {
                // started `minutes` ago, so it lands on the right day even if logged straight after
                vm.logManualWorkout(sport, minutes.roundToInt(), effort.roundToInt(), System.currentTimeMillis() / 1000 - (minutes * 60).toLong())
                vm.pop()
            }, color = c.activity)
        }
    }
}
