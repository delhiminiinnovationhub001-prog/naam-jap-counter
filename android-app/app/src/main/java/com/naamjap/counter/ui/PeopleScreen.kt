package com.naamjap.counter.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.naamjap.counter.AppViewModel
import com.naamjap.counter.data.DeviceStatus
import com.naamjap.counter.data.MIN_VOICE_SAMPLES
import com.naamjap.counter.data.Person

private sealed interface PeopleDialog {
    data class Train(val person: Person, val more: Boolean) : PeopleDialog
    data class Rename(val person: Person) : PeopleDialog
    data class SetCount(val person: Person) : PeopleDialog
    data class ClearVoice(val person: Person) : PeopleDialog
    data class Remove(val person: Person) : PeopleDialog
}

@Composable
fun PeopleScreen(status: DeviceStatus, vm: AppViewModel) {
    var dialog by remember { mutableStateOf<PeopleDialog?>(null) }
    var newName by rememberSaveable { mutableStateOf("") }
    val current = if (status.training.active) status.training.slot else status.currentSlot
    val shown = status.people.filter { it.active || it.isGuest }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Add up to 5 people, then train each one: that person says “${status.word.name}” alone about 20 times, " +
                    "with a short pause after each. Only the taught word is counted, also in continuous chanting.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(shown, key = { it.slot }) { p ->
            PersonCard(
                person = p,
                wordPatterns = status.word.patterns.getOrElse(p.slot) { 0 },
                highlighted = p.slot == current,
                trainingThis = status.training.active && status.training.slot == p.slot,
                trainingLabel = "${status.training.done} / ${status.training.target}",
                onTrain = { dialog = PeopleDialog.Train(p, more = false) },
                onMore = { dialog = PeopleDialog.Train(p, more = true) },
                onFast = { vm.command("${p.label}: chant fast now, without pauses, until it says done") { it.personAction(p.slot, "fast") } },
                onStop = { vm.command("Training stopped") { it.personAction(p.slot, "stop") } },
                onRename = { dialog = PeopleDialog.Rename(p) },
                onClearVoice = { dialog = PeopleDialog.ClearVoice(p) },
                onRemove = { dialog = PeopleDialog.Remove(p) },
                onAdd = { n -> vm.command { it.addCount(p.slot, n) } },
                onSet = { dialog = PeopleDialog.SetCount(p) },
            )
        }
        if (status.freeSlots > 0) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Add a person", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = newName,
                                onValueChange = { newName = it.take(16) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                placeholder = { Text("Name (English letters)") },
                            )
                            Spacer(Modifier.size(8.dp))
                            Button(enabled = newName.isNotBlank(), onClick = {
                                val name = newName.trim()
                                vm.command("$name added") { it.addPerson(name) }
                                newName = ""
                            }) { Text("Add") }
                        }
                        Text(
                            "${status.freeSlots} of ${status.places} places free",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        is PeopleDialog.Train -> ConfirmDialog(
            title = if (d.more) "Add chants for ${d.person.label}" else "Train ${d.person.label}'s voice",
            text = "${d.person.label} should now say “${status.word.name}” ${status.settings.trainChants} times, alone, " +
                "at the usual distance and volume, with a short pause after each one." +
                if (!d.more && d.person.samples > 0) "\n\nThis replaces the voice trained before." else "",
            confirmLabel = "Start",
            onConfirm = { vm.command("Training started — chant now") { it.personAction(d.person.slot, if (d.more) "more" else "train") } },
            onDismiss = { dialog = null },
        )
        is PeopleDialog.Rename -> InputDialog(
            title = "Rename ${d.person.label}",
            label = "Name",
            initial = d.person.name,
            onConfirm = { name -> vm.command("Renamed") { it.renamePerson(d.person.slot, name) } },
            onDismiss = { dialog = null },
        )
        is PeopleDialog.SetCount -> InputDialog(
            title = "Set today's count",
            label = "Count for ${d.person.label}",
            initial = d.person.today.toString(),
            numeric = true,
            onConfirm = { v -> vm.command("Count updated") { it.setCount(d.person.slot, v.toLong()) } },
            onDismiss = { dialog = null },
        )
        is PeopleDialog.ClearVoice -> ConfirmDialog(
            title = "Clear voice?",
            text = "Forget the trained voice of ${d.person.label}? Their counts are kept.",
            confirmLabel = "Clear voice",
            destructive = true,
            onConfirm = { vm.command("Voice cleared") { it.personAction(d.person.slot, "clearvoice") } },
            onDismiss = { dialog = null },
        )
        is PeopleDialog.Remove -> ConfirmDialog(
            title = "Remove ${d.person.label}?",
            text = "Their counts and trained voice will be erased from the counter. This cannot be undone.",
            confirmLabel = "Remove",
            destructive = true,
            onConfirm = { vm.command("${d.person.label} removed") { it.personAction(d.person.slot, "remove") } },
            onDismiss = { dialog = null },
        )
    }
}

@Composable
private fun PersonCard(
    person: Person,
    wordPatterns: Int,
    highlighted: Boolean,
    trainingThis: Boolean,
    trainingLabel: String,
    onTrain: () -> Unit,
    onMore: () -> Unit,
    onFast: () -> Unit,
    onStop: () -> Unit,
    onRename: () -> Unit,
    onClearVoice: () -> Unit,
    onRemove: () -> Unit,
    onAdd: (Int) -> Unit,
    onSet: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth(),
        border = if (highlighted) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(person.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (!person.isGuest) {
                        Text(
                            when {
                                trainingThis -> "training… $trainingLabel"
                                person.voiceTrained -> "voice trained (${person.samples} chants)"
                                person.samples > 0 -> "voice: ${person.samples} chants, needs $MIN_VOICE_SAMPLES+"
                                else -> "voice not trained"
                            } + if (wordPatterns > 0 && !trainingThis) " · word: $wordPatterns patterns" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (person.voiceTrained && !trainingThis) VoiceGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text("japs that matched no one", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!person.isGuest) {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More for ${person.label}") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Add more chants") }, onClick = { menu = false; onMore() }, enabled = !trainingThis)
                            DropdownMenuItem(text = { Text("Teach fast chanting") }, onClick = { menu = false; onFast() }, enabled = !trainingThis && wordPatterns > 0)
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                            DropdownMenuItem(text = { Text("Clear voice") }, onClick = { menu = false; onClearVoice() }, enabled = person.samples > 0)
                            DropdownMenuItem(text = { Text("Remove person", color = Danger) }, onClick = { menu = false; onRemove() })
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Today ${person.today.indian()}  ·  Total ${person.grand.indian()}  ·  Prev day ${person.prev.indian()}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!person.isGuest) {
                    if (trainingThis) {
                        FilledTonalButton(onClick = onStop) { Text("Stop") }
                    } else {
                        FilledTonalButton(onClick = onTrain) { Text(if (person.samples > 0) "Retrain" else "Train voice") }
                    }
                }
                OutlinedButton(onClick = { onAdd(-1) }) { Text("−1") }
                OutlinedButton(onClick = { onAdd(1) }) { Text("+1") }
                OutlinedButton(onClick = onSet) { Text("Set") }
            }
        }
    }
}
