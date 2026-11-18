package com.rlcdpanel.phone.ventilation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VentilationGuideTest {

    private data class FixtureRow(
        val tempInnen: Double, val rFInnen: Double, val tempAussen: Double, val rFAussen: Double,
        val ventilating: Boolean, val minutes: Int, val action: String?, val diff: Double?
    )

    private fun loadFixture(): List<FixtureRow> {
        var dir: File? = File(".").canonicalFile
        var file: File? = null
        while (dir != null && file == null) {
            val candidate = File(dir, "tests/fixtures/ventilation_vectors.txt")
            if (candidate.isFile) file = candidate
            dir = dir.parentFile
        }
        checkNotNull(file) { "Could not locate tests/fixtures/ventilation_vectors.txt" }
        return file.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { line ->
            val f = line.split("|").map { it.trim() }
            FixtureRow(f[0].toDouble(), f[1].toDouble(), f[2].toDouble(), f[3].toDouble(),
                f[4] == "1", f[5].toInt(), if (f[6] == "-") null else f[6],
                if (f[7] == "-") null else f[7].toDouble())
        }
    }

    @Test
    fun testSharedFixtureVectors() {
        val rows = loadFixture()
        assertTrue(rows.isNotEmpty())
        for (row in rows) {
            val result = VentilationGuide.analysiereLueftung(
                row.tempInnen, row.rFInnen, row.tempAussen, row.rFAussen, row.ventilating, row.minutes
            )
            if (row.action == null) {
                assertNull("$row", result)
            } else {
                assertNotNull("$row", result)
                assertEquals("$row", row.action, result!!.aktion.name)
                assertEquals("$row", row.diff!!, result.daten.differenz, 0.01)
            }
        }
    }

    @Test
    fun testAbsoluteHumidityFormula() {
        val ah1 = VentilationGuide.berechneAbsoluteFeuchte(20.0, 50.0)
        assertNotNull(ah1)
        assertTrue("AH at 20C/50% should be ~8.65 g/m3, got $ah1", ah1!! in 8.5..8.8)

        val ahCold = VentilationGuide.berechneAbsoluteFeuchte(0.0, 80.0)
        assertNotNull(ahCold)
        assertTrue("AH at 0C/80% should be ~3.88 g/m3, got $ahCold", ahCold!! in 3.7..4.0)

        val ahZero = VentilationGuide.berechneAbsoluteFeuchte(25.0, 0.0)
        assertNotNull(ahZero)
        assertEquals(0.0, ahZero!!, 0.0001)
    }

    @Test
    fun testAbsoluteHumidityInvalidInputs() {
        assertNull(VentilationGuide.berechneAbsoluteFeuchte(Double.NaN, 50.0))
        assertNull(VentilationGuide.berechneAbsoluteFeuchte(20.0, Double.NaN))
        assertNull(VentilationGuide.berechneAbsoluteFeuchte(Double.POSITIVE_INFINITY, 50.0))
        assertNull(VentilationGuide.berechneAbsoluteFeuchte(-60.0, 50.0))
        assertNull(VentilationGuide.berechneAbsoluteFeuchte(70.0, 50.0))
        assertNull(VentilationGuide.berechneAbsoluteFeuchte(20.0, -5.0))
        assertNull(VentilationGuide.berechneAbsoluteFeuchte(20.0, 105.0))
    }

    @Test
    fun testAnalysiereLueftungInvalidInputs() {
        assertNull(VentilationGuide.analysiereLueftung(Double.NaN, 50.0, 10.0, 50.0))
        assertNull(VentilationGuide.analysiereLueftung(20.0, Double.NaN, 10.0, 50.0))
        assertNull(VentilationGuide.analysiereLueftung(20.0, 50.0, Double.NaN, 50.0))
        assertNull(VentilationGuide.analysiereLueftung(20.0, 50.0, 10.0, Double.NaN))
    }

    @Test
    fun testClosedWindowOptimalRoomHumidity() {
        // Room humidity below 53% -> NICHT_LUEFTEN (grau)
        val result = VentilationGuide.analysiereLueftung(
            tempInnen = 21.0,
            rFInnen = 52.9,
            tempAussen = 10.0,
            rFAussen = 50.0,
            istGeradeAmLueften = false
        )
        assertNotNull(result)
        assertEquals(VentilationAction.NICHT_LUEFTEN, result!!.aktion)
        assertEquals(StatusFarbe.GRAU, result.statusFarbe)
        assertEquals("Raumluft in Ordnung", result.titel)
    }

    @Test
    fun testClosedWindowOutdoorMoreHumid() {
        // Room 20C / 55% (~9.5 g/m3), Outdoor 25C / 80% (~18.4 g/m3) -> differenz < -0.5
        val result = VentilationGuide.analysiereLueftung(
            tempInnen = 20.0,
            rFInnen = 55.0,
            tempAussen = 25.0,
            rFAussen = 80.0,
            istGeradeAmLueften = false
        )
        assertNotNull(result)
        assertEquals(VentilationAction.NICHT_LUEFTEN, result!!.aktion)
        assertEquals(StatusFarbe.ROT, result.statusFarbe)
        assertEquals("Fenster geschlossen halten", result.titel)
        assertTrue(result.nachricht.contains("feuchter"))
    }

    @Test
    fun testClosedWindowNegligibleDifference() {
        // Difference within [-0.5, 0.5]
        val result = VentilationGuide.analysiereLueftung(
            tempInnen = 20.0,
            rFInnen = 55.0,
            tempAussen = 20.0,
            rFAussen = 54.0,
            istGeradeAmLueften = false
        )
        assertNotNull(result)
        assertEquals(VentilationAction.NEUTRAL, result!!.aktion)
        assertEquals(StatusFarbe.GELB, result.statusFarbe)
        assertEquals("Kaum Entfeuchtungseffekt", result.titel)
    }

    @Test
    fun testClosedWindowStartRecommendationColdWeather() {
        // Room 21C / 60%, Outdoor 4.9C / 60% -> Cold limit < 5.0C -> max 5 min
        val result = VentilationGuide.analysiereLueftung(
            tempInnen = 21.0,
            rFInnen = 60.0,
            tempAussen = 4.9,
            rFAussen = 60.0,
            istGeradeAmLueften = false
        )
        assertNotNull(result)
        assertEquals(VentilationAction.LUEFTUNG_STARTEN, result!!.aktion)
        assertEquals(StatusFarbe.GRUEN, result.statusFarbe)
        assertEquals("Kurz Stoßlüften (Kälteschutz!)", result.titel)
        assertEquals(5, result.maxDauerMinuten)
    }

    @Test
    fun testClosedWindowStartRecommendationMildWeather() {
        // Outdoor 5.0C -> Mild weather threshold -> max 15 min
        val result = VentilationGuide.analysiereLueftung(
            tempInnen = 21.0,
            rFInnen = 60.0,
            tempAussen = 5.0,
            rFAussen = 50.0,
            istGeradeAmLueften = false
        )
        assertNotNull(result)
        assertEquals(VentilationAction.LUEFTUNG_STARTEN, result!!.aktion)
        assertEquals(StatusFarbe.GRUEN, result.statusFarbe)
        assertEquals("Jetzt optimal zum Lüften", result.titel)
        assertEquals(15, result.maxDauerMinuten)
    }

    @Test
    fun testActiveVentilationTargetReachedBoundary() {
        // Room humidity reaches exactly 48.0% -> LUEFTUNG_BEENDEN (blau)
        val result = VentilationGuide.analysiereLueftung(
            tempInnen = 20.0,
            rFInnen = 48.0,
            tempAussen = 8.0,
            rFAussen = 50.0,
            istGeradeAmLueften = true,
            lueftungsDauerMinuten = 4
        )
        assertNotNull(result)
        assertEquals(VentilationAction.LUEFTUNG_BEENDEN, result!!.aktion)
        assertEquals(StatusFarbe.BLAU, result.statusFarbe)
        assertEquals("Ziel erreicht – Fenster schließen!", result.titel)
    }

    @Test
    fun testActiveVentilationCoolingAbortBoundary() {
        // Room drops to exactly 17.5C -> LUEFTUNG_ABBRECHEN (orange)
        val result = VentilationGuide.analysiereLueftung(
            tempInnen = 17.5,
            rFInnen = 52.0,
            tempAussen = 2.0,
            rFAussen = 50.0,
            istGeradeAmLueften = true,
            lueftungsDauerMinuten = 3
        )
        assertNotNull(result)
        assertEquals(VentilationAction.LUEFTUNG_ABBRECHEN, result!!.aktion)
        assertEquals(StatusFarbe.ORANGE, result.statusFarbe)
        assertEquals("Lüftung abbrechen (Auskühlung)", result.titel)
    }

    @Test
    fun testActiveVentilationTimeoutMildWeather() {
        // Outdoor >= 5C -> max 15 min. Elapsed 15 min -> timeout abort
        val result = VentilationGuide.analysiereLueftung(
            tempInnen = 19.0,
            rFInnen = 52.0,
            tempAussen = 10.0,
            rFAussen = 50.0,
            istGeradeAmLueften = true,
            lueftungsDauerMinuten = 15
        )
        assertNotNull(result)
        assertEquals(VentilationAction.LUEFTUNG_ABBRECHEN, result!!.aktion)
        assertEquals(StatusFarbe.ORANGE, result.statusFarbe)
        assertEquals("Lüftung abbrechen (Zeitüberschreitung)", result.titel)
    }

    @Test
    fun testActiveVentilationWeatherShiftAbortBoundary() {
        // When diff <= 0.1 -> LUEFTUNG_ABBRECHEN (rot)
        val result = VentilationGuide.analysiereLueftung(
            tempInnen = 20.0,
            rFInnen = 50.0,
            tempAussen = 20.0,
            rFAussen = 50.0,
            istGeradeAmLueften = true,
            lueftungsDauerMinuten = 2
        )
        assertNotNull(result)
        assertEquals(VentilationAction.LUEFTUNG_ABBRECHEN, result!!.aktion)
        assertEquals(StatusFarbe.ROT, result.statusFarbe)
        assertEquals("Lüftung abbrechen (Wetterumschwung)", result.titel)
    }

    @Test
    fun testActiveVentilationOngoingNormal() {
        val result = VentilationGuide.analysiereLueftung(
            tempInnen = 20.0,
            rFInnen = 52.0,
            tempAussen = 10.0,
            rFAussen = 50.0,
            istGeradeAmLueften = true,
            lueftungsDauerMinuten = 3
        )
        assertNotNull(result)
        assertEquals(VentilationAction.WEITER_LUEFTEN, result!!.aktion)
        assertEquals(StatusFarbe.GRUEN, result.statusFarbe)
        assertEquals("Lüftung läuft...", result.titel)
        assertTrue(result.nachricht.contains("Restzeit: ca. 12 Min"))
    }

    @Test
    fun testOpenMeteoJsonParsing() {
        val sampleJson = """
            {
              "latitude": 52.52,
              "longitude": 13.41,
              "current": {
                "time": "2026-11-16T14:00",
                "temperature_2m": 8.7,
                "relative_humidity_2m": 72.0
              }
            }
        """.trimIndent()

        val parsed = OutdoorWeatherService.parseResponse(sampleJson, "Berlin")
        assertNotNull(parsed)
        assertEquals(8.7, parsed!!.tempC, 0.01)
        assertEquals(72.0, parsed.rhPct, 0.01)
        assertEquals("Berlin", parsed.sourceName)
    }

    @Test
    fun testOpenMeteoJsonParsingMalformed() {
        val badJson = "{\"invalid\": 123}"
        val parsed = OutdoorWeatherService.parseResponse(badJson)
        assertNull(parsed)
    }
}
