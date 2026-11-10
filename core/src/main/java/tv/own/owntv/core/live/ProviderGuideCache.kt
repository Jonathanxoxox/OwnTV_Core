package tv.own.owntv.core.live

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import tv.own.owntv.core.parser.XtEpgEntry
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * How long an answer stays usable at most, however well it still covers "now". A provider that
 * reschedules its day should be noticed within the hour.
 */
internal const val PROVIDER_GUIDE_MAX_AGE_MS = 60 * 60_000L

/**
 * How long "the provider is reachable but has no guide for this channel" is remembered. Shorter than
 * the age limit because an empty answer is cheap to re-ask and often only means the feed has not
 * caught up yet; long enough that scrolling past hundreds of such channels does not re-ask each one.
 */
internal const val PROVIDER_GUIDE_EMPTY_TTL_MS = 30 * 60_000L

/**
 * After a failed attempt the same channel is not asked again for this long. Without it every scroll
 * and every periodic refresh re-sent a request to a provider that is down or rate-limiting us.
 */
internal const val PROVIDER_GUIDE_BACKOFF_MS = 30_000L

/**
 * Any non-empty answer is used for at least this long without another request, whatever it covers. A
 * provider whose list ends with the programme on now, or has a gap at this hour, would otherwise be
 * asked again on every single look — the answer can never become "fresh" by the rule above.
 */
internal const val PROVIDER_GUIDE_MIN_HOLD_MS = 5 * 60_000L

/** Pause before the single retry of a request that failed on I/O — a dropped connection usually
 *  works the second time, but not if we hammer it in the same instant. */
internal const val PROVIDER_GUIDE_RETRY_DELAY_MS = 400L

/**
 * The provider's short guide per channel: one request shared by everyone asking, kept while it still
 * answers.
 *
 * Why this exists. `get_short_epg` returns about eight programmes spanning hours, and for playlists
 * whose bulk guide is empty it is the only guide there is. It used to be fetched once for the preview
 * pane and once for the grid (two caches, two requests), thrown away after five minutes even though the
 * answer still covered the current time, and — worst — a failed request was cached as "nothing is on",
 * blanking the channel for the whole five minutes. This class is the single owner of those answers.
 *
 * **Policy**
 *  - A stored answer is used with no request while one of its entries covers "now" *and* another
 *    starts after it (so Next is known), up to [PROVIDER_GUIDE_MAX_AGE_MS]; and in any case for its
 *    first [PROVIDER_GUIDE_MIN_HOLD_MS]. Otherwise a refresh is due.
 *  - A failure is never stored as an answer. It is retried once after [PROVIDER_GUIDE_RETRY_DELAY_MS]
 *    when it is an [IOException]; if it still fails the channel is left alone for
 *    [PROVIDER_GUIDE_BACKOFF_MS], and callers get the old answer if it still reaches "now" or later, else
 *    null.
 *  - An empty answer is remembered for [PROVIDER_GUIDE_EMPTY_TTL_MS]; "nobody to ask" for the age limit.
 *  - Concurrent callers for one channel share one request.
 *
 * Entries are stored **unshifted**, on the provider's own clock. The guide shift is applied by the
 * reader, so changing a shift needs no refetch; [entries] only takes it to decide freshness on the
 * right clock.
 *
 * **Cancellation.** The request runs in this cache's own [scope], not in the first caller's. A caller
 * that is cancelled (the row scrolled away) stops waiting but does not cancel the request the others
 * still await, and its answer is stored for whoever asks next.
 *
 * [fetch] throws on failure; returns null when there is nobody to ask (no stream id, no source, a
 * source type without short EPG) and an empty list when the provider has no guide for the channel.
 */
internal class ProviderGuideCache(
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val retryDelayMs: Long = PROVIDER_GUIDE_RETRY_DELAY_MS,
    private val fetch: suspend (channelId: Long) -> List<XtEpgEntry>?,
) {
    /** [entries] null = nobody to ask; empty = the provider has nothing. */
    private class Answer(val at: Long, val entries: List<XtEpgEntry>?)

    // Written from the IO dispatcher, cleared from Main — see LiveEpgReader's cache for why these
    // must be concurrent maps.
    private val answers = ConcurrentHashMap<Long, Answer>()
    private val failedAt = ConcurrentHashMap<Long, Long>()
    private val inFlight = ConcurrentHashMap<Long, Deferred<Answer?>>()

    /**
     * The provider's entries for [channelId], unshifted. Null when there is nobody to ask, or when
     * the provider could not be reached and no earlier answer still reaches "now". [shiftMinutes] is
     * the guide shift in force; it only moves "now" onto the provider's clock for the freshness check.
     */
    suspend fun entries(channelId: Long, shiftMinutes: Int = 0): List<XtEpgEntry>? {
        val now = clock()
        val providerNow = now - shiftMinutes * 60_000L
        val old = answers[channelId]
        if (old != null && isFresh(old, now, providerNow)) return old.entries

        val failed = failedAt[channelId]
        if (failed != null && now - failed < PROVIDER_GUIDE_BACKOFF_MS) return stillUseful(old, providerNow)

        val answer = join(channelId).await() ?: return stillUseful(old, providerNow)
        return answer.entries
    }

    /** What is held for [channelId], unshifted, without ever asking. Null when nothing usable is held. */
    fun peek(channelId: Long): List<XtEpgEntry>? {
        val a = answers[channelId] ?: return null
        return if (clock() - a.at < PROVIDER_GUIDE_MAX_AGE_MS) a.entries else null
    }

    /** Forget [channelId]'s answer and any backoff, so the next ask goes to the provider. */
    fun invalidate(channelId: Long) {
        answers.remove(channelId)
        failedAt.remove(channelId)
    }

    fun clear() {
        answers.clear()
        failedAt.clear()
    }

    private fun isFresh(a: Answer, now: Long, providerNow: Long): Boolean {
        val age = now - a.at
        val list = a.entries ?: return age < PROVIDER_GUIDE_MAX_AGE_MS
        if (list.isEmpty()) return age < PROVIDER_GUIDE_EMPTY_TTL_MS
        if (age < PROVIDER_GUIDE_MIN_HOLD_MS) return true
        return age < PROVIDER_GUIDE_MAX_AGE_MS &&
            list.any { it.startMs <= providerNow && it.stopMs > providerNow } &&
            list.any { it.startMs > providerNow }
    }

    /** The old answer's entries when it still reaches "now" or later; otherwise nothing worth showing. */
    private fun stillUseful(old: Answer?, providerNow: Long): List<XtEpgEntry>? =
        old?.entries?.takeIf { list -> list.any { it.stopMs > providerNow } }

    /** The request in flight for [channelId], starting one when there is none. */
    private fun join(channelId: Long): Deferred<Answer?> {
        // LAZY, then putIfAbsent, then start: the body removes itself from the map when it ends, so it
        // must not be able to run before it has been put there.
        val mine = scope.async(start = CoroutineStart.LAZY) { load(channelId) }
        val existing = inFlight.putIfAbsent(channelId, mine)
        if (existing != null) {
            mine.cancel()
            return existing
        }
        mine.start()
        return mine
    }

    /** One shared attempt: fetch, retry once on I/O, store a success, record a failure. Null = failed. */
    private suspend fun load(channelId: Long): Answer? {
        try {
            val result = try {
                fetch(channelId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                delay(retryDelayMs)
                fetch(channelId)
            }
            val answer = Answer(clock(), result)
            answers[channelId] = answer
            failedAt.remove(channelId)
            return answer
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failedAt[channelId] = clock()
            return null
        } finally {
            inFlight.remove(channelId)
        }
    }
}
