package app.loop.ui.screens

import app.loop.ui.theme.activity
import app.loop.ui.theme.bad
import app.loop.ui.theme.good
import app.loop.ui.theme.sleep
import app.loop.ui.theme.warn
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.loop.BuildConfig
import app.loop.analytics.Scoring
import app.loop.ble.HeartRateMonitor
import app.loop.model.Category
import app.loop.model.DataMode
import app.loop.model.MetricDef
import app.loop.model.Metrics
import app.loop.model.ThemeMode
import app.loop.ui.AppViewModel
import app.loop.ui.Dashboard
import app.loop.ui.ImportUi
import app.loop.ui.Route
import app.loop.ui.Tab
import app.loop.ui.brand.LoopMark
import app.loop.ui.brand.LoopWordmark
import app.loop.ui.components.CategoryLabel
import app.loop.ui.components.Divider
import app.loop.ui.components.DetailHeader
import app.loop.ui.components.IconBadge
import app.loop.ui.components.LoopCard
import app.loop.ui.components.MetricInfoSheet
import app.loop.ui.components.NavRow
import app.loop.ui.components.PrimaryButton
import app.loop.ui.components.SecondaryButton
import app.loop.ui.components.Segmented
import app.loop.ui.components.icon
import app.loop.ui.theme.color
import kotlin.math.roundToInt
import app.loop.model.plural

@Composable
fun YouScreen(vm: AppViewModel, dash: Dashboard) {
    val c = MaterialTheme.colorScheme
    val s = dash.settings
    val ctx = LocalContext.current
    val st by vm.monitor.state.collectAsStateWithLifecycle()
    var confirmWipe by remember { mutableStateOf(false) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.exportCsv(uri) { ok -> Toast.makeText(ctx, if (ok) "Exported your data" else "Export failed", Toast.LENGTH_SHORT).show() }
    }

    ScreenList {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("You", style = MaterialTheme.typography.headlineLarge, color = c.onSurface)
                    Text("Your data, devices and settings.", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                }
            }
        }

        item {
            LoopCard(onClick = { vm.push(Route.Profile) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(52.dp).clip(CircleShape).background(c.primary.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                        Text((s.name.firstOrNull() ?: 'L').uppercase(), style = MaterialTheme.typography.titleLarge, color = c.primary, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.name.ifBlank { "Add your name" }, style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                        Text("${s.age} years · sleep need ${Scoring.hm(s.sleepNeedMin.toDouble())}", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    }
                    Text("Edit", style = MaterialTheme.typography.labelLarge, color = c.primary)
                }
            }
        }

        item { GroupLabel("Data") }
        item {
            LoopCard {
                val modeText = when (s.mode) { DataMode.DEMO -> "Sample data"; DataMode.REAL -> "Your data"; DataMode.NONE -> "Nothing yet" }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Showing", style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                        Text(modeText, style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                    }
                    if (s.mode == DataMode.DEMO) TextButton({ vm.setMode(DataMode.REAL) }) { Text("Switch to mine", color = c.primary) }
                    else TextButton({ vm.setMode(DataMode.DEMO) }) { Text("Try sample data", color = c.primary) }
                }
                Divider()
                NavRow(Icons.Rounded.Bluetooth, "Heart-rate device", if (st.phase == HeartRateMonitor.Phase.CONNECTED) "Connected: ${st.device?.name}" else "Pair a strap or band", c.secondary, { vm.push(Route.Devices) })
                Divider()
                NavRow(Icons.Rounded.FileDownload, "Import WHOOP history", "From your WHOOP data export", c.tertiary, { vm.push(Route.ImportData) })
                Divider()
                NavRow(Icons.Rounded.FileUpload, "Export my data", "Save everything as a spreadsheet (CSV)", c.sleep, { exporter.launch("loop-export.csv") })
            }
        }

        item { GroupLabel("Preferences") }
        item {
            LoopCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.Straighten, c.primary, 38.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Imperial units", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                        Text("Pounds and degrees Fahrenheit", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    }
                    Switch(s.imperial, { v -> vm.update { it.copy(imperial = v) } }, colors = SwitchDefaults.colors(checkedTrackColor = c.primary))
                }
                Spacer(Modifier.height(14.dp))
                Text("Colour palette", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                app.loop.ui.components.PalettePicker(s.palette) { p -> vm.update { it.copy(palette = p) } }
                Spacer(Modifier.height(14.dp))
                Text("Appearance", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Segmented(listOf("System", "Light", "Dark", "Black"), s.theme.ordinal, { i -> vm.update { it.copy(theme = ThemeMode.entries[i]) } }, c.primary)
            }
        }

        item { GroupLabel("Help") }
        item {
            LoopCard {
                NavRow(Icons.AutoMirrored.Rounded.HelpOutline, "How Loop works", "Plain-language guide and glossary", c.secondary, { vm.push(Route.Help) })
                Divider()
                NavRow(Icons.Rounded.Info, "About Loop", "Version, credits and licence", c.onSurfaceVariant, { vm.push(Route.About) })
            }
        }

        item {
            SecondaryButton("Erase my data", { confirmWipe = true }, icon = Icons.Rounded.DeleteForever, color = c.bad)
        }
        item { Text("Loop ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = c.outline, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
    }

    if (confirmWipe) AlertDialog(
        onDismissRequest = { confirmWipe = false },
        containerColor = c.surfaceContainer,
        title = { Text("Erase all your data?", color = c.onSurface) },
        text = { Text("This permanently deletes every day, workout and mood entry Loop has stored on this phone. Sample data is not affected. This can't be undone.", color = c.onSurfaceVariant) },
        confirmButton = { TextButton({ vm.wipeMyData(); confirmWipe = false }) { Text("Erase", color = c.bad) } },
        dismissButton = { TextButton({ confirmWipe = false }) { Text("Keep my data", color = c.onSurface) } },
    )
}

@Composable
private fun GroupLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp), color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 6.dp))
}

