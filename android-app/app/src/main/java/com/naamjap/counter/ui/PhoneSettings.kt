package com.naamjap.counter.ui

import android.Manifest
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.naamjap.counter.AppViewModel
import com.naamjap.counter.BuildConfig
import com.naamjap.counter.reminder.Reminders
import com.naamjap.counter.widget.JapWidget

/** Settings of the app itself: goal, reminder, and how the phone listens. */
@Composable
fun PhoneSettings(vm: AppViewModel) {
    val store = vm.local
    val context = LocalContext.current
    var goal by remember { mutableIntStateOf(store.goalMalas) }
    var remOn by remember { mutableStateOf(store.reminderOn) }
    var remH by remember { mutableIntStateOf(store.reminderHour) }
    var remM by remember { mutableIntStateOf(store.reminderMinute) }
    var sens by remember { mutableIntStateOf(store.sensitivity) }
    var strict by remember { mutableIntStateOf(vm.chanter.model.strictness) }
    var word by remember { mutableStateOf(store.wordName) }

    fun saveReminder() {
        store.reminderOn = remOn
        store.reminderHour = remH
        store.reminderMinute = remM
        store.save()
        Reminders.schedule(context)
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) vm.say("Allow notifications for the app to show the reminder")
    }

    Section("Daily goal") {
        IntSlider("Malas per day (108 japs each)", goal, 0..21, if (goal == 0) "No goal" else "${goal * 108} japs a day") {
            goal = it
            store.goalMalas = it
            store.save()
            JapWidget.refreshAll(context)
        }
        Text("The streak counts the days in a row on which the goal was reached.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Section("Reminder") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Remind me every day", style = MaterialTheme.typography.bodyLarge)
                Text("at %02d:%02d".format(remH, remM), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = remOn, onCheckedChange = { on ->
                remOn = on
                if (on && Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                saveReminder()
            })
        }
        OutlinedButton(onClick = {
            TimePickerDialog(context, { _, h, m -> remH = h; remM = m; saveReminder() }, remH, remM, false).show()
        }) { Text("Change time") }
    }

    Section("Chanting on this phone") {
        OutlinedTextField(
            value = word,
            onValueChange = { v -> word = v.filter { it.code in 0x20..0x7E }.take(16) },
            label = { Text("Word or mantra (name shown)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (word.isNotBlank() && word != store.wordName) OutlinedButton(onClick = { vm.chanter.setWordName(word) }) { Text("Save word") }
        IntSlider("Sensitivity", sens, 1..10, "Higher picks up softer chanting") { sens = it; vm.chanter.setSensitivity(it) }
        IntSlider("Word matching strictness", strict, 1..10, "Higher gives fewer false counts, but may miss some") {
            strict = it
            vm.chanter.setStrictness(it)
        }
        Text("The phone only listens while the Chant screen says Listening. Nothing is recorded or sent anywhere.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Section("This app") {
        InfoRow("Version", BuildConfig.VERSION_NAME)
        InfoRow("Japs not yet on the counter", store.unsentTotal().indian())
    }
}
