package com.rlcdpanel.watch.protocol

import com.rlcdpanel.watch.SaltState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SaltTextTest {
    @Test
    fun skipAndUnstableHaveReadableTexts() {
        assertEquals("SKIP slot1: nur 3 h, mindestens 6 h nötig", SaltText.outcome(SaltReply.parse(listOf("SKIP slot1 hours=3"))))
        assertEquals(
            "UNSTABLE slot1: Schwankung 1.5 %RH, Messung zu unruhig",
            SaltText.outcome(SaltReply.parse(listOf("UNSTABLE slot1 span=1.5")))
        )
    }

    @Test
    fun errorLinesHaveReadableTexts() {
        assertEquals("ERR BAD_TEMP: Temperatur außerhalb 10 bis 35 °C", SaltText.status("ERR BAD_TEMP"))
        assertEquals("Set ble_console=2 to apply", SaltText.status("ERR READ_ONLY"))
        assertTrue(SaltText.status("ERR BAD_ARG").startsWith("ERR BAD_ARG"))
    }

    @Test
    fun measuredResultHasTheMeanAndTheReference() {
        val r = SaltReply.parse(listOf("SALT slot2 hours=6 rh_mean=73.0 rh_span=0.4 temp_mean=25.0 reference=75.3"))
        assertEquals("slot2: 6 h, Mittel 73.0 %RH, Soll 75.3 %RH", SaltText.outcome(r))
    }

    @Test
    fun pickerListsOnboardAndSlotsWithMacOrName() {
        val config = PanelConfig.parseText("slot2_mac=AA:BB:CC:DD:EE:02\nslot7_name=Bad\nslot10_mac=AA:BB:CC:DD:EE:10\n")
        assertEquals(listOf(0, 2, 7, 10), SaltPicker.sensors(config))
        assertEquals(listOf("Onboard", "Slot 2", "Bad", "Slot 10"), SaltPicker.sensors(config).map { config.tileName(it) })
    }

    @Test
    fun pickerKeepsTheSelectedSensorEvenWithoutMacOrName() {
        val config = PanelConfig.parseText("slot2_mac=AA:BB:CC:DD:EE:02\n")
        assertEquals(listOf(0, 2, 5), SaltPicker.sensors(config, selected = 5))
        assertEquals(listOf(0, 2), SaltPicker.sensors(config, selected = 2))
    }

    @Test
    fun sensorChangeClearsBothReplies() {
        val reply = SaltReply.parse(listOf("SALT slot2 hours=6 rh_mean=73.0 rh_span=0.4 temp_mean=25.0 reference=75.3"))
        val state = SaltState().withReply(false, reply).withReply(true, reply)
        assertTrue(state.naCl != null && state.mgCl2 != null)
        val changed = state.selectSensor()
        assertEquals(SaltState(), changed)
    }

    @Test
    fun magnesiumLineHasTheSensor() {
        assertEquals("SALT 7 MGCL2", SaltCommand.line(7, true))
        assertEquals("SALT 7", SaltCommand.line(7, false))
    }
}
