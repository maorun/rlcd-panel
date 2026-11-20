package com.rlcdpanel.watch.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WallTextTest {
    private val indoor = TelemetryTile(20.0, 50.0)
    private val outdoor = TelemetryTile(0.0, 80.0)

    private fun config(frsi: Int, outdoorSlot: Int = 2) =
        PanelConfig(mapOf("wall_frsi_pct" to "$frsi", "outdoor_slot" to "$outdoorSlot"))

    @Test
    fun formatsWallText() {
        val result = WallText.format(indoor, 1, outdoor, config(70))
        assertNotNull(result)
        assertEquals("Wall 73%", result!!.text)
        assertFalse(result.warning)
    }

    @Test
    fun calculatesWallValue() {
        val v = WallText.value(indoor, 1, outdoor, config(70))
        assertNotNull(v)
        assertTrue("Expected 72.5 to 74.0 but got $v", v!! in 72.5..74.0)
        assertNull(WallText.value(indoor, 1, outdoor, config(0)))
        val formatted = WallText.format(indoor, 1, outdoor, config(70))
        assertEquals("Wall 73%", formatted!!.text)
    }

    @Test
    fun warnsAtEightyPercent() {
        val result = WallText.format(TelemetryTile(21.0, 55.0), 1, TelemetryTile(5.0, 50.0), config(60))
        assertEquals("Wall 82%", result!!.text)
        assertTrue(result.warning)
    }

    @Test
    fun warningUsesUnroundedValue() {
        // Wall humidity is 79.6 %RH: the text rounds to 80 and the warning stays off below 80.0.
        val result = WallText.format(TelemetryTile(20.0, 54.4), 1, TelemetryTile(0.0, 50.0), config(70))
        assertEquals("Wall 80%", result!!.text)
        assertFalse(result.warning)
    }

    @Test
    fun returnsNullWhenKeyIsZeroOrMissing() {
        assertNull(WallText.format(indoor, 1, outdoor, config(0)))
        assertNull(WallText.format(indoor, 1, outdoor, PanelConfig()))
    }

    @Test
    fun returnsNullForStaleOrMissingOutdoorTile() {
        assertNull(WallText.format(indoor, 1, null, config(70)))
        val tiles = listOf(indoor, indoor, TelemetryTile(null, null))
        assertNull(WallText.outdoorTile(tiles, config(70)))
    }

    @Test
    fun returnsNullForOutdoorCardAndInvalidTile() {
        assertNull(WallText.format(outdoor, 2, outdoor, config(70)))
        assertNull(WallText.format(TelemetryTile(null, null), 1, outdoor, config(70)))
    }

    @Test
    fun outdoorTileComesFromOutdoorSlot() {
        val tiles = listOf(indoor, indoor, outdoor)
        assertEquals(outdoor, WallText.outdoorTile(tiles, config(70, 2)))
        assertNull(WallText.outdoorTile(tiles, config(70, 0)))
    }

    @Test
    fun warningThreshold() {
        assertFalse(WallText.isWarning(79.9))
        assertTrue(WallText.isWarning(80.0))
    }
}
