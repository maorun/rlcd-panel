package com.rlcdpanel.watch.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

class TelemetryFrameTest {

    data class Vector(
        val name: String,
        val sequence: Int,
        val cell: Int?,
        val alerts: Int,
        val tiles: List<Pair<Double, Double>?>,
        val hex: String
    )

    private fun hexBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Odd hex length: ${hex.length}" }
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun fixtureFile(): File {
        val candidates = listOf(
            File("../../tests/fixtures/ble_telemetry_vectors.txt"),
            File("../tests/fixtures/ble_telemetry_vectors.txt"),
            File("tests/fixtures/ble_telemetry_vectors.txt")
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("Could not locate tests/fixtures/ble_telemetry_vectors.txt from ${File(".").absolutePath}")
    }

    private fun loadVectors(): List<Vector> {
        val lines = fixtureFile().readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        return lines.map { line ->
            val fields = line.split("|").map { it.trim() }
            val tiles = (4..9).map { idx ->
                val raw = fields[idx]
                if (raw == "-") {
                    null
                } else {
                    val parts = raw.split("/")
                    parts[0].toDouble() to parts[1].toDouble()
                }
            }
            val rawCell = fields[2].toInt()
            Vector(
                name = fields[0],
                sequence = fields[1].toInt(),
                cell = if (rawCell == 255) null else rawCell,
                alerts = fields[3].toInt(),
                tiles = tiles,
                hex = fields[10]
            )
        }
    }

    private fun roundHalfAwayFromZero(value: Double): Double {
        return if (value >= 0.0) {
            kotlin.math.floor(value + 0.5)
        } else {
            kotlin.math.ceil(value - 0.5)
        }
    }

    @Test
    fun allFixtureVectorsDecode() {
        val vectors = loadVectors()
        assertTrue(vectors.size >= 13)
        for (v in vectors) {
            val fullBytes = hexBytes(v.hex)
            val frame = TelemetryFrame.decode(fullBytes)
            val payloadFrame = TelemetryFrame.decodePayload(fullBytes.copyOfRange(2, fullBytes.size))
            assertEquals(v.name, frame, payloadFrame)
            assertEquals(v.name, v.sequence, frame.sequence)
            assertEquals(v.name, v.cell, frame.cellPercent)
            assertEquals(v.name, v.alerts, frame.alerts.rawValue)
            assertEquals(v.name, 6, frame.tiles.size)
            for (i in 0 until 6) {
                val expected = v.tiles[i]
                val tile = frame.tiles[i]
                if (expected != null) {
                    val expectedTemp = roundHalfAwayFromZero(expected.first * 100.0) / 100.0
                    val expectedRh = roundHalfAwayFromZero(expected.second * 2.0) / 2.0
                    assertTrue("${v.name} tile $i valid", tile.isValid)
                    assertEquals("${v.name} temp $i", expectedTemp, tile.temperatureC ?: Double.NaN, 0.001)
                    assertEquals("${v.name} rh $i", expectedRh, tile.humidityPct ?: Double.NaN, 0.001)
                } else {
                    assertFalse("${v.name} tile $i empty", tile.isValid)
                    assertNull(tile.temperatureC)
                    assertNull(tile.humidityPct)
                }
            }
        }
    }

    @Test
    fun badCrcIsRejectedForEveryVector() {
        for (v in loadVectors()) {
            val bytes = hexBytes(v.hex)
            bytes[25] = (bytes[25].toInt() xor 0x01).toByte()
            val expected = Checksums.crc8(bytes, offset = 2, length = 23)
            try {
                TelemetryFrame.decode(bytes)
                fail("Expected BadCrc for ${v.name}")
            } catch (e: TelemetryException.BadCrc) {
                assertEquals(expected, e.expected)
                assertEquals(bytes[25].toInt() and 0xFF, e.actual)
            }
        }
    }

    @Test
    fun changedPayloadByteIsRejected() {
        val bytes = hexBytes(loadVectors().first().hex)
        bytes[7] = (bytes[7].toInt() xor 0x10).toByte()
        try {
            TelemetryFrame.decode(bytes)
            fail("Expected TelemetryException")
        } catch (_: TelemetryException.BadCrc) {
            // expected
        }
    }

    @Test
    fun wrongCompanyIdIsRejected() {
        val bytes = hexBytes(loadVectors().first().hex)
        bytes[0] = 0x69
        bytes[1] = 0x09
        try {
            TelemetryFrame.decode(bytes)
            fail("Expected WrongCompanyId")
        } catch (e: TelemetryException.WrongCompanyId) {
            assertEquals(0x0969, e.companyId)
        }
    }

    @Test
    fun unknownVersionWithValidCrcIsRejected() {
        val bytes = hexBytes(loadVectors().first().hex)
        bytes[2] = 1
        bytes[25] = Checksums.crc8(bytes, offset = 2, length = 23).toByte()
        try {
            TelemetryFrame.decode(bytes)
            fail("Expected UnknownVersion")
        } catch (e: TelemetryException.UnknownVersion) {
            assertEquals(1, e.version)
        }
    }

    @Test
    fun shortAndLongFramesAreRejected() {
        val bytes = hexBytes(loadVectors().first().hex)
        try {
            TelemetryFrame.decode(bytes.copyOf(25))
            fail("Expected BadLength(25)")
        } catch (e: TelemetryException.BadLength) {
            assertEquals(25, e.actual)
        }

        try {
            TelemetryFrame.decode(ByteArray(0))
            fail("Expected BadLength(0)")
        } catch (e: TelemetryException.BadLength) {
            assertEquals(0, e.actual)
        }

        try {
            TelemetryFrame.decode(bytes + byteArrayOf(0))
            fail("Expected BadLength(27)")
        } catch (e: TelemetryException.BadLength) {
            assertEquals(27, e.actual)
        }

        try {
            TelemetryFrame.decodePayload(bytes.copyOfRange(2, 25))
            fail("Expected BadLength(23)")
        } catch (e: TelemetryException.BadLength) {
            assertEquals(23, e.actual)
        }
    }

    @Test
    fun tileOutsidePhysicalRangeCountsAsEmpty() {
        val bytes = hexBytes(loadVectors().first().hex)
        bytes[7] = 0x10
        bytes[8] = 0x27 // 100.00 C
        bytes[9] = 201.toByte() // 100.5 %RH (above range)
        bytes[25] = Checksums.crc8(bytes, offset = 2, length = 23).toByte()
        assertFalse(TelemetryFrame.decode(bytes).tiles[0].isValid)

        bytes[9] = 200.toByte()
        bytes[8] = 0x28 // 102.56 C (above range)
        bytes[25] = Checksums.crc8(bytes, offset = 2, length = 23).toByte()
        assertFalse(TelemetryFrame.decode(bytes).tiles[0].isValid)

        bytes[7] = 0x77
        bytes[8] = 0xEC.toByte() // -50.01 C (-5001 = 0xEC77 LE, below -50.0 C range)
        bytes[9] = 100.toByte()
        bytes[25] = Checksums.crc8(bytes, offset = 2, length = 23).toByte()
        assertFalse(TelemetryFrame.decode(bytes).tiles[0].isValid)

        bytes[4] = 101.toByte() // cellPercent 101 (> 100) maps to null
        bytes[25] = Checksums.crc8(bytes, offset = 2, length = 23).toByte()
        assertNull(TelemetryFrame.decode(bytes).cellPercent)
    }

    @Test
    fun alertMaskNamesFollowBitOrder() {
        val all = listOf(
            "FREEZER", "HUMIDITY", "NOISE HIGH", "SENSOR LOST",
            "CHARGE FULL", "VENTILATE", "SENSOR BATTERY", "HEAT"
        )
        assertEquals(all, AlertMask(0xFF).names)
        assertEquals(AlertMask(0x01), AlertMask(0x101))
        assertFalse(AlertMask(0xFF).contains(AlertMask.NONE))
        assertFalse(AlertMask.NONE.contains(AlertMask.NONE))
        for (i in all.indices) {
            assertEquals(listOf(all[i]), AlertMask(1 shl i).names)
        }
        assertEquals(listOf("FREEZER", "HEAT"), AlertMask(0x81).names)
        assertTrue(AlertMask(0).names.isEmpty())
    }

    @Test
    fun crcReferenceValuesMatchSensirionAndIeee() {
        assertEquals(0x92, Checksums.crc8(byteArrayOf(0xBE.toByte(), 0xEF.toByte())))
        assertEquals(0xCBF43926L, Checksums.crc32("123456789".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun climateMathMatchesFirmwareFormulas() {
        val dp = ClimateMath.dewPointC(20.0, 50.0)
        assertNotNull(dp)
        assertEquals(9.26, dp!!, 0.05)

        val ah = ClimateMath.absoluteHumidityGm3(20.0, 50.0)
        assertNotNull(ah)
        assertEquals(8.65, ah!!, 0.1)

        // Below 26.7 C, heat index equals air temperature
        assertEquals(22.0, ClimateMath.heatIndexC(22.0, 60.0)!!, 0.001)
        // Hot and humid increases heat index
        assertTrue(ClimateMath.heatIndexC(32.0, 70.0)!! > 38.0)

        assertEquals(ComfortZone.OK, ClimateMath.comfort(21.0, 45.0))
        assertEquals(ComfortZone.COLD, ClimateMath.comfort(16.0, 45.0))
        assertEquals(ComfortZone.WARM, ClimateMath.comfort(28.0, 45.0))
        assertEquals(ComfortZone.DRY, ClimateMath.comfort(21.0, 20.0))
        assertEquals(ComfortZone.HUMID, ClimateMath.comfort(21.0, 70.0))
        assertEquals(ComfortZone.INVALID, ClimateMath.comfort(Double.NaN, 50.0))
    }

    @Test
    fun alertTransitionsDetectRisingBitsOnlyOnNewSequence() {
        val baseTiles = List(6) { TelemetryTile(null, null) }
        val f1 = TelemetryFrame(sequence = 1, cellPercent = 80, alerts = AlertMask(0x01), tiles = baseTiles)
        val f1SameSeq = TelemetryFrame(sequence = 1, cellPercent = 80, alerts = AlertMask(0x03), tiles = baseTiles)
        val f2 = TelemetryFrame(sequence = 2, cellPercent = 80, alerts = AlertMask(0x03), tiles = baseTiles)

        assertTrue(AlertTransitions.risingAlerts(null, f1).isEmpty)
        assertTrue(AlertTransitions.risingAlerts(f1, f1SameSeq).isEmpty)
        assertEquals(AlertMask(0x02), AlertTransitions.risingAlerts(f1, f2))
    }
}
