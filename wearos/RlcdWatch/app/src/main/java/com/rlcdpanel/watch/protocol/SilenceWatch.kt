package com.rlcdpanel.watch.protocol

/**
 * Pure state machine that detects a panel which stopped to send telemetry frames.
 *
 * [check] reports [Event.SILENT] once when the last frame is older than [SILENCE_MS].
 * [onFrame] reports [Event.BACK] once for the first frame after a [Event.SILENT].
 * Without any frame since the creation nothing is reported.
 */
class SilenceWatch(private val clock: () -> Long = { System.currentTimeMillis() }) {

    enum class Event { NONE, SILENT, BACK }

    private var lastFrameMillis: Long? = null
    private var silent = false

    /** Time of the last frame, or null without a frame. */
    val lastFrameAtMillis: Long? get() = lastFrameMillis

    /** Records a received frame. Returns [Event.BACK] when the panel was silent. */
    fun onFrame(): Event {
        lastFrameMillis = clock()
        if (!silent) return Event.NONE
        silent = false
        return Event.BACK
    }

    /** Periodic check. Returns [Event.SILENT] once for each silent period. */
    fun check(): Event {
        val last = lastFrameMillis ?: return Event.NONE
        if (silent) return Event.NONE
        val now = clock()
        val age = now - last
        if (age < 0) {
            // A clock that ran back gives no report. Anchor the last frame to the new time.
            lastFrameMillis = now
            return Event.NONE
        }
        if (age <= SILENCE_MS) return Event.NONE
        silent = true
        return Event.SILENT
    }

    companion object {
        const val SILENCE_MS = 35L * 60L * 1000L
    }
}
