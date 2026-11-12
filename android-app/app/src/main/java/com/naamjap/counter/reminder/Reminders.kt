package com.naamjap.counter.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.naamjap.counter.MainActivity
import com.naamjap.counter.R
import com.naamjap.counter.data.LocalStore
import com.naamjap.counter.data.Tally
import com.naamjap.counter.ui.indian
import java.util.Calendar

/** A daily reminder at the time set in the app (inexact, so no special permission). */
object Reminders {
    private const val CHANNEL = "jap_reminder"
    private const val ID = 108

    fun schedule(context: Context) {
        val store = LocalStore(context)
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(context)
        am.cancel(pi)
        if (!store.reminderOn) return
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, store.reminderHour)
            set(Calendar.MINUTE, store.reminderMinute)
            set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, next.timeInMillis, AlarmManager.INTERVAL_DAY, pi)
    }

    private fun alarmIntent(context: Context) = PendingIntent.getBroadcast(
        context, 0, Intent(context, ReminderReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun show(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val store = LocalStore(context)
        val tally = Tally(store)
        val today = tally.today(store.me)
        val goal = store.goalMalas * 108L
        val text = when {
            goal > 0 && today >= goal -> "Goal reached today (${today.indian()} japs). Radha Radha!"
            goal > 0 -> "Today ${today.indian()} of ${goal.indian()} japs. Time for naam jap."
            else -> "Time for naam jap. Today ${today.indian()} japs."
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Radha Radha 🙏")
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID, n) }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Reminders.show(context)
}

/** Alarms are cleared when the phone restarts: set the reminder again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) Reminders.schedule(context)
    }
}