// ---------------------------------------------------------------- Profile
@Composable
fun ProfileScreen(vm: AppViewModel, dash: Dashboard) {
    val c = MaterialTheme.colorScheme
    val s = dash.settings
    var name by remember { mutableStateOf(s.name) }
    var confirmMale by remember { mutableStateOf(false) }
    if (confirmMale) AlertDialog(
        onDismissRequest = { confirmMale = false }, containerColor = c.surfaceContainerHigh,
        title = { Text("Switch to male?", color = c.onSurface) },
        text = { Text("Cycle features will be removed from Loop, and any cycle dates stored on this phone will be deleted. This can't be undone.", color = c.onSurfaceVariant) },
        confirmButton = { TextButton({ confirmMale = false; vm.setSex(false) }) { Text("Switch and delete", color = c.error) } },
        dismissButton = { TextButton({ confirmMale = false }) { Text("Cancel", color = c.onSurface) } },
    )
    val sliderColors = SliderDefaults.colors(thumbColor = c.primary, activeTrackColor = c.primary, inactiveTrackColor = c.outlineVariant)

    ScreenList {
        item { DetailHeader("Your profile", { vm.update { it.copy(profileDone = true) }; vm.pop() }, subtitle = "Used for zones, calories and sleep targets") }
        item {
            LoopCard {
                OutlinedTextField(
                    name, { name = it.take(30); vm.update { s -> s.copy(name = name.trim(), profileDone = true) } }, Modifier.fillMaxWidth(), label = { Text("First name (optional)") }, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.primary, unfocusedBorderColor = c.outlineVariant, focusedTextColor = c.onSurface, unfocusedTextColor = c.onSurface),
                    shape = RoundedCornerShape(14.dp),
                )
            }
        }
        item {
            LoopCard {
                StepRow("Age", "${s.age}", { vm.update { it.copy(age = (it.age - 1).coerceAtLeast(13), profileDone = true) } }, { vm.update { it.copy(age = (it.age + 1).coerceAtMost(100), profileDone = true) } })
                val w = if (s.imperial) s.weightKg * 2.20462 else s.weightKg
                val step = if (s.imperial) 1.0 else 0.5
                StepRow("Weight", "%.1f %s".format(w, if (s.imperial) "lb" else "kg"),
                    { vm.update { it.copy(weightKg = (it.weightKg - step / (if (it.imperial) 2.20462 else 1.0)).coerceAtLeast(30.0), profileDone = true) } },
                    { vm.update { it.copy(weightKg = (it.weightKg + step / (if (it.imperial) 2.20462 else 1.0)).coerceAtMost(250.0), profileDone = true) } })
                Spacer(Modifier.height(8.dp))
                Text("Sex (used for calorie and fitness estimates)", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Segmented(listOf("Male", "Female"), if (s.female) 1 else 0, { i ->
                    if (i == 0 && s.female && (s.cycleTracking || dash.cycleStarts.isNotEmpty())) confirmMale = true else vm.setSex(i == 1)
                }, c.primary)
            }
        }
        item {
            LoopCard {
                val advice = dash.sleepAdvice
                Row {
                    Text("Sleep need", style = MaterialTheme.typography.titleMedium, color = c.onSurface, modifier = Modifier.weight(1f))
                    Text(Scoring.hm((if (s.sleepNeedAuto && advice != null) advice.needMin else s.sleepNeedMin).toDouble()), style = MaterialTheme.typography.titleMedium, color = c.sleep)
                }
                Spacer(Modifier.height(8.dp))
                Segmented(listOf("Recommended", "Set my own"), if (s.sleepNeedAuto) 0 else 1, { i -> vm.update { it.copy(sleepNeedAuto = i == 0, profileDone = true) } }, c.sleep)
                Spacer(Modifier.height(8.dp))
                if (s.sleepNeedAuto) {
                    Text(advice?.basis ?: "Based on your age.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                } else {
                    Slider(s.sleepNeedMin / 60f, { v -> vm.update { it.copy(sleepNeedMin = ((v * 4).roundToInt() * 15), profileDone = true) } }, valueRange = 5f..11f, colors = sliderColors.copy(thumbColor = c.sleep, activeTrackColor = c.sleep))
                    Text(
                        "How much sleep you need to feel your best." + (advice?.let { " The guideline for your age is ${it.guidelineMin / 60} to ${it.guidelineMax / 60} hours." } ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = c.outline,
                    )
                }
            }
        }
        item { PrimaryButton("Done", { vm.update { it.copy(profileDone = true) }; vm.pop() }) }
    }
}

@Composable
private fun StepRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        IconButton(onMinus) { Icon(Icons.Rounded.Remove, "Less", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.width(96.dp), textAlign = TextAlign.Center)
        IconButton(onPlus) { Icon(Icons.Rounded.Add, "More", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

// ---------------------------------------------------------------- Import
@Composable
fun ImportScreen(vm: AppViewModel, dash: Dashboard) {
    val c = MaterialTheme.colorScheme
    val state by vm.importState.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.importFiles(uris) }
    DisposableEffect(Unit) { onDispose { vm.clearImportState() } }

    ScreenList {
        item { DetailHeader("Import WHOOP history", { vm.pop() }, subtitle = "Read on your phone, never uploaded") }
        when (val s = state) {
            ImportUi.Working -> item {
                LoopCard { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(28.dp), color = c.tertiary, strokeWidth = 3.dp); Spacer(Modifier.width(16.dp)); Text("Reading your export…", style = MaterialTheme.typography.titleMedium, color = c.onSurface) } }
            }
            is ImportUi.Done -> {
                item {
                    LoopCard(accent = c.good) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = c.good, modifier = Modifier.size(32.dp)); Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Import complete", style = MaterialTheme.typography.titleLarge, color = c.onSurface)
                                Text("${plural(s.days, "day")} and ${plural(s.workouts, "workout")} added.", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                            }
                        }
                    }
                }
                item { PrimaryButton("See my data", { vm.clearImportState(); vm.selectTab(Tab.TODAY) }) }
            }
            else -> {
                if (s is ImportUi.Failed) item { LoopCard(accent = c.warn) { Text("That didn't work", style = MaterialTheme.typography.titleMedium, color = c.onSurface); Text(s.message, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant) } }
                item {
                    LoopCard {
                        Text("Get your WHOOP export", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                        Spacer(Modifier.height(8.dp))
                        NumberedStep(1, "In the WHOOP app or at app.whoop.com, open your account settings and request a Data Export.")
                        NumberedStep(2, "WHOOP emails you a .zip file, usually within a day. Save it to this phone.")
                        NumberedStep(3, "Tap the button below and choose that .zip. You can also pick physiological_cycles.csv, sleeps.csv and workouts.csv individually.")
                    }
                }
                item { PrimaryButton("Choose export file", { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "text/csv", "text/comma-separated-values", "application/octet-stream", "*/*")) }, icon = Icons.Rounded.FileDownload, color = c.tertiary) }
                item { Text("Imported values are merged with what's already here. Nothing leaves your phone. Loop reads recovery, strain, heart-rate variability, resting heart rate, sleep, workouts and vitals.", style = MaterialTheme.typography.bodySmall, color = c.outline) }
            }
        }
    }
}

