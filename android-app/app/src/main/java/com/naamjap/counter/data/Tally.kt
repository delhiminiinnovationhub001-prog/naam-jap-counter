package com.naamjap.counter.data

import java.time.LocalDate

/** One day in the combined history: counter + phone, per person name. */
data class TallyDay(val date: String, val counts: Map<String, Long>) {
    val total get() = counts.values.sum()
}

/**
 * Combined counts: what the counter had when the app last read it, plus the
 * japs chanted on this phone that the counter does not have yet. Works with
 * no counter at all (then it is just the phone's own counts).
 */
class Tally(private val store: LocalStore) {

    private fun status(): DeviceStatus? = store.cache?.let { runCatching { DeviceStatus.parse(it.status) }.getOrNull() }

    /** Names in the counter's slots, in slot order (people only, no guest). */
    fun deviceNames(): List<String> = status()?.let { s -> s.people.filter { !it.isGuest && it.active }.map { it.name } }.orEmpty()

    fun allNames(): List<String> {
        val out = mutableListOf<String>()
        (deviceNames() + store.names + store.phone.values.flatMap { it.keys }).forEach { n ->
            if (n.isNotBlank() && out.none { it.equals(n, true) }) out.add(n)
        }
        return out
    }

    private fun slotOf(s: DeviceStatus, name: String) =
        s.people.indexOfFirst { !it.isGuest && it.active && it.name.equals(name, true) }

    /** The counter's count for a person on a day, as last read. */
    private fun deviceDay(date: String, name: String?): Long {
        val c = store.cache ?: return 0
        val s = status() ?: return 0
        fun pick(counts: List<Long>): Long = if (name == null) counts.sum()
        else slotOf(s, name).let { if (it >= 0 && it < counts.size) counts[it] else 0 }
        var n = 0L
        if (c.date == date) n += pick(s.people.map { it.today })
        for (i in 0 until c.history.length()) {
            val o = c.history.optJSONObject(i) ?: continue
            if (o.optString("d") != date) continue
            val a = o.optJSONArray("c") ?: continue
            n += pick((0 until a.length()).map { a.optLong(it) })
        }
        return n
    }

    private fun phoneDay(date: String, name: String?): Long =
        if (name == null) store.phone[date]?.keys?.sumOf { store.unsent(date, it) } ?: 0
        else store.phone[date]?.keys?.filter { it.equals(name, true) }?.sumOf { store.unsent(date, it) } ?: 0

    /** name = null: everyone. */
    fun day(date: String, name: String?): Long = deviceDay(date, name) + phoneDay(date, name)

    fun today(name: String?) = day(store.today(), name)

    fun grand(name: String?): Long {
        val s = status()
        val device = when {
            s == null -> 0
            name == null -> s.grandAll
            else -> slotOf(s, name).let { if (it >= 0) s.people[it].grand else 0 }
        }
        val phone = store.phone.entries.sumOf { (d, m) ->
            m.keys.filter { name == null || it.equals(name, true) }.sumOf { store.unsent(d, it) }
        }
        return device + phone
    }

    /** Every day with japs, newest first. */
    fun days(): List<TallyDay> {
        val dates = sortedSetOf<String>(compareByDescending { it })
        store.cache?.let { c ->
            dates.add(c.date)
            for (i in 0 until c.history.length()) c.history.optJSONObject(i)?.optString("d")?.let { dates.add(it) }
        }
        dates.addAll(store.phone.keys)
        val names = allNames()
        return dates.map { d -> TallyDay(d, names.associateWith { day(d, it) }) }.filter { it.total > 0 }
    }

    /** Days in a row (up to today) on which [name] reached the goal; today counts once reached. */
    fun streak(name: String?, goalJaps: Long): Int {
        if (goalJaps <= 0) return 0
        var d = LocalDate.now()
        if (day(d.toString(), name) < goalJaps) d = d.minusDays(1)
        var n = 0
        while (n < 3660 && day(d.toString(), name) >= goalJaps) {
            n++
            d = d.minusDays(1)
        }
        return n
    }

    /** Totals per "YYYY-MM" or "YYYY" key, newest first. */
    fun periods(keyLen: Int): List<Pair<String, Map<String, Long>>> =
        days().groupBy { it.date.take(keyLen) }.map { (k, list) ->
            k to allNames().associateWith { n -> list.sumOf { it.counts[n] ?: 0 } }
        }.sortedByDescending { it.first }
}
