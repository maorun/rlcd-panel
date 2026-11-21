package com.rlcdpanel.watch.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AckCommandTest {

    @Test
    fun lineCoversBits0To7() {
        for (bit in 0..7) assertEquals("ACK $bit", AckCommand.line(bit))
    }

    @Test
    fun lineRejectsBitsOutsideRange() {
        assertThrows(IllegalArgumentException::class.java) { AckCommand.line(8) }
        assertThrows(IllegalArgumentException::class.java) { AckCommand.line(-1) }
    }

    @Test
    fun resultTexts() {
        assertEquals("Acknowledged", AckCommand.resultText(ConsoleReply.Status("OK ACK 1")))
        assertEquals("The alert is off", AckCommand.resultText(ConsoleReply.Status("ERR NOT_ACTIVE")))
        assertEquals("Set ble_console=2 to apply", AckCommand.resultText(ConsoleReply.Status("ERR READ_ONLY")))
        assertEquals("Panel not in range", AckCommand.resultText(ConsoleReply.Corrupt(ReplyError.Timeout)))
        assertEquals("Panel not in range", AckCommand.resultText(null))
    }

    @Test
    fun risingChargeFullGivesNoAction() {
        assertEquals(emptyList<Int>(), AckCommand.actionBits(AlertMask.CHARGE_FULL))
    }

    @Test
    fun risingHumidityGivesOneActionWithBit1() {
        assertEquals(listOf(1), AckCommand.actionBits(AlertMask.HUMIDITY))
    }

    @Test
    fun mixedMaskSkipsOnlyChargeFull() {
        val mask = AlertMask(AlertMask.CHARGE_FULL.rawValue or AlertMask.HEAT.rawValue or AlertMask.FREEZER.rawValue)
        assertEquals(listOf(0, 7), AckCommand.actionBits(mask))
    }

    @Test
    fun otherRepliesHaveFallbackTexts() {
        assertEquals("Panel not in range", AckCommand.resultText(ConsoleReply.Status("ERR BLUETOOTH_OFF")))
        assertEquals("Panel not in range", AckCommand.resultText(ConsoleReply.Status("ERR NO_CONSOLE")))
        assertEquals("Panel replied: ERR BAD_ARG", AckCommand.resultText(ConsoleReply.Status("ERR BAD_ARG")))
        assertEquals("Bad reply from the panel", AckCommand.resultText(ConsoleReply.Corrupt(ReplyError.TooLarge)))
        assertEquals("Bad reply from the panel", AckCommand.resultText(ConsoleReply.Frame(listOf("X"))))
    }

    @Test
    fun emptyMaskGivesNoAction() {
        assertEquals(emptyList<Int>(), AckCommand.actionBits(AlertMask.NONE))
    }

    @Test
    fun allActionNeedsTwoRepeatableBits() {
        val humidityVentilate = AlertMask.HUMIDITY.rawValue or AlertMask.VENTILATE.rawValue
        val humidityChargeFull = AlertMask.HUMIDITY.rawValue or AlertMask.CHARGE_FULL.rawValue
        assertTrue(AckCommand.canAcknowledgeAll(humidityVentilate, 0))
        assertFalse(AckCommand.canAcknowledgeAll(humidityChargeFull, 0))
        assertFalse(AckCommand.canAcknowledgeAll(AlertMask.HUMIDITY.rawValue, 0))
        assertEquals("ACK ALL", AckCommand.ALL)
    }

    @Test
    fun allResultTextNamesNewBits() {
        assertEquals("Acknowledged: Humidity, Ventilate", AckCommand.resultText(ConsoleReply.Status("OK ACK ALL 22")))
        assertEquals("The alert is off", AckCommand.resultText(ConsoleReply.Status("ERR NOT_ACTIVE")))
    }
}
