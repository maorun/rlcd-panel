package com.rlcdpanel.watch.protocol

import com.rlcdpanel.watch.EventLogState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventTextTest {
    private fun row(event: String, detail: Int) = EventRow("2026-11-18 09:00:00", event, detail)

    private fun sampleLog(): EventsLog {
        val lines = Fixtures.file("events_reply_sample.txt").readLines().filterNot { it.startsWith("CRC32") }
        return EventsReply.parse(lines)
    }

    @Test
    fun resetGivesReasonAndAbnormalMark() {
        assertEquals("reset PANIC (abnormal)", EventText.describe(row("RESET", 4)))
        assertTrue(EventText.isAbnormal(row("RESET", 4)))
        assertFalse(EventText.isAbnormal(row("RESET", 1)))
    }

    @Test
    fun jumpLimitKeysGiveConfigSetTexts() {
        assertEquals("Config set: jump_limit_c (USB)", EventText.describe(row("CONFIG_SET", 99)))
        assertEquals("Config set: jump_limit_rh (BLE)", EventText.describe(row("CONFIG_SET", 0x0100 or 100)))
        assertEquals("Config set: drift_limit_c (USB)", EventText.describe(row("CONFIG_SET", 101)))
        assertEquals("Config set: drift_limit_rh (BLE)", EventText.describe(row("CONFIG_SET", 0x0100 or 102)))
        assertEquals("Config set: heat_alert_delay_h (BLE)", EventText.describe(row("CONFIG_SET", 0x0100 or 103)))
    }

    @Test
    fun sensorDriftGivesTexts() {
        assertEquals("Sensor drift (tile 3)", EventText.describe(row("SENSOR_DRIFT", 0x0103)))
        assertEquals("Sensor drift gone (Onboard)", EventText.describe(row("SENSOR_DRIFT", 0x0000)))
        assertEquals("SENSOR_DRIFT,523", EventText.describe(row("SENSOR_DRIFT", 0x020B)))
        assertEquals("Sensor drift (Onboard)", EventText.describe(row("SENSOR_DRIFT", 0x0100)))
        assertEquals("Sensor drift gone (tile 2)", EventText.describe(row("SENSOR_DRIFT", 2)))
        assertEquals(EventFilter.Category.SENSORS, EventFilter.category("SENSOR_DRIFT"))
    }

    @Test
    fun receptionLowGivesWeakAndGood() {
        assertEquals("Slot 3 weak signal", EventText.describe(row("RECEPTION_LOW", 0x0103)))
        assertEquals("Slot 3 signal good", EventText.describe(row("RECEPTION_LOW", 0x0003)))
        assertEquals("RECEPTION_LOW,256", EventText.describe(row("RECEPTION_LOW", 0x0100)))
        assertEquals("RECEPTION_LOW,523", EventText.describe(row("RECEPTION_LOW", 0x020B)))
        assertEquals("Wall damp (tile 3)", EventText.describe(row("WALL_DAMP", 0x0103)))
        assertEquals("Wall dry again (Onboard)", EventText.describe(row("WALL_DAMP", 0x0000)))
        assertEquals("Dry air (tile 10)", EventText.describe(row("DRY_AIR", 0x010A)))
        assertEquals("Air humidity good (tile 1)", EventText.describe(row("DRY_AIR", 0x0001)))
        assertEquals("WALL_DAMP,512", EventText.describe(row("WALL_DAMP", 0x0200)))
        assertEquals("DRY_AIR,11", EventText.describe(row("DRY_AIR", 0x000B)))
    }

    @Test
    fun heatTileGivesHotAndGood() {
        assertEquals("Heat (tile 10)", EventText.describe(row("HEAT_TILE", 0x010A)))
        assertEquals("Heat (Onboard)", EventText.describe(row("HEAT_TILE", 0x0100)))
        assertEquals("Heat good again (tile 1)", EventText.describe(row("HEAT_TILE", 0x0001)))
        assertEquals("HEAT_TILE,11", EventText.describe(row("HEAT_TILE", 0x000B)))
        assertEquals("HEAT_TILE,267", EventText.describe(row("HEAT_TILE", 0x010B)))
        assertEquals("HEAT_TILE,512", EventText.describe(row("HEAT_TILE", 0x0200)))
    }

    @Test
    fun sensorStuckGivesFrozenAndGood() {
        assertEquals("Sensor frozen (tile 3)", EventText.describe(row("SENSOR_STUCK", 0x0103)))
        assertEquals("Sensor frozen (Onboard)", EventText.describe(row("SENSOR_STUCK", 0x0100)))
        assertEquals("Sensor good again (tile 10)", EventText.describe(row("SENSOR_STUCK", 0x000A)))
        assertEquals("Sensor good again (Onboard)", EventText.describe(row("SENSOR_STUCK", 0x0000)))
        assertEquals("SENSOR_STUCK,11", EventText.describe(row("SENSOR_STUCK", 0x000B)))
        assertEquals("SENSOR_STUCK,512", EventText.describe(row("SENSOR_STUCK", 0x0200)))
        assertEquals(EventFilter.Category.SENSORS, EventFilter.category("SENSOR_STUCK"))
    }

    @Test
    fun scanFaultGivesFailedAndBack() {
        assertEquals("BLE scan start failed", EventText.describe(row("SCAN_FAULT", 1)))
        assertEquals("BLE scan works again", EventText.describe(row("SCAN_FAULT", 0)))
        assertEquals("SCAN_FAULT,2", EventText.describe(row("SCAN_FAULT", 2)))
        assertEquals("SCAN_FAULT,256", EventText.describe(row("SCAN_FAULT", 256)))
    }

    @Test
    fun windowOpenGivesTileText() {
        assertEquals("Window open (Onboard)", EventText.describe(row("WINDOW_OPEN", 0x0100)))
        assertEquals("Window closed (Onboard)", EventText.describe(row("WINDOW_OPEN", 0x0000)))
        assertEquals("Window open (tile 3)", EventText.describe(row("WINDOW_OPEN", 0x0103)))
        assertEquals("Window closed (tile 10)", EventText.describe(row("WINDOW_OPEN", 0x000A)))
        assertEquals("WINDOW_OPEN,523", EventText.describe(row("WINDOW_OPEN", 523)))
        assertEquals("WINDOW_OPEN,11", EventText.describe(row("WINDOW_OPEN", 11)))
    }

    @Test
    fun jumpDropGivesSlotText() {
        assertEquals("Slot 3 reading jump dropped", EventText.describe(row("JUMP_DROP", 3)))
        assertEquals("Slot 10 reading jump dropped", EventText.describe(row("JUMP_DROP", 10)))
        assertEquals("JUMP_DROP,0", EventText.describe(row("JUMP_DROP", 0)))
        assertEquals("JUMP_DROP,11", EventText.describe(row("JUMP_DROP", 11)))
        assertEquals("JUMP_DROP,259", EventText.describe(row("JUMP_DROP", 259)))
    }

    @Test
    fun slotStaleUsesHighAndLowByte() {
        assertEquals("Slot 3 back", EventText.describe(row("SLOT_STALE", 259)))
    }

    @Test
    fun powerSourceGivesUsbText() {
        assertEquals("USB power connected", EventText.describe(row("POWER_SOURCE", 1)))
    }

    @Test
    fun clockSetGivesActionAndTransport() {
        assertEquals("Clock set (USB)", EventText.describe(row("CLOCK_SET", 0x0000)))
        assertEquals("RTC trimmed (BLE)", EventText.describe(row("CLOCK_SET", 0x0101)))
        assertEquals("CLOCK_SET,2", EventText.describe(row("CLOCK_SET", 0x0002)))
        assertEquals("Onboard sensor fault (NACK)", EventText.describe(row("SHTC3_FAULT", 0x0101)))
        assertEquals("Onboard sensor back", EventText.describe(row("SHTC3_FAULT", 0x0000)))
        assertEquals("SHTC3_FAULT,1025", EventText.describe(row("SHTC3_FAULT", 0x0401)))
        assertEquals("SHTC3_FAULT,1", EventText.describe(row("SHTC3_FAULT", 0x0001)))
        assertEquals("SHTC3_FAULT,256", EventText.describe(row("SHTC3_FAULT", 0x0100)))
        assertEquals("SHTC3_FAULT,2", EventText.describe(row("SHTC3_FAULT", 0x0002)))
        assertEquals("Slot 4 battery low", EventText.describe(row("SENSOR_BATTERY", 260)))
        assertEquals("Slot 4 battery OK", EventText.describe(row("SENSOR_BATTERY", 4)))
        assertEquals("SENSOR_BATTERY,11", EventText.describe(row("SENSOR_BATTERY", 11)))
        assertEquals("SENSOR_BATTERY,516", EventText.describe(row("SENSOR_BATTERY", 516)))
    }

    @Test
    fun screenModelKeepsNewestEightRowsNewestFirst() {
        val log = sampleLog()
        val state = EventLogState(rows = log.rows, dropped = log.dropped, loaded = true)
        assertEquals(8, state.visibleRows.size)
        assertEquals(log.rows.last(), state.visibleRows.first())
        assertEquals(log.rows[log.rows.size - 8], state.visibleRows.last())
        assertEquals(listOf("FUTURE_EVENT", "WINDOW_OPEN", "SCAN_FAULT"), state.visibleRows.take(3).map { it.event })
    }

    @Test
    fun timeShowsHoursAndMinutes() {
        assertEquals("09:01", EventLogState.formatTime("2026-11-18T09:01:30"))
        assertEquals("09:01", EventLogState.formatTime("2026-11-18 09:01:30"))
        assertEquals(EventLogState.UNKNOWN_TIME, EventLogState.formatTime(""))
    }
}
