package app.loop.ui.screens

import app.loop.ui.theme.activity
import app.loop.ui.theme.bad
import app.loop.ui.theme.good
import app.loop.ui.theme.mind
import app.loop.ui.theme.recovery
import app.loop.ui.theme.sleep
import app.loop.ui.theme.warn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.SentimentDissatisfied
import androidx.compose.material.icons.rounded.SentimentNeutral
import androidx.compose.material.icons.rounded.SentimentSatisfied
import androidx.compose.material.icons.rounded.SentimentVeryDissatisfied
import androidx.compose.material.icons.rounded.SentimentVerySatisfied
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.loop.analytics.Insight
import app.loop.analytics.Scoring
import app.loop.model.Category
import app.loop.model.DataMode
import app.loop.model.MetricId
import app.loop.model.Metrics
import app.loop.model.window
import app.loop.ui.AppViewModel
import app.loop.ui.Dashboard
import app.loop.ui.Route
import app.loop.ui.brand.LoopMark
import app.loop.ui.components.Banner
import app.loop.ui.components.CategoryLabel
import app.loop.ui.components.EmptyState
import app.loop.ui.components.IconBadge
import app.loop.ui.components.LoopCard
import app.loop.ui.components.ScoreRing
import app.loop.ui.components.SectionTitle
import app.loop.ui.components.icon
import app.loop.ui.theme.color
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val keyMetrics = mapOf(
    Category.RECOVERY to listOf(MetricId.RECOVERY, MetricId.HRV, MetricId.RHR),
    Category.SLEEP to listOf(MetricId.SLEEP_SCORE, MetricId.SLEEP_DURATION, MetricId.SLEEP_EFFICIENCY),
    Category.ACTIVITY to listOf(MetricId.STRAIN, MetricId.CALORIES, MetricId.STEPS),
    Category.BODY to listOf(MetricId.RESP, MetricId.SKIN_TEMP),
    Category.MIND to listOf(MetricId.STRESS, MetricId.MOOD),
    Category.DAYTIME to listOf(MetricId.DT_HR, MetricId.DT_HRV, MetricId.DT_STRESS),
)

@Composable
fun TodayScreen(vm: AppViewModel, dash: Dashboard) {
    val s = dash.settings
    val hour = LocalTime.now().hour
    val greet = when { hour < 5 -> "Good evening"; hour < 12 -> "Good morning"; hour < 18 -> "Good afternoon"; else -> "Good evening" }
    val name = s.name.trim().split(" ").firstOrNull().orEmpty()
    val latest = dash.latest
    val today = LocalDate.now().toString()

    ScreenList {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM")).uppercase(), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp), color = MaterialTheme.colorScheme.outline)
                    Text(if (name.isNotEmpty()) "$greet, $name" else greet, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurface)
                }
                LoopMark(size = 44.dp)
            }
        }

        if (s.mode == DataMode.DEMO) item {
            Banner(
                "You're exploring sample data. Anything you log here isn't saved.",
                MaterialTheme.colorScheme.primary, actionLabel = "Use my data", onAction = { vm.push(Route.Devices) },
            )
        }

        if (!dash.hasData) {
            item {
                LoopCard {
                    EmptyState(
                        Icons.Rounded.Sensors, "Let's get your first data in",
                        "Loop fills up once it can see your heart rate or your history. Pick whichever is easiest.",
                        MaterialTheme.colorScheme.primary, "Connect a device", { vm.push(Route.Devices) },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        TextButton({ vm.push(Route.ImportData) }) { Text("Import WHOOP history") }
                        TextButton({ vm.setMode(DataMode.DEMO) }) { Text("Try sample data") }
                    }
                }
            }
        }

        item { SetupChecklist(vm, dash) }
        item { RightNowCard(vm, dash) { vm.push(Route.Live) } }

        if (dash.hasData) {
            item { HeroCard(vm, dash) }
            // Right under the rings, where it is seen every morning, but slim enough not to crowd them.
            item { MoodQuickCard(vm, dash) }

            item {
                val why = androidx.compose.runtime.remember(dash.days, dash.settings) { app.loop.analytics.WhyTodays.build(dash.days, dash.settings) }
                if (why != null) WhyCard(why, latest?.recovery)
            }

            dash.brief?.target?.let { t ->
                item { StrainTargetCard(t, latest?.takeIf { it.day == today }?.strain, vm) }
            }
        }

        if (!dash.hasData) item { MoodQuickCard(vm, dash) }

        if (dash.hasData) {
            if (latest != null && latest.day != today) item {
                Text("Showing your latest data, from ${java.time.LocalDate.parse(latest.day).format(DateTimeFormatter.ofPattern("EEEE d MMM"))}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            item { KeyNumbers(vm, dash) }
            if (dash.insights.isNotEmpty()) {
                item {
                    SectionTitle("What we're noticing", subtitle = "Patterns in your own data", trailing = {
                        TextButton({ vm.selectTab(app.loop.ui.Tab.EXPLORE) }) { Text("See all") }
                    })
                }
                items(dash.insights.take(2).size) { i -> InsightCard(dash.insights[i]) }
            }
        }
    }
}

