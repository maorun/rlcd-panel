package com.rlcdpanel.phone.finder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalSeriesTest {
    private var t = 1_000L
    private val series = SignalSeries { t }
    private val mac = "AA:BB:CC:DD:EE:01"

    private fun window(vararg rssi: Pair<String, Int>) {
        for ((m, r) in rssi) series.record(m, r)
        t += SignalSeries.WINDOW_MILLIS
        series.advance()
    }

    @Test
    fun statisticsOverWindows() {
        series.start()
        window(mac to -70)
        window(mac to -60)
        window(mac to -80)
        val s = series.stats(mac)!!
        assertEquals(-80, s.min)
        assertEquals(-60, s.max)
        assertEquals(-70.0, s.mean, 0.0001)
        assertEquals(3, s.count)
        assertEquals(0, s.missed)
        assertEquals(3, s.windows)
    }

    @Test
    fun strongestPacketOfWindowWins() {
        series.start()
        series.record(mac, -90)
        series.record(mac, -65)
        series.record(mac, -75)
        t += SignalSeries.WINDOW_MILLIS
        series.advance()
        assertEquals(-65, series.stats(mac)!!.max)
        assertEquals(1, series.stats(mac)!!.count)
    }

    @Test
    fun keepsLastTenValues() {
        series.start()
        for (i in 0 until 12) window(mac to -100 + i)
        val s = series.stats(mac)!!
        assertEquals(10, s.count)
        assertEquals(-98, s.min)
        assertEquals(-89, s.max)
        assertEquals(12, s.windows)
        assertEquals(0, s.missed)
    }

    @Test
    fun countsMissedWindows() {
        series.start()
        window(mac to -70)
        window("AA:BB:CC:DD:EE:02" to -50)
        window()
        window(mac to -72)
        val s = series.stats(mac)!!
        assertEquals(2, s.count)
        assertEquals(2, s.missed)
        assertEquals(4, s.windows)
        assertEquals(3, series.stats("aa:bb:cc:dd:ee:02")!!.missed)
        assertEquals(listOf(mac, "AA:BB:CC:DD:EE:02"), series.stats().map { it.mac })
    }

    @Test
    fun openWindowIsNotCounted() {
        series.start()
        series.record(mac, -70)
        assertNull(series.stats(mac))
        assertEquals(0, series.windows)
    }

    @Test
    fun silentGapClosesEmptyWindows() {
        series.start()
        window(mac to -70)
        t += 3 * SignalSeries.WINDOW_MILLIS
        series.advance()
        assertEquals(1, series.stats(mac)!!.count)
        assertEquals(3, series.stats(mac)!!.missed)
    }

    @Test
    fun stopsAfterTenMinutes() {
        series.start()
        for (i in 0 until 39) window(mac to -70)
        assertTrue(series.isRunning)
        window(mac to -70)
        assertFalse(series.isRunning)
        assertEquals(SignalSeries.StopReason.TIMEOUT, series.stopReason)
        assertEquals(40, series.windows)
        series.record(mac, -10)
        t += SignalSeries.WINDOW_MILLIS
        series.advance()
        assertEquals(40, series.windows)
        assertEquals(10, series.stats(mac)!!.count)
        assertEquals(-70, series.stats(mac)!!.max)
    }

    @Test
    fun timeoutWithoutPacketsStopsOnAdvance() {
        series.start()
        t += SignalSeries.MAX_DURATION_MILLIS
        series.advance()
        assertFalse(series.isRunning)
        assertEquals(SignalSeries.StopReason.TIMEOUT, series.stopReason)
    }

    @Test
    fun stopKeepsValuesAndFirstReason() {
        series.start()
        window(mac to -70)
        series.stop(SignalSeries.StopReason.PERMISSION_LOST)
        series.stop(SignalSeries.StopReason.USER)
        assertEquals(SignalSeries.StopReason.PERMISSION_LOST, series.stopReason)
        series.record(mac, -50)
        assertEquals(1, series.stats(mac)!!.count)
    }

    @Test
    fun startResetsSeries() {
        series.start()
        window(mac to -70)
        series.start()
        assertTrue(series.stats().isEmpty())
        assertEquals(0, series.windows)
        assertNull(series.stopReason)
    }

    @Test
    fun lateMacCountsEarlierWindowsAsMissed() {
        // Same rule as ScanSeries.swift: missed = all windows minus the windows with a packet.
        series.start()
        window(mac to -70)
        window(mac to -70)
        window(mac to -70, "AA:BB:CC:DD:EE:02" to -60)
        assertEquals(2, series.stats("AA:BB:CC:DD:EE:02")!!.missed)
        assertEquals(3, series.stats("AA:BB:CC:DD:EE:02")!!.windows)
    }
}
