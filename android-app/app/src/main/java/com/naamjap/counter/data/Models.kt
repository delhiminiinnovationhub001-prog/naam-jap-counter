package com.naamjap.counter.data

import org.json.JSONArray
import org.json.JSONObject

/** A trained voice needs at least this many chants (same as the firmware). */
const val MIN_VOICE_SAMPLES = 5

data class DeviceInfo(val id: String, val firmware: String, val maxPeople: Int)

data class Person(
    val slot: Int,
    val name: String,
    val active: Boolean,
    val today: Long,
    val grand: Long,
    val prev: Long,
    val samples: Int,
    /** The counter's last slot collects japs that matched no trained voice. */
    val isGuest: Boolean = false,
) {
    val voiceTrained get() = samples >= MIN_VOICE_SAMPLES
    val label get() = if (isGuest) "Guest" else name.ifBlank { "Person ${slot + 1}" }
}

data class Training(val slot: Int, val done: Int, val target: Int) {
    val active get() = slot >= 0
    val progress get() = if (target > 0) done.toFloat() / target else 0f
}

data class DeviceSettings(
    val sensitivity: Int,
    val minVoiceMs: Int,
    val minGapMs: Int,
    val brightness: Int,
    val strictness: Int,
    val smoothing: Boolean,
    val trainChants: Int,
    val wordName: String,
    val wordStrictness: Int,
)

/** The taught word (firmware 2.1+): only this word is counted. */
data class WordState(
    val name: String,
    val ready: Boolean,         // someone has taught it
    val active: Boolean,        // counting it right now (not paused for training)
    val recentScore: Float,     // closest match in the last second, -1 if none
    val threshold: Float,       // scores at or below this count
    val patterns: List<Int>,    // templates per person slot
)

data class DeviceStatus(
    val people: List<Person>,
    val currentSlot: Int,
    val confidence: Int,
    val training: Training,
    val todayAll: Long,
    val grandAll: Long,
    val prevAll: Long,
    val paused: Boolean,
    val voice: Boolean,
    val levelDb: Float,
    val thresholdDb: Float,
    val time: String,
    val timeOk: Boolean,
    val wifi: Boolean,
    val ssid: String,
    val ip: String,
    val rssi: Int,
    val sd: Boolean,
    val uptimeSec: Long,
    val settings: DeviceSettings,
    val word: WordState,
    val ignoredNoises: Long,
    val micState: String,  // ok, nosignal, stuckhigh, starting
    val together: Boolean = false,                 // firmware 2.4.1+: every word counts for everyone
    val fastTraining: Training = Training(-1, 0, 0),  // "teach fast chanting" in progress
    val fastPatterns: List<Int> = emptyList(),
) {
    val micFault get() = micState == "nosignal" || micState == "stuckhigh"
    val guestSlot get() = people.size - 1
    val places get() = people.count { !it.isGuest }
    val hasPeople get() = people.any { !it.isGuest && it.active }
    val freeSlots get() = people.count { !it.isGuest && !it.active }

    /** Person shown on the live screen, or null for the combined "everyone" view. */
    val featured: Person?
        get() {
            val slot = if (training.active) training.slot else currentSlot
            if (slot !in people.indices) return null
            if (!hasPeople && people[slot].isGuest) return null
            return people[slot]
        }

    companion object {
        fun parse(j: JSONObject): DeviceStatus {
            val arr = j.getJSONArray("people")
            val people = (0 until arr.length()).map { i ->
                val p = arr.getJSONObject(i)
                Person(
                    slot = i,
                    name = p.optString("n"),
                    active = p.optBoolean("a"),
                    today = p.optLong("t"),
                    grand = p.optLong("g"),
                    prev = p.optLong("p"),
                    samples = p.optInt("s"),
                    isGuest = i == arr.length() - 1,
                )
            }
            val t = j.getJSONObject("train")
            val w = j.optJSONObject("word")
            val wt = w?.optJSONArray("t")
            val word = WordState(
                name = w?.optString("name").orEmpty().ifBlank { "Radha" },
                ready = w?.optBoolean("ready") ?: false,
                active = w?.optBoolean("active") ?: false,
                recentScore = (w?.optDouble("score", -1.0) ?: -1.0).toFloat(),
                threshold = (w?.optDouble("thr", -1.0) ?: -1.0).toFloat(),
                patterns = (0 until (wt?.length() ?: 0)).map { wt!!.optInt(it) },
            )
            val wf = w?.optJSONArray("f")
            val fast = w?.optJSONObject("fast")
            return DeviceStatus(
                people = people,
                currentSlot = j.optInt("cur", -1),
                confidence = j.optInt("conf"),
                training = Training(t.optInt("p", -1), t.optInt("d"), t.optInt("t")),
                todayAll = j.optLong("tAll"),
                grandAll = j.optLong("gAll"),
                prevAll = j.optLong("pAll"),
                paused = j.optBoolean("paused"),
                voice = j.optBoolean("voice"),
                levelDb = j.optDouble("level", -100.0).toFloat(),
                thresholdDb = j.optDouble("thr", -60.0).toFloat(),
                time = j.optString("time"),
                timeOk = j.optBoolean("timeOk"),
                wifi = j.optBoolean("wifi"),
                ssid = j.optString("ssid"),
                ip = j.optString("ip"),
                rssi = j.optInt("rssi"),
                sd = j.optBoolean("sd"),
                uptimeSec = j.optLong("uptime"),
                settings = DeviceSettings(
                    sensitivity = j.optInt("sens", 5),
                    minVoiceMs = j.optInt("minv", 150),
                    minGapMs = j.optInt("gap", 350),
                    brightness = j.optInt("bright", 100),
                    strictness = j.optInt("strict", 5),
                    smoothing = j.optBoolean("smooth", true),
                    trainChants = j.optInt("tchants", 20),
                    wordName = word.name,
                    wordStrictness = w?.optInt("strict", 5) ?: 5,
                ),
                word = word,
                ignoredNoises = j.optLong("rej"),
                micState = j.optString("mic", "ok"),
                together = j.optBoolean("tog"),
                fastTraining = Training(fast?.optInt("p", -1) ?: -1, fast?.optInt("d") ?: 0, fast?.optInt("t") ?: 0),
                fastPatterns = (0 until (wf?.length() ?: 0)).map { wf!!.optInt(it) },
            )
        }
    }
}

/** One finished day: date as YYYY-MM-DD and a count per slot (people + guest). */
data class HistoryDay(val date: String, val counts: List<Long>) {
    val total get() = counts.sum()
    val displayDate get() = date.split('-').reversed().joinToString("-")

    companion object {
        fun parseList(arr: JSONArray): List<HistoryDay> = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val c = o.getJSONArray("c")
            HistoryDay(o.getString("d"), (0 until c.length()).map { c.getLong(it) })
        }
    }
}
