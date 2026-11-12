package com.naamjap.counter

import com.naamjap.counter.data.Batch
import com.naamjap.counter.data.DeviceApi
import com.naamjap.counter.data.DeviceStatus
import com.naamjap.counter.data.mergeDays
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Talks to a real counter (only when COUNTER_HOST is set): merges +1 for the
 * first person through the app's own code, checks a resent batch is not
 * counted twice, then sets the count back.
 */
class LiveCounterTest {
    @Test fun mergeOnRealCounter() = runBlocking {
        val host = System.getenv("COUNTER_HOST")
        assumeTrue("COUNTER_HOST not set", !host.isNullOrBlank())
        val api = DeviceApi(host!!)
        val before = DeviceStatus.parse(api.statusJson())
        val p = before.people.first { !it.isGuest && it.active }
        val slots = before.people.filter { !it.isGuest && it.active }.associate { it.name.lowercase() to it.slot }
        val today = before.time.let { t -> Regex("^(\\d{2})-(\\d{2})-(\\d{4})").find(t)!!.groupValues.let { "${it[3]}-${it[2]}-${it[1]}" } }
        val batch = Batch("t" + System.currentTimeMillis().toString(36), "test", mapOf(today to mapOf(p.name to 1L)))
        val days = mergeDays(batch, slots, before.places)
        try {
            assertEquals(1L, api.merge(batch.id, days))
            assertEquals("resent batch", 0L, api.merge(batch.id, days))
            val after = DeviceStatus.parse(api.statusJson())
            assertEquals(p.today + 1, after.people[p.slot].today)
            println("merge OK: ${p.name} ${p.today} -> ${after.people[p.slot].today} ($days, phone date ${LocalDate.now()})")
        } finally {
            api.setCount(p.slot, p.today)  // put it back exactly
            val restored = DeviceStatus.parse(api.statusJson())
            assertEquals(p.today, restored.people[p.slot].today)
            println("restored: ${p.name} today ${restored.people[p.slot].today}, total ${restored.people[p.slot].grand}")
        }
    }
}
