package tv.own.owntv.core.live

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Counters for the guide's network sources since the process started, so the TV itself can show which
 * source is slow or failing (a photo of the screen is all the diagnosis there is on a box without adb).
 *
 * Why counters and a lazily built list: [record] and [served] run on every guide read, from any thread.
 * They only bump atomics; [lines] is rebuilt from them on demand and the StateFlow is refreshed at most
 * about once a second, so a screen that collects it is not recomposed per request.
 */
object GuideStats {
    const val SOURCE_PROVIDER = "provider"
    const val SOURCE_MAGENTA = "magenta"

    enum class Outcome { OK, EMPTY, FAILED }

    /** One source's totals. [lastFailureAt] is wall-clock ms; [avgMs] and [maxMs] cover real requests only. */
    data class Line(
        val source: String,
        val requests: Int,
        val ok: Int,
        val empty: Int,
        val failed: Int,
        val served: Int,
        val avgMs: Long,
        val maxMs: Long,
        val lastFailureAt: Long?,
    )

    private class Counters {
        val ok = AtomicLong()
        val empty = AtomicLong()
        val failed = AtomicLong()
        val served = AtomicLong()
        val totalMs = AtomicLong()
        val maxMs = AtomicLong()
        val lastFailureAt = AtomicLong(0)
    }

    private const val REFRESH_MS = 1_000L

    private val sources = ConcurrentHashMap<String, Counters>()
    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    private val lastPublish = AtomicLong(0)
    private val trailingScheduled = AtomicBoolean(false)
    private val trailer by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "GuideStats").apply { isDaemon = true } }
    }

    /** Sorted by source name; refreshed at most about once a second while requests keep coming. */
    val lines: StateFlow<List<Line>> = _lines.asStateFlow()

    fun record(source: String, tookMs: Long, outcome: Outcome) {
        val c = sources.getOrPut(source) { Counters() }
        val took = tookMs.coerceAtLeast(0)
        when (outcome) {
            Outcome.OK -> c.ok.incrementAndGet()
            Outcome.EMPTY -> c.empty.incrementAndGet()
            Outcome.FAILED -> {
                c.failed.incrementAndGet()
                c.lastFailureAt.set(System.currentTimeMillis())
            }
        }
        c.totalMs.addAndGet(took)
        c.maxMs.accumulateAndGet(took, ::maxOf)
        publish(force = false)
    }

    fun served(source: String) {
        sources.getOrPut(source) { Counters() }.served.incrementAndGet()
        publish(force = false)
    }

    fun reset() {
        sources.clear()
        publish(force = true)
    }

    private fun publish(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPublish.get() < REFRESH_MS) {
            // Inside the quiet second: make sure one trailing refresh runs, so the last counts of a burst
            // are not left unseen until the next request.
            if (trailingScheduled.compareAndSet(false, true)) {
                trailer.schedule({ trailingScheduled.set(false); rebuild() }, REFRESH_MS, TimeUnit.MILLISECONDS)
            }
            return
        }
        rebuild()
    }

    @Synchronized
    private fun rebuild() {
        lastPublish.set(System.currentTimeMillis())
        _lines.value = sources.entries.sortedBy { it.key }.map { (name, c) ->
            val ok = c.ok.get().toInt()
            val empty = c.empty.get().toInt()
            val failed = c.failed.get().toInt()
            val requests = ok + empty + failed
            Line(
                source = name,
                requests = requests,
                ok = ok,
                empty = empty,
                failed = failed,
                served = c.served.get().toInt(),
                avgMs = if (requests == 0) 0 else c.totalMs.get() / requests,
                maxMs = c.maxMs.get(),
                lastFailureAt = c.lastFailureAt.get().takeIf { it != 0L },
            )
        }
    }
}
