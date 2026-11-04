package tv.own.owntv.player

import kotlin.math.abs

/**
 * A video's frame rate measured from the presentation timestamps of the frames being rendered.
 *
 * Counting rendered frames against the wall clock ([FpsSample]) cannot tell 59.94 from 60 — they differ by
 * one frame in 17 seconds — so Auto frame rate picked a 60 Hz mode for 59.94 content and the panel dropped a
 * frame every 17 s. Timestamps carry the exact interval: the mean gap over a few seconds of frames resolves
 * the NTSC rates. A gap far from the median (a dropped frame) is left out, and a timestamp jump (a
 * feed that restarts its clock, the next channel) starts the window again, so neither bends the reading.
 *
 * Fed from a video frame listener on the playback thread and read on the main thread, hence synchronized.
 */
class PtsFrameRate {
    private val gaps = LongArray(WINDOW)
    private var count = 0
    private var next = 0
    private var lastUs = NONE

    @Synchronized
    fun add(presentationTimeUs: Long) {
        if (lastUs != NONE) {
            val gap = presentationTimeUs - lastUs
            if (gap in 1..MAX_GAP_US) {
                gaps[next] = gap
                next = (next + 1) % WINDOW
                if (count < WINDOW) count++
            } else if (gap in 1..MAX_STALL_US) {
                // A stall or a run of dropped frames on the same clock: the frames on either side are the same
                // video, so what was measured stays. Emptying the window here meant a stream that hiccups while
                // starting (common on IPTV) never collected MIN_SPAN_US in one go: a 60p channel read no exact
                // rate for ~40 s, and the frame counter's guess went out in the meantime.
            } else {
                // A jump: the clock restarted, or these are the next channel's frames (unrelated clock).
                // Gaps from before it belong to other video.
                count = 0
                next = 0
            }
        }
        lastUs = presentationTimeUs
    }

    /** The rate, snapped to a standard one when it is that close; null until [MIN_SPAN_US] of video is in. */
    @Synchronized
    fun rate(): Float? {
        if (count == 0) return null
        val window = gaps.copyOf(count).also { it.sort() }
        val median = window[count / 2]
        // Within a quarter of the median: a dropped frame (twice the gap) stays out; the alternating
        // 1501/1502-tick gaps of 59.94 stay in.
        val steady = window.filter { it * 4 >= median * 3 && it * 4 <= median * 5 }
        val span = steady.sum()
        if (span < MIN_SPAN_US) {
            // A fine clock (90 kHz, whole microseconds) needs no 4 s: its steady gaps sit within a few µs of
            // each other (16666/16667 at 60, 16678/16689 at 59.94), and 1.5 s of them already tells 59.94 from
            // 60. Waiting the full span mattered on a 50 Hz display showing 60p: every sixth frame is dropped,
            // the renderer falls further behind, and the 4 s never came while the display stayed wrong.
            // Only a standard rate is given early; anything else waits for the full span.
            // Only the gaps right at the median count, so a provider's odd stamp elsewhere spoils nothing.
            // Whole-millisecond stamps (16/17 ms) leave a single value there, which snaps to no rate.
            val core = steady.filter { abs(it - median) <= QUICK_JITTER_US }
            if (core.sum() < QUICK_SPAN_US) return null
            return snap((1_000_000.0 / core.average()).toFloat())
        }
        val raw = (1_000_000.0 / steady.average()).toFloat()
        return snap(raw) ?: raw
    }

    /** [raw] as the standard rate it is within [SNAP_FRACTION] of, or null when it is near none. */
    private fun snap(raw: Float): Float? {
        val nearest = STANDARD_RATES.minByOrNull { abs(it - raw) } ?: return null
        return nearest.takeIf { abs(it - raw) <= it * SNAP_FRACTION }
    }

    @Synchronized
    fun reset() {
        count = 0
        next = 0
        lastUs = NONE
    }

    private companion object {
        const val WINDOW = 600
        /**
         * Some providers stamp frames to the whole millisecond (gaps of 16 and 17 ms at 59.94). Rounding
         * does not add up across frames — the error is at most 1 ms over the whole span — so 4 s holds it to
         * 0.025 %, a quarter of the 0.1 % between 59.94 and 60. One second could not tell them apart.
         */
        const val MIN_SPAN_US = 4_000_000L
        /** The span a fine clock needs ([QUICK_JITTER_US]): 0.003 % over it, far inside the 0.1 % from 59.94 to 60. */
        const val QUICK_SPAN_US = 1_500_000L
        /** Steady gaps this close together are whole-µs stamps of a 90 kHz clock; ms-rounded stamps differ by ~1000. */
        const val QUICK_JITTER_US = 20L
        /** Longer than any real frame interval (5 fps); a bigger gap is a jump, not a frame. */
        const val MAX_GAP_US = 200_000L
        /** Up to this far ahead a jump is a stall on the same clock and is stepped over; beyond, or backwards, a new clock. */
        const val MAX_STALL_US = 5_000_000L
        const val NONE = Long.MIN_VALUE
        /** 59.94 and 60 are 0.1 % apart; over [MIN_SPAN_US] a reading is within 0.025 % of the true rate. */
        const val SNAP_FRACTION = 0.0004f
        val STANDARD_RATES = floatArrayOf(
            23.976f, 24f, 25f, 29.97f, 30f, 47.952f, 48f, 50f, 59.94f, 60f, 100f, 119.88f, 120f,
        )
    }
}
