package app.loop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.loop.ui.AppViewModel
import app.loop.ui.components.LoopCard
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Shown when no sleep was found for the night: either it really was an all-nighter, or the strap missed it.
 * Either way the person can say when they slept, and Loop uses that instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoSleepCard(vm: AppViewModel, wakeDay: String) {
    var asking by remember { mutableStateOf(false) }
    LoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("No sleep found for last night", style = MaterialTheme.typography.titleSmall)
            Text(
                "If you stayed up, Loop counts it as a night without sleep and suggests extra rest tonight. If you did sleep and the strap missed it, tell Loop when.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton({ asking = true }) { Text("I did sleep") }
            }
        }
    }
    if (asking) {
        val bed = rememberTimePickerState(23, 0, true)
        val wake = rememberTimePickerState(7, 0, true)
        var step by remember { mutableStateOf(0) }
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(if (step == 0) "When did you go to bed?" else "When did you wake up?") },
            text = { TimePicker(if (step == 0) bed else wake) },
            confirmButton = {
                TextButton({
                    if (step == 0) step = 1 else {
                        val zone = ZoneId.systemDefault()
                        val day = LocalDate.parse(wakeDay)
                        val end = day.atTime(LocalTime.of(wake.hour, wake.minute))
                        val bedTime = LocalTime.of(bed.hour, bed.minute)
                        val start = (if (bedTime > end.toLocalTime()) day.minusDays(1) else day).atTime(bedTime)
                        vm.addManualSleep(start.atZone(zone).toEpochSecond(), end.atZone(zone).toEpochSecond())
                        asking = false
                    }
                }) { Text(if (step == 0) "Next" else "Save") }
            },
            dismissButton = { TextButton({ if (step == 1) step = 0 else asking = false }) { Text(if (step == 1) "Back" else "Cancel") } },
        )
    }
}
