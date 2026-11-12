package com.naamjap.counter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.naamjap.counter.AppViewModel
import com.naamjap.counter.Connection
import com.naamjap.counter.data.SavedDevice

private enum class Tab(val label: String, val icon: ImageVector) {
    Live("Live", Icons.Filled.Home),
    People("People", Icons.Filled.Person),
    History("History", Icons.AutoMirrored.Filled.List),
    Settings("Settings", Icons.Filled.Settings),
}

private const val SETUP_AP_URL = "http://192.168.4.1/setup"

@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    val device by vm.device.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var web by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val openWeb = web
    val d = device
    when {
        openWeb != null -> WebScreen(title = if (openWeb.endsWith("/setup")) "WiFi setup" else "Counter web page", url = openWeb) { web = null }
        d == null -> DevicesScreen(vm, snackbar, onOpenSetupPage = { web = SETUP_AP_URL })
        else -> DeviceShell(d, vm, snackbar, onOpenWeb = { path -> web = "http://${d.host}$path" })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceShell(device: SavedDevice, vm: AppViewModel, snackbar: SnackbarHostState, onOpenWeb: (String) -> Unit) {
    val status by vm.status.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.Live) }
    var menu by remember { mutableStateOf(false) }

    // Poll the counter only while the app is visible.
    LifecycleStartEffect(device.id) {
        vm.startPolling()
        onStopOrDispose { vm.stopPolling() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(device.id, style = MaterialTheme.typography.titleMedium)
                        Text(
                            when (connection) {
                                Connection.Online -> "Connected · ${device.host}"
                                Connection.Connecting -> "Connecting…"
                                Connection.Offline -> "Not reachable"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (connection == Connection.Offline) Danger else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { onOpenWeb("/") }) { Text("Web page") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Switch counter") }, onClick = { menu = false; vm.disconnect() })
                            DropdownMenuItem(text = { Text("Reconnect") }, onClick = { menu = false; vm.retryNow() })
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (connection == Connection.Offline) OfflineBanner(device, vm)
            val s = status
            if (s == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (connection == Connection.Offline) Text("Waiting for the counter…") else CircularProgressIndicator()
                }
            } else {
                when (tab) {
                    Tab.Live -> LiveScreen(s, vm)
                    Tab.People -> PeopleScreen(s, vm)
                    Tab.History -> HistoryScreen(s, vm)
                    Tab.Settings -> SettingsScreen(device.id, device.host, s, vm, onOpenWeb)
                }
            }
        }
    }
}

@Composable
private fun OfflineBanner(device: SavedDevice, vm: AppViewModel) {
    Card(
        Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Can't reach ${device.id} at ${device.host}. Make sure it is switched on and the phone is on the same WiFi. Searching for it again…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = vm::retryNow) { Text("Retry now") }
                TextButton(onClick = vm::disconnect) { Text("Choose another") }
            }
        }
    }
}
