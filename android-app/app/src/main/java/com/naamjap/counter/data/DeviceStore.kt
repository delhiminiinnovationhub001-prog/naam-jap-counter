package com.naamjap.counter.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SavedDevice(val id: String, val host: String, val lastSeen: Long)

/** Remembers counters the user has connected to, and which one is selected. */
class DeviceStore(context: Context) {

    private val prefs = context.getSharedPreferences("devices", Context.MODE_PRIVATE)

    fun all(): List<SavedDevice> {
        val arr = runCatching { JSONArray(prefs.getString(KEY_DEVICES, "[]")) }.getOrElse { JSONArray() }
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SavedDevice(o.getString("id"), o.getString("host"), o.optLong("seen"))
        }.sortedByDescending { it.lastSeen }
    }

    fun get(id: String): SavedDevice? = all().firstOrNull { it.id == id }

    fun save(device: SavedDevice) = write(all().filter { it.id != device.id } + device)

    fun remove(id: String) {
        write(all().filter { it.id != id })
        if (selectedId == id) selectedId = null
    }

    /** Stores a new address for a known device (e.g. after the router changed it). */
    fun updateHost(id: String, host: String): Boolean {
        val d = get(id) ?: return false
        if (d.host == host) return false
        save(d.copy(host = host))
        return true
    }

    var selectedId: String?
        get() = prefs.getString(KEY_SELECTED, null)
        set(value) = prefs.edit().putString(KEY_SELECTED, value).apply()

    fun selected(): SavedDevice? = selectedId?.let { get(it) }

    private fun write(list: List<SavedDevice>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("host", it.host).put("seen", it.lastSeen)) }
        prefs.edit().putString(KEY_DEVICES, arr.toString()).apply()
    }

    private companion object {
        const val KEY_DEVICES = "devices"
        const val KEY_SELECTED = "selected"
    }
}
