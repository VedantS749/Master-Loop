package app.loop.ui.screens

import app.loop.ui.theme.activity
import app.loop.ui.theme.bad
import app.loop.ui.theme.good
import app.loop.ui.theme.sleep
import app.loop.ui.theme.warn
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.loop.analytics.Scoring
import app.loop.ble.HeartRateMonitor
import app.loop.ui.AppViewModel
import app.loop.ui.Dashboard
import app.loop.ui.Route
import app.loop.ui.components.Banner
import app.loop.ui.components.DetailHeader
import app.loop.ui.components.IconBadge
import app.loop.ui.components.LoopCard
import app.loop.ui.components.PrimaryButton
import app.loop.ui.components.SecondaryButton
import app.loop.ui.components.SectionTitle
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import app.loop.model.plural

private val sports = listOf("Run", "Cycle", "Strength", "HIIT", "Walk", "Other")

@Composable
fun LiveScreen(vm: AppViewModel, dash: Dashboard) {
    val mon = vm.monitor
    val st by mon.state.collectAsStateWithLifecycle()
    val c = MaterialTheme.colorScheme
    val ctx = LocalContext.current

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (mon.hasPermissions()) mon.startScan() else mon.clearError()
    }
    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { if (mon.isEnabled) mon.startScan() }

    fun search(all: Boolean = false) {
        when {
            !mon.isSupported -> {}
            !mon.hasPermissions() -> permLauncher.launch(HeartRateMonitor.requiredPermissions() + if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(android.Manifest.permission.POST_NOTIFICATIONS) else emptyArray())
            !mon.isEnabled -> btLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            else -> mon.startScan(all)
        }
    }

    // Keep the screen awake while a device is streaming so a workout isn't interrupted.
    val view = LocalView.current
    DisposableEffect(st.phase) {
        view.keepScreenOn = st.phase == HeartRateMonitor.Phase.CONNECTED
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(Unit) { onDispose { mon.stopScan() } }

    ScreenList {
        item { DetailHeader("Live heart rate", { vm.pop() }, subtitle = st.device?.name ?: "Connect a device") }

        st.error?.let { e -> item { Banner(e, c.warn, onClose = { mon.clearError() }) } }

        when (st.phase) {
            HeartRateMonitor.Phase.CONNECTED -> connectedContent(vm, dash, st)
            HeartRateMonitor.Phase.CONNECTING, HeartRateMonitor.Phase.RECONNECTING -> item {
                LoopCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(28.dp), color = c.secondary, strokeWidth = 3.dp)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (st.phase == HeartRateMonitor.Phase.CONNECTING) "Connecting to ${st.device?.name}…" else "Connection dropped, retrying…", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                            Text("Keep the device on your body and near the phone.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    SecondaryButton("Cancel", { vm.disconnectStrap() })
                }
            }
            else -> {
                item { HowToCard() }
                item {
                    if (st.phase == HeartRateMonitor.Phase.SCANNING) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), color = c.secondary, strokeWidth = 2.5.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("Searching nearby…", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                        }
                    } else PrimaryButton(if (st.devices.isEmpty()) "Search for devices" else "Search again", { search() }, icon = Icons.Rounded.BluetoothSearching, color = c.secondary)
                }
                if (st.devices.isNotEmpty()) {
                    item { SectionTitle("Found nearby", subtitle = "Tap yours to connect") }
                    st.devices.forEach { d ->
                        item(key = d.address) {
                            LoopCard(onClick = { vm.connectStrap(d) }, padding = 14.dp) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconBadge(Icons.Rounded.Bluetooth, c.secondary, 40.dp)
                                    Spacer(Modifier.width(14.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(d.name, style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                                        Text(signalWord(d.rssi), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                                    }
                                    Text("Connect", style = MaterialTheme.typography.labelLarge, color = c.secondary)
                                }
                            }
                        }
                    }
                } else if (st.phase != HeartRateMonitor.Phase.SCANNING && st.devices.isEmpty() && st.error == null) {
                    item { Text("Nothing found yet? Make sure the device is on your body and broadcasting.", style = MaterialTheme.typography.bodySmall, color = c.outline) }
                }
                item {
                    TextButton({ search(all = true) }) { Text("Can't see it? Show all nearby Bluetooth devices", color = c.onSurfaceVariant) }
                }
                if (dash.settings.lastDeviceName.isNotEmpty()) item {
                    Text("Last used: ${dash.settings.lastDeviceName}", style = MaterialTheme.typography.bodySmall, color = c.outline)
                }
            }
        }
    }
}

private fun signalWord(rssi: Int) = when { rssi >= -60 -> "Very close"; rssi >= -75 -> "Nearby"; else -> "Far away" }

