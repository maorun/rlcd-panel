package com.rlcdpanel.phone.protocol

import kotlin.math.abs

/**
 * Pure signal meter for the packets of one sensor. The caller feeds each packet RSSI to [onPacket].
 * It keeps the newest value, the mean of the last [WINDOW] packets and the time of the last packet.
 *
 * [onPacket] returns true once when the mean differs by [JUMP_DB] or more from the mean that the last
 * report or the first packet set. A true result moves that reference to the new mean, so one change
 * gives one report. [check] returns true once when the run is older than [MAX_RUN_MS]; after it and
 * after [stop] a packet changes nothing.
 */
class SignalMeter(private val clock: () -> Long = { System.currentTimeMillis() }) {

    /** Values for the large text of the screen. [ageMs] is null without a packet. */
    data class Reading(val newestDbm: Int?, val meanDbm: Double?, val packets: Int, val ageMs: Long?)

    private val window = IntArray(WINDOW)
    private var count = 0
    private var next = 0
    private var newest: Int? = null
    private var lastPacketMillis: Long? = null
    private var reference: Double? = null
    private var startedMillis: Long? = null
    private var stopped = false

    /** True from [start] until [stop] or the end of the run. */
    val isRunning: Boolean get() = startedMillis != null && !stopped

    /** Starts a new run and clears the packets. */
    fun start() {
        count = 0
        next = 0
        newest = null
        lastPacketMillis = null
        reference = null
        stopped = false
        startedMillis = clock()
    }

    /** Ends the run, for example when the screen closes. */
    fun stop() {
        stopped = true
    }

    /** Records one packet. Returns true for the vibration edge; a packet of a stopped or unstarted meter gives false. */
    fun onPacket(rssi: Int): Boolean {
        if (!isRunning) return false
        window[next] = rssi
        next = (next + 1) % WINDOW
        if (count < WINDOW) count++
        newest = rssi
        lastPacketMillis = clock()
        val mean = meanOrNull() ?: return false
        val ref = reference
        if (ref == null) {
            reference = mean
            return false
        }
        if (abs(mean - ref) < JUMP_DB) return false
        reference = mean
        return true
    }

    /** Periodic check. Returns true once when the run is older than [MAX_RUN_MS]; the meter then stops. */
    fun check(): Boolean {
        val started = startedMillis ?: return false
        if (stopped) return false
        val age = clock() - started
        if (age < 0) {
            // A clock that ran back gives no stop. Anchor the start to the new time.
            startedMillis = clock()
            return false
        }
        if (age < MAX_RUN_MS) return false
        stopped = true
        return true
    }

    /** The current values. */
    fun reading(): Reading {
        val last = lastPacketMillis
        return Reading(newest, meanOrNull(), count, last?.let { maxOf(0L, clock() - it) })
    }

    private fun meanOrNull(): Double? {
        if (count == 0) return null
        var sum = 0
        for (i in 0 until count) sum += window[i]
        return sum.toDouble() / count
    }

    companion object {
        const val WINDOW = 10
        const val JUMP_DB = 10.0
        const val MAX_RUN_MS = 5L * 60L * 1000L

        /** `-67 dBm`; `-- dBm` without a value. */
        fun dbm(value: Int?): String = value?.let { "$it dBm" } ?: "-- dBm"

        /** Mean with one decimal, for example `-66.5 dBm`. */
        fun meanText(mean: Double?): String =
            mean?.let { "%.1f dBm".format(java.util.Locale.ROOT, it) } ?: "-- dBm"

        /** `12 s`, `3 min 5 s`; `--` without a packet. */
        fun ageText(ageMs: Long?): String {
            if (ageMs == null) return "--"
            val s = ageMs / 1000
            return if (s < 60) "$s s" else "${s / 60} min ${s % 60} s"
        }
    }
}
