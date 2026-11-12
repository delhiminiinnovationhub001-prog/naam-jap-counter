package com.naamjap.counter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naamjap.counter.AppViewModel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class Period(val label: String) { Day("Days"), Month("Months"), Year("Years") }

/** History of the counter and this phone together; works offline from the saved copy. */
@Composable
fun TotalsScreen(vm: AppViewModel) {
    val st by vm.chanter.state.collectAsStateWithLifecycle()  // recompose when counts change
    val tally = vm.chanter.tally
    var period by rememberSaveable { mutableStateOf(Period.Day) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val names = tally.allNames()

    val rows: List<Pair<String, Map<String, Long>>> = when (period) {
        Period.Day -> tally.days().map { it.date to it.counts }
        Period.Month -> tally.periods(7)
        Period.Year -> tally.periods(4)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Period.entries.forEach { p -> FilterChip(selected = period == p, onClick = { period = p }, label = { Text(p.label) }) }
        }
        val c = vm.local.cache
        Text(
            if (c == null) "From this phone only. Connect to the counter to include its counts."
            else "Counter data from ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(c.savedAt))}, plus this phone" +
                if (st.revision > 0) "." else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Everyone, all time", tally.grand(null).indian(), Modifier.weight(1f))
            StatTile("Malas", (tally.grand(null) / 108).indian(), Modifier.weight(1f))
        }
        OutlinedButton(onClick = {
            val csv = buildString {
                append("date,").append(names.joinToString(",")).append(",total\n")
                tally.days().sortedBy { it.date }.forEach { d ->
                    append(d.date).append(',').append(names.joinToString(",") { (d.counts[it] ?: 0).toString() }).append(',').append(d.total).append('\n')
                }
            }
            scope.launch { shareCsv(context, csv) }
        }) { Text("Share as CSV") }

        if (rows.isEmpty()) {
            Text("No japs yet.", modifier = Modifier.padding(top = 24.dp))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
            items(rows, key = { it.first }) { (key, counts) ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row {
                            Text(label(period, key), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            val total = counts.values.sum()
                            Text("${total.indian()}  ·  ${(total / 108).indian()} mala", style = MaterialTheme.typography.titleSmall)
                        }
                        counts.filterValues { it > 0 }.forEach { (n, v) ->
                            Row {
                                Text(n, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Text(v.indian(), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun label(p: Period, key: String): String = runCatching {
    when (p) {
        Period.Day -> {
            val d = LocalDate.parse(key)
            val today = LocalDate.now()
            when (d) {
                today -> "Today"
                today.minusDays(1) -> "Yesterday"
                else -> d.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH))
            }
        }
        Period.Month -> LocalDate.parse("$key-01").format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH))
        Period.Year -> key
    }
}.getOrDefault(key)