@Composable
private fun HowToCard() {
    val c = MaterialTheme.colorScheme
    LoopCard {
        Text("Works with any Bluetooth heart-rate device", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
        Spacer(Modifier.height(10.dp))
        Step(1, "Wear it", "Put your strap or band on and make sure it's awake.")
        Step(2, "Turn on broadcasting", "WHOOP: open the WHOOP app and switch on Heart Rate Broadcast. Garmin: enable broadcast mode. Polar, Wahoo and most chest straps broadcast by default.")
        Step(3, "Search and tap", "Loop finds it automatically. If the WHOOP app is connected, you may need to turn its broadcast on first.")
    }
}

@Composable
private fun Step(n: Int, title: String, body: String) {
    Row(Modifier.padding(vertical = 6.dp)) {
        Box(Modifier.size(24.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
            Text("$n", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp), color = MaterialTheme.colorScheme.onSurface)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.connectedContent(vm: AppViewModel, dash: Dashboard, st: HeartRateMonitor.State) {
    item { LivePanel(vm, dash, st) }
    item { LiveMetricsCard(vm, dash, st) }
    if (st.family != null) item { StrapSyncCard(vm, dash, st) }
    item { WorkoutPanel(vm, dash, st) }
    item { SecondaryButton("Disconnect", { vm.disconnectStrap() }) }
}

@Composable
private fun zoneColor(zone: Int): Color = with(MaterialTheme.colorScheme) { listOf(onSurfaceVariant, sleep, secondary, warn, activity, bad)[zone.coerceIn(0, 5)] }

@Composable
private fun LivePanel(vm: AppViewModel, dash: Dashboard, st: HeartRateMonitor.State) {
    val c = MaterialTheme.colorScheme
    val bpm = st.bpm
    val rhr = dash.days.takeLast(14).mapNotNull { it.rhr }.takeIf { it.isNotEmpty() }?.average() ?: 60.0
    val zone = bpm?.let { Scoring.zone(it.toDouble(), rhr, Scoring.maxHr(dash.settings)) } ?: 0
    val zc = zoneColor(zone)
    val dur = bpm?.let { (60000 / it).coerceIn(350, 1500) / 50 * 50 } ?: 900
    val t = rememberInfiniteTransition(label = "pulse")
    val pulse by t.animateFloat(1f, 1.14f, infiniteRepeatable(tween(dur, delayMillis = 0), RepeatMode.Reverse), label = "p")
    LoopCard(accent = zc) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Favorite, null, tint = zc, modifier = Modifier.size(34.dp).scale(if (bpm != null) pulse else 1f))
            Spacer(Modifier.width(12.dp))
            Text(bpm?.toString() ?: "--", style = MaterialTheme.typography.displayLarge.copy(fontSize = 72.sp), color = c.onSurface)
            Text(" bpm", style = MaterialTheme.typography.titleMedium, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 30.dp))
        }
        Text(if (zone == 0) "Easy / resting" else "Zone $zone · ${Scoring.zoneNames[zone - 1]}", style = MaterialTheme.typography.titleMedium, color = zc)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Mini("Live HRV", st.rmssd?.roundToInt()?.let { "$it ms" } ?: "Warming up…")
            Mini("Battery", st.battery?.let { "$it%" } ?: "n/a")
            Mini("Max HR", "${Scoring.maxHr(dash.settings).roundToInt()}")
        }
        st.onWrist?.let { w ->
            Spacer(Modifier.height(8.dp))
            Text(if (w) "On wrist" else "Off wrist", style = MaterialTheme.typography.labelLarge, color = if (w) c.good else c.warn)
        }
        if (st.contactLost) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Warning, null, tint = c.warn, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text("Poor skin contact. Tighten the strap.", style = MaterialTheme.typography.bodySmall, color = c.warn)
            }
        }
    }
}

@Composable
private fun Mini(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun WorkoutPanel(vm: AppViewModel, dash: Dashboard, st: HeartRateMonitor.State) {
    val c = MaterialTheme.colorScheme
    val rec by vm.recording.collectAsStateWithLifecycle()
    var sport by remember { mutableStateOf("Run") }
    LaunchedEffect(rec.active) { while (rec.active) { delay(1000); vm.tickRecording() } }

    LoopCard {
        if (!rec.active) {
            Text("Record a workout", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
            Text("Loop tracks your zones, strain and calories while you train. Keep the app open.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sports.forEach { s ->
                    val on = s == sport
                    Box(
                        Modifier.clip(RoundedCornerShape(50)).background(if (on) c.activity else c.surfaceContainerHigh).clickable { sport = s }.padding(horizontal = 16.dp, vertical = 9.dp),
                    ) { Text(s, style = MaterialTheme.typography.labelLarge, color = if (on) Color.White else c.onSurfaceVariant) }
                }
            }
            Spacer(Modifier.height(14.dp))
            PrimaryButton("Start $sport", { vm.startWorkout(sport) }, color = c.activity)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(rec.sport.uppercase(), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp), color = c.activity)
                Spacer(Modifier.weight(1f))
                Text("RECORDING", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp), color = c.bad)
            }
            Text("%d:%02d".format(rec.elapsedSec / 60, rec.elapsedSec % 60), style = MaterialTheme.typography.displayMedium, color = c.onSurface)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Mini("Strain", rec.strain.roundToInt().toString()); Mini("Calories", rec.kcal.roundToInt().toString()); Mini("Avg HR", rec.avgHr?.toString() ?: "–")
            }
            Spacer(Modifier.height(12.dp))
            val zs = listOf(c.sleep.copy(alpha = 0.55f), c.secondary, c.warn, c.activity, c.bad)
            val total = rec.zoneMin.sum().takeIf { it > 0 }
            Canvas(Modifier.fillMaxWidth().height(10.dp)) {
                drawRoundRect(c.outlineVariant.copy(alpha = 0.5f), Offset.Zero, size, CornerRadius(size.height / 2))
                if (total != null) {
                    var x = 0f
                    rec.zoneMin.forEachIndexed { i, m -> val w = (size.width * (m / total)).toFloat(); if (w > 0) { drawRoundRect(zs[i], Offset(x, 0f), Size(w, size.height), CornerRadius(size.height / 2)); x += w } }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { SecondaryButton("Discard", { vm.stopWorkout(false) }) }
                Box(Modifier.weight(1f)) {
                    PrimaryButton("Finish and save", {
                        val id = vm.stopWorkout(true)
                        if (id != null) { vm.pop(); vm.push(Route.WorkoutScreen(id)) }
                    }, icon = Icons.Rounded.Stop, color = c.activity)
                }
            }
        }
    }
}


