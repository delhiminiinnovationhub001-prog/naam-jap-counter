package com.naamjap.counter.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/** Counts per day ("YYYY-MM-DD") and person name. */
typealias DayCounts = MutableMap<String, MutableMap<String, Long>>

/** Japs sent to the counter in one request; resent with the same id until it answers. */
data class Batch(val id: String, val deviceId: String, val days: Map<String, Map<String, Long>>) {
    val total get() = days.values.sumOf { it.values.sum() }
}

/**
 * The counter's merge format: "YYYY-MM-DD,n,n;..." with one count per person
 * slot. [slots] maps lower-case names to the counter's slots.
 */
fun mergeDays(batch: Batch, slots: Map<String, Int>, places: Int): String =
    batch.days.entries.sortedBy { it.key }.joinToString(";") { (date, m) ->
        date + "," + (0 until places).joinToString(",") { slot ->
            m.entries.filter { slots[it.key.lowercase()] == slot }.sumOf { it.value }.toString()
        }
    }

/** The last data read from the counter, so the app works without it. */
data class DeviceCache(
    val deviceId: String,
    val date: String,          // the counter's "today" when this was read
    val status: JSONObject,
    val history: JSONArray,
    val savedAt: Long,
)

/**
 * Everything the app keeps on the phone, in one JSON file (written to a temp
 * file first, so a crash or power cut never leaves it half written).
 */
class LocalStore(dir: File) {
    constructor(context: Context) : this(context.filesDir)

    private val file = File(dir, "local.json")
    private val voiceFile = File(dir, "voice.json")
    private val lock = Any()

    var me: String? = null                 // the name picked on this phone
    val names = mutableListOf<String>()    // people known to the app
    val phone: DayCounts = mutableMapOf()  // japs counted on this phone
    val sent: DayCounts = mutableMapOf()   // of those, already added to the counter
    var pending: Batch? = null
    var cache: DeviceCache? = null
    var goalMalas = 1                      // 0 = no daily goal
    var reminderOn = false
    var reminderHour = 6
    var reminderMinute = 0
    var sensitivity = 5
    var wordName = "Radha"
    var celebrated: MutableMap<String, Long> = mutableMapOf()  // "date|name" -> last milestone shown

    init {
        load()
    }

    // --------------------------------------------------------------- counts

    fun today(): String = LocalDate.now().toString()

    fun addPhone(name: String, n: Long = 1, date: String = today()) = synchronized(lock) {
        val day = phone.getOrPut(date) { mutableMapOf() }
        day[name] = (day[name] ?: 0) + n
        save()
    }

    /** Phone japs not yet confirmed by the counter (including a batch in flight). */
    fun unsent(date: String, name: String): Long =
        ((phone[date]?.get(name) ?: 0) - (sent[date]?.get(name) ?: 0)).coerceAtLeast(0)

    fun unsentTotal(): Long = phone.entries.sumOf { (d, m) -> m.keys.sumOf { unsent(d, it) } }

    /** Unsent japs that are not in the batch in flight: what a new batch would carry. */
    fun freshUnsent(): Map<String, Map<String, Long>> {
        val out = mutableMapOf<String, MutableMap<String, Long>>()
        for ((d, m) in phone) for (name in m.keys) {
            val n = unsent(d, name) - (pending?.days?.get(d)?.get(name) ?: 0)
            if (n > 0) out.getOrPut(d) { mutableMapOf() }[name] = n
        }
        return out
    }

    fun markSent(b: Batch) = synchronized(lock) {
        for ((d, m) in b.days) for ((name, n) in m) {
            val day = sent.getOrPut(d) { mutableMapOf() }
            day[name] = (day[name] ?: 0) + n
        }
        if (pending?.id == b.id) pending = null
        save()
    }

    fun rename(from: String, to: String) = synchronized(lock) {
        fun DayCounts.move() = values.forEach { m -> m.remove(from)?.let { m[to] = (m[to] ?: 0) + it } }
        phone.move()
        sent.move()
        val i = names.indexOfFirst { it.equals(from, true) }
        if (i >= 0) names[i] = to
        if (me.equals(from, true)) me = to
        save()
    }

    fun addName(name: String) = synchronized(lock) {
        if (names.none { it.equals(name, true) }) names.add(name)
        save()
    }

    fun removeName(name: String) = synchronized(lock) {
        names.removeAll { it.equals(name, true) }
        if (me.equals(name, true)) me = null
        save()
    }

    // ---------------------------------------------------------------- voice

    fun loadVoice(): JSONObject? = runCatching { JSONObject(voiceFile.readText()) }.getOrNull()

    fun saveVoice(j: JSONObject) = writeAtomic(voiceFile, j.toString())

    // ----------------------------------------------------------------- file

    fun save() = synchronized(lock) {
        val j = JSONObject()
            .put("me", me ?: JSONObject.NULL)
            .put("names", JSONArray(names))
            .put("phone", dayJson(phone))
            .put("sent", dayJson(sent))
            .put("goal", goalMalas)
            .put("remOn", reminderOn).put("remH", reminderHour).put("remM", reminderMinute)
            .put("sens", sensitivity)
            .put("word", wordName)
            .put("cel", JSONObject(celebrated as Map<*, *>))
        pending?.let { j.put("pending", JSONObject().put("id", it.id).put("dev", it.deviceId).put("days", dayJson(it.days))) }
        cache?.let {
            j.put("cache", JSONObject().put("dev", it.deviceId).put("date", it.date).put("status", it.status)
                .put("history", it.history).put("at", it.savedAt))
        }
        writeAtomic(file, j.toString())
    }

    private fun load() {
        val j = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        me = j.optString("me").takeIf { it.isNotBlank() && !j.isNull("me") }
        j.optJSONArray("names")?.let { a -> for (i in 0 until a.length()) names.add(a.getString(i)) }
        readDays(j.optJSONObject("phone"), phone)
        readDays(j.optJSONObject("sent"), sent)
        goalMalas = j.optInt("goal", 1)
        reminderOn = j.optBoolean("remOn")
        reminderHour = j.optInt("remH", 6)
        reminderMinute = j.optInt("remM", 0)
        sensitivity = j.optInt("sens", 5)
        wordName = j.optString("word", "Radha").ifBlank { "Radha" }
        j.optJSONObject("cel")?.let { o -> o.keys().forEach { celebrated[it] = o.optLong(it) } }
        j.optJSONObject("pending")?.let { o ->
            val days: DayCounts = mutableMapOf()
            readDays(o.optJSONObject("days"), days)
            pending = Batch(o.getString("id"), o.optString("dev"), days)
        }
        j.optJSONObject("cache")?.let { o ->
            runCatching {
                cache = DeviceCache(o.getString("dev"), o.getString("date"), o.getJSONObject("status"),
                    o.optJSONArray("history") ?: JSONArray(), o.optLong("at"))
            }
        }
    }

    private fun dayJson(d: Map<String, Map<String, Long>>) = JSONObject().also { j ->
        for ((date, m) in d) j.put(date, JSONObject().also { o -> m.forEach { (k, v) -> o.put(k, v) } })
    }

    private fun readDays(o: JSONObject?, into: DayCounts) {
        o ?: return
        for (date in o.keys()) {
            val m = o.optJSONObject(date) ?: continue
            into[date] = m.keys().asSequence().associateWith { m.optLong(it) }.toMutableMap()
        }
    }

    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }
}
