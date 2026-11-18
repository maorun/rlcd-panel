package com.rlcdpanel.phone.widget

import com.rlcdpanel.phone.TelemetrySnapshot
import com.rlcdpanel.phone.protocol.PanelConfig
import com.rlcdpanel.phone.protocol.TelemetryFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClimateWidgetModelTest {

    private val receivedAt = 1_000_000L

    private fun fixtureHex(name: String): String {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            val candidate = File(dir, "tests/fixtures/ble_telemetry_vectors.txt")
            if (candidate.isFile) {
                val line = candidate.readLines().map { it.trim() }
                    .first { it.isNotEmpty() && !it.startsWith("#") && it.substringBefore("|").trim() == name }
                return line.split("|").last().trim()
            }
            dir = dir.parentFile
        }
        error("Could not locate tests/fixtures/ble_telemetry_vectors.txt")
    }

    private fun snapshot(name: String, at: Long = receivedAt): TelemetrySnapshot {
        val hex = fixtureHex(name)
        val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return TelemetrySnapshot(TelemetryFrame.decode(bytes), rssi = -60, address = null, receivedAtMillis = at)
    }

    private val config = PanelConfig.parseText(
        """
        onboard_name=Panel
        slot1_mac=AA:BB:CC:DD:EE:01
        slot1_name=Living
        slot2_mac=AA:BB:CC:DD:EE:02
        slot2_name=Cellar
        """.trimIndent()
    )

    @Test
    fun fiveValidTilesGiveFourRowsAndAlertNames() {
        val state = ClimateWidgetModel.build(snapshot("five_tiles"), receivedAt + 60_000L, config)
        assertEquals(4, state.rows.size)
        assertEquals(listOf("Panel", "Living", "Cellar", "Slot 3"), state.rows.map { it.name })
        assertEquals("21.0°C", state.rows[0].temperature)
        assertEquals("45%", state.rows[0].humidity)
        assertEquals(listOf("HUMIDITY"), state.alertNames)
        assertEquals("1 min ago", state.ageText)
        assertNull(state.hint)
    }

    @Test
    fun tileWithoutValidBitShowsStaleBadge() {
        val state = ClimateWidgetModel.build(snapshot("one_tile_onboard"), receivedAt, config)
        assertEquals(3, state.rows.size)
        assertTrue(state.rows[0].badges.isEmpty())
        assertEquals("Living", state.rows[1].name)
        assertEquals("--", state.rows[1].temperature)
        assertEquals(listOf(ClimateWidgetModel.BADGE_STALE), state.rows[1].badges)
    }

    @Test
    fun unconfiguredEmptyTileGetsNoRow() {
        val state = ClimateWidgetModel.build(snapshot("one_tile_onboard"), receivedAt, PanelConfig())
        assertEquals(1, state.rows.size)
    }

    @Test
    fun emptyStoreShowsDashesAndHint() {
        val state = ClimateWidgetModel.build(null, receivedAt, config)
        assertTrue(state.isEmpty)
        assertEquals("--", state.headline)
        assertEquals("--", state.ageText)
        assertEquals("Open the app", state.hint)
    }

    @Test
    fun frameOf36MinutesShowsDashesAndHint() {
        val state = ClimateWidgetModel.build(snapshot("five_tiles"), receivedAt + 36 * 60_000L, config)
        assertTrue(state.isEmpty)
        assertEquals("Open the app", state.hint)
        val fresh = ClimateWidgetModel.build(snapshot("five_tiles"), receivedAt + 35 * 60_000L, config)
        assertFalse(fresh.isEmpty)
    }

    @Test
    fun sensorBatteryAlertGivesBatLowBadgeForSlotsOnly() {
        // Vector two_tiles has the FREEZER alert, so use extremes_hot_wet (all bits set) for the battery bit.
        val state = ClimateWidgetModel.build(snapshot("extremes_hot_wet"), receivedAt, config)
        assertTrue(state.rows[0].badges.isEmpty())
        assertEquals(listOf(ClimateWidgetModel.BADGE_BAT_LOW), state.rows[1].badges)
        assertTrue(state.alertNames.contains("SENSOR BATTERY"))
    }

    @Test
    fun sameSequenceRequestsNoUpdate() {
        val frame = snapshot("five_tiles").frame
        val key = ClimateWidgetModel.updateKey(frame)
        assertTrue(ClimateWidgetModel.shouldRequestUpdate(null, frame))
        assertFalse(ClimateWidgetModel.shouldRequestUpdate(key, frame))
        assertTrue(ClimateWidgetModel.shouldRequestUpdate(key + 1, frame))
        // The same sequence number on page 1 is a new frame.
        assertTrue(ClimateWidgetModel.shouldRequestUpdate(key, frame.copy(page = 1)))
    }

    @Test
    fun storedSnapshotRoundTrips() {
        val snap = snapshot("five_tiles")
        val decoded = WidgetSnapshotStore.decode(WidgetSnapshotStore.encode(snap))
        assertNotNull(decoded)
        assertEquals(snap.frame.sequence, decoded!!.frame.sequence)
        assertEquals(snap.frame.tiles, decoded.frame.tiles)
        assertEquals(snap.frame.alerts, decoded.frame.alerts)
        assertEquals(snap.receivedAtMillis, decoded.receivedAtMillis)
        val eleven = snap.copy(frame = snap.frame.copy(tiles = snap.frame.tiles + List(5) { snap.frame.tiles[0] }))
        assertEquals(11, WidgetSnapshotStore.decode(WidgetSnapshotStore.encode(eleven))!!.frame.tiles.size)
        val seven = snap.copy(frame = snap.frame.copy(tiles = snap.frame.tiles + snap.frame.tiles[0]))
        assertNull(WidgetSnapshotStore.decode(WidgetSnapshotStore.encode(seven)))
        assertNull(WidgetSnapshotStore.decode("garbage"))
        assertNull(WidgetSnapshotStore.decode("1;50;0;1000;-"))
        assertNull(WidgetSnapshotStore.decode("1;50;0;1000;NaN,5|-|-|-|-|-"))
        assertNull(WidgetSnapshotStore.decode(null))
    }
}
