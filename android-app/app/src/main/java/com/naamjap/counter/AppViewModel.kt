package com.naamjap.counter

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.naamjap.counter.data.Batch
import com.naamjap.counter.data.DeviceApi
import com.naamjap.counter.data.DeviceApiException
import com.naamjap.counter.data.DeviceCache
import com.naamjap.counter.data.DeviceStatus
import com.naamjap.counter.data.DeviceStore
import com.naamjap.counter.data.Discovery
import com.naamjap.counter.data.FirmwareCheck
import com.naamjap.counter.data.FirmwareOffer
import com.naamjap.counter.data.FoundDevice
import com.naamjap.counter.data.HistoryDay
import com.naamjap.counter.data.LocalStore
import com.naamjap.counter.data.SavedDevice
import com.naamjap.counter.data.mergeDays
import com.naamjap.counter.phone.Chanter
import com.naamjap.counter.widget.JapWidget
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
import java.util.UUID

enum class Connection { None, Connecting, Online, Offline }

/** Japs chanted on the phone and how they get to the counter. */
data class SyncState(
    val unsent: Long,              // japs on the phone the counter does not have yet
    val sending: Boolean,
    val lastAdded: Long,           // japs added at the last successful sync
    val lastSyncAt: Long,
    val notOnCounter: List<String>,  // phone names the counter has no person for
    val needsFirmware: Boolean,    // the counter is older than 2.4.6 (no merge)
)

