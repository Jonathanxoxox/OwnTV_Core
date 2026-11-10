package tv.own.owntv.core.live

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.parser.XtEpgEntry
import java.io.IOException

class ProviderGuideCacheTest {

    private val minute = 60_000L
    private val hour = 60 * minute

    private fun e(startMin: Long, stopMin: Long, title: String = "p$startMin") =
        XtEpgEntry(title = title, description = null, startMs = startMin * minute, stopMs = stopMin * minute)

    /** Covers minute 10 and starts something at minute 60. */
    private val covering = listOf(e(0, 60), e(60, 120))

    private class Harness(scope: TestScope, var answer: () -> List<XtEpgEntry>?) {
        var now = 10 * 60_000L
        var calls = 0
        val cache = ProviderGuideCache(
            clock = { now },
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler)),
            fetch = { calls++; answer() },
        )
    }

    @Test
    fun freshAnswerIsFetchedOnce() = runTest {
        val h = Harness(this) { covering }
        assertEquals(covering, h.cache.entries(1))
        assertEquals(covering, h.cache.entries(1))
        h.now += 20 * minute
        assertEquals(covering, h.cache.entries(1))
        assertEquals(1, h.calls)
    }

    @Test
    fun answerThatNoLongerCoversNowIsRefetched() = runTest {
        val h = Harness(this) { covering }
        h.cache.entries(1)
        h.now = 65 * minute // the first programme is over; one still runs but nothing follows
        val newer = listOf(e(60, 120), e(120, 180))
        h.answer = { newer }
        assertEquals(newer, h.cache.entries(1))
        assertEquals(2, h.calls)
    }

    @Test
    fun answerWithNothingAfterNowIsRefetched() = runTest {
        val h = Harness(this) { listOf(e(0, 60)) }
        h.cache.entries(1)
        h.cache.entries(1)
        assertEquals(2, h.calls)
    }

    @Test
    fun hardAgeLimitForcesARefresh() = runTest {
        val long = listOf(e(0, 600), e(600, 700))
        val h = Harness(this) { long }
        h.cache.entries(1)
        h.now += hour - 1
        h.cache.entries(1)
        assertEquals(1, h.calls)
        h.now += 1
        h.cache.entries(1)
        assertEquals(2, h.calls)
    }

    @Test
    fun shiftMovesNowOntoTheProvidersClock() = runTest {
        val h = Harness(this) { covering }
        h.cache.entries(1)
        // Shifted by +60 min, "now" is minute -50 on the provider's clock: nothing covers it.
        h.cache.entries(1, shiftMinutes = 60)
        assertEquals(2, h.calls)
    }

    @Test
    fun failureThenSuccessIsRetriedOnce() = runTest {
        var attempt = 0
        val h = Harness(this) { if (attempt++ == 0) throw IOException("reset") else covering }
        assertEquals(covering, h.cache.entries(1))
        assertEquals(2, h.calls)
    }

    @Test
    fun nonIoFailureIsNotRetried() = runTest {
        val h = Harness(this) { throw IllegalStateException("bad json") }
        assertNull(h.cache.entries(1))
        assertEquals(1, h.calls)
    }

    @Test
    fun failureIsNotStoredAndBacksOffThirtySeconds() = runTest {
        val h = Harness(this) { throw IOException("down") }
        assertNull(h.cache.entries(1))
        assertEquals(2, h.calls) // first try + one retry
        h.now += PROVIDER_GUIDE_BACKOFF_MS - 1
        assertNull(h.cache.entries(1))
        assertEquals(2, h.calls)
        assertNull(h.cache.peek(1))
        h.now += 1
        h.answer = { covering }
        assertEquals(covering, h.cache.entries(1))
        assertEquals(3, h.calls)
    }

    @Test
    fun failureKeepsAnOldAnswerThatStillCoversNow() = runTest {
        val h = Harness(this) { covering }
        h.cache.entries(1)
        h.now = 70 * minute // refresh due: nothing follows the programme running now
        h.answer = { throw IOException("down") }
        assertEquals(covering, h.cache.entries(1))
        // Inside the backoff the old answer keeps being served without a request.
        val calls = h.calls
        assertEquals(covering, h.cache.entries(1))
        assertEquals(calls, h.calls)
    }

    @Test
    fun failureDropsAnOldAnswerThatIsOver() = runTest {
        val h = Harness(this) { covering }
        h.cache.entries(1)
        h.now = 130 * minute
        h.answer = { throw IOException("down") }
        assertNull(h.cache.entries(1))
    }

    @Test
    fun emptyAnswerIsRememberedThirtyMinutes() = runTest {
        val h = Harness(this) { emptyList() }
        assertEquals(emptyList<XtEpgEntry>(), h.cache.entries(1))
        h.now += PROVIDER_GUIDE_EMPTY_TTL_MS - 1
        h.cache.entries(1)
        assertEquals(1, h.calls)
        h.now += 1
        h.cache.entries(1)
        assertEquals(2, h.calls)
    }

    @Test
    fun nobodyToAskIsNullNotEmptyAndRememberedForTheAgeLimit() = runTest {
        val h = Harness(this) { null }
        assertNull(h.cache.entries(1))
        h.now += PROVIDER_GUIDE_EMPTY_TTL_MS + minute
        assertNull(h.cache.entries(1))
        assertEquals(1, h.calls)
        h.now += hour
        h.cache.entries(1)
        assertEquals(2, h.calls)

        val empty = Harness(this) { emptyList() }
        assertNotNull(empty.cache.entries(1))
    }

    @Test
    fun concurrentCallersShareOneRequest() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = Harness(this) { covering }
        val gated = ProviderGuideCache(
            clock = { h.now },
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
            fetch = { h.calls++; gate.await(); covering },
        )
        val a = async { gated.entries(1) }
        val b = async { gated.entries(1) }
        runCurrent()
        assertEquals(1, h.calls)
        gate.complete(Unit)
        assertEquals(covering, a.await())
        assertEquals(covering, b.await())
        assertEquals(1, h.calls)
    }

    @Test
    fun cancellingTheFirstCallerDoesNotCancelTheSharedRequest() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val cache = ProviderGuideCache(
            clock = { 10 * minute },
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
            fetch = { calls++; gate.await(); covering },
        )
        val first = async { cache.entries(1) }
        val second = async { cache.entries(1) }
        runCurrent()
        first.cancel()
        runCurrent()
        gate.complete(Unit)
        assertEquals(covering, second.await())
        assertEquals(1, calls)
    }

    @Test
    fun invalidateForgetsOneChannelAndClearForgetsAll() = runTest {
        val h = Harness(this) { covering }
        h.cache.entries(1)
        h.cache.entries(2)
        assertEquals(2, h.calls)
        h.cache.invalidate(1)
        assertNull(h.cache.peek(1))
        assertNotNull(h.cache.peek(2))
        h.cache.entries(1)
        assertEquals(3, h.calls)
        h.cache.clear()
        assertNull(h.cache.peek(1))
        assertNull(h.cache.peek(2))
    }

    @Test
    fun peekNeverFetches() = runTest {
        val h = Harness(this) { covering }
        assertNull(h.cache.peek(1))
        assertEquals(0, h.calls)
        h.cache.entries(1)
        assertEquals(covering, h.cache.peek(1))
        assertEquals(1, h.calls)
    }
}
