package com.naamjap.counter.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** An error the device reported, with a message fit to show the user. */
class DeviceApiException(message: String) : Exception(message)

/** HTTP client for the counter's JSON API (see src/web.cpp in the firmware). */
class DeviceApi(val host: String) {

    val baseUrl = "http://$host"

    suspend fun info(): DeviceInfo = io {
        val j = JSONObject(request("GET", "/api/info"))
        if (j.optString("app") != "japcounter") throw DeviceApiException("This is not a Naam Jap Counter")
        DeviceInfo(j.getString("id"), j.optString("fw"), j.optInt("maxPeople", 5))
    }

    suspend fun status(): DeviceStatus = io { DeviceStatus.parse(JSONObject(request("GET", "/api/status"))) }

    suspend fun history(): List<HistoryDay> = io { HistoryDay.parseList(JSONArray(request("GET", "/api/history"))) }

    suspend fun historyCsv(): String = io { request("GET", "/history.csv") }

    suspend fun togglePause() = post("/api/pause")

    suspend fun addCount(slot: Int, n: Int) = post("/api/count", "slot" to slot, "op" to "add", "n" to n)

    suspend fun setCount(slot: Int, n: Long) = post("/api/count", "slot" to slot, "op" to "set", "n" to n)

    suspend fun addPerson(name: String) = post("/api/person", "action" to "add", "name" to name)

    suspend fun renamePerson(slot: Int, name: String) =
        post("/api/person", "slot" to slot, "action" to "name", "name" to name)

    /** action: train | more | stop | clearvoice | remove */
    suspend fun personAction(slot: Int, action: String) = post("/api/person", "slot" to slot, "action" to action)

    suspend fun saveSettings(s: DeviceSettings) = post(
        "/api/settings",
        "sens" to s.sensitivity, "minv" to s.minVoiceMs, "gap" to s.minGapMs, "bright" to s.brightness,
        "strict" to s.strictness, "smooth" to if (s.smoothing) 1 else 0, "tchants" to s.trainChants,
        "wname" to s.wordName, "wstrict" to s.wordStrictness, "wmode" to 1,
    )

    suspend fun resetAllCounts() = post("/api/reset", "what" to "all")

    suspend fun forgetWifi() = post("/api/forget")

    private suspend fun post(path: String, vararg params: Pair<String, Any>) {
        io {
            val body = params.joinToString("&") { (k, v) ->
                URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v.toString(), "UTF-8")
            }
            val j = JSONObject(request("POST", path, body))
            if (!j.optBoolean("ok")) throw DeviceApiException(j.optString("error", "The device refused the request"))
        }
    }

    private fun request(method: String, path: String, body: String? = null): String {
        val c = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = CONNECT_TIMEOUT_MS
            c.readTimeout = READ_TIMEOUT_MS
            c.useCaches = false
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).optString("error") }.getOrNull()
                throw DeviceApiException(if (msg.isNullOrBlank()) "Device error (HTTP $code)" else msg)
            }
            return text
        } finally {
            c.disconnect()
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 3000
        const val READ_TIMEOUT_MS = 6000
    }
}
