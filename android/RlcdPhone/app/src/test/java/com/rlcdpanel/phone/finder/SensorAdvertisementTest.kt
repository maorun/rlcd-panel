package com.rlcdpanel.phone.finder

import com.rlcdpanel.phone.protocol.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SensorAdvertisementTest {
    private fun hex(s: String): ByteArray? =
        if (s == "-") null else ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun decodeLine(f: List<String>): SensorAdvertisement? {
        val mac = f[1].split(":").map { it.toInt(16).toByte() }.toByteArray()
        return SensorAdvertisement.decode(
            bleMac = mac,
            name = f[2].takeIf { it != "-" },
            mfg = hex(f[3]),
            svcUuid = f[4].takeIf { it != "-" }?.toInt(16),
            svc = hex(f[5])
        )
    }

    private fun lines(): List<List<String>> =
        Fixtures.file("sensor_adv_vectors.txt").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split(" | ") }

    @Test
    fun everyFixtureLineGivesTheFirmwareValues() {
        val rows = lines()
        assertEquals(47, rows.size)
        var rejected = 0
        for (f in rows) {
            assertEquals(f[0], 11, f.size)
            val ad = decodeLine(f)
            if (f[6] == "null") {
                assertNull(f[0], ad)
                rejected++
            } else {
                assertNotNull(f[0], ad)
                assertEquals(f[0], f[6], ad!!.format.name)
                assertEquals(f[0], f[7], ad.mac)
                assertEquals(f[0], f[8].toDouble(), ad.temperatureC, 0.05)
                assertEquals(f[0], f[9].toInt(), ad.humidityPct)
                assertEquals(f[0], f[10].toInt(), ad.batteryPct)
            }
        }
        assertEquals(25, rejected)
    }

    @Test
    fun everyFormatHasGoodAndBadLine() {
        val rows = lines()
        for (format in listOf("WOIO", "METER", "BTHOME", "ATC", "THERMOPRO")) {
            assertEquals(format, true, rows.any { it[6] == format })
        }
        for (prefix in listOf("woio_", "meter_", "bthome_", "atc_", "thermopro_")) {
            assertEquals(prefix, true, rows.any { it[0].startsWith(prefix) && it[6] == "null" })
        }
    }

    @Test
    fun badLengthGivesNull() {
        val mac = ByteArray(6) { 1 }
        assertNull(SensorAdvertisement.decode(mac, null, null, 0x181A, ByteArray(14)))
        assertNull(SensorAdvertisement.decode(mac, null, null, 0xFD3D, byteArrayOf(0x54, 0, 100, 3, 0x96.toByte())))
        assertNull(SensorAdvertisement.decode(mac, null, byteArrayOf(0xC2.toByte(), 1, 2, 3), null, null))
    }

    @Test
    fun humidityAbove100GivesNull() {
        val mac = ByteArray(6) { 1 }
        assertNull(SensorAdvertisement.decode(mac, null, null, 0xFD3D, byteArrayOf(0x54, 0, 100, 3, 0x96.toByte(), 101)))
        assertNull(SensorAdvertisement.decode(mac, null, byteArrayOf(0xC2.toByte(), 0xD7.toByte(), 0, 101, 90), null, null))
    }
}
