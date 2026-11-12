package com.naamjap.counter

import com.naamjap.counter.data.LocalStore
import com.naamjap.counter.phone.ChantMode
import com.naamjap.counter.phone.Chanter
import com.naamjap.counter.phone.Milestone
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.yield
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * What the app does when it opens: creates the phone's chanting state from
 * the saved data. (2.0.0 crashed here: a field was read before it was set.)
 */
class StartupTest {
    @Test fun opensWithNoDataAndWithSavedData() {
        val dir = Files.createTempDirectory("jap").toFile()
        val first = Chanter(LocalStore(dir)) {}
        assertEquals(ChantMode.Voice, first.state.value.mode)
        assertEquals(null, first.state.value.me)

        first.pickMe("vijay")
        repeat(5) { first.tap() }
        assertTrue(first.untap())
        first.close()

        // opened again: the saved name and counts are there
        val again = Chanter(LocalStore(dir)) {}
        assertEquals("vijay", again.state.value.me)
        assertEquals(4L, again.tally.today("vijay"))
        again.close()
    }

    @Test fun celebratesEveryHundred() = runBlocking {
        val c = Chanter(LocalStore(Files.createTempDirectory("jap").toFile())) {}
        c.pickMe("vijay")
        val got = mutableListOf<Milestone>()
        repeat(99) { c.tap() }
        val wait = async { withTimeout(2000) { c.milestones.first() } }
        yield()
        c.tap()
        got.add(wait.await())
        assertEquals(100L, got[0].count)
        assertEquals(false, got[0].big)
        c.close()
    }
}
