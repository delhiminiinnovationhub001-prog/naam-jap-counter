package com.naamjap.counter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
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
import com.naamjap.counter.phone.Milestone

private enum class Tab(val label: String, val icon: ImageVector) {
    Chant("Chant", Icons.Filled.Favorite),
    Counter("Counter", Icons.Filled.Home),
    Bhakti("Bhakti", Icons.Filled.Star),
    History("History", Icons.AutoMirrored.Filled.List),
    Settings("Settings", Icons.Filled.Settings),
}

private const val AP_HOST = "192.168.4.1"

/**
 * The app works on its own (chanting on the phone, history, goal) and with the
 * counter when it can reach it - on the home WiFi or on the counter's hotspot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    val device by vm.device.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var web by rememberSaveable { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableStateOf(Tab.Chant) }
    var menu by remember { mutableStateOf(false) }
    var people by rememberSaveable { mutableStateOf(false) }  // Counter tab: Live or People
    var milestone by remember { mutableStateOf<Milestone?>(null) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { vm.chanter.milestones.collect { milestone = it } }
    // Talk to the counter while the app is visible (also syncs the phone's japs).
    LifecycleStartEffect(Unit) {
        vm.startPolling()
        onStopOrDispose { vm.stopPolling() }
    }

    val openWeb = web
    if (openWeb != null) {
        WebScreen(title = if (openWeb.endsWith("/setup")) "WiFi setup" else "Counter web page", url = openWeb) { web = null }
        return
    }
    val d = device

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Naam Jap", style = MaterialTheme.typography.titleMedium)
                            Text(
                                when {
                                    d == null -> "Phone only · no counter connected"
                                    connection == Connection.Online -> "Counter connected · ${d.host}"
                                    connection == Connection.Offline -> "Counter not reachable · phone mode"
                                    else -> "Connecting to the counter…"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (d != null && connection == Connection.Offline) Danger else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        if (d != null) TextButton(onClick = { web = "http://${d.host}/" }) { Text("Web page") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Counter on its hotspot ($AP_HOST)") }, onClick = { menu = false; web = "http://$AP_HOST/" })
                                DropdownMenuItem(text = { Text("Counter WiFi setup page") }, onClick = { menu = false; web = "http://$AP_HOST/setup" })
                                if (d != null) {
                                    DropdownMenuItem(text = { Text("Reconnect") }, onClick = { menu = false; vm.retryNow() })
                                    DropdownMenuItem(text = { Text("Switch counter") }, onClick = { menu = false; vm.disconnect(); tab = Tab.Counter })
                                }
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
                when (tab) {
                    Tab.Chant -> ChantScreen(vm)
                    Tab.History -> TotalsScreen(vm)
                    Tab.Bhakti -> BhaktiScreen()
                    Tab.Counter -> when {
                        d == null -> DevicesScreen(vm, onOpenSetupPage = { web = "http://$AP_HOST/setup" }, onOpenHotspotPage = { web = "http://$AP_HOST/" })
                        else -> {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                androidx.compose.material3.FilterChip(selected = !people, onClick = { people = false }, label = { Text("Live") })
                                androidx.compose.material3.FilterChip(selected = people, onClick = { people = true }, label = { Text("People & training") })
                            }
                            if (connection == Connection.Offline) OfflineBanner(d, vm, showingCached = status != null)
                            val s = status
                            when {
                                s == null -> Waiting(connection)
                                !people -> LiveScreen(s, vm)
                                connection == Connection.Online -> PeopleScreen(s, vm)
                                else -> NeedCounter("People and voice training on the counter need the counter connected. " +
                                    "Teaching the word to this phone is on the Chant tab.") { vm.retryNow() }
                            }
                        }
                    }
                    Tab.Settings -> Column(
                        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        PhoneSettings(vm)
                        val s = status
                        if (d != null && s != null && connection == Connection.Online) {
                            SettingsScreen(d.id, d.host, s, vm) { path -> web = "http://${d.host}$path" }
                        } else {
                            NeedCounter("Connect to the counter to change its settings and update its firmware.") { tab = Tab.Counter }
                        }
                    }
                }
            }
        }
        milestone?.let { MilestoneOverlay(it) { milestone = null } }
    }
}

@Composable
private fun Waiting(connection: Connection) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (connection == Connection.Offline) Text("Waiting for the counter…") else CircularProgressIndicator()
    }
}

@Composable
private fun NeedCounter(text: String, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onOpen) { Text("Go to Counter") }
        }
    }
}

@Composable
private fun OfflineBanner(device: SavedDevice, vm: AppViewModel, showingCached: Boolean) {
    Card(
        Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Can't reach ${device.id} at ${device.host}. Make sure it is switched on and the phone is on the same WiFi. " +
                    (if (showingCached) "Showing the last known counts. " else "") + "Searching for it again…",
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
