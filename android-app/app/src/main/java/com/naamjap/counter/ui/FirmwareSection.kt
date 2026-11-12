package com.naamjap.counter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naamjap.counter.AppViewModel

/** Firmware update from GitHub: the phone checks, the counter shows a PIN, the user types it. */
@Composable
fun FirmwareSection(vm: AppViewModel) {
    val u by vm.update.collectAsStateWithLifecycle()
    val fw by vm.firmware.collectAsStateWithLifecycle()
    var pinDialog by rememberSaveable { mutableStateOf(false) }

    Section("Firmware update") {
        InfoRow("Counter firmware", fw ?: "…")
        u.offer?.let { o ->
            Text(
                if (o.newer) "New version ${o.version} is available.\n${o.notes}" else "Up to date (${o.version} on GitHub)",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        u.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (u.checking || u.installing) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !u.checking && !u.installing, onClick = vm::checkFirmware) { Text("Check for update") }
            if (u.offer?.newer == true && !u.installing) {
                Button(onClick = { vm.showUpdatePin(); pinDialog = true }) { Text("Install") }
            }
        }
        Text(
            "Counts, names, voices and WiFi are kept. If an update fails, the old firmware keeps running.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (pinDialog) {
        var pin by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pinDialog = false },
            title = { Text("PIN from the counter") },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text("Type the 4-digit PIN shown in yellow on the counter's screen.")
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { v -> pin = v.filter(Char::isDigit).take(4) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        label = { Text("PIN") },
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = pin.length == 4, onClick = { pinDialog = false; vm.installFirmware(pin) }) { Text("Install") }
            },
            dismissButton = { TextButton(onClick = { pinDialog = false }) { Text("Cancel") } },
        )
    }
}
