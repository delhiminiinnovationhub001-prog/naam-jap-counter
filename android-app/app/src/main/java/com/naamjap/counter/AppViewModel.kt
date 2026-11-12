package com.naamjap.counter

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.naamjap.counter.data.DeviceApi
import com.naamjap.counter.data.DeviceApiException
import com.naamjap.counter.data.DeviceStatus
import com.naamjap.counter.data.DeviceStore
import com.naamjap.counter.data.Discovery
import com.naamjap.counter.data.FoundDevice
import com.naamjap.counter.data.HistoryDay
import com.naamjap.counter.data.SavedDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class Connection { Connecting, Online, Offline }

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = DeviceStore(app)
    private val discovery = Discovery(app)

    private val _saved = MutableStateFlow(store.all())
    val saved: StateFlow<List<SavedDevice>> = _saved

    private val _found = MutableStateFlow<List<FoundDevice>>(emptyList())
    val found: StateFlow<List<FoundDevice>> = _found

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val _device = MutableStateFlow(store.selected())
    val device: StateFlow<SavedDevice?> = _device

    private val _status = MutableStateFlow<DeviceStatus?>(null)
    val status: StateFlow<DeviceStatus?> = _status

    private val _connection = MutableStateFlow(Connection.Connecting)
    val connection: StateFlow<Connection> = _connection

    private val _history = MutableStateFlow<List<HistoryDay>?>(null)
    val history: StateFlow<List<HistoryDay>?> = _history

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    private var pollJob: Job? = null
    private var scanJob: Job? = null
    private var failures = 0

    private fun api(): DeviceApi? = _device.value?.let { DeviceApi(it.host) }

    // ------------------------------------------------------------ discovery

    fun startScan() {
        scanJob?.cancel()
        _found.value = emptyList()
        _scanning.value = true
        scanJob = viewModelScope.launch {
            try {
                withTimeoutOrNull(SCAN_DURATION_MS) {
                    discovery.devices().collect { f ->
                        if (_found.value.none { it.id == f.id }) _found.value = _found.value + f
                        // A known counter may have a new address after a router restart.
                        if (store.updateHost(f.id, f.host)) {
                            _saved.value = store.all()
                            if (_device.value?.id == f.id) _device.value = store.get(f.id)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(e.message ?: "Could not search the network")
            } finally {
                _scanning.value = false
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
    }

    fun connect(id: String, host: String) {
        val d = SavedDevice(id, host, System.currentTimeMillis())
        store.save(d)
        store.selectedId = id
        _saved.value = store.all()
        _status.value = null
        _history.value = null
        _connection.value = Connection.Connecting
        failures = 0
        _device.value = d
    }

    /** Checks that [host] is a counter, then connects to it. */
    fun connectManual(host: String) {
        val clean = host.trim().removePrefix("http://").trimEnd('/')
        if (clean.isEmpty()) return
        viewModelScope.launch {
            _busy.value = true
            try {
                val info = DeviceApi(clean).info()
                connect(info.id, clean)
            } catch (e: Exception) {
                _messages.tryEmit(friendly(e, "No counter answered at $clean"))
            } finally {
                _busy.value = false
            }
        }
    }

    fun forget(id: String) {
        store.remove(id)
        _saved.value = store.all()
        if (_device.value?.id == id) disconnect()
    }

    fun disconnect() {
        stopPolling()
        store.selectedId = null
        _device.value = null
        _status.value = null
        _history.value = null
    }

    // --------------------------------------------------------------- status

    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                val a = api()
                if (a == null) {
                    delay(POLL_MS)
                    continue
                }
                try {
                    _status.value = a.status()
                    _connection.value = Connection.Online
                    failures = 0
                    _device.value?.id?.let { store.get(it) }?.let {
                        val now = System.currentTimeMillis()
                        if (now - it.lastSeen > SEEN_SAVE_MS) store.save(it.copy(lastSeen = now))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++
                    if (failures >= OFFLINE_AFTER_FAILURES) {
                        _connection.value = Connection.Offline
                        // Look for the device again in case its IP address changed.
                        if (failures == OFFLINE_AFTER_FAILURES || failures % 20 == 0) {
                            if (scanJob?.isActive != true) startScan()
                        }
                    }
                }
                delay(if (_connection.value == Connection.Online) POLL_MS else POLL_OFFLINE_MS)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun retryNow() {
        stopPolling()
        _connection.value = Connection.Connecting
        startPolling()
    }

    fun loadHistory() {
        viewModelScope.launch { onDevice { _history.value = it.history() } }
    }

    suspend fun fetchHistoryCsv(): String? {
        val a = api() ?: return null
        return try {
            a.historyCsv()
        } catch (e: Exception) {
            _messages.tryEmit(friendly(e))
            null
        }
    }

    // -------------------------------------------------------------- actions

    /** Runs a device command, shows [done] on success, and refreshes the status. */
    fun command(done: String? = null, block: suspend (DeviceApi) -> Unit) {
        viewModelScope.launch {
            if (onDevice(block)) {
                done?.let { _messages.tryEmit(it) }
                runCatching { api()?.status() }.getOrNull()?.let { _status.value = it }
            }
        }
    }

    private suspend fun onDevice(block: suspend (DeviceApi) -> Unit): Boolean {
        val a = api() ?: return false
        return try {
            block(a)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _messages.tryEmit(friendly(e))
            false
        }
    }

    private fun friendly(e: Exception, fallback: String = "Could not reach the counter. Is the phone on the same WiFi?") =
        if (e is DeviceApiException) e.message ?: fallback else fallback

    override fun onCleared() {
        stopPolling()
        stopScan()
    }

    private companion object {
        const val POLL_MS = 1000L
        const val POLL_OFFLINE_MS = 2500L
        const val OFFLINE_AFTER_FAILURES = 3
        const val SCAN_DURATION_MS = 15_000L
        const val SEEN_SAVE_MS = 60_000L
    }
}