data class FirmwareUpdate(
    val checking: Boolean = false,
    val offer: FirmwareOffer? = null,
    val installing: Boolean = false,
    val message: String? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val devices = DeviceStore(app)
    private val discovery = Discovery(app)
    // Shared with the background listening service: they live as long as the app's process.
    val local: LocalStore = (app as JapApp).store
    val chanter: Chanter = (app as JapApp).chanter

    private val _saved = MutableStateFlow(devices.all())
    val saved: StateFlow<List<SavedDevice>> = _saved

    private val _found = MutableStateFlow<List<FoundDevice>>(emptyList())
    val found: StateFlow<List<FoundDevice>> = _found

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val _device = MutableStateFlow(devices.selected())
    val device: StateFlow<SavedDevice?> = _device

    private val _status = MutableStateFlow<DeviceStatus?>(null)
    val status: StateFlow<DeviceStatus?> = _status

    private val _firmware = MutableStateFlow<String?>(null)
    val firmware: StateFlow<String?> = _firmware

    private val _connection = MutableStateFlow(if (_device.value == null) Connection.None else Connection.Connecting)
    val connection: StateFlow<Connection> = _connection

    private val _history = MutableStateFlow<List<HistoryDay>?>(null)
    val history: StateFlow<List<HistoryDay>?> = _history

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _sync = MutableStateFlow(SyncState(local.unsentTotal(), false, 0, 0, emptyList(), false))
    val sync: StateFlow<SyncState> = _sync

    private val _update = MutableStateFlow(FirmwareUpdate())
    val update: StateFlow<FirmwareUpdate> = _update

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    private var pollJob: Job? = null
    private var scanJob: Job? = null
    private var failures = 0
    private var lastHistoryAt = 0L
    private var lastCounterToday = -1L

    init {
        // Cached counter data from last time, so everything shows while offline.
        local.cache?.let { c -> if (c.deviceId == _device.value?.id) _status.value = runCatching { DeviceStatus.parse(c.status) }.getOrNull() }
        viewModelScope.launch { chanter.state.collect { refreshSyncCount() } }
    }

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
                        if (devices.updateHost(f.id, f.host)) {
                            _saved.value = devices.all()
                            if (_device.value?.id == f.id) _device.value = devices.get(f.id)
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
        devices.save(d)
        devices.selectedId = id
        _saved.value = devices.all()
        if (local.cache?.deviceId != id) _status.value = null
        _history.value = null
        _firmware.value = null
        _connection.value = Connection.Connecting
        failures = 0
        lastHistoryAt = 0
        _device.value = d
        retryNow()
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
        devices.remove(id)
        _saved.value = devices.all()
        if (_device.value?.id == id) disconnect()
    }

    fun disconnect() {
        stopPolling()
        devices.selectedId = null
        _device.value = null
        _status.value = null
        _history.value = null
        _connection.value = Connection.None
    }

    // --------------------------------------------------------------- status

    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                val a = api()
                if (a == null) {
                    _connection.value = Connection.None
                    delay(POLL_OFFLINE_MS)
                    continue
                }
                try {
                    if (_firmware.value == null) _firmware.value = a.info().firmware
                    val raw = a.statusJson()
                    val s = DeviceStatus.parse(raw)
                    _status.value = s
                    _connection.value = Connection.Online
                    failures = 0
                    onStatus(a, raw, s)
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

    private suspend fun onStatus(a: DeviceApi, raw: org.json.JSONObject, s: DeviceStatus) {
        val id = _device.value?.id ?: return
        _device.value?.let {
            val now = System.currentTimeMillis()
            if (now - it.lastSeen > SEEN_SAVE_MS) devices.save(it.copy(lastSeen = now))
        }
        val now = System.currentTimeMillis()
        val history = if (now - lastHistoryAt > HISTORY_EVERY_MS || local.cache?.deviceId != id) {
            lastHistoryAt = now
            runCatching { a.historyJson() }.getOrNull()
        } else null
        val old = local.cache
        local.cache = DeviceCache(
            id, counterDate(s), raw,
            history ?: old?.takeIf { it.deviceId == id }?.history ?: org.json.JSONArray(), now,
        )
        if (history != null || now - (old?.savedAt ?: 0) > CACHE_SAVE_MS) local.save()

        // Celebrate the counter's own milestones too.
        val featured = s.featured
        val today = featured?.today ?: s.todayAll
        if (lastCounterToday >= 0) chanter.counterMilestone(featured?.name, today, lastCounterToday)
        lastCounterToday = today

        sync(a, s)
        chanter.refresh()
    }

    /** "12-11-2026 02:31:57 PM" -> "2026-11-12"; the phone's date if the counter has no time. */
    private fun counterDate(s: DeviceStatus): String {
        val m = Regex("^(\\d{2})-(\\d{2})-(\\d{4})").find(s.time)
        return if (s.timeOk && m != null) "${m.groupValues[3]}-${m.groupValues[2]}-${m.groupValues[1]}" else local.today()
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun retryNow() {
        stopPolling()
        if (_device.value != null) _connection.value = Connection.Connecting
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

    // ----------------------------------------------------------------- sync

    private fun refreshSyncCount() {
        _sync.value = _sync.value.copy(unsent = local.unsentTotal())
    }

    /**
     * Adds the japs chanted on the phone to the counter, each on its real day.
     * A batch is saved before it is sent and resent with the same id until the
     * counter confirms it, so japs are never lost or counted twice.
     */
    private suspend fun sync(a: DeviceApi, s: DeviceStatus) {
        val id = _device.value?.id ?: return
        val fw = _firmware.value ?: return
        if (_sync.value.sending) return
        val slots = s.people.filter { !it.isGuest && it.active }.associate { it.name.lowercase() to it.slot }
        val fresh = local.freshUnsent()
        val unknown = fresh.values.flatMap { it.keys }.distinct().filter { it.lowercase() !in slots }
        val needsFw = !FirmwareCheck.atLeast(fw, "2.4.6")
        _sync.value = _sync.value.copy(notOnCounter = unknown, needsFirmware = needsFw && local.unsentTotal() > 0)
        if (needsFw) return

        val batch = local.pending ?: run {
            val sendable = fresh.mapValues { (_, m) -> m.filterKeys { it.lowercase() in slots } }.filterValues { it.isNotEmpty() }
            if (sendable.isEmpty()) return
            Batch(UUID.randomUUID().toString().replace("-", "").take(12), id, sendable).also {
                local.pending = it
                local.save()
            }
        }
        val days = mergeDays(batch, slots, s.people.count { !it.isGuest })
        _sync.value = _sync.value.copy(sending = true)
        try {
            a.merge(batch.id, days)  // 0 added = this batch was already added (a resend): fine
            local.markSent(batch)
            lastHistoryAt = 0  // read the counter's new totals now
            _sync.value = _sync.value.copy(lastAdded = batch.total, lastSyncAt = System.currentTimeMillis())
            if (batch.total > 0) _messages.tryEmit("Added ${batch.total} japs from this phone to the counter")
            chanter.refresh()
            JapWidget.refreshAll(getApplication())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Kept as pending: sent again with the same id next time.
        } finally {
            _sync.value = _sync.value.copy(sending = false, unsent = local.unsentTotal())
        }
    }

    fun addPersonToCounter(name: String) = command("$name added to the counter") { it.addPerson(name) }

    // ------------------------------------------------------- firmware update

    fun checkFirmware() {
        viewModelScope.launch {
            val a = api() ?: return@launch
            _update.value = FirmwareUpdate(checking = true)
            try {
                val st = a.otaStatus()
                val offer = FirmwareCheck.check(st.optString("repo"), st.optString("branch"), st.optString("board"), st.optString("version"))
                _update.value = FirmwareUpdate(offer = offer,
                    message = if (offer.newer) null else "The counter has the latest firmware (${st.optString("version")})")
            } catch (e: Exception) {
                _update.value = FirmwareUpdate(message = friendly(e, "Could not check for an update"))
            }
        }
    }

    fun showUpdatePin() = command("Look at the counter's screen for the PIN") { it.otaShowPin() }

    /** Starts the update with the PIN from the counter's screen, then waits for the new version. */
    fun installFirmware(pin: String) {
        viewModelScope.launch {
            val a = api() ?: return@launch
            val before = _firmware.value
            _update.value = _update.value.copy(installing = true, message = "Starting the update…")
            try {
                a.otaInstall(pin.trim())
            } catch (e: Exception) {
                _update.value = _update.value.copy(installing = false, message = friendly(e))
                return@launch
            }
            _update.value = _update.value.copy(message = "The counter is downloading the update. This takes 1 to 3 minutes. Keep it switched on.")
            val deadline = System.currentTimeMillis() + 6 * 60_000L
            while (System.currentTimeMillis() < deadline) {
                delay(5000)
                val fw = runCatching { a.info().firmware }.getOrNull() ?: continue
                if (fw != before) {
                    _firmware.value = fw
                    _update.value = FirmwareUpdate(message = "Updated to $fw")
                    return@launch
                }
                val msg = runCatching { a.otaStatus().optString("message") }.getOrNull()
                if (msg != null && (msg.contains("fail", true) || msg.contains("rejected", true) || msg.contains("no wifi", true))) {
                    _update.value = FirmwareUpdate(message = "Update did not finish: $msg. The counter kept its old firmware; try again.")
                    return@launch
                }
            }
            _update.value = FirmwareUpdate(message = "No answer from the counter yet. Check its screen, then press Check again.")
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

    fun say(message: String) {
        _messages.tryEmit(message)
    }

    private suspend fun onDevice(block: suspend (DeviceApi) -> Unit): Boolean {
        val a = api()
        if (a == null) {
            _messages.tryEmit("Connect to the counter first")
            return false
        }
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
        stopScan()  // the chanter keeps running (background listening)
    }

    private companion object {
        const val POLL_MS = 1000L
        const val POLL_OFFLINE_MS = 3000L
        const val OFFLINE_AFTER_FAILURES = 3
        const val SCAN_DURATION_MS = 15_000L
        const val SEEN_SAVE_MS = 60_000L
        const val HISTORY_EVERY_MS = 5 * 60_000L
        const val CACHE_SAVE_MS = 30_000L
    }
}
