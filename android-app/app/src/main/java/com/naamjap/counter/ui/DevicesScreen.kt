package com.naamjap.counter.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naamjap.counter.AppViewModel

/** Shown when no counter is selected: finds counters on the WiFi or adds one by IP. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(vm: AppViewModel, onOpenSetupPage: () -> Unit, onOpenHotspotPage: () -> Unit) {
    val found by vm.found.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var manualHost by rememberSaveable { mutableStateOf("") }

    DisposableEffect(Unit) {
        vm.startScan()
        onDispose { vm.stopScan() }
    }

    androidx.compose.foundation.layout.Column {
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text("Find your counter", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (scanning) {
                CircularProgressIndicator(Modifier.size(22.dp).padding(end = 4.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = vm::startScan) { Icon(Icons.Filled.Refresh, contentDescription = "Search again") }
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "The phone and the counter must be on the same WiFi.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item { SectionTitle(if (scanning) "Searching the WiFi…" else "Found on this WiFi") }
            if (found.isEmpty()) {
                item {
                    Text(
                        if (scanning) "Looking for counters…" else "No counter found. Check that it is switched on and shows the WiFi bars in green.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(found, key = { "f" + it.id }) { d ->
                DeviceRow(d.id, d.host, onClick = { vm.connect(d.id, d.host) })
            }

            val others = saved.filter { s -> found.none { it.id == s.id } }
            if (others.isNotEmpty()) {
                item { SectionTitle("Used before") }
                items(others, key = { "s" + it.id }) { d ->
                    DeviceRow(d.id, d.host, onClick = { vm.connect(d.id, d.host) }, onForget = { vm.forget(d.id) })
                }
            }

            item {
                SectionTitle("Connect by IP address")
                Text(
                    "The IP address is shown on the counter's screen for a moment after it starts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = manualHost,
                        onValueChange = { manualHost = it.trim().take(40) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("192.168.1.50") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { vm.connectManual(manualHost) }),
                    )
                    Spacer(Modifier.size(8.dp))
                    Button(onClick = { vm.connectManual(manualHost) }, enabled = !busy && manualHost.isNotBlank()) {
                        Text(if (busy) "Checking…" else "Connect")
                    }
                }
            }

            item {
                SectionTitle("New counter?")
                Text(
                    "1. Power it on. Its screen shows WIFI SETUP with a hotspot name and password.\n" +
                        "2. Join that hotspot in your phone's WiFi settings.\n" +
                        "3. Come back here and open the setup page to choose your home WiFi.\n" +
                        "4. Rejoin your home WiFi; the counter will appear above.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onOpenSetupPage) { Text("Open setup page (192.168.4.1)") }
                OutlinedButton(onClick = onOpenHotspotPage) { Text("Open the counter's page on its hotspot") }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun DeviceRow(id: String, host: String, onClick: () -> Unit, onForget: (() -> Unit)? = null) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(id, style = MaterialTheme.typography.titleMedium)
                Text(host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onForget != null) {
                IconButton(onClick = onForget) { Icon(Icons.Filled.Delete, contentDescription = "Forget $id") }
            }
        }
    }
}
