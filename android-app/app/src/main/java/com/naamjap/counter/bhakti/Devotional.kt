package com.naamjap.counter.bhakti

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Aarti(val id: String, val deity: String, val titleHi: String, val titleEn: String, val hi: List<String>, val en: List<String>)

data class Verse(val chapter: Int, val n: String, val sa: String, val iast: String, val hi: String, val en: String)

data class Chapter(val n: Int, val titleSa: String, val titleEn: String, val verses: List<Verse>)

/**
 * Aartis and the Bhagavad Gita, shipped with the app (assets: aartis.json and gita.json, built by
 * tools/build_devotional.py from the MIT-licensed hindu-devotional-texts:
 * Sanskrit from the public-domain gita/gita data, English by Shri Purohit Swami
 * (1935, public domain), Hindi meanings written for that package).
 * Everything works offline.
 */
object Devotional {
    @Volatile private var aartis: List<Aarti>? = null
    @Volatile private var gita: List<Chapter>? = null
    var source: String = ""
        private set

    fun aartis(context: Context): List<Aarti> = aartis ?: synchronized(this) {
        aartis ?: parseAartis(read(context, "aartis.json")).also { aartis = it }
    }

    fun gita(context: Context): List<Chapter> = gita ?: synchronized(this) {
        gita ?: parseGita(read(context, "gita.json")).also { gita = it }
    }

    private fun read(context: Context, name: String) = context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun strings(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.getString(it) }

    fun parseAartis(text: String): List<Aarti> {
        val j = JSONObject(text)
        val arr = j.getJSONArray("aartis")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Aarti(o.getString("id"), o.optString("deity"), o.optString("titleHi"), o.optString("titleEn"),
                strings(o.optJSONArray("hi")), strings(o.optJSONArray("en")))
        }
    }

    fun parseGita(text: String): List<Chapter> {
        val j = JSONObject(text)
        source = j.optString("source")
        val arr = j.getJSONArray("chapters")
        return (0 until arr.length()).map { i ->
            val c = arr.getJSONObject(i)
            val n = c.getInt("n")
            val vs = c.getJSONArray("verses")
            Chapter(n, c.optString("titleSa"), c.optString("titleEn"), (0 until vs.length()).map { k ->
                val v = vs.getJSONObject(k)
                Verse(n, v.getString("n"), v.getString("sa"), v.optString("iast"), v.optString("hi"), v.optString("en"))
            })
        }
    }

    /** Text for the speech engine: the Hindi voice reads Devanagari Sanskrit; dandas become pauses. */
    fun forSpeech(s: String): String = s
        .replace(Regex("[॥।|]+\\s*[\\d.]*\\s*[॥।|]*"), ". ")
        .replace(Regex("\\b(x\\d)\\b"), "")
        .replace('\n', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
}
