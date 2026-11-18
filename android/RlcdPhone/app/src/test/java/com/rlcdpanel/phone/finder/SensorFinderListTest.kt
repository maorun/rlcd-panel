package com.rlcdpanel.phone.finder

import org.junit.Assert.assertEquals
import org.junit.Test

class SensorFinderListTest {
    @Test
    fun finderListKeepsStrongestRssiOncePerMac() {
        val ad = SensorAdvertisement(SensorFormat.ATC, "A4:C1:38:11:22:33", 21.5, 45, 90)
        val newer = ad.copy(temperatureC = 22.0)
        val list = SensorFinderList()
        list.add(ad, -80, 1)
        list.add(newer, -60, 2)
        list.add(ad, -90, 3)
        val rows = list.sorted()
        assertEquals(1, rows.size)
        assertEquals(-60, rows[0].rssi)
        assertEquals(22.0, rows[0].advertisement.temperatureC, 0.001)
    }

    @Test
    fun fullListDropsNewMacButKeepsKnownMac() {
        val list = SensorFinderList(maxRows = 2)
        fun ad(n: Int) = SensorAdvertisement(SensorFormat.ATC, "A4:C1:38:11:22:0$n", 21.5, 45, 90)
        assertEquals(true, list.add(ad(1), -80, 1))
        assertEquals(true, list.add(ad(2), -70, 1))
        assertEquals(false, list.add(ad(3), -50, 1))
        assertEquals(true, list.add(ad(1), -60, 2))
        assertEquals(2, list.sorted().size)
        assertEquals(-60, list.sorted()[0].rssi)
    }

    @Test
    fun equalRssiTakesTheNewerPacket() {
        val ad = SensorAdvertisement(SensorFormat.ATC, "A4:C1:38:11:22:33", 21.5, 45, 90)
        val list = SensorFinderList()
        list.add(ad, -70, 1)
        list.add(ad.copy(temperatureC = 23.0), -70, 2)
        assertEquals(23.0, list.sorted()[0].advertisement.temperatureC, 0.001)
    }
}
