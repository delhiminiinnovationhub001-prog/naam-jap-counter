package com.naamjap.counter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ButtonDefaults
import com.naamjap.counter.AppViewModel
import com.naamjap.counter.BuildConfig
import com.naamjap.counter.data.DeviceStatus
import kotlin.math.roundToInt

private enum class DeviceDialog { ForgetWifi, ResetAll }

@Composable
fun SettingsScreen(
    deviceId: String,
    host: String,
    status: DeviceStatus,
    vm: AppViewModel,
    onOpenWeb: (path: String) -> Unit,
) {
    // Edit a local copy; it is sent to the counter only when Save is pressed.
    var edit by remember(deviceId) { mutableStateOf(status.settings) }
    var dialog by rememberSaveable { mutableStateOf<DeviceDialog?>(null) }
    val changed = edit != status.settings

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Counter settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Section("Word") {
            Text(
                "Only this word is counted, also in continuous chanting. Changing the name does not need retraining " +
                    "if it is the same word; for a different word, retrain everyone in People.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = edit.wordName,
                onValueChange = { v -> edit = edit.copy(wordName = v.filter { it.code in 0x20..0x7E && it != ',' && it != '"' }.take(16)) },
                label = { Text("Word or mantra") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            IntSlider("Word matching strictness", edit.wordStrictness, 1..10, "Higher gives fewer false counts, but may miss some") {
                edit = edit.copy(wordStrictness = it)
            }
        }
        Section("Jap detection") {
            IntSlider("Sensitivity", edit.sensitivity, 1..10, "Higher picks up softer chanting") { edit = edit.copy(sensitivity = it) }
            IntSlider("Minimum chant length", edit.minVoiceMs, 40..1000, "Raise if clicks or coughs are counted", unit = " ms", step = 10) {
                edit = edit.copy(minVoiceMs = it)
            }
            IntSlider("Silence between two japs", edit.minGapMs, 100..3000, "Lower if two names count as one, raise if one counts twice", unit = " ms", step = 10) {
                edit = edit.copy(minGapMs = it)
            }
        }
        Section("Voice recognition") {
            IntSlider("Strictness", edit.strictness, 1..10, "Higher gives fewer wrong names, more Guest") { edit = edit.copy(strictness = it) }
            IntSlider("Chants per training", edit.trainChants, 5..40, "More chants help with similar voices") { edit = edit.copy(trainChants = it) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Smoothing", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "When unsure, give the jap to whoever chanted the previous japs",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = edit.smoothing, onCheckedChange = { edit = edit.copy(smoothing = it) })
            }
        }
        Section("Chanting together") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Together mode", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "When everyone chants at the same time, every word counts for each person. Turn off when chanting alone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = status.together, onCheckedChange = { on ->
                    vm.command(if (on) "Together mode on" else "Together mode off") { it.setTogether(on) }
                })
            }
        }
        FirmwareSection(vm)
        Section("Screen") {
            IntSlider("Brightness", edit.brightness, 5..100, null, unit = "%") { edit = edit.copy(brightness = it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = changed && edit.wordName.isNotBlank(),
                onClick = { val s = edit.copy(wordName = edit.wordName.trim()); vm.command("Settings saved") { it.saveSettings(s) } },
            ) { Text("Save settings") }
            OutlinedButton(enabled = changed, onClick = { edit = status.settings }) { Text("Undo") }
        }

        Section("Counter") {
            InfoRow("Name", deviceId)
            InfoRow("Address", host)
            InfoRow("WiFi", if (status.wifi) "${status.ssid} (${status.rssi} dBm)" else "not connected")
            InfoRow("Microphone", status.micState)
            InfoRow("SD card", if (status.sd) "inserted" else "not found")
            InfoRow("Clock", if (status.timeOk) "set (IST)" else "not set yet")
            InfoRow("Running for", "${status.uptimeSec / 3600} h ${status.uptimeSec % 3600 / 60} min")
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { onOpenWeb("/") }, modifier = Modifier.fillMaxWidth()) { Text("Open the counter's web page") }
            OutlinedButton(onClick = { onOpenWeb("/setup") }, modifier = Modifier.fillMaxWidth()) { Text("Change the counter's WiFi") }
            OutlinedButton(onClick = { dialog = DeviceDialog.ForgetWifi }, modifier = Modifier.fillMaxWidth()) { Text("Forget WiFi (setup mode)") }
            OutlinedButton(
                onClick = { dialog = DeviceDialog.ResetAll },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger),
            ) { Text("Reset all counts") }
        }

        OutlinedButton(onClick = vm::disconnect, modifier = Modifier.fillMaxWidth()) { Text("Switch to another counter") }
    }

    when (dialog) {
        DeviceDialog.ForgetWifi -> ConfirmDialog(
            title = "Forget WiFi?",
            text = "The counter restarts in setup mode and shows a hotspot password on its screen. " +
                "The app cannot reach it until its WiFi is set up again. Counts are kept.",
            confirmLabel = "Forget WiFi",
            destructive = true,
            onConfirm = { vm.command("Counter is restarting in setup mode") { it.forgetWifi() } },
            onDismiss = { dialog = null },
        )
        DeviceDialog.ResetAll -> ConfirmDialog(
            title = "Reset all counts?",
            text = "Erases today's counts, totals and history of everyone on the counter. " +
                "Names and trained voices are kept. This cannot be undone.",
            confirmLabel = "Reset everything",
            destructive = true,
            onConfirm = { vm.command("All counts reset") { it.resetAllCounts() } },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
fun IntSlider(
    label: String,
    value: Int,
    range: IntRange,
    hint: String?,
    unit: String = "",
    step: Int = 1,
    onChange: (Int) -> Unit,
) {
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("$value$unit", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        }
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val stepsCount = (range.last - range.first) / step
        Slider(
            value = value.toFloat(),
            onValueChange = { v -> onChange(((v / step).roundToInt() * step).coerceIn(range)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = if (stepsCount <= 40) stepsCount - 1 else 0,
        )
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
