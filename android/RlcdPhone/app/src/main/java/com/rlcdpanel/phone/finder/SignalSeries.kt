package com.rlcdpanel.phone.finder

/**
 * Signal series for sensor placement (port of `ScanSeries.swift`). The phone scan runs without a break. The series
 * cuts it into windows of [WINDOW_MILLIS]. Each window keeps the strongest RSSI of each MAC. A MAC without a packet
 * in a window gets one missed window. The clock is injected for tests.
 */
class SignalSeries(private val now: () -> Long) {
    enum class StopReason { USER, SCREEN_STOPPED, TIMEOUT, PERMISSION_LOST, SCAN_FAILED }

    /** RSSI statistics of one MAC over the last [MAX_VALUES] windows with a packet. */
    data class Stats(
        val mac: String,
        val min: Int,
        val mean: Double,
        val max: Int,
        /** Number of kept values (at most [MAX_VALUES]). */
        val count: Int,
        /** Closed windows without this MAC. */
        val missed: Int,
        /** All closed windows of the series. */
        val windows: Int
    )

    var isRunning: Boolean = false
        private set
    var stopReason: StopReason? = null
        private set
    var windows: Int = 0
        private set

    private val values = HashMap<String, ArrayDeque<Int>>()
    private val hits = HashMap<String, Int>()
    private val order = ArrayList<String>()
    private val current = HashMap<String, Int>()
    private var startedAt = 0L
    private var windowEnd = 0L

    /** Starts a new series and drops the old values. */
    fun start() {
        values.clear(); hits.clear(); order.clear(); current.clear()
        windows = 0
        startedAt = now()
        windowEnd = startedAt + WINDOW_MILLIS
        stopReason = null
        isRunning = true
    }

    fun stop(reason: StopReason) {
        if (!isRunning) return
        isRunning = false
        stopReason = reason
        current.clear()
    }

    /** Adds one packet to the open window. The strongest RSSI of the window wins. */
    fun record(mac: String, rssi: Int) {
        if (!isRunning) return
        advance()
        if (!isRunning) return
        val key = mac.uppercase()
        val old = current[key]
        if (old == null || rssi > old) current[key] = rssi
    }

    /** Closes the elapsed windows and stops the series after 10 minutes. Call it from a timer too. */
    fun advance() {
        while (isRunning && now() >= windowEnd) {
            closeWindow()
            windowEnd += WINDOW_MILLIS
            if (windows >= MAX_WINDOWS) stop(StopReason.TIMEOUT)
        }
    }

    private fun closeWindow() {
        windows += 1
        for ((mac, rssi) in current) {
            if (!values.containsKey(mac)) order.add(mac)
            val list = values.getOrPut(mac) { ArrayDeque() }
            list.addLast(rssi)
            while (list.size > MAX_VALUES) list.removeFirst()
            hits[mac] = (hits[mac] ?: 0) + 1
        }
        current.clear()
    }

    /** Statistics for each MAC in the order of the first packet. */
    fun stats(): List<Stats> = order.mapNotNull { mac ->
        val list = values[mac]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        Stats(mac, list.min(), list.sum().toDouble() / list.size, list.max(), list.size, windows - (hits[mac] ?: 0), windows)
    }

    fun stats(mac: String): Stats? = stats().firstOrNull { it.mac == mac.uppercase() }

    companion object {
        const val MAX_VALUES = 10
        const val WINDOW_MILLIS = 15_000L
        const val MAX_DURATION_MILLIS = 600_000L
        const val MAX_WINDOWS = (MAX_DURATION_MILLIS / WINDOW_MILLIS).toInt()
    }
}
