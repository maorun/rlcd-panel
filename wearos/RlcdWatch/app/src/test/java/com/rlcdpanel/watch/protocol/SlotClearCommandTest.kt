package com.rlcdpanel.watch.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotClearCommandTest {
    private fun sensor(index: Int, state: String) =
        StatusReply.parse(listOf("SENSOR $index S$index temp=-- rh=-- bat=-- rssi=-- age=-- state=$state")).sensors.single()

    private val config = PanelConfig(mapOf("slot1_mac" to "AA:BB:CC:DD:EE:01", "slot3_mac" to "", "slot2_name" to "Bad"))

    @Test
    fun slot3GivesSlotClear3() {
        assertEquals("SLOT CLEAR 3", SlotClearCommand.line(3))
        assertEquals("SLOT CLEAR 10", SlotClearCommand.line(10))
    }

    @Test
    fun slotsOutsideRangeThrow() {
        assertThrows(IllegalArgumentException::class.java) { SlotClearCommand.line(0) }
        assertThrows(IllegalArgumentException::class.java) { SlotClearCommand.line(11) }
    }

    @Test
    fun replyTexts() {
        assertEquals("Platz 3 freigegeben" to false, SlotClearCommand.result(3, ConsoleReply.Status("OK SLOT CLEAR 3")))
        assertEquals(SlotClearCommand.FIXED_MAC_TEXT to true, SlotClearCommand.result(3, ConsoleReply.Status("ERR FIXED_MAC")))
        assertEquals(SlotClearCommand.READ_ONLY_TEXT to true, SlotClearCommand.result(3, ConsoleReply.Status("ERR READ_ONLY")))
    }

    @Test
    fun unknownReplyGivesErrorText() {
        assertEquals("Panel meldet: ERR BAD_ARG" to true, SlotClearCommand.result(3, ConsoleReply.Status("ERR BAD_ARG")))
        assertEquals("Panel meldet: ERR PERMISSION" to true, SlotClearCommand.result(3, ConsoleReply.Status("ERR PERMISSION")))
        assertEquals(SlotClearCommand.UNKNOWN_TEXT to true, SlotClearCommand.result(3, ConsoleReply.Status("OK SLOT CLEAR 4")))
        assertEquals(SlotClearCommand.UNKNOWN_TEXT to true, SlotClearCommand.result(3, ConsoleReply.Frame(listOf("x"))))
        assertEquals(SlotClearCommand.UNKNOWN_TEXT to true, SlotClearCommand.result(3, ConsoleReply.Corrupt(ReplyError.Timeout)))
    }

    @Test
    fun longErrLineIsCutAt80Characters() {
        val line = "ERR " + "X".repeat(100)
        assertEquals("Panel meldet: ${line.take(80)}" to true, SlotClearCommand.result(3, ConsoleReply.Status(line)))
    }

    @Test
    fun staleSlotWithoutFixedMacGetsButton() {
        assertTrue(SlotClearCommand.canFree(sensor(3, "STALE"), config))
        assertTrue(SlotClearCommand.canFree(sensor(2, "STALE"), config))
    }

    @Test
    fun zeroOrMalformedMacCountsAsNoFixedMac() {
        val loose = PanelConfig(mapOf("slot10_mac" to "00:00:00:00:00:00", "slot4_mac" to "xyz", "slot5_mac" to "AA:BB:CC:DD:EE:05"))
        assertTrue(SlotClearCommand.canFree(sensor(10, "STALE"), loose))
        assertTrue(SlotClearCommand.canFree(sensor(4, "STALE"), loose))
        assertFalse(SlotClearCommand.canFree(sensor(5, "STALE"), loose))
    }

    @Test
    fun slotWithFixedMacGetsNoButton() {
        assertFalse(SlotClearCommand.canFree(sensor(1, "STALE"), config))
    }

    @Test
    fun otherRowsGetNoButton() {
        assertFalse(SlotClearCommand.canFree(sensor(3, "OK"), config))
        assertFalse(SlotClearCommand.canFree(sensor(3, "EMPTY"), config))
        assertFalse(SlotClearCommand.canFree(sensor(0, "STALE"), config))
        assertFalse(SlotClearCommand.canFree(sensor(3, "STALE"), PanelConfig()))
    }
}
