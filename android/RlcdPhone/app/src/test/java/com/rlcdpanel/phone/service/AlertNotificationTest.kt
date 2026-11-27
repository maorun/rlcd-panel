package com.rlcdpanel.phone.service

import com.rlcdpanel.phone.protocol.AlertMask
import com.rlcdpanel.phone.protocol.ClimateMath
import com.rlcdpanel.phone.protocol.PanelConfig
import com.rlcdpanel.phone.protocol.TelemetryFrame
import com.rlcdpanel.phone.protocol.TelemetryTile
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class AlertNotificationTest {
    private fun frame(vararg tiles: Pair<Double, Double>?): TelemetryFrame = TelemetryFrame(
        sequence = 1,
        cellPercent = 80,
        alerts = AlertMask.NONE,
        tiles = List(11) { i ->
            tiles.getOrNull(i)?.let { TelemetryTile(it.first, it.second) } ?: TelemetryTile(null, null)
        }
    )

    private val config = PanelConfig(mapOf("onboard_name" to "Flur", "slot1_name" to "Küche", "slot2_name" to "Bad"))

    @Test
    fun heatNamesTileWithHighestFeelsLike() {
        // Below 26.7 C the feels-like value equals the air temperature, so the literal is exact.
        assertEquals("HEAT: Küche feels like 25.0 °C", AlertNotification.text(7, frame(22.0 to 40.0, 25.0 to 60.0, 24.0 to 70.0), config))
    }

    @Test
    fun heatPrefersHotHumidTileOverHotDryTile() {
        val feels = ClimateMath.heatIndexC(33.0, 80.0)!!
        val expected = "HEAT: Küche feels like ${String.format(Locale.ROOT, "%.1f", feels)} °C"
        assertEquals(expected, AlertNotification.text(7, frame(33.0 to 30.0, 33.0 to 80.0), config))
    }

    @Test
    fun heatWithoutTilesKeepsOldText() {
        assertEquals("HEAT", AlertNotification.text(7, frame(), config))
    }

    @Test
    fun freezerNamesColdestTile() {
        assertEquals("FREEZER: Küche -18.5 °C", AlertNotification.text(0, frame(5.0 to 40.0, -18.5 to 80.0, -2.0 to 50.0), config))
    }

    @Test
    fun humidityNamesMostHumidTile() {
        assertEquals("HUMIDITY: Bad 82 %RH", AlertNotification.text(1, frame(20.0 to 50.0, 21.0 to 60.0, 19.0 to 82.0), config))
    }

    @Test
    fun otherBitKeepsOldText() {
        assertEquals("NOISE HIGH", AlertNotification.text(2, frame(20.0 to 50.0), config))
    }

    @Test
    fun alertTilesMaskExcludesTiles() {
        val masked = PanelConfig(config.values + ("climate_alert_tiles" to "1"))
        assertEquals("HUMIDITY: Flur 50 %RH", AlertNotification.text(1, frame(20.0 to 50.0, 21.0 to 90.0), masked))
    }

    @Test
    fun noUsableTileKeepsOldText() {
        val none = PanelConfig(config.values + ("climate_alert_tiles" to "0"))
        assertEquals("FREEZER", AlertNotification.text(0, frame(-5.0 to 50.0), none))
        assertEquals("HEAT", AlertNotification.text(7, frame(), config))
    }

    @Test
    fun missingConfigUsesDefaultTileNames() {
        assertEquals("HUMIDITY: Slot 1 70 %RH", AlertNotification.text(1, frame(20.0 to 50.0, 21.0 to 70.0), PanelConfig()))
    }

    @Test
    fun badBitGivesEmptyText() {
        assertEquals("", AlertNotification.text(9, frame(20.0 to 50.0), config))
    }

    @Test
    fun negativeBitGivesEmptyText() {
        assertEquals("", AlertNotification.text(-1, frame(20.0 to 50.0), config))
    }

    @Test
    fun nullFrameKeepsOldText() {
        assertEquals("FREEZER", AlertNotification.text(0, null, config))
    }

    @Test
    fun maskJoinsTextsLowestBitFirst() {
        val text = AlertNotification.text(AlertMask(1 or 4), frame(20.0 to 50.0, -3.0 to 60.0), config)
        assertEquals("FREEZER: Küche -3.0 °C, NOISE HIGH", text)
    }

    @Test
    fun mergedSecondPageNamesSlotSixToTen() {
        val page0 = TelemetryFrame(1, 80, AlertMask.NONE, List(6) { TelemetryTile(20.0, 50.0) }, page = 0)
        val page1 = TelemetryFrame(2, 80, AlertMask.NONE, listOf(TelemetryTile(21.0, 91.0)), page = 1)
        val merged = TelemetryFrame.merge(TelemetryFrame.merge(null, page0), page1)
        val named = PanelConfig(mapOf("slot6_name" to "Keller", "climate_alert_tiles" to "2047"))
        assertEquals("HUMIDITY: Keller 91 %RH", AlertNotification.text(1, merged, named))
    }

    @Test
    fun missingMaskKeyExcludesTilesBeyondFirmwareDefault() {
        // Default mask 63 covers tiles 0 to 5; the humid tile 6 stays out.
        val tiles = Array(7) { if (it == 6) 21.0 to 91.0 else 20.0 to 50.0 }
        assertEquals("HUMIDITY: Onboard 50 %RH", AlertNotification.text(1, frame(*tiles), PanelConfig()))
    }
}
