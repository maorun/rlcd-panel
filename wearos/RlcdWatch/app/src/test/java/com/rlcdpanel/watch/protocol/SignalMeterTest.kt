package com.rlcdpanel.watch.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalMeterTest {
    private var now = 1_000_000L
    private fun meter() = SignalMeter { now }.also { it.start() }

    @Test
    fun meanCoversTheLastTenPackets() {
        val m = meter()
        for (v in listOf(-60, -62)) m.onPacket(v)
        assertEquals(-61.0, m.reading().meanDbm!!, 0.0001)
        assertEquals(-62, m.reading().newestDbm)
        // 12 packets: only the last 10 count (-70 x 10).
        val m2 = meter()
        m2.onPacket(-10); m2.onPacket(-20)
        repeat(10) { m2.onPacket(-70) }
        assertEquals(-70.0, m2.reading().meanDbm!!, 0.0001)
        assertEquals(10, m2.reading().packets)
    }

    @Test
    fun ageGrowsWithTheClock() {
        val m = meter()
        assertNull(m.reading().ageMs)
        m.onPacket(-60)
        now += 12_000
        assertEquals(12_000L, m.reading().ageMs)
        assertEquals("12 s", SignalMeter.ageText(12_000))
        assertEquals("3 min 5 s", SignalMeter.ageText(185_000))
        assertEquals("--", SignalMeter.ageText(null))
    }

    @Test
    fun vibratesOnceWhenTheMeanMovesTenDb() {
        val m = meter()
        repeat(10) { assertFalse(m.onPacket(-60)) }   // full window, reference -60
        repeat(3) { assertFalse(m.onPacket(-90)) }    // mean -69, change 9
        assertTrue(m.onPacket(-90))                   // mean -72, change 12: the edge
        assertFalse(m.onPacket(-90))                  // mean -75, change 3 from the new reference -72
    }

    @Test
    fun noEdgeBelowTenDb() {
        val m = meter()
        m.onPacket(-60)
        repeat(9) { assertFalse(m.onPacket(-69)) } // mean ends at -68.1, change 8.1
    }

    @Test
    fun stopsAfterFiveMinutes() {
        val m = meter()
        m.onPacket(-60)
        now += 299_999
        assertFalse(m.check())
        assertTrue(m.isRunning)
        now += 1
        assertTrue(m.check())
        assertFalse(m.isRunning)
        assertFalse(m.check())            // reported once
        val before = m.reading().packets
        assertFalse(m.onPacket(-10))      // ignored after the stop
        assertEquals(before, m.reading().packets)
    }

    @Test
    fun stopEndsTheRunAndStartClearsIt() {
        val m = meter()
        m.onPacket(-60)
        m.stop()
        assertFalse(m.onPacket(-60))
        m.start()
        assertEquals(0, m.reading().packets)
        assertTrue(m.isRunning)
    }

    @Test
    fun clockThatRanBackDoesNotStop() {
        val m = meter()
        now -= 10_000
        assertFalse(m.check())
        now += 299_000
        assertFalse(m.check())
    }

    @Test
    fun textsOfTheValues() {
        assertEquals("-67 dBm", SignalMeter.dbm(-67))
        assertEquals("-- dBm", SignalMeter.dbm(null))
        assertEquals("-66.5 dBm", SignalMeter.meanText(-66.5))
    }
}
