package com.naamjap.counter.ui

import android.Manifest
import android.content.pm.PackageManager
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naamjap.counter.AppViewModel
import com.naamjap.counter.Connection
import com.naamjap.counter.phone.ChantMode

/** Chanting on the phone: works with or without the counter. */
@Composable
fun ChantScreen(vm: AppViewModel) {
    val ch = vm.chanter
    val st by ch.state.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    var addName by rememberSaveable { mutableStateOf(false) }
    var trainWho by rememberSaveable { mutableStateOf<String?>(null) }
    var forget by rememberSaveable { mutableStateOf(false) }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) vm.say("The app needs the microphone to hear the word. You can still use the tap counter.")
    }
    fun withMic(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) action()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Keep the screen on while listening.
    DisposableEffect(st.listening) {
        view.keepScreenOn = st.listening
        onDispose { view.keepScreenOn = false }
    }

    val me = st.me
    val tally = ch.tally  // st changes (revision) whenever counts change, so these are recomputed
    val today = tally.today(me)
    val grand = tally.grand(me)
    val goal = vm.local.goalMalas * 108L
    val streak = tally.streak(me, goal)

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- not yet on the counter
        if (sync.unsent > 0 && connection != Connection.Online) {
            Banner(
                "${sync.unsent.indian()} japs are saved on this phone",
                if (connection == Connection.None) "Connect to your counter (Counter tab) to add them to its count."
                else "Connect the phone to the same WiFi as the counter to add them to its count.",
                warn = true,
            )
        }
        if (connection == Connection.Online && sync.needsFirmware) {
            Banner("Counter firmware too old", "Update the counter (Settings > Firmware update) so it can take the phone's japs.", warn = true)
        }
        if (connection == Connection.Online) sync.notOnCounter.forEach { n ->
            Banner("$n is not on the counter", "Their phone japs are kept until the counter has a person named $n.", warn = true) {
                TextButton(onClick = { vm.addPersonToCounter(n) }) { Text("Add $n to the counter") }
            }
        }

        // ---- who is chanting on this phone
        Text("Who is chanting?", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            st.names.forEach { n ->
                FilterChip(selected = n.equals(me, true), onClick = { ch.pickMe(n) }, label = { Text(n) })
            }
            FilterChip(selected = false, onClick = { addName = true }, label = { Text("+ Add name") })
        }

        // ---- the count
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text((me ?: "Pick your name").uppercase(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Saffron)
                Text(today.indian(), fontSize = 64.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("Mala ${(today / 108).indian()}   ·   Total ${grand.indian()}", style = MaterialTheme.typography.bodyMedium)
                if (goal > 0) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(progress = { (today.toFloat() / goal).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text(
                        (if (today >= goal) "Goal reached! " else "Goal ") + "${(today / 108).indian()} / ${vm.local.goalMalas} mala" +
                            if (streak > 0) "   ·   🔥 $streak day${if (streak > 1) "s" else ""}" else "",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }

        // ---- voice or tap
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = st.mode == ChantMode.Voice, onClick = { ch.setMode(ChantMode.Voice) }, label = { Text("🎙 Voice") })
            FilterChip(selected = st.mode == ChantMode.Tap, onClick = { ch.setMode(ChantMode.Tap) }, label = { Text("👆 Tap counter") })
        }

        st.error?.let { Banner("Microphone problem", it, warn = true) }
        st.soundsLike?.let { other ->
            Banner("This sounds like $other", "The japs go to ${me ?: "you"}. If $other is chanting, switch the name.", warn = true) {
                TextButton(onClick = { ch.pickMe(other) }) { Text("Switch to $other") }
            }
        }

        val training = st.training
        val fast = st.fast
        when {
            training != null -> TrainingCard(vm, training.person, training.done, training.target, training.last, training.finishing, st.wordName)
            st.mode == ChantMode.Tap -> TapCard(
                enabled = me != null,
                onTap = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); ch.tap() },
                onUndo = { if (!ch.untap()) vm.say("Nothing to take back (already on the counter)") },
            )
            me == null -> Banner("Pick your name", "Choose who is chanting on this phone above, or add your name.", warn = false)
            st.trained == 0 -> Banner(
                "Teach the word on this phone",
                "Say ${st.wordName} about 20 times with a short pause after each. The phone learns your voice; it takes about a minute.",
                warn = false,
            ) { Button(onClick = { withMic { trainWho = me } }) { Text("Teach ${st.wordName}") } }
            else -> ListenCard(vm, st.listening, st.wordName, fast?.learnt, fast?.target, onStart = { withMic { ch.startListening() } })
        }

        // ---- the phone's voice model
        if (me != null && training == null && st.trained > 0) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("$me's voice on this phone", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${st.trained} word patterns" + (if (st.fastTrained > 0) " (${st.fastTrained} from fast chanting)" else "") +
                            ". The counter keeps its own training, because its microphone sounds different.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { withMic { trainWho = me } }) { Text("Teach again") }
                        if (fast == null) OutlinedButton(onClick = { withMic { if (!ch.startFast(me)) vm.say("Teach the word with pauses first") } }) {
                            Text("Learn fast chanting")
                        }
                    }
                    TextButton(onClick = { forget = true }) { Text("Forget $me's voice on this phone", color = Danger) }
                }
            }
        }
    }

    if (addName) InputDialog(title = "Add a name", label = "Name", confirmLabel = "Add",
        onConfirm = { n -> ch.pickMe(n.filter { it.code in 0x20..0x7E && it != ',' && it != ';' }.trim().take(16)) },
        onDismiss = { addName = false })
    trainWho?.let { who ->
        ConfirmDialog(
            title = "Teach ${st.wordName} to the phone",
            text = "Only $who should chant now. Say ${st.wordName}, pause a moment, and say it again, about 20 times, " +
                "at your normal speed and loudness. Hold the phone as you will when chanting.",
            confirmLabel = "Start",
            onConfirm = { ch.startTraining(who) },
            onDismiss = { trainWho = null },
        )
    }
    if (forget && me != null) ConfirmDialog(
        title = "Forget $me's voice?",
        text = "The phone stops counting $me's voice until the word is taught again. Counts are kept.",
        confirmLabel = "Forget", destructive = true,
        onConfirm = { ch.forgetVoice(me) }, onDismiss = { forget = false },
    )
}

