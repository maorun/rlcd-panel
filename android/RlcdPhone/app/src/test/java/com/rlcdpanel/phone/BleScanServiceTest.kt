package com.rlcdpanel.phone

import com.rlcdpanel.phone.protocol.AlertMask
import com.rlcdpanel.phone.protocol.AlertTransitions
import com.rlcdpanel.phone.protocol.TelemetryFrame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleScanServiceTest {

    @After
    fun tearDown() {
        BleScanService.resetForTest()
    }

    @Test
    fun testActionConstants() {
        assertEquals("com.rlcdpanel.phone.action.START_SCAN", BleScanService.ACTION_START)
        assertEquals("com.rlcdpanel.phone.action.STOP_SCAN", BleScanService.ACTION_STOP)
        assertEquals("rlcd_scan_service_channel", BleScanService.SERVICE_CHANNEL_ID)
        assertEquals("rlcd_ventilation_channel", BleScanService.ALERT_CHANNEL_ID)
        assertEquals(800, BleScanService.NOTIFICATION_ID)
        assertEquals(900, BleScanService.ALERT_NOTIFICATION_ID)
    }

    @Test
    fun testStateFlowUpdates() {
        BleScanService.setScanningForTest(true)
        assertTrue(BleScanService.isScanning.value)

        BleScanService.updateStatusMessage("Test Status")
        assertEquals("Test Status", BleScanService.statusMessage.value)

        BleScanService.setScanningForTest(false)
        assertFalse(BleScanService.isScanning.value)
    }

    @Test
    fun testSnapshotUpdateDirect() {
        val payload1 = hexToBytes("0208370103980850D4FE8200000000000000000000000091")
        val frame1 = TelemetryFrame.decodePayload(payload1)
        val snap1 = TelemetrySnapshot(
            frame = frame1,
            rssi = -65,
            address = "AA:BB:CC:DD:EE:01",
            receivedAtMillis = 1000L
        )

        BleScanService.updateSnapshotForTest(snap1)
        assertNotNull(BleScanService.snapshot.value)
        assertEquals(8, BleScanService.snapshot.value?.frame?.sequence)
        assertEquals("AA:BB:CC:DD:EE:01", BleScanService.snapshot.value?.address)

        BleScanService.updateSnapshotForTest(null)
        assertNull(BleScanService.snapshot.value)
    }

    @Test
    fun testComputeTransitionFirstFrame() {
        val payload1 = hexToBytes("0208370103980850D4FE8200000000000000000000000091")
        val frame1 = TelemetryFrame.decodePayload(payload1)
        val incoming = TelemetrySnapshot(
            frame = frame1,
            rssi = -70,
            address = "AA:BB:CC:DD:EE:01",
            receivedAtMillis = 1000L
        )

        val transition = BleScanService.computeTransition(null, incoming)
        assertEquals("Seq #8 empfangen", transition.statusMessage)
        assertEquals(8, transition.snapshot.frame.sequence)
        assertTrue(transition.risingAlerts.contains(AlertMask.FREEZER))
    }

    @Test
    fun testComputeTransitionSubsequentMergeAndAlerts() {
        val payload1 = hexToBytes("0208370103980850D4FE8200000000000000000000000091")
        val frame1 = TelemetryFrame.decodePayload(payload1)
        val snap1 = TelemetrySnapshot(
            frame = frame1,
            rssi = -70,
            address = "AA:BB:CC:DD:EE:01",
            receivedAtMillis = 1000L
        )

        val payload2 = hexToBytes("0209550303980850D4FE8200000000000000000000000091")
        // Sequence 9, alertMask = 3 (FREEZER + HUMIDITY)
        val frame2 = TelemetryFrame(
            sequence = 9,
            cellPercent = 85,
            alerts = AlertMask(3),
            tiles = frame1.tiles,
            page = 0
        )
        val snap2 = TelemetrySnapshot(
            frame = frame2,
            rssi = -68,
            address = "AA:BB:CC:DD:EE:01",
            receivedAtMillis = 2000L
        )

        val transition = BleScanService.computeTransition(snap1, snap2)
        assertEquals("Seq #9 empfangen", transition.statusMessage)
        assertEquals(9, transition.snapshot.frame.sequence)
        assertEquals(TelemetryFrame.TOTAL_TILES, transition.snapshot.frame.tiles.size)
        // HUMIDITY is new (rising), FREEZER was already active
        assertTrue(transition.risingAlerts.contains(AlertMask.HUMIDITY))
        assertFalse(transition.risingAlerts.contains(AlertMask.FREEZER))
    }

    @Test
    fun testFrameMergePreservesTiles() {
        val payload1 = hexToBytes("0208370103980850D4FE8200000000000000000000000091")
        val frame1 = TelemetryFrame.decodePayload(payload1)
        val merged = TelemetryFrame.merge(frame1, frame1)
        assertEquals(frame1.sequence, merged.sequence)
        assertEquals(TelemetryFrame.TOTAL_TILES, merged.tiles.size)
        assertTrue(merged.tiles[0].isValid)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) +
                Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
