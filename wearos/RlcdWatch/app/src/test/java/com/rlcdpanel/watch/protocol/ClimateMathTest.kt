package com.rlcdpanel.watch.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClimateMathTest {

    private data class Vector(
        val line: Int,
        val temp: Double,
        val rh: Double,
        val dew: Double?,
        val abs: Double?,
        val heat: Double?,
        val comfort: String,
    )

    private fun number(text: String): Double? = if (text == "-") null else text.toDouble()

    private fun vectors(): List<Vector> {
        var dir: File? = File(".").canonicalFile
        var file: File? = null
        while (dir != null && file == null) {
            val candidate = File(dir, "tests/fixtures/climate_math_vectors.txt")
            if (candidate.isFile) file = candidate
            dir = dir.parentFile
        }
        checkNotNull(file) { "Could not locate tests/fixtures/climate_math_vectors.txt" }
        return file.readLines().withIndex().mapNotNull { (index, raw) ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
            val cols = line.split("|").map { it.trim() }
            check(cols.size == 6) { "Bad vector line ${index + 1}" }
            Vector(index + 1, cols[0].toDouble(), cols[1].toDouble(), number(cols[2]), number(cols[3]), number(cols[4]), cols[5])
        }
    }

    private fun assertNear(label: String, expected: Double?, actual: Double?, tolerance: Double) {
        if (expected == null) {
            assertNull(label, actual)
        } else {
            assertNotNull(label, actual)
            assertEquals(label, expected, actual!!, tolerance)
        }
    }

    @Test
    fun sharedVectorsMatch() {
        val all = vectors()
        assertTrue(all.size >= 15)
        for (v in all) {
            val tag = "line ${v.line} (${v.temp}, ${v.rh})"
            assertNear("dew $tag", v.dew, ClimateMath.dewPointC(v.temp, v.rh), 0.05)
            assertNear("abs $tag", v.abs, ClimateMath.absoluteHumidityGm3(v.temp, v.rh), 0.05)
            assertNear("heat $tag", v.heat, ClimateMath.heatIndexC(v.temp, v.rh), 0.05)
            assertEquals("comfort $tag", v.comfort, ClimateMath.comfort(v.temp, v.rh).name)
        }
    }

    @Test
    fun dewPointMatchesReference() {
        assertEquals(9.26, ClimateMath.dewPointC(20.0, 50.0)!!, 0.05)
    }

    @Test
    fun dewPointRejectsInvalidInput() {
        assertNull(ClimateMath.dewPointC(20.0, 0.0))
        assertNull(ClimateMath.dewPointC(Double.NaN, 50.0))
        assertNull(ClimateMath.dewPointC(70.0, 50.0))
        assertNull(ClimateMath.dewPointC(20.0, 101.0))
    }

    @Test
    fun absoluteHumidityMatchesReference() {
        assertEquals(8.65, ClimateMath.absoluteHumidityGm3(20.0, 50.0)!!, 0.1)
        assertEquals(0.0, ClimateMath.absoluteHumidityGm3(25.0, 0.0)!!, 0.0001)
        assertNull(ClimateMath.absoluteHumidityGm3(-46.0, 50.0))
        assertNull(ClimateMath.absoluteHumidityGm3(20.0, Double.POSITIVE_INFINITY))
    }

    @Test
    fun heatIndexEqualsAirTemperatureBelowThresholds() {
        assertEquals(22.0, ClimateMath.heatIndexC(22.0, 60.0)!!, 0.001)
        assertEquals(30.0, ClimateMath.heatIndexC(30.0, 39.0)!!, 0.001)
    }

    @Test
    fun heatIndexRisesWhenHotAndHumid() {
        val hi = ClimateMath.heatIndexC(32.0, 70.0)
        assertNotNull(hi)
        assertTrue(hi!! > 38.0)
        assertNull(ClimateMath.heatIndexC(Double.NaN, 70.0))
    }

    @Test
    fun comfortZonesFollowLimits() {
        assertEquals(ComfortZone.OK, ClimateMath.comfort(21.0, 45.0))
        assertEquals(ComfortZone.COLD, ClimateMath.comfort(16.0, 45.0))
        assertEquals(ComfortZone.WARM, ClimateMath.comfort(28.0, 45.0))
        assertEquals(ComfortZone.DRY, ClimateMath.comfort(21.0, 20.0))
        assertEquals(ComfortZone.HUMID, ClimateMath.comfort(21.0, 70.0))
        assertEquals(ComfortZone.INVALID, ClimateMath.comfort(Double.NaN, 50.0))
    }
}