@Composable
private fun ListenCard(vm: AppViewModel, listening: Boolean, word: String, fastLearnt: Int?, fastTarget: Int?, onStart: () -> Unit) {
    val ch = vm.chanter
    val level by ch.level.collectAsStateWithLifecycle()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (fastLearnt != null) "Learning fast chanting: $fastLearnt / $fastTarget words. Chant continuously at your fast speed."
                else if (listening) "Listening for “$word”… only this word is counted" else "Press Start and chant “$word”",
                style = MaterialTheme.typography.bodyMedium,
            )
            LevelBar(level.db, level.triggerDb, level.voice, !listening)
            if (listening) Text("Word match ${level.matchPercent}% (counts at 100%)", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (listening) {
                    Button(onClick = ch::stopListening, colors = ButtonDefaults.buttonColors(containerColor = Danger)) { Text("Stop") }
                    if (fastLearnt != null) OutlinedButton(onClick = { ch.stopFast(keep = true) }) { Text("Done learning") }
                } else {
                    Button(onClick = onStart, colors = ButtonDefaults.buttonColors(containerColor = VoiceGreen)) { Text("Start chanting") }
                }
            }
        }
    }
}

@Composable
private fun TrainingCard(vm: AppViewModel, who: String, done: Int, target: Int, last: String, finishing: Boolean, word: String) {
    val ch = vm.chanter
    val level by ch.level.collectAsStateWithLifecycle()
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Teaching $who: $done / $target", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Say $word, pause, say it again…", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { done.toFloat() / target }, modifier = Modifier.fillMaxWidth())
            LevelBar(level.db, level.triggerDb, level.voice, finishing)
            Text(last, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (!finishing) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = done > 0, onClick = ch::redoLast) { Text("Redo last") }
                Button(enabled = done >= 5, onClick = ch::finishTraining) { Text("Finish") }
                TextButton(onClick = ch::cancelTraining) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun TapCard(enabled: Boolean, onTap: () -> Unit, onUndo: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.size(200.dp).clip(CircleShape)
                .background(Brush.radialGradient(listOf(SaffronLight, Saffron)))
                .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (enabled) "राधा\n+1" else "Pick\nyour name", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
        Text("Tap once for each jap. Silent jap works too.", style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = enabled, onClick = onUndo) { Text("Undo last (−1)") }
    }
}

@Composable
fun Banner(title: String, text: String, warn: Boolean, action: (@Composable () -> Unit)? = null) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (warn) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(text, style = MaterialTheme.typography.bodySmall)
            action?.invoke()
        }
    }
}
