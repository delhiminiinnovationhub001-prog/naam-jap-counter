package com.naamjap.counter.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class FirmwareOffer(val version: String, val notes: String, val newer: Boolean)

/**
 * Reads ota/manifest.json from the GitHub repository the counter updates from
 * (the phone has the memory for a secure connection; the counter does not
 * while it is running). The counter checks the file's signature itself.
 */
object FirmwareCheck {

    suspend fun check(repo: String, branch: String, board: String, current: String): FirmwareOffer =
        withContext(Dispatchers.IO) {
            if (!Regex("^[\\w.-]+/[\\w.-]+$").matches(repo)) throw DeviceApiException("No update source is set on the counter")
            val url = URL("https://raw.githubusercontent.com/$repo/${branch.ifBlank { "main" }}/ota/manifest.json")
            val c = url.openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 8000
                c.readTimeout = 10000
                c.useCaches = false
                if (c.responseCode != 200) throw DeviceApiException("GitHub answered HTTP ${c.responseCode}")
                val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val version = j.optString("version")
                val hasBoard = j.optJSONObject("files")?.has(board) == true
                if (!hasBoard) throw DeviceApiException("No firmware for $board in the update file")
                FirmwareOffer(version, j.optString("notes"), isNewer(version, current))
            } catch (e: DeviceApiException) {
                throw e
            } catch (e: Exception) {
                throw DeviceApiException("Could not read the update from GitHub. Is the phone on the internet?")
            } finally {
                c.disconnect()
            }
        }

    /** "2.4.10" > "2.4.9". */
    fun isNewer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** Firmware version a feature needs, e.g. merge needs 2.4.6. */
    fun atLeast(version: String, needed: String) = !isNewer(needed, version)
}
