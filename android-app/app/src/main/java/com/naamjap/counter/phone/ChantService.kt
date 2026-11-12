package com.naamjap.counter.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.naamjap.counter.JapApp
import com.naamjap.counter.MainActivity
import com.naamjap.counter.R
import com.naamjap.counter.ui.indian
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the microphone listening while the app is in the background (WhatsApp,
 * screen off...). Android allows that only for a foreground service with a
 * notification, which here shows the live count with Pause and Stop.
 */
class ChantService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val chanter get() = (application as JapApp).chanter

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Listening for the jap", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the phone counts your chanting"
                setShowBadge(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                chanter.stopListening()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> chanter.setPaused(true)
            ACTION_RESUME -> chanter.setPaused(false)
        }
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        try {
            ServiceCompat.startForeground(this, ID, build(), type)
        } catch (e: Exception) {
            // Android refused (e.g. started from the background): listen only while the app is open.
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == null) {
            scope.launch {
                chanter.state.collect {
                    getSystemService(NotificationManager::class.java).notify(ID, build())
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun build(): Notification {
        val st = chanter.state.value
        val me = st.me
        val today = chanter.tally.today(me)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = when {
            st.training != null -> "Teaching ${st.wordName}: ${st.training.done} / ${st.training.target}"
            st.paused -> "Paused · ${(me ?: "").ifBlank { "Naam Jap" }}"
            else -> "🎙 ${me ?: "Naam Jap"} · ${today.indian()} today"
        }
        val text = when {
            st.training != null -> st.training.last
            st.paused -> "${today.indian()} japs today · tap Resume to count again"
            else -> "Listening for “${st.wordName}” · Mala ${(today / 108).indian()}"
        }
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (st.training == null) {
            b.addAction(0, if (st.paused) "Resume" else "Pause", action(if (st.paused) ACTION_RESUME else ACTION_PAUSE, 1))
        }
        b.addAction(0, "Stop", action(ACTION_STOP, 2))
        return b.build()
    }

    private fun action(a: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, ChantService::class.java).setAction(a),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "jap_listening"
        private const val ID = 1008
        private const val ACTION_STOP = "stop"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_RESUME = "resume"

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, ChantService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ChantService::class.java))
        }
    }
}