@Composable
private fun StrapSyncCard(vm: AppViewModel, dash: Dashboard, st: HeartRateMonitor.State) {
    val c = MaterialTheme.colorScheme
    val sy = st.sync
    val note by vm.analysisNote.collectAsStateWithLifecycle()
    var ask by remember { mutableStateOf(false) }
    val enabled = dash.settings.strapSync
    val running = sy.phase == HeartRateMonitor.SyncPhase.RUNNING

    if (st.pairingNeeded) {
        LoopCard(accent = c.warn) {
            Text("Pair to unlock sleep and history", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
            Spacer(Modifier.height(4.dp))
            Text("Live heart rate works as is. To download your strap's stored history, a ${st.family?.label} must be paired with this phone.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Step(1, "Quit the WHOOP app", "The strap can only be paired with one phone at a time. Close the official app fully, or switch off its phone's Bluetooth.")
            Step(2, "Put the strap in pairing mode", "Tap the band firmly and repeatedly until its lights flash blue.")
            Step(3, "Tap Pair now", "Pairing with Loop may mean the WHOOP app needs to pair again afterwards.")
            Spacer(Modifier.height(10.dp))
            if (st.pairing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = c.warn, strokeWidth = 2.5.dp); Spacer(Modifier.width(12.dp))
                    Text("Pairing… keep tapping the band if the lights stop.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
            } else PrimaryButton("Pair now", { vm.pairStrap() }, color = c.warn)
        }
        return
    }

    LoopCard(accent = c.secondary) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${st.family?.label} history", style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                Text("Sleep, recovery and strain from your strap's own memory", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(10.dp))
        if (running) {
            androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)), color = c.secondary, trackColor = c.outlineVariant)
            Spacer(Modifier.height(8.dp))
            Text("${sy.message ?: "Downloading…"}  ${plural(sy.records, "record")} · ${plural(sy.chunks, "chunk")}", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            SecondaryButton("Stop", { vm.monitor.cancelSync() })
        } else {
            when (sy.phase) {
                HeartRateMonitor.SyncPhase.DONE -> Text("Downloaded ${plural(sy.records, "record")}.", style = MaterialTheme.typography.bodyMedium, color = c.good)
                HeartRateMonitor.SyncPhase.ERROR -> Text(sy.message ?: "Sync stopped.", style = MaterialTheme.typography.bodyMedium, color = c.warn)
                else -> {}
            }
            if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            if (dash.settings.lastSyncAt > 0) Text("Last scored ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(dash.settings.lastSyncAt))}", style = MaterialTheme.typography.labelSmall, color = c.outline)
            Spacer(Modifier.height(10.dp))
            PrimaryButton(if (enabled) "Sync now" else "Download history from strap", { if (enabled) vm.syncStrap() else ask = true }, color = c.secondary)
            if (enabled) Text("Loop also syncs automatically every 15 minutes while connected.", style = MaterialTheme.typography.labelSmall, color = c.outline, modifier = Modifier.padding(top = 8.dp))
        }
    }

    if (ask) androidx.compose.material3.AlertDialog(
        onDismissRequest = { ask = false }, containerColor = c.surfaceContainer,
        title = { Text("Download history from your strap?", color = c.onSurface) },
        text = {
            Text(
                "Loop will copy the data stored on your strap to this phone, save it, and then tell the strap it may free that space. " +
                    "That is how syncing normally works, but it means the official WHOOP app may no longer receive the records Loop downloads. " +
                    "Nothing is uploaded anywhere.",
                color = c.onSurfaceVariant,
            )
        },
        confirmButton = { TextButton({ ask = false; vm.update { it.copy(strapSync = true) }; vm.syncStrap() }) { Text("Download", color = c.secondary) } },
        dismissButton = { TextButton({ ask = false }) { Text("Not now", color = c.onSurface) } },
    )
}
