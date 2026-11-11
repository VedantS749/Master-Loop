package app.loop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.loop.analytics.Confidence
import app.loop.analytics.MedEffect
import app.loop.analytics.MedicineImpacts
import app.loop.analytics.PainTrend
import app.loop.analytics.ProblemStatus
import app.loop.analytics.Readiness
import app.loop.analytics.RecoveryChecks
import app.loop.analytics.Scoring
import app.loop.model.Medicine
import app.loop.model.Problem
import app.loop.model.ProblemKind
import app.loop.model.Schedule
import app.loop.model.plural
import app.loop.ui.AppViewModel
import app.loop.ui.Dashboard
import app.loop.ui.components.Banner
import app.loop.ui.components.LoopCard
import app.loop.ui.components.Pill
import app.loop.ui.components.PrimaryButton
import app.loop.ui.components.Segmented
import app.loop.ui.components.Sparkline
import app.loop.ui.theme.good
import app.loop.ui.theme.warn
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

private val shortDate = DateTimeFormatter.ofPattern("d MMM")
private fun fmt(day: String) = runCatching { LocalDate.parse(day).format(shortDate) }.getOrDefault(day)

/** One place for medicines, injuries and illness: what you take or are healing from, and what it does to your numbers. */
@Composable
fun HealthLogScreen(vm: AppViewModel, dash: Dashboard) {
    val c = MaterialTheme.colorScheme
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var addMed by remember { mutableStateOf(false) }
    var addProblem by remember { mutableStateOf(false) }

    ScreenList {
        item {
            Column(Modifier.padding(top = 8.dp)) {
                Text("Health log", style = MaterialTheme.typography.headlineLarge, color = c.onSurface)
                Text("Medicines, injuries and illness, and what they do to your numbers.", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            }
        }
        item { Segmented(listOf("Medicines", "Injuries and illness"), tab, { tab = it }, c.primary) }
        if (tab == 0) medicineItems(vm, dash) { addMed = true } else problemItems(vm, dash) { addProblem = true }
        item { Text("Loop's notes are general information, not medical advice.", style = MaterialTheme.typography.labelSmall, color = c.outline) }
    }
    if (addMed) AddMedicineDialog({ addMed = false }) { n, d, s, start -> vm.addMedicine(n, d, s, start); addMed = false }
    if (addProblem) AddProblemDialog({ addProblem = false }) { k, a, sev, start -> vm.addProblem(k, a, sev, start); addProblem = false }
}

// =================================================================== medicines

private fun LazyListScope.medicineItems(vm: AppViewModel, dash: Dashboard, onAdd: () -> Unit) {
    val meds = dash.log.medicines
    val active = meds.filter { it.active }
    if (active.isEmpty()) item {
        LoopCard {
            Text("Track what you take", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "Add a medicine or supplement and Loop compares your resting heart rate, HRV, sleep and recovery from before and after, so you can see what it seems to do.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    active.forEach { m -> item(key = "med_${m.id}") { MedicineCard(vm, dash, m) } }
    item { PrimaryButton("Add a medicine", onAdd, Modifier.fillMaxWidth(), icon = Icons.Rounded.Add) }
    val stopped = meds.filter { !it.active }
    if (stopped.isNotEmpty()) {
        item { Text("Stopped", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 8.dp)) }
        stopped.forEach { m ->
            item(key = "old_${m.id}") {
                LoopCard {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                            Text("${fmt(m.start)} to ${fmt(m.end ?: m.start)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton({ vm.removeMedicine(m.id) }) { Text("Remove", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MedicineCard(vm: AppViewModel, dash: Dashboard, m: Medicine) {
    val c = MaterialTheme.colorScheme
    val today = LocalDate.now().toString()
    val impact = remember(m, dash.days, dash.log.doses) { MedicineImpacts.compute(m, dash.days, dash.log.doses) }
    val takenToday = dash.log.doses.any { it.medId == m.id && it.day == today }
    val doseCount = dash.log.doses.count { it.medId == m.id }
    LoopCard {
        Text(m.name, style = MaterialTheme.typography.titleMedium, color = c.onSurface)
        Text(
            listOfNotNull(m.dose.takeIf { it.isNotBlank() }, m.schedule.label, "since ${fmt(m.start)}").joinToString("  ·  "),
            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
        )
        if (m.schedule == Schedule.AS_NEEDED) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                PrimaryButton(if (takenToday) "Taken today" else "Took it today", { vm.toggleDose(m.id) }, color = if (takenToday) c.good else c.primary)
                Spacer(Modifier.width(12.dp))
                Text(plural(doseCount, "dose") + " logged", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("What it seems to do", style = MaterialTheme.typography.labelLarge, color = c.onSurface)
        when {
            !impact.enough -> Text(
                if (m.schedule == Schedule.DAILY) "Loop needs about 5 days from before you started and 5 days from after the first few days. Keep wearing your strap and this fills in."
                else "Loop needs at least 3 mornings after a dose and 5 without one to compare.",
                style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
            )
            impact.effects.none { it.confidence != Confidence.UNCLEAR } -> Text(
                "No clear change in your numbers so far, based on ${plural(impact.daysBefore, "day")} before and ${plural(impact.daysAfter, "day")} after.",
                style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
            )
            else -> {
                impact.effects.filter { it.confidence != Confidence.UNCLEAR }.take(3).forEach { EffectLine(it) }
                Text("A pattern in your own data, not proof of cause.", style = MaterialTheme.typography.labelSmall, color = c.outline, modifier = Modifier.padding(top = 6.dp))
            }
        }
        MedicineImpacts.noteFor(m.name)?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            Text(MedicineImpacts.DISCLAIMER, style = MaterialTheme.typography.labelSmall, color = c.outline, modifier = Modifier.padding(top = 4.dp))
        }
        Row(Modifier.padding(top = 6.dp)) {
            TextButton({ vm.stopMedicine(m.id) }) { Text("Stopped taking it") }
            TextButton({ vm.removeMedicine(m.id) }) { Text("Remove", color = c.onSurfaceVariant) }
        }
    }
}

@Composable
private fun EffectLine(e: MedEffect) {
    val c = MaterialTheme.colorScheme
    val up = e.delta > 0
    val col = when { e.higherIsBetter == null -> c.onSurfaceVariant; e.higherIsBetter == up -> c.good; else -> c.warn }
    val size = when (e.unit) {
        "min" -> Scoring.hm(abs(e.delta))
        else -> if (abs(e.delta) < 10) "%.1f %s".format(abs(e.delta), e.unit) else "${abs(e.delta).roundToInt()} ${e.unit}"
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(e.label, style = MaterialTheme.typography.bodyMedium, color = c.onSurface, modifier = Modifier.weight(1f))
        Text((if (up) "▲ " else "▼ ") + size, style = MaterialTheme.typography.titleSmall, color = col, fontWeight = FontWeight.Bold)
    }
    Text(e.confidence.label, style = MaterialTheme.typography.labelSmall, color = c.outline)
}

// =================================================================== injuries and illness

private fun LazyListScope.problemItems(vm: AppViewModel, dash: Dashboard, onAdd: () -> Unit) {
    val active = dash.log.problems.filter { it.active }
    if (active.isEmpty()) item {
        LoopCard {
            Text("Healing from something?", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "Log an injury or illness, rate how it feels each day, and Loop shows how you are recovering and when it looks safe to ease back in.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    active.forEach { p -> item(key = "prob_${p.id}") { ProblemCard(vm, dash, p) } }
    item { PrimaryButton("Log an injury or illness", onAdd, Modifier.fillMaxWidth(), icon = Icons.Rounded.Add) }
    val healed = dash.log.problems.filter { !it.active }
    if (healed.isNotEmpty()) {
        item { Text("Healed", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 8.dp)) }
        healed.forEach { p ->
            item(key = "healed_${p.id}") {
                LoopCard {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                            Text("${fmt(p.start)} to ${fmt(p.end ?: p.start)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton({ vm.removeProblem(p.id) }) { Text("Remove", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProblemCard(vm: AppViewModel, dash: Dashboard, p: Problem) {
    val c = MaterialTheme.colorScheme
    val st: ProblemStatus = remember(p, dash.log.pain, dash.days) { RecoveryChecks.status(p, dash.log.pain, dash.days) }
    var level by remember(p.id, st.painNow) { mutableFloatStateOf(st.painNow.toFloat()) }
    val label = if (p.kind == ProblemKind.INJURY) "pain" else "how unwell"
    LoopCard {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.title, style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                Text("Day ${st.dayCount}  ·  began ${fmt(p.start)}", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
            Pill(st.trend.label, when (st.trend) { PainTrend.IMPROVING -> c.good; PainTrend.WORSENING -> c.warn; PainTrend.STEADY -> c.outline })
        }
        Spacer(Modifier.height(14.dp))
        Text("How is the $label today?  ${level.roundToInt()} out of 10", style = MaterialTheme.typography.labelLarge, color = c.onSurface)
        Slider(level, { level = it }, valueRange = 0f..10f, steps = 9)
        TextButton({ vm.logPain(p.id, level.roundToInt()) }) { Text("Save today's rating") }

        Spacer(Modifier.height(8.dp))
        val col = when (st.readiness) { Readiness.REST -> c.warn; Readiness.GENTLE -> c.primary; else -> c.good }
        Text(st.readiness.label, style = MaterialTheme.typography.titleSmall, color = col, fontWeight = FontWeight.Bold)
        Text(st.advice, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        st.warning?.let { Spacer(Modifier.height(10.dp)); Banner(it, c.warn) }

        if (st.painSeries.count { it != null } >= 2) {
            Spacer(Modifier.height(14.dp))
            Text("${label.replaceFirstChar { it.uppercase() }}, last 14 days", style = MaterialTheme.typography.labelMedium, color = c.outline)
            Sparkline(st.painSeries, col, Modifier.fillMaxWidth().height(34.dp))
        }
        if (st.recoverySeries.count { it != null } >= 2) {
            Spacer(Modifier.height(8.dp))
            Text("Recovery, last 14 days", style = MaterialTheme.typography.labelMedium, color = c.outline)
            Sparkline(st.recoverySeries, c.good, Modifier.fillMaxWidth().height(34.dp))
        }
        Row(Modifier.padding(top = 6.dp)) {
            TextButton({ vm.healProblem(p.id) }) { Text("Mark as healed") }
            TextButton({ vm.removeProblem(p.id) }) { Text("Remove", color = c.onSurfaceVariant) }
        }
    }
}

// =================================================================== dialogs

private val startOptions = listOf("Today" to 0, "Yesterday" to 1, "Last week" to 7, "Last month" to 30)
private val problemStarts = listOf("Today" to 0, "Yesterday" to 1, "3 days ago" to 3, "Last week" to 7)

@Composable
private fun AddMedicineDialog(onDismiss: () -> Unit, onAdd: (String, String, Schedule, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var dose by remember { mutableStateOf("") }
    var sched by remember { mutableIntStateOf(0) }
    var start by remember { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("Add a medicine") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(dose, { dose = it }, label = { Text("Dose (optional)") }, singleLine = true)
                Segmented(Schedule.entries.map { it.label }, sched, { sched = it }, MaterialTheme.colorScheme.primary)
                Text("Started", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                Segmented(startOptions.map { it.first }, start, { start = it }, MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = {
            TextButton(
                { onAdd(name, dose, Schedule.entries[sched], LocalDate.now().minusDays(startOptions[start].second.toLong()).toString()) },
                enabled = name.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

private val injuryAreas = listOf("Knee", "Ankle", "Foot", "Hip", "Back", "Neck", "Shoulder", "Elbow", "Wrist", "Muscle", "Head", "Other")
private val illnessKinds = listOf("Cold or flu", "Fever", "Stomach bug", "Other illness")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddProblemDialog(onDismiss: () -> Unit, onAdd: (ProblemKind, String, Int, String) -> Unit) {
    var kind by remember { mutableIntStateOf(0) }
    var area by remember { mutableStateOf("") }
    var custom by remember { mutableStateOf("") }
    var severity by remember { mutableFloatStateOf(5f) }
    var start by remember { mutableIntStateOf(0) }
    val options = if (kind == 0) injuryAreas else illnessKinds
    val isOther = area == "Other" || area == "Other illness"
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("Log an injury or illness") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Segmented(ProblemKind.entries.map { it.label }, kind, { kind = it; area = "" }, MaterialTheme.colorScheme.primary)
                Text(if (kind == 0) "Where?" else "What kind?", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { o -> FilterChip(area == o, { area = o }, label = { Text(o) }) }
                }
                if (isOther) OutlinedTextField(custom, { custom = it }, label = { Text("Describe it") }, singleLine = true)
                Text("How bad is it? ${severity.roundToInt()} out of 10", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                Slider(severity, { severity = it }, valueRange = 1f..10f, steps = 8)
                Text("Began", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                Segmented(problemStarts.map { it.first }, start, { start = it }, MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = {
            val name = if (isOther) custom.trim() else area
            TextButton(
                { onAdd(ProblemKind.entries[kind], name, severity.roundToInt(), LocalDate.now().minusDays(problemStarts[start].second.toLong()).toString()) },
                enabled = name.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
