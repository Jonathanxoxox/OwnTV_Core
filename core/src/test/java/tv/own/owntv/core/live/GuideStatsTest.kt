package tv.own.owntv.core.live

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tv.own.owntv.core.live.GuideStats.Outcome

class GuideStatsTest {

    @Before @After
    fun clean() = GuideStats.reset()

    private fun line(source: String) = GuideStats.lines.value.single { it.source == source }

    @Test
    fun countsOutcomesAndAveragesDurations() {
        GuideStats.record(GuideStats.SOURCE_PROVIDER, 100, Outcome.OK)
        GuideStats.record(GuideStats.SOURCE_PROVIDER, 300, Outcome.EMPTY)
        GuideStats.record(GuideStats.SOURCE_PROVIDER, 200, Outcome.FAILED)
        awaitLine(GuideStats.SOURCE_PROVIDER) { it.requests == 3 }
        val l = line(GuideStats.SOURCE_PROVIDER)
        assertEquals(1, l.ok)
        assertEquals(1, l.empty)
        assertEquals(1, l.failed)
        assertEquals(200L, l.avgMs)
        assertEquals(300L, l.maxMs)
        assertNotNull(l.lastFailureAt)
    }

    @Test
    fun servedAnswersAreNotRequests() {
        GuideStats.served(GuideStats.SOURCE_PROVIDER)
        GuideStats.served(GuideStats.SOURCE_PROVIDER)
        awaitLine(GuideStats.SOURCE_PROVIDER) { it.served == 2 }
        val l = line(GuideStats.SOURCE_PROVIDER)
        assertEquals(0, l.requests)
        assertEquals(0L, l.avgMs)
        assertNull(l.lastFailureAt)
    }

    @Test
    fun linesAreSortedBySourceAndResetClears() {
        GuideStats.record(GuideStats.SOURCE_PROVIDER, 10, Outcome.OK)
        GuideStats.record(GuideStats.SOURCE_MAGENTA, 10, Outcome.OK)
        val deadline = System.currentTimeMillis() + 5_000
        while (GuideStats.lines.value.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(listOf("magenta", "provider"), GuideStats.lines.value.map { it.source })
        GuideStats.reset()
        assertTrue(GuideStats.lines.value.isEmpty())
    }

    /** The flow is refreshed at most about once a second, with a trailing refresh: wait for it. */
    private fun awaitLine(source: String, ok: (GuideStats.Line) -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (GuideStats.lines.value.any { it.source == source && ok(it) }) return
            Thread.sleep(20)
        }
    }
}
