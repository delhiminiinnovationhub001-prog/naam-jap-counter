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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naamjap.counter.AppViewModel
import com.naamjap.counter.data.DeviceStatus

@Composable
fun LiveScreen(status: DeviceStatus, vm: AppViewModel) {
    val featured = status.featured
    val training = status.training
    val today = featured?.today ?: status.todayAll

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (status.micFault) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(
                    "Microphone not working: " + if (status.micState == "stuckhigh")
                        "its data line is stuck high. Check the SD wire, and that VDD goes to 3V3 (not 5V)."
                    else "no signal. Check the mic wires (SD, SCK, WS, VDD to 3V3, GND, L/R to GND).",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        if (!status.word.ready && !training.active) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Not counting yet", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "The counter only counts the taught word “${status.word.name}”. Open People, add a person and press " +
                            "Train voice; they say ${status.word.name} about 20 times with a short pause after each.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    when {
                        status.paused -> "PAUSED"
                        training.active -> "TRAINING"
                        else -> "NOW CHANTING"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (status.paused) Danger else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    featured?.label ?: "Everyone",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (featured != null && !featured.isGuest && !training.active && status.confidence > 0) {
                    Text("match ${status.confidence}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    today.indian(),
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "Mala ${(today / 108).indian()}   ·   Total ${(featured?.grand ?: status.grandAll).indian()}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (status.word.active) {
                    Text(
                        "Counting only “${status.word.name}”",
                        style = MaterialTheme.typography.labelMedium,
                        color = VoiceGreen,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        val fast = status.fastTraining
        if (fast.active) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Learning fast chanting of ${status.people.getOrNull(fast.slot)?.label}: ${fast.done} / ${fast.target} words",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text("Chant continuously at your normal fast speed, no pauses needed.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { fast.progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { vm.command("Saved what was learnt") { it.personAction(fast.slot, "stop") } }) {
                        Text("Stop & keep what was learnt")
                    }
                }
            }
        }

        if (training.active) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Training ${status.people.getOrNull(training.slot)?.label}: ${training.done} / ${training.target} chants",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("Only this person should chant now, normally, one naam after another.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { training.progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { vm.command("Training stopped") { it.personAction(training.slot, "stop") } }) {
                        Text("Stop training")
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("All today", status.todayAll.indian(), Modifier.weight(1f))
            StatTile("Grand total", status.grandAll.indian(), Modifier.weight(1f))
            StatTile("Prev day", status.prevAll.indian(), Modifier.weight(1f))
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Microphone", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Green while a jap is heard · red line = trigger level",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                LevelBar(status.levelDb, status.thresholdDb, status.voice, status.paused)
                if (status.word.ready) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Word match — counts when the bar ends left of the red line",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    MatchBar(status.word.recentScore, status.word.threshold)
                }
                Text(
                    "Ignored noises (taps, claps, clicks): ${status.ignoredNoises.indian()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { vm.command { it.togglePause() } },
                        colors = if (status.paused) ButtonDefaults.buttonColors(containerColor = VoiceGreen) else ButtonDefaults.buttonColors(),
                    ) { Text(if (status.paused) "Resume" else "Pause") }
                    val slot = featured?.slot ?: status.guestSlot.takeIf { !status.hasPeople }
                    if (slot != null) {
                        OutlinedButton(onClick = { vm.command { it.addCount(slot, -1) } }) { Text("−1") }
                        OutlinedButton(onClick = { vm.command { it.addCount(slot, 1) } }) { Text("+1") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { vm.command(if (status.together) "Together mode off" else "Together mode on") { it.setTogether(!status.together) } }) {
                    Text(if (status.together) "Together: ON (every word counts for each)" else "Together: off")
                }
            }
        }

        Text(
            if (status.timeOk) "Counter time: ${status.time} IST" else "Counter clock not set yet (needs internet)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}
