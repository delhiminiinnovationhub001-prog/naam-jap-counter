package com.naamjap.counter

import android.app.Application
import android.os.SystemClock
import com.naamjap.counter.data.LocalStore
import com.naamjap.counter.phone.ChantService
import com.naamjap.counter.phone.Chanter
import com.naamjap.counter.widget.JapWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Lives as long as the app's process: the phone's chanting keeps running here
 * (with [ChantService] keeping the process and microphone alive) while the
 * screens come and go, e.g. while the user is in WhatsApp.
 */
class JapApp : Application() {
    lateinit var store: LocalStore
        private set
    lateinit var chanter: Chanter
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var widgetDue = false
    private var lastWidget = 0L

    override fun onCreate() {
        super.onCreate()
        store = LocalStore(this)
        chanter = Chanter(store) { refreshWidgetSoon() }
        // While the phone listens, a foreground service (with its notification)
        // keeps the microphone working when the app is in the background.
        scope.launch {
            chanter.state.map { it.listening }.distinctUntilChanged().collect { on ->
                if (on) ChantService.start(this@JapApp) else ChantService.stop(this@JapApp)
            }
        }
    }

    /** The widget at most every 2 s (it reads the saved counts). */
    fun refreshWidgetSoon() {
        if (widgetDue) return
        val wait = 2000 - (SystemClock.elapsedRealtime() - lastWidget)
        if (wait <= 0) {
            lastWidget = SystemClock.elapsedRealtime()
            JapWidget.refreshAll(this)
            return
        }
        widgetDue = true
        scope.launch {
            delay(wait)
            widgetDue = false
            lastWidget = SystemClock.elapsedRealtime()
            JapWidget.refreshAll(this@JapApp)
        }
    }
}