@Composable
private fun NumberedStep(n: Int, text: String) {
    Row(Modifier.padding(vertical = 5.dp)) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) { Text("$n", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------- Help
private val faqs = listOf(
    "Why is my recovery score blank?" to "Recovery needs overnight heart-rate variability and resting heart rate, plus about five nights of history so Loop knows what's normal for you. Sleep-only or workout-only data still gives you sleep and strain.",
    "Why don't my scores match WHOOP's app?" to "Loop recalculates everything on your phone with open, documented methods. They follow the same ideas, but WHOOP's model is proprietary, so numbers will differ a little. Imported WHOOP scores are shown as WHOOP calculated them.",
    "Which devices work for live heart rate?" to "Anything that speaks the standard Bluetooth Heart Rate profile: WHOOP (with Heart Rate Broadcast on), Polar, Garmin in broadcast mode, Wahoo, Coospo, Amazfit and most chest straps.",
    "Why isn't my sleep tracked from the strap?" to "Overnight sleep detection needs your strap's stored history. Loop can download it from a paired WHOOP strap (see Live heart rate), and it needs a full night of wear before it can score sleep. Other heart-rate devices give live heart rate only, so bring sleep in by importing your WHOOP export.",
    "Is my data uploaded anywhere?" to "No. Loop has no account, no server and no analytics. Everything is stored in the app's private storage. You can export or erase it in the You tab.",
    "How is strain calculated?" to "From time spent in each heart-rate zone (Edwards TRIMP), mapped onto a 0 to 100 scale that rises quickly at first and flattens near the top, plus a little background load from steps.",
    "What do Good, Okay and Watch mean?" to "Every number is graded against a good range, which you can see next to it. Good is inside the range, Okay is close to it, and Watch is well outside it. Ranges for heart-rate variability, resting heart rate and breathing are built around your own normal. Totals such as steps are not graded until the day is over.",
    "What is the Health log for?" to "It keeps your medicines, injuries and illnesses in one place. Loop compares your numbers from before and after a medicine, shows how an injury is healing, and leaves illness days out of your normal so a rough week does not skew it. It is general information, not medical advice.",
    "How is the Overview score worked out?" to "Loop grades each day's numbers, averages them over the last day, 7 days or 30 days, and combines the areas (Recovery, Sleep, Activity, Mind, Body and Daytime). It needs real overnight data, so it stays blank until your first full night with the strap on.",
)

@Composable
fun HelpScreen(vm: AppViewModel) {
    val c = MaterialTheme.colorScheme
    var open by remember { mutableStateOf<Int?>(null) }
    var info by remember { mutableStateOf<MetricDef?>(null) }
    ScreenList {
        item { DetailHeader("How Loop works", { vm.pop() }) }
        item {
            LoopCard {
                Text("The loop", style = MaterialTheme.typography.titleLarge, color = c.onSurface)
                Spacer(Modifier.height(10.dp))
                LoopFlow("1", "Collect", "Heart rate from your strap, or history imported from WHOOP.", c.secondary)
                LoopFlow("2", "Learn your normal", "After about five nights Loop knows your personal baselines.", c.primary)
                LoopFlow("3", "Score", "Recovery, sleep, strain and stress are compared with you, not with everyone else.", c.tertiary)
                LoopFlow("4", "Act", "Daily guidance on how hard to push, then the cycle repeats.", c.activity)
            }
        }
        item { Text("Questions", style = MaterialTheme.typography.titleLarge, color = c.onSurface, modifier = Modifier.padding(top = 4.dp)) }
        faqs.forEachIndexed { i, (q, a) ->
            item(key = "faq$i") {
                LoopCard(onClick = { open = if (open == i) null else i }, padding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(q, style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp), color = c.onSurface, modifier = Modifier.weight(1f))
                        Icon(if (open == i) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = c.onSurfaceVariant)
                    }
                    if (open == i) { Spacer(Modifier.height(8.dp)); Text(a, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant) }
                }
            }
        }
        item { Text("Glossary", style = MaterialTheme.typography.titleLarge, color = c.onSurface, modifier = Modifier.padding(top = 8.dp)) }
        app.loop.model.Families.categories.forEach { cat ->
            item(key = "g${cat.name}") {
                LoopCard {
                    CategoryLabel(cat)
                    Spacer(Modifier.height(6.dp))
                    Metrics.all.filter { it.category == cat }.forEach { m ->
                        Text(m.title, style = MaterialTheme.typography.bodyLarge, color = c.onSurface, modifier = Modifier.fillMaxWidth().clickable { info = m }.padding(vertical = 8.dp))
                    }
                }
            }
        }
        item { Text("Tap any metric for a plain-language explanation and how Loop works it out.", style = MaterialTheme.typography.bodySmall, color = c.outline) }
        item { Text("Terms", style = MaterialTheme.typography.titleLarge, color = c.onSurface, modifier = Modifier.padding(top = 8.dp)) }
        app.loop.model.Glossary.terms.forEach { t ->
            item(key = "term_${t.name}") {
                LoopCard(padding = 14.dp) {
                    Text(t.name, style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                    Spacer(Modifier.height(4.dp))
                    Text(t.meaning, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                }
            }
        }
    }
    info?.let { MetricInfoSheet(it, { info = null }, vm.settings.value.female) }
}

@Composable
private fun LoopFlow(n: String, title: String, body: String, color: androidx.compose.ui.graphics.Color) {
    Row(Modifier.padding(vertical = 6.dp)) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(color.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) { Text(n, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge) }
        Spacer(Modifier.width(12.dp))
        Column { Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface); Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

// ---------------------------------------------------------------- About
@Composable
fun AboutScreen(vm: AppViewModel) {
    val c = MaterialTheme.colorScheme
    ScreenList {
        item { DetailHeader("About Loop", { vm.pop() }) }
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                LoopMark(size = 180.dp, animate = true)
                Text("Loop", style = MaterialTheme.typography.displayMedium, color = c.onSurface)
                Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text("The mark is a Möbius strip: one surface, one edge, no start or finish, like the daily loop of effort and recovery.", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        item {
            LoopCard {
                Text("Credits and licence", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                Spacer(Modifier.height(6.dp))
                Text("Loop is a ground-up Kotlin and Jetpack Compose rebuild of the NOOP project (github.com/ryanbr/noop). Its recovery and strain methods, and its WHOOP export import, are adapted from NOOP's documented approach.", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text("Licensed under the PolyForm Noncommercial License 1.0.0. Free for personal and other non-commercial use. Required Notice: Copyright 2026 NoopApp. The Quicksand typeface is by The Quicksand Project Authors, used under the SIL Open Font License 1.1.", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            }
        }
        item {
            LoopCard {
                Text("Important", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                Spacer(Modifier.height(6.dp))
                Text("Loop is not a medical device and gives wellness estimates only. Don't use it to diagnose or treat any condition. It is independent and not affiliated with, or endorsed by, WHOOP, Inc.; the WHOOP name is used only to describe compatibility.", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            }
        }
    }
}
