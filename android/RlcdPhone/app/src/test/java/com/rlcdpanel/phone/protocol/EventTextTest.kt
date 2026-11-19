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
        assertEquals("CONFIG_SET,512", EventText.describe(row("CONFIG_SET", 0x0200)))
    }

    @Test
    fun clockSetGivesActionAndTransport() {
        assertEquals("Clock set (USB)", EventText.describe(row("CLOCK_SET", 0x0000)))
        assertEquals("RTC trimmed (BLE)", EventText.describe(row("CLOCK_SET", 0x0101)))
        assertEquals("CLOCK_SET,2", EventText.describe(row("CLOCK_SET", 0x0002)))
    }

    @Test
    fun unknownEventGivesRawText() {
        assertEquals("FUTURE_EVENT,7", EventText.describe(row("FUTURE_EVENT", 7)))
    }
}
