package app.loop.ui.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.loop.analytics.Confidence
import app.loop.analytics.CycleEngine
import app.loop.analytics.JournalCatalog
import app.loop.analytics.Scoring
import app.loop.analytics.StrengthMath
import app.loop.ble.HeartRateMonitor
import app.loop.model.StrengthExercise
import app.loop.model.StrengthSession
import app.loop.model.StrengthSet
import app.loop.ui.AppViewModel
import app.loop.ui.Dashboard
import app.loop.ui.Route
import app.loop.ui.components.DetailHeader
import app.loop.ui.components.LoopCard
import app.loop.ui.components.Pill
import app.loop.ui.components.PrimaryButton
import app.loop.ui.components.SecondaryButton
import app.loop.ui.components.Segmented
import app.loop.ui.components.Sparkline
import app.loop.ui.theme.bad
import app.loop.ui.theme.good
import app.loop.ui.theme.mind
import app.loop.ui.theme.activity
import app.loop.ui.theme.body
import app.loop.ui.theme.sleep
import app.loop.ui.theme.warn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt
import app.loop.model.plural

// ======================================================================== Journal

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JournalScreen(vm: AppViewModel, dash: Dashboard) {
    val c = MaterialTheme.colorScheme
    var yesterday by remember { mutableStateOf(false) }
    val day = LocalDate.now().minusDays(if (yesterday) 1 else 0).toString()
    val existing = dash.journal.firstOrNull { it.day == day }
    var picked by remember(day, existing) { mutableStateOf(existing?.factors?.toSet() ?: emptySet()) }
    var saved by remember(day) { mutableStateOf(existing != null) }

    ScreenList {
        item { DetailHeader("Journal", { vm.pop() }, subtitle = "See what actually moves your recovery") }
        item {
            LoopCard {
                Segmented(listOf("Today", "Yesterday"), if (yesterday) 1 else 0, { yesterday = it == 1 }, c.mind)
                Spacer(Modifier.height(12.dp))
                Text("What applied ${if (yesterday) "yesterday" else "today"}?", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                Text("Tick everything that fits. Saving with nothing ticked also counts: it tells Loop it was a normal day.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    JournalCatalog.factors.forEach { f ->
                        FilterChip(selected = f.id in picked, onClick = { picked = if (f.id in picked) picked - f.id else picked + f.id; saved = false }, label = { Text(f.label) })
                    }
                }
                Spacer(Modifier.height(14.dp))
                PrimaryButton(if (saved) "Saved" else "Save journal", { vm.saveJournal(day, picked.toList()); saved = true }, enabled = !saved, color = c.mind)
            }
        }
        item {
            Text("What your journal shows", style = MaterialTheme.typography.titleLarge, color = c.onSurface, modifier = Modifier.padding(top = 4.dp))
            Text("Effects on the next morning, compared with your other journaled days. ${plural(dash.journal.size, "day")} logged.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
        if (dash.journalEffects.isEmpty()) item {
            LoopCard { Text("Not enough yet. Loop needs at least ${app.loop.analytics.JournalImpact.MIN_DAYS} days with a factor and ${app.loop.analytics.JournalImpact.MIN_DAYS} without it, plus the recovery that followed. Keep logging.", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant) }
        }
        dash.journalEffects.forEach { e ->
            item(key = "fx_${e.factorId}") {
                val rd = e.recoveryDelta
                val tone = when { rd == null -> c.onSurfaceVariant; rd >= 3 -> c.good; rd <= -3 -> c.warn; else -> c.onSurfaceVariant }
                LoopCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(JournalCatalog.label(e.factorId), style = MaterialTheme.typography.titleMedium, color = c.onSurface, modifier = Modifier.weight(1f))
                        Pill(e.confidence.label, when (e.confidence) { Confidence.CLEAR -> c.good; Confidence.POSSIBLE -> c.primary; Confidence.UNCLEAR -> c.outline })
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (rd == null) "Not enough next-morning data." else "${if (rd >= 0) "+" else "−"}${abs(rd).roundToInt()} points of recovery the next morning",
                        style = MaterialTheme.typography.titleLarge, color = tone,
                    )
                    val extra = listOfNotNull(
                        e.hrvDelta?.let { "HRV ${if (it >= 0) "+" else "−"}${abs(it).roundToInt()} ms" },
                        e.sleepDelta?.let { "Sleep score ${if (it >= 0) "+" else "−"}${abs(it).roundToInt()}" },
                    ).joinToString("  ·  ")
                    if (extra.isNotEmpty()) Text(extra, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                    Text("Based on ${plural(e.withDays, "day")} with it and ${e.withoutDays} without. A pattern in your data, not proof of cause.", style = MaterialTheme.typography.labelSmall, color = c.outline, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

// ========================================================================== Cycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CycleScreen(vm: AppViewModel, dash: Dashboard) {
    val c = MaterialTheme.colorScheme
    val on = dash.settings.cycleTracking
    val starts = dash.cycleStarts
    val info = CycleEngine.info(LocalDate.now(), starts)
    var picking by remember { mutableStateOf(false) }
    val fmt = DateTimeFormatter.ofPattern("d MMM yyyy")

    ScreenList {
        item { DetailHeader("Cycle tracking", { vm.pop() }, subtitle = "Optional and private") }
        if (!on) {
            item {
                LoopCard(accent = c.body) {
                    Text("Understand your cycle's effect on recovery", style = MaterialTheme.typography.titleLarge, color = c.onSurface)
                    Spacer(Modifier.height(6.dp))
                    Text("Log when each period starts. Loop works out your phase and shows how recovery, HRV, resting heart rate, skin temperature and sleep change across it. Everything stays on this phone.", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton("Turn on cycle tracking", { vm.update { it.copy(cycleTracking = true) } }, color = c.body)
                }
            }
            return@ScreenList
        }
        item {
            LoopCard(accent = c.body) {
                if (info == null) {
                    Text("No cycle logged yet", style = MaterialTheme.typography.titleLarge, color = c.onSurface)
                    Text("Log the first day of your last period to get started.", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                } else {
                    Text("Day ${info.dayOfCycle} · ${info.phase.label} phase", style = MaterialTheme.typography.titleLarge, color = c.body)
                    Spacer(Modifier.height(4.dp))
                    Text(info.phase.blurb, style = MaterialTheme.typography.bodyMedium, color = c.onSurface)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (info.daysUntilNext >= 0) "Next period expected around ${info.nextStart.format(fmt)} (${if (info.daysUntilNext == 0) "today" else "in " + plural(info.daysUntilNext, "day")}). Typical length ${plural(info.cycleLength, "day")}."
                        else "Your period is ${plural(-info.daysUntilNext, "day")} later than your typical ${info.cycleLength}-day cycle.",
                        style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            LoopCard {
                Text("Log a period start", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { PrimaryButton("Today", { vm.addPeriodStart(LocalDate.now()) }, color = c.body) }
                    Box(Modifier.weight(1f)) { SecondaryButton("Yesterday", { vm.addPeriodStart(LocalDate.now().minusDays(1)) }) }
                }
                Spacer(Modifier.height(8.dp))
                SecondaryButton("Pick another date", { picking = true })
            }
        }
        val stats = CycleEngine.phaseStats(dash.days, starts).filter { it.days > 0 }
        if (stats.isNotEmpty() && starts.size >= 2) {
            item {
                LoopCard {
                    Text("How each phase looks for you", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                    Text("Averages over your logged cycles. Not enough cycles means rough numbers.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    stats.forEach { ps ->
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Text("${ps.phase.label}  ·  ${plural(ps.days, "day")}", style = MaterialTheme.typography.labelLarge, color = c.body)
                            Text(
                                listOfNotNull(
                                    ps.recovery?.let { "Recovery ${it.roundToInt()}%" }, ps.hrv?.let { "HRV ${it.roundToInt()} ms" },
                                    ps.rhr?.let { "Resting HR ${it.roundToInt()}" }, ps.sleepScore?.let { "Sleep ${it.roundToInt()}" },
                                    ps.skinDev?.let { "Skin %+.1f°".format(it) },
                                ).joinToString("  ·  "),
                                style = MaterialTheme.typography.bodySmall, color = c.onSurface,
                            )
                        }
                    }
                }
            }
        }
        if (starts.isNotEmpty()) {
            item { Text("Logged periods", style = MaterialTheme.typography.titleMedium, color = c.onSurface, modifier = Modifier.padding(top = 4.dp)) }
            starts.reversed().forEach { d ->
                item(key = "cs_$d") {
                    LoopCard(padding = 12.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(d.format(fmt), style = MaterialTheme.typography.bodyLarge, color = c.onSurface, modifier = Modifier.weight(1f))
                            IconButton({ vm.removePeriodStart(d.toString()) }) { Icon(Icons.Rounded.DeleteOutline, "Remove", tint = c.outline) }
                        }
                    }
                }
            }
        }
        item {
            Text("Cycle insights are general wellness information, not contraception or medical advice.", style = MaterialTheme.typography.labelSmall, color = c.outline)
            TextButton({ vm.update { it.copy(cycleTracking = false) } }) { Text("Turn off cycle tracking", color = c.onSurfaceVariant) }
        }
    }

    if (picking) {
        val st = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton({
                    st.selectedDateMillis?.let { ms -> vm.addPeriodStart(Instant.ofEpochMilli(ms).atZone(ZoneId.of("UTC")).toLocalDate()) }
                    picking = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton({ picking = false }) { Text("Cancel") } },
        ) { DatePicker(st) }
    }
}

// ======================================================================= Strength

@Composable
fun StrengthScreen(vm: AppViewModel, dash: Dashboard) {
    val c = MaterialTheme.colorScheme
    val imp = dash.settings.imperial
    fun w(kg: Double) = if (imp) "${(kg * 2.20462).roundToInt()} lb" else "${(kg * 10).roundToInt() / 10.0} kg"
    val names = StrengthMath.exerciseNames(dash.strength)
    val dateFmt = DateTimeFormatter.ofPattern("EEE d MMM")

    ScreenList {
        item { DetailHeader("Strength log", { vm.pop() }, subtitle = plural(dash.strength.size, "session")) }
        item { PrimaryButton("New session", { vm.push(Route.StrengthEdit) }, icon = Icons.Rounded.Add, color = c.activity) }
        if (names.isNotEmpty()) {
            item { Text("Progress by exercise", style = MaterialTheme.typography.titleLarge, color = c.onSurface, modifier = Modifier.padding(top = 4.dp)) }
            names.take(6).forEach { n ->
                val pr = StrengthMath.progress(dash.strength, n)
                if (pr.isEmpty()) return@forEach
                item(key = "pr_$n") {
                    LoopCard(padding = 14.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(n, style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                                val first = pr.first().second; val last = pr.last().second
                                val gain = if (pr.size > 1 && first >= 5) (last - first) / first * 100 else null
                                Text("Est. 1RM ${w(last)}" + (gain?.let { "  ·  ${if (it >= 0) "+" else "−"}${abs(it).roundToInt()}% since first" } ?: ""), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                                if (pr.size > 1 && last >= pr.maxOf { it.second }) Text("Personal best", style = MaterialTheme.typography.labelMedium, color = c.good)
                            }
                            Sparkline(pr.map { it.second }, c.activity, Modifier.width(90.dp).height(36.dp))
                        }
                    }
                }
            }
        }
        item { Text("Sessions", style = MaterialTheme.typography.titleLarge, color = c.onSurface, modifier = Modifier.padding(top = 4.dp)) }
        if (dash.strength.isEmpty()) item {
            LoopCard { Text("No sessions yet. Log one to see volume, estimated one-rep maxes and personal bests over time.", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant) }
        }
        dash.strength.forEach { s ->
            item(key = "ss_${s.id}") {
                LoopCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                            Text("${LocalDate.parse(s.day).format(dateFmt)}  ·  ${s.durationMin} min  ·  ${plural(s.totalSets, "set")}  ·  ${if (imp) "${(s.volumeKg * 2.20462).roundToInt()} lb" else "${s.volumeKg.roundToInt()} kg"} volume", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                        }
                        IconButton({ vm.deleteStrength(s.id) }) { Icon(Icons.Rounded.DeleteOutline, "Delete", tint = c.outline) }
                    }
                    Spacer(Modifier.height(6.dp))
                    s.exercises.forEach { e ->
                        val top = e.sets.maxByOrNull { it.kg * it.reps }
                        Text("${e.name}: ${e.sets.size} × ${e.sets.map { it.reps }.let { r -> if (r.distinct().size == 1) "${r[0]}" else "${r.min()}-${r.max()}" }}" + (top?.let { "  @ up to ${w(e.sets.maxOf { st -> st.kg })}" } ?: ""), style = MaterialTheme.typography.bodySmall, color = c.onSurface)
                    }
                }
            }
        }
    }
}

private class SetState(reps: Int, kg: Double) { var reps by mutableStateOf(reps); var kg by mutableStateOf(kg) }
private class ExState(val name: String, val sets: androidx.compose.runtime.snapshots.SnapshotStateList<SetState> = mutableStateListOf())

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StrengthEditScreen(vm: AppViewModel, dash: Dashboard) {
    val c = MaterialTheme.colorScheme
    val imp = dash.settings.imperial
    var template by remember { mutableStateOf("Full body") }
    var minutes by remember { mutableStateOf(50f) }
    var rpe by remember { mutableStateOf(7f) }
    val exercises = remember { mutableStateListOf<ExState>() }
    var query by remember { mutableStateOf("") }
    val suggestions = remember(dash.strength) { (StrengthMath.exerciseNames(dash.strength) + StrengthMath.commonExercises).distinctBy { it.lowercase() } }
    val sliderColors = SliderDefaults.colors(thumbColor = c.activity, activeTrackColor = c.activity, inactiveTrackColor = c.outlineVariant)
    val step = if (imp) 5.0 / 2.20462 else 2.5
    fun show(kg: Double) = if (imp) "${(kg * 2.20462).roundToInt()} lb" else "${(kg * 10).roundToInt() / 10.0} kg"

    fun addExercise(name: String) {
        val n = name.trim(); if (n.isEmpty()) return
        // Start from the last weight and reps used for this exercise, if any.
        val last = dash.strength.firstNotNullOfOrNull { s -> s.exercises.firstOrNull { it.name.equals(n, true) }?.sets?.lastOrNull() }
        exercises += ExState(n).also { it.sets += SetState(last?.reps ?: 8, last?.kg ?: 20.0) }
        query = ""
    }

    ScreenList {
        item { DetailHeader("New strength session", { vm.pop() }) }
        item {
            LoopCard {
                Text("Session", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StrengthMath.templates.forEach { t -> FilterChip(selected = t == template, onClick = { template = t }, label = { Text(t) }) }
                }
                Spacer(Modifier.height(10.dp))
                Row { Text("Duration", style = MaterialTheme.typography.titleMedium, color = c.onSurface, modifier = Modifier.weight(1f)); Text("${minutes.roundToInt()} min", style = MaterialTheme.typography.titleMedium, color = c.activity) }
                Slider(minutes, { minutes = (it / 5).roundToInt() * 5f }, valueRange = 15f..150f, colors = sliderColors)
                Row { Text("How hard did it feel?", style = MaterialTheme.typography.titleMedium, color = c.onSurface, modifier = Modifier.weight(1f)); Text("${rpe.roundToInt()} / 10", style = MaterialTheme.typography.titleMedium, color = c.activity) }
                Slider(rpe, { rpe = it.roundToInt().toFloat() }, valueRange = 1f..10f, steps = 8, colors = sliderColors)
            }
        }
        exercises.forEachIndexed { exIdx, ex ->
            item(key = "ex_${exIdx}_${ex.name}") {
                LoopCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(ex.name, style = MaterialTheme.typography.titleMedium, color = c.onSurface, modifier = Modifier.weight(1f))
                        IconButton({ exercises.removeAt(exIdx) }) { Icon(Icons.Rounded.Close, "Remove exercise", tint = c.outline) }
                    }
                    ex.sets.forEachIndexed { i, st ->
                        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Set ${i + 1}", style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant, modifier = Modifier.width(48.dp))
                            Stepper2(plural(st.reps, "rep"), { if (st.reps > 1) st.reps-- }, { st.reps++ }, Modifier.weight(1f))
                            Stepper2(show(st.kg), { st.kg = (st.kg - step).coerceAtLeast(0.0) }, { st.kg += step }, Modifier.weight(1.2f))
                            IconButton({ if (ex.sets.size > 1) ex.sets.removeAt(i) }, Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, "Remove set", tint = c.outline, modifier = Modifier.size(16.dp)) }
                        }
                    }
                    TextButton({ val l = ex.sets.last(); ex.sets += SetState(l.reps, l.kg) }) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add set") }
                }
            }
        }
        item {
            LoopCard {
                Text("Add an exercise", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(query, { query = it.take(40) }, Modifier.fillMaxWidth(), placeholder = { Text("Type a name") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                if (query.isNotBlank()) TextButton({ addExercise(query) }) { Text("Add \"${query.trim()}\"") }
                Spacer(Modifier.height(8.dp))
                val shown = suggestions.filter { query.isBlank() || it.contains(query, true) }.filter { s -> exercises.none { it.name.equals(s, true) } }.take(12)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    shown.forEach { s -> FilterChip(selected = false, onClick = { addExercise(s) }, label = { Text(s) }) }
                }
            }
        }
        item {
            val ready = exercises.isNotEmpty() && exercises.all { it.sets.isNotEmpty() }
            PrimaryButton("Save session", {
                val session = StrengthSession(
                    id = System.currentTimeMillis() / 1000 - (minutes * 60).toLong(), name = if (template == "Custom") "Strength" else template, durationMin = minutes.roundToInt(), rpe = rpe.roundToInt(),
                    exercises = exercises.map { e -> StrengthExercise(e.name, e.sets.map { StrengthSet(it.reps, (it.kg * 10).roundToInt() / 10.0) }) },
                )
                vm.saveStrength(session); vm.pop()
            }, enabled = ready, color = c.activity)
        }
    }
}

@Composable
private fun Stepper2(label: String, onMinus: () -> Unit, onPlus: () -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        IconButton(onMinus, Modifier.size(32.dp)) { Icon(Icons.Rounded.Remove, "Less", tint = c.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
        Text(label, style = MaterialTheme.typography.labelLarge, color = c.onSurface, modifier = Modifier.padding(horizontal = 2.dp))
        IconButton(onPlus, Modifier.size(32.dp)) { Icon(Icons.Rounded.Add, "More", tint = c.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
    }
}

// ========================================================================== Alarm

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmScreen(vm: AppViewModel, dash: Dashboard) {
    val c = MaterialTheme.colorScheme
    val st by vm.monitor.state.collectAsStateWithLifecycle()
    val s = dash.settings
    val time = rememberTimePickerState(initialHour = s.alarmHour, initialMinute = s.alarmMinute, is24Hour = true)
    val ready = st.phase == HeartRateMonitor.Phase.CONNECTED && st.family != null && !st.pairingNeeded
    val armed = s.alarmAtMs > System.currentTimeMillis()
    val fmt = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")
    var pickingTime by remember { mutableStateOf(false) }
    if (pickingTime) AlertDialog(
        onDismissRequest = { pickingTime = false }, containerColor = c.surfaceContainerHigh,
        confirmButton = { TextButton({ pickingTime = false }) { Text("Done") } },
        text = { androidx.compose.material3.TimePicker(time) },
    )

    ScreenList {
        item { DetailHeader("Strap wake alarm", { vm.pop() }, subtitle = "Your strap buzzes you awake") }
        if (!ready) item {
            LoopCard(accent = c.warn) {
                Text("Connect and pair your WHOOP first", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                Text("The alarm is stored on the strap itself, which needs the full WHOOP connection. Open Live heart rate and finish pairing, then come back.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                SecondaryButton("Open Live heart rate", { vm.push(Route.Live) })
            }
        }
        item {
            val sysAlarm by vm.systemAlarm.collectAsStateWithLifecycle()
            val ctx = androidx.compose.ui.platform.LocalContext.current
            LaunchedEffect(Unit) { vm.refreshSystemAlarm() }
            LoopCard(accent = if (s.alarmFollowSystem) c.primary else null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Follow my phone's alarm", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                        Text("Loop reads your phone's next alarm and arms the strap for the same moment, so your strap and phone wake you together. Change or delete the phone alarm and the strap follows.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    }
                    androidx.compose.material3.Switch(s.alarmFollowSystem, { v -> vm.setFollowSystemAlarm(v) })
                }
                if (s.alarmFollowSystem) {
                    Spacer(Modifier.height(12.dp))
                    Text("Which alarms", style = MaterialTheme.typography.labelLarge, color = c.onSurface)
                    Spacer(Modifier.height(6.dp))
                    Segmented(listOf("All alarms", "First alarm only"), if (s.alarmAllAlarms) 0 else 1, { i -> vm.update { it.copy(alarmAllAlarms = i == 0) } }, c.primary)
                    Text(
                        if (s.alarmAllAlarms) "The strap holds one alarm at a time, so Loop arms it for your next phone alarm, then the one after, and so on."
                        else "The strap buzzes for your first alarm only. Snoozes and backup alarms within 6 hours of it are skipped.",
                        style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                    )
                    val soon = sysAlarm?.let { it > System.currentTimeMillis() && it < System.currentTimeMillis() + 24 * 3_600_000L } == true
                    Text(
                        when {
                            sysAlarm == null -> "No alarm is set on your phone."
                            else -> "Next phone alarm: " + Instant.ofEpochMilli(sysAlarm!!).atZone(ZoneId.systemDefault()).format(fmt)
                        },
                        style = MaterialTheme.typography.titleMedium, color = c.onSurface,
                    )
                    Text(
                        when {
                            !ready -> "Waiting for a paired strap connection."
                            sysAlarm != null && !soon -> "More than a day away, so the strap isn't armed yet. Loop arms it once the alarm is within 24 hours."
                            armed -> "Strap armed for the same time."
                            sysAlarm == null -> "Nothing to mirror."
                            else -> "Arming the strap…"
                        },
                        style = MaterialTheme.typography.bodySmall, color = if (armed && soon) c.good else c.onSurfaceVariant,
                    )
                    SecondaryButton("Open phone clock", { app.loop.SystemAlarms.openClock(ctx) }, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
        if (!s.alarmFollowSystem) {
            item {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                LoopCard {
                    Text("Wake me at", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("%02d:%02d".format(time.hour, time.minute), style = MaterialTheme.typography.displayMedium, color = c.onSurface, modifier = Modifier.weight(1f))
                        SecondaryButton("Change", { pickingTime = true }, modifier = Modifier.width(130.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    PrimaryButton(if (armed) "Update alarm" else "Arm alarm", { vm.setWakeAlarm(time.hour, time.minute) }, enabled = ready, color = c.primary)
                    if (armed) {
                        Spacer(Modifier.height(8.dp))
                        Text("Armed for ${Instant.ofEpochMilli(s.alarmAtMs).atZone(ZoneId.systemDefault()).format(fmt)}", style = MaterialTheme.typography.bodyMedium, color = c.good)
                        SecondaryButton("Clear alarm", { vm.clearWakeAlarm() }, modifier = Modifier.padding(top = 8.dp))
                    }
                    SecondaryButton("Also add to phone clock", { app.loop.SystemAlarms.addToClock(ctx, time.hour, time.minute, "Loop wake alarm") }, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        item {
            LoopCard {
                if (!s.alarmFollowSystem) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Repeat every day", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                            Text("The strap fires each alarm once. Loop re-arms it whenever the strap is connected.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                        }
                        androidx.compose.material3.Switch(s.alarmRepeat, { v -> vm.update { it.copy(alarmRepeat = v) } })
                    }
                    Spacer(Modifier.height(10.dp))
                }
                SecondaryButton("Test buzz now", { vm.monitor.buzzNow() })
            }
        }
        item {
            LoopCard {
                st.alarmNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.onSurface) }
                st.clockSkewSec?.let {
                    Text(
                        if (abs(it) <= 5) "Strap clock matches your phone." else "Strap clock is ${abs(it)} seconds ${if (it > 0) "ahead of" else "behind"} your phone. Loop adjusts the alarm for this.",
                        style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                    )
                }
                Text("Experimental: the strap confirms it accepted the alarm, but Loop can't see the buzz itself. Test it once before relying on it.", style = MaterialTheme.typography.labelSmall, color = c.outline, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

// ================================================================ Stress timeline

/** Daytime stress across one date in 5-minute windows, from the strap's saved history. */
@Composable
fun StressTimelineCard(vm: AppViewModel, date: LocalDate) {
    val c = MaterialTheme.colorScheme
    val data by produceState<List<Pair<Int, Double>>?>(null, date) { value = null; value = vm.stressTimeline(date) }
    LoopCard {
        Text("Stress through the day", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
        val d = data
        when {
            d == null -> Text("Working it out…", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            d.size < 6 -> Text("Needs strap history for this day. Download it from Live heart rate and Loop will chart your stress by the hour whenever you were sitting still.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            else -> {
                val peak = d.maxBy { it.second }
                Text("Average ${d.map { it.second }.average().roundToInt()}  ·  peak ${peak.second.roundToInt()} around ${"%02d:%02d".format(peak.first / 60, peak.first % 60)}", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                val good = c.good; val warn = c.warn; val bad = c.bad; val track = c.outlineVariant
                Canvas(Modifier.fillMaxWidth().height(90.dp)) {
                    drawRoundRect(track.copy(alpha = 0.3f), Offset.Zero, size, CornerRadius(8f))
                    val w = size.width / (24 * 12f)
                    d.forEach { (minOfDay, v) ->
                        val h = (size.height * (v / 100.0)).toFloat().coerceAtLeast(3f)
                        val col = if (v >= 65) bad else if (v >= 35) warn else good
                        drawRect(col, Offset(minOfDay / 5f * w, size.height - h), Size(w * 0.85f, h))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("00:00", "06:00", "12:00", "18:00", "24:00").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = c.outline) }
                }
                Text("Only moments when your wrist was still are scored, so exercise isn't counted as stress.", style = MaterialTheme.typography.labelSmall, color = c.outline, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}
