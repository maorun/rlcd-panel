package com.rlcdpanel.phone.protocol

/**
 * Progress of a salt test: the count of stable hours of one sensor from `HISTORY N` replies.
 * Port of `SaltProgress.swift`. The caller sends `HISTORY N` every [INTERVAL_SECONDS] while [isRunning];
 * the clock ([nowMs], milliseconds) is injected for tests.
 */
class SaltProgress(private val nowMs: () -> Long = System::currentTimeMillis) {
    enum class StopReason { USER, VIEW_CLOSED, TIMEOUT, LINK_LOST, REPLY_FAILED, COMPLETED }

    var isRunning: Boolean = false
        private set
    var stopReason: StopReason? = null
        private set
    /** Stable hours of the last reply, 0 before the first reply. */
    var stableHours: Int = 0
        private set
    var replies: Int = 0
        private set
    /** True while the watch sends `HISTORY N 6`; false after an older firmware answered `ERR BAD_ARG`. */
    var usesHourWindow: Boolean = true
        private set
    private var startedAt: Long = 0

    /** Hours until [REQUIRED_HOURS] stable hours, 0 when reached. */
    val hoursRemaining: Int get() = maxOf(0, REQUIRED_HOURS - stableHours)
    val isReady: Boolean get() = stableHours >= REQUIRED_HOURS
    /** Text of the screen, for example `3 von 6 Stunden stabil`. The count stops at 6. */
    val text: String get() = "${minOf(stableHours, REQUIRED_HOURS)} von $REQUIRED_HOURS Stunden stabil"

    /** Line of one tick: 6 rows give the full count, so the short read saves BLE bytes. */
    fun historyLine(sensor: Int): String =
        if (usesHourWindow) HistoryCommand.lineHours(sensor, REQUIRED_HOURS) else HistoryCommand.line(sensor)

    /**
     * Switches once to `HISTORY N` when [replyLine] is `ERR BAD_ARG` for the short read (firmware without the hour window).
     * Returns true once, while the watch runs; the caller then repeats the tick.
     */
    fun fallBackToFullHistory(replyLine: String): Boolean {
        if (!isRunning || !usesHourWindow || !HistoryCommand.shouldFallBackFromHours(replyLine)) return false
        usesHourWindow = false
        return true
    }

    /** Starts the watch and clears the count. */
    fun start() {
        usesHourWindow = true
        stableHours = 0
        replies = 0
        startedAt = nowMs()
        stopReason = null
        isRunning = true
    }

    /** Ends the watch. The first reason stays; a stop of a stopped watch changes nothing. */
    fun stop(reason: StopReason) {
        if (!isRunning) return
        isRunning = false
        stopReason = reason
    }

    /** True while the watch runs and 8 hours are not over; stops the watch at the limit. */
    fun shouldPoll(): Boolean {
        if (!isRunning) return false
        if (nowMs() - startedAt >= MAX_DURATION_SECONDS * 1000L) {
            stop(StopReason.TIMEOUT)
            return false
        }
        return true
    }

    /** Adds one `HISTORY N` reply. Returns the new count. A stopped watch ignores the reply. */
    fun record(rows: List<PanelHistoryRow>, sensor: Int): Int {
        if (!isRunning) return stableHours
        replies += 1
        stableHours = stableHours(rows, sensor)
        shouldPoll()
        return stableHours
    }

    /**
     * Adds one console reply to `HISTORY N`. A frame that parses counts like [record]. A `Status` reply (`ERR ...`)
     * or an unreadable frame stops the watch with [StopReason.REPLY_FAILED]; a corrupt reply (timeout, bad CRC)
     * stops it with [StopReason.LINK_LOST]. Returns the stable hours.
     */
    fun record(reply: ConsoleReply, sensor: Int): Int {
        if (!isRunning) return stableHours
        when (reply) {
            is ConsoleReply.Frame -> try {
                record(HistoryReply.parse(reply.lines), sensor)
            } catch (e: ParseException) {
                stop(StopReason.REPLY_FAILED)
            }
            is ConsoleReply.Status -> stop(StopReason.REPLY_FAILED)
            is ConsoleReply.Corrupt -> stop(StopReason.LINK_LOST)
        }
        return stableHours
    }

    companion object {
        /** Stable hours that the salt check needs (same number as `SALT N`). */
        const val REQUIRED_HOURS = 6
        /** Largest humidity span of stable hours in %RH (same limit as `SALT N`). */
        const val MAX_SPAN_PCT = 1.0
        /** Seconds between two history commands (`HISTORY N 6`, or `HISTORY N` after the fallback). */
        const val INTERVAL_SECONDS = 600
        /** The watch stops after this time. */
        const val MAX_DURATION_SECONDS = 8 * 3600

        /**
         * Count of the newest hours of [sensor] whose humidity span is at most [MAX_SPAN_PCT].
         * The run starts at age 0 and ends at the first missing hour or when the span grows above the limit.
         */
        fun stableHours(rows: List<PanelHistoryRow>, sensor: Int): Int {
            val byAge = HashMap<Int, Double>()
            for (r in rows) {
                val rh = r.humidityPct
                if (r.sensor == sensor && rh != null) byAge[r.ageHours] = rh
            }
            var lo = Double.POSITIVE_INFINITY
            var hi = Double.NEGATIVE_INFINITY
            var count = 0
            while (true) {
                val rh = byAge[count] ?: break
                lo = minOf(lo, rh)
                hi = maxOf(hi, rh)
                if (hi - lo > MAX_SPAN_PCT + 1e-9) break
                count += 1
            }
            return count
        }
    }
}
