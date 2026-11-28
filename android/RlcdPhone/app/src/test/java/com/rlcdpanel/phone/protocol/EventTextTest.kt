package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventTextTest {
    private fun row(event: String, detail: Int) = EventRow("2026-11-18 09:00:00", event, detail)

    @Test
    fun resetGivesReasonAndAbnormalMark() {
        assertEquals("reset PANIC (abnormal)", EventText.describe(row("RESET", 4)))
        assertTrue(EventText.isAbnormal(row("RESET", 4)))
        assertEquals("reset POWERON", EventText.describe(row("RESET", 1)))
        assertFalse(EventText.isAbnormal(row("RESET", 1)))
        assertEquals("RESET,11", EventText.describe(row("RESET", 11)))
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
    fun slotEventsUseHighAndLowByte() {
        assertEquals("Slot 3 back", EventText.describe(row("SLOT_STALE", 0x0103)))
        assertEquals("Slot 2 lost", EventText.describe(row("SLOT_STALE", 0x0002)))
        assertEquals("SLOT_STALE,256", EventText.describe(row("SLOT_STALE", 0x0100)))
        assertEquals("Slot 2 released by the user", EventText.describe(row("SLOT_RELEASE", 0x0102)))
        assertEquals("slot 2 released", EventText.describe(row("SLOT_RELEASE", 0x0002)))
        assertEquals("SLOT_RELEASE,514", EventText.describe(row("SLOT_RELEASE", 0x0202)))
        assertEquals("slot 2 METER", EventText.describe(row("SLOT_ASSIGN", 0x0201)))
        assertEquals("SLOT_ASSIGN,5", EventText.describe(row("SLOT_ASSIGN", 5)))
    }

    @Test
    fun powerSourceGivesUsbAndBattery() {
        assertEquals("USB power connected", EventText.describe(row("POWER_SOURCE", 1)))
        assertEquals("Battery power", EventText.describe(row("POWER_SOURCE", 0)))
        assertEquals("POWER_SOURCE,2", EventText.describe(row("POWER_SOURCE", 2)))
    }

    @Test
    fun muteChangeGivesStateAndSource() {
        assertEquals("Chime muted (BOOT)", EventText.describe(row("MUTE_CHANGE", 0x0001)))
        assertEquals("Chime muted (USB)", EventText.describe(row("MUTE_CHANGE", 0x0101)))
        assertEquals("Chime on (BLE)", EventText.describe(row("MUTE_CHANGE", 0x0200)))
        assertEquals("Chime snoozed (USB)", EventText.describe(row("MUTE_CHANGE", 0x0102)))
        assertEquals("MUTE_CHANGE,771", EventText.describe(row("MUTE_CHANGE", 0x0303)))
        assertEquals("MUTE_CHANGE,770", EventText.describe(row("MUTE_CHANGE", 0x0302)))
    }

    @Test
    fun sdFaultGivesStatusText() {
        assertEquals("SD card fault: NO_CARD", EventText.describe(row("SD_FAULT", 2)))
        assertEquals("SD card OK", EventText.describe(row("SD_FAULT", 1)))
        assertEquals("SD_FAULT,9", EventText.describe(row("SD_FAULT", 9)))
    }

    @Test
    fun otherEventsMatchTheMacTexts() {
        assertEquals("HEAT alert on", EventText.describe(row("ALERT_RISE", 128)))
        assertEquals("ALERT_RISE,7", EventText.describe(row("ALERT_RISE", 7)))
        assertEquals("Alert HEAT acknowledged", EventText.describe(row("ALERT_ACK", 128)))
        assertEquals("config loaded, 1 rejected line", EventText.describe(row("CONFIG_LOAD", 1)))
        assertEquals("NTP sync WIFI_FAILED", EventText.describe(row("NTP_SYNC", 1)))
        assertEquals("battery low set at 15 %", EventText.describe(row("BATTERY_LOW_SET", 15)))
        assertEquals("empty battery protection set", EventText.describe(row("EMPTY_PROTECTION", 1)))
        assertEquals("Config set: wake_interval_s (BLE)", EventText.describe(row("CONFIG_SET", 0x0100 or 12)))
        assertEquals("Config set: key #200", EventText.describe(row("CONFIG_SET", 0x0100 or 200)))
        assertEquals("Config set: auto_assign_min_rssi (USB)", EventText.describe(row("CONFIG_SET", 94)))
        assertEquals("Config set: window_drop_c (USB)", EventText.describe(row("CONFIG_SET", 95)))
        assertEquals("Config set: mold_alert_delay_h (USB)", EventText.describe(row("CONFIG_SET", 96)))
        assertEquals("Config set: jump_limit_c (USB)", EventText.describe(row("CONFIG_SET", 99)))
        assertEquals("Config set: jump_limit_rh (USB)", EventText.describe(row("CONFIG_SET", 100)))
        assertEquals("Config set: drift_limit_c (USB)", EventText.describe(row("CONFIG_SET", 101)))
        assertEquals("Config set: drift_limit_rh (USB)", EventText.describe(row("CONFIG_SET", 102)))
        assertEquals("Config set: heat_alert_delay_h (USB)", EventText.describe(row("CONFIG_SET", 103)))
        assertEquals("CONFIG_SET,512", EventText.describe(row("CONFIG_SET", 0x0200)))
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
    fun unknownEventGivesRawText() {
        assertEquals("FUTURE_EVENT,7", EventText.describe(row("FUTURE_EVENT", 7)))
    }
}
