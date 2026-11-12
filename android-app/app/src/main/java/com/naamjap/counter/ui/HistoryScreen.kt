package com.naamjap.counter.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naamjap.counter.AppViewModel
import com.naamjap.counter.data.DeviceStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun HistoryScreen(status: DeviceStatus, vm: AppViewModel) {
    val history by vm.history.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { vm.loadHistory() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::loadHistory) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Text("  Refresh")
                }
                OutlinedButton(onClick = {
                    scope.launch { vm.fetchHistoryCsv()?.let { shareCsv(context, it) } }
                }) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Text("  Share CSV")
                }
            }
        }
        item {
            Text(
                "Each day is saved at 12 midnight IST. Last 60 days are shown; the CSV has all of them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val days = history
        when {
            days == null -> item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            days.isEmpty() -> item { Text("No finished days yet. Today's counts are added at midnight.") }
            else -> items(days, key = { it.date }) { day ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row {
                            Text(day.displayDate, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Text(day.total.indian(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        day.counts.forEachIndexed { slot, count ->
                            val person = status.people.getOrNull(slot)
                            if (count > 0 || (person?.active == true)) {
                                Row(Modifier.padding(top = 2.dp)) {
                                    Text(person?.label ?: "Person ${slot + 1}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    Text(count.indian(), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private suspend fun shareCsv(context: Context, csv: String) {
    val file = withContext(Dispatchers.IO) {
        File(context.cacheDir, "exports").apply { mkdirs() }.resolve("jap_history.csv").apply { writeText(csv) }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Naam Jap history")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share history"))
}
