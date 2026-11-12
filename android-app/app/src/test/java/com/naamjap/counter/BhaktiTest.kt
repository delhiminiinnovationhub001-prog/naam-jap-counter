package com.naamjap.counter

import com.naamjap.counter.bhakti.Devotional
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The Aarti and Gita data shipped in the app. */
class BhaktiTest {
    private fun asset(name: String) = File("src/main/assets/$name").readText(Charsets.UTF_8)

    @Test fun gitaHasEveryVerseWithSanskritAndHindi() {
        val g = Devotional.parseGita(asset("gita.json"))
        assertEquals(18, g.size)
        assertEquals(701, g.sumOf { it.verses.size })
        g.forEach { c -> c.verses.forEach { v ->
            assertTrue("${c.n}.${v.n} Sanskrit", v.sa.isNotBlank())
            assertTrue("${c.n}.${v.n} Hindi", v.hi.isNotBlank())
        } }
        val v = g[1].verses.first { it.n == "47" }
        assertTrue(v.sa.startsWith("कर्मण्येवाधिकार"))
    }

    @Test fun aartisHaveText() {
        val a = Devotional.parseAartis(asset("aartis.json"))
        assertTrue(a.size >= 19)
        assertEquals("krishna", a[0].deity)
        a.forEach { assertTrue(it.id, it.hi.isNotEmpty() || it.en.isNotEmpty()) }
    }

    @Test fun speechTextDropsVerseMarks() {
        val s = Devotional.forSpeech("धर्मक्षेत्रे कुरुक्षेत्रे।\nसमवेता युयुत्सवः॥1.1॥")
        assertFalse(s.contains("॥") || s.contains("1.1") || s.contains("\n"))
        assertTrue(s.startsWith("धर्मक्षेत्रे कुरुक्षेत्रे."))
    }
}