@Composable
private fun HeroCard(vm: AppViewModel, dash: Dashboard) {
    val brief = dash.brief
    val d = dash.latest
    val c = MaterialTheme.colorScheme
    val rec = d?.recovery
    val recColor = rec?.let { when (Scoring.recoveryBand(it)) { Scoring.Band.HIGH -> c.good; Scoring.Band.MID -> c.warn; Scoring.Band.LOW -> c.bad } } ?: c.recovery
    LoopCard(accent = recColor) {
        Text(brief?.headline ?: "Here's your day", style = MaterialTheme.typography.headlineMedium, color = c.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(brief?.sub ?: "Tap any ring to dig in.", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
        brief?.learning?.let { (have, goal) ->
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(progress = { have / goal.toFloat() }, Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), color = c.primary, trackColor = c.outlineVariant)
            Spacer(Modifier.height(4.dp))
            Text("Learning your normal: $have of $goal nights", style = MaterialTheme.typography.labelSmall, color = c.outline)
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Gauge("Recovery", rec, recColor, rec?.let { Scoring.recoveryWord(it) }) { vm.openMetric(MetricId.RECOVERY) }
            Gauge("Sleep", d?.sleepScore, c.sleep, d?.sleepScore?.let { Scoring.sleepWord(it) }) { vm.openMetric(MetricId.SLEEP_SCORE) }
            Gauge("Strain", d?.strain, c.activity, d?.strain?.let { Scoring.strainWord(it) }) { vm.openMetric(MetricId.STRAIN) }
        }
    }
}

@Composable
private fun Gauge(label: String, value: Double?, color: Color, word: String?, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ScoreRing(value?.let { (it / 100.0).toFloat() }, color, size = 92.dp, stroke = 9.dp) {
            Text(value?.roundToInt()?.toString() ?: "–", style = MaterialTheme.typography.titleLarge.copy(fontSize = 26.sp, fontWeight = FontWeight.Bold), color = if (value == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(word ?: "No data", style = MaterialTheme.typography.labelSmall, color = if (word == null) MaterialTheme.colorScheme.outline else color)
    }
}

@Composable
private fun StrainTargetCard(target: IntRange, strainToday: Double?, vm: AppViewModel) {
    val c = MaterialTheme.colorScheme
    LoopCard(onClick = { vm.openMetric(MetricId.STRAIN) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Category.ACTIVITY.icon(), c.activity, 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Today's strain target", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                Text("Best for your recovery: ${target.first} to ${target.last}", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
            Text(strainToday?.roundToInt()?.toString() ?: "0", style = MaterialTheme.typography.titleLarge, color = c.activity, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(14.dp))
        val track = c.outlineVariant; val tgt = c.activity.copy(alpha = 0.35f); val now = c.activity
        Canvas(Modifier.fillMaxWidth().height(12.dp)) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(track, Offset.Zero, size, r)
            val x0 = size.width * target.first / 100f; val x1 = size.width * target.last / 100f
            drawRoundRect(tgt, Offset(x0, 0f), Size(x1 - x0, size.height), r)
            val cur = ((strainToday ?: 0.0) / 100.0 * size.width).toFloat()
            if (cur > 0f) drawRoundRect(now, Offset.Zero, Size(cur.coerceAtLeast(size.height), size.height), r)
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Rest", style = MaterialTheme.typography.labelSmall, color = c.outline)
            Text("All-out", style = MaterialTheme.typography.labelSmall, color = c.outline)
        }
    }
}

@Composable
private fun CategorySection(vm: AppViewModel, dash: Dashboard, cat: Category) {
    val ids = keyMetrics.getValue(cat)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { vm.openCategory(cat) }.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(cat.icon(), cat.color(), 34.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(cat.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(cat.tagline, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("See all", style = MaterialTheme.typography.labelLarge, color = cat.color())
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = cat.color(), modifier = Modifier.padding(start = 4.dp).size(16.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ids.forEach { id -> StatTile(Metrics[id], dash, { vm.openMetric(id) }, Modifier.weight(1f)) }
            if (cat == Category.MIND) {
                LoopCard(Modifier.weight(1f), onClick = { vm.push(Route.Breathe) }, padding = 12.dp) {
                    Text("Breathe", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Icon(Icons.Rounded.Air, null, tint = cat.color(), modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(6.dp))
                    Text("Calm down in 2 min", style = MaterialTheme.typography.labelSmall, color = cat.color(), maxLines = 2)
                }
            }
        }
    }
}

@Composable
fun InsightCard(i: Insight, modifier: Modifier = Modifier) {
    LoopCard(modifier, accent = i.category.color()) {
        CategoryLabel(i.category)
        Spacer(Modifier.height(6.dp))
        Text(i.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(i.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val moodIcons: List<ImageVector> = listOf(
    Icons.Rounded.SentimentVeryDissatisfied, Icons.Rounded.SentimentDissatisfied, Icons.Rounded.SentimentNeutral,
    Icons.Rounded.SentimentSatisfied, Icons.Rounded.SentimentVerySatisfied,
)
private val moodWords = listOf("Low", "Meh", "Okay", "Good", "Great")

@Composable
private fun MoodQuickCard(vm: AppViewModel, dash: Dashboard) {
    val c = MaterialTheme.colorScheme
    val today = dash.moods.firstOrNull { it.day == LocalDate.now().toString() }
    LoopCard(accent = null) {
        if (today == null) {
            Text("How are you feeling today?", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                moodIcons.forEachIndexed { i, icon ->
                    Column(Modifier.clip(RoundedCornerShape(14.dp)).clickable { vm.addMood(i + 1, emptyList()) }.padding(horizontal = 6.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(icon, moodWords[i], tint = c.mind, modifier = Modifier.size(32.dp))
                        Text(moodWords[i], style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                    }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(moodIcons[(today.level - 1).coerceIn(0, 4)], null, tint = c.mind, modifier = Modifier.size(40.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Feeling ${moodWords[(today.level - 1).coerceIn(0, 4)].lowercase()} today", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                    Text(if (today.tags.isEmpty()) "Add what's behind it" else today.tags.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
                TextButton({ vm.push(Route.MoodCheckIn) }) { Text("Details", color = c.mind) }
            }
        }
    }
}

@Composable
private fun SetupChecklist(vm: AppViewModel, dash: Dashboard) {
    val s = dash.settings
    val steps = listOf(
        Triple("Tell Loop about you", s.profileDone, Route.Profile as Route),
        Triple("Bring in your data", dash.hasData && s.mode != DataMode.DEMO, Route.Devices as Route),
        Triple("Log your first mood check-in", dash.moods.isNotEmpty(), Route.MoodCheckIn as Route),
    )
    val done = steps.count { it.second }
    if (s.checklistDismissed || done == steps.size) return
    val c = MaterialTheme.colorScheme
    LoopCard(accent = c.primary) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Get set up", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                Text("$done of ${steps.size} done", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
            TextButton({ vm.update { it.copy(checklistDismissed = true) } }) { Text("Hide", color = c.onSurfaceVariant) }
        }
        steps.forEach { (label, ok, route) ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = !ok) { vm.push(route) }.padding(horizontal = 6.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null, tint = if (ok) c.good else c.outline, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(label, style = MaterialTheme.typography.bodyLarge, color = if (ok) c.outline else c.onSurface, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                if (!ok) Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = c.primary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** The handful of numbers worth a glance every day. Everything else lives in Explore. */
@Composable
private fun KeyNumbers(vm: AppViewModel, dash: Dashboard) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("Your key numbers", subtitle = "Overnight, compared with your own normal")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(MetricId.HRV, MetricId.RHR, MetricId.STRESS).forEach { id -> StatTile(Metrics[id], dash, { vm.openMetric(id) }, Modifier.weight(1f)) }
        }
        TextButton({ vm.selectTab(app.loop.ui.Tab.EXPLORE) }, Modifier.fillMaxWidth()) { Text("Explore every number") }
    }
}

/** What is behind today's recovery: each factor with its number against your usual, biggest first, then one line on what matters. */
@Composable
private fun WhyCard(why: app.loop.analytics.WhyToday, recovery: Double?) {
    val c = MaterialTheme.colorScheme
    LoopCard {
        Text(if (recovery != null) "What's behind your ${recovery.roundToInt()}% recovery" else "What Loop can see so far", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
        why.factors.forEach { f ->
            val col = when (f.effect) { app.loop.analytics.Effect.LIFTING -> c.good; app.loop.analytics.Effect.DRAGGING -> c.warn; app.loop.analytics.Effect.STEADY -> c.outline }
            val mark = when (f.effect) { app.loop.analytics.Effect.LIFTING -> "▲"; app.loop.analytics.Effect.DRAGGING -> "▼"; app.loop.analytics.Effect.STEADY -> "•" }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.Top) {
                Text(mark, style = MaterialTheme.typography.labelLarge, color = col, modifier = Modifier.width(22.dp).padding(top = 2.dp))
                Column(Modifier.weight(1f)) {
                    Row {
                        Text(f.title, style = MaterialTheme.typography.titleSmall, color = c.onSurface, modifier = Modifier.weight(1f))
                        Text(f.value, style = MaterialTheme.typography.titleSmall, color = col, fontWeight = FontWeight.Bold)
                    }
                    Text(f.detail, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
            }
        }
        why.bottomLine?.let {
            Spacer(Modifier.height(14.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = c.onSurface)
        }
    }
}
