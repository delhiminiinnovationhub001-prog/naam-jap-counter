package com.naamjap.counter

import com.naamjap.counter.data.Batch
import com.naamjap.counter.data.DeviceCache
import com.naamjap.counter.data.LocalStore
import com.naamjap.counter.data.Tally
import com.naamjap.counter.data.mergeDays
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDate

/** Phone counts, the offline copy of the counter, merging and the streak. */
class CountsTest {

    private fun store() = LocalStore(Files.createTempDirectory("jap").toFile())

    private val today = LocalDate.now().toString()
    private val yesterday = LocalDate.now().minusDays(1).toString()

    /** A counter status like /api/status: vijay, Nidhi and Guest. */
    private fun status(vijayToday: Long, nidhiToday: Long, vijayGrand: Long, nidhiGrand: Long) = JSONObject(
        """{"people":[{"n":"vijay","a":true,"t":$vijayToday,"g":$vijayGrand,"p":0,"s":20},
           {"n":"Nidhi","a":true,"t":$nidhiToday,"g":$nidhiGrand,"p":0,"s":20},
           {"n":"Guest","a":false,"t":0,"g":0,"p":0,"s":0}],
           "train":{"p":-1,"d":0,"t":0},"tAll":${vijayToday + nidhiToday},"gAll":${vijayGrand + nidhiGrand},"time":"x","timeOk":true}""",
    )

    @Test fun mergeFormatHasOneCountPerSlot() {
        val b = Batch("abc", "dev", mapOf(today to mapOf("vijay" to 5L, "Nidhi" to 2L), yesterday to mapOf("Nidhi" to 7L)))
        val s = mergeDays(b, mapOf("vijay" to 0, "nidhi" to 1), places = 2)
        assertEquals("$yesterday,0,7;$today,5,2", s)
    }

    @Test fun phoneJapsAddToTheCounterCopyUntilSent() {
        val st = store()
        st.cache = DeviceCache("dev", today, status(100, 50, 1000, 500), JSONArray(), 0)
        val t = Tally(st)
        repeat(3) { st.addPhone("vijay") }
        st.addPhone("Nidhi", 4, yesterday)
        assertEquals(103, t.today("vijay"))
        assertEquals(1003, t.grand("vijay"))
        assertEquals(504, t.grand("Nidhi"))
        assertEquals(7, st.unsentTotal())

        // Sent: the batch is no longer unsent; once the counter's new numbers arrive they include it.
        val batch = Batch("b1", "dev", st.freshUnsent())
        st.pending = batch
        assertEquals("a batch in flight is not sent twice", 0, st.freshUnsent().size)
        st.markSent(batch)
        assertEquals(0, st.unsentTotal())
        st.cache = DeviceCache("dev", today, status(103, 50, 1003, 504), JSONArray(), 0)
        assertEquals(103, t.today("vijay"))
        assertEquals(1003, t.grand("vijay"))
    }

    @Test fun historyCombinesCounterDaysAndPhoneDays() {
        val st = store()
        val hist = JSONArray("""[{"d":"$yesterday","c":[200,10,0]}]""")
        st.cache = DeviceCache("dev", today, status(5, 0, 205, 10), hist, 0)
        st.addPhone("vijay", 8, yesterday)
        val days = Tally(st).days()
        assertEquals(today, days[0].date)
        assertEquals(208L, days.first { it.date == yesterday }.counts["vijay"])
    }

    @Test fun streakCountsDaysThatReachedTheGoal() {
        val st = store()
        for (d in 0..3) st.addPhone("vijay", 108, LocalDate.now().minusDays(d.toLong()).toString())
        st.addPhone("vijay", 50, LocalDate.now().minusDays(5).toString())
        assertEquals(4, Tally(st).streak("vijay", 108))
        // today not done yet: the streak up to yesterday still shows
        val st2 = store()
        for (d in 1..2) st2.addPhone("vijay", 108, LocalDate.now().minusDays(d.toLong()).toString())
        st2.addPhone("vijay", 10)
        assertEquals(2, Tally(st2).streak("vijay", 108))
    }

    @Test fun savedAndLoaded() {
        val dir = Files.createTempDirectory("jap").toFile()
        val a = LocalStore(dir)
        a.addPhone("vijay", 12)
        a.goalMalas = 3
        a.pending = Batch("p1", "dev", mapOf(today to mapOf("vijay" to 12L)))
        a.save()
        val b = LocalStore(dir)
        assertEquals(12, b.unsent(today, "vijay"))
        assertEquals(3, b.goalMalas)
        assertEquals("p1", b.pending?.id)
    }
}
