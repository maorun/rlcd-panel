package com.rlcdpanel.watch.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotSwapCommandTest {
    private fun sensor(index: Int, state: String = "OK") =
        StatusReply.parse(listOf("SENSOR $index S$index temp=-- rh=-- bat=-- rssi=-- age=-- state=$state")).sensors.single()

    private val config = PanelConfig(mapOf("slot1_mac" to "AA:BB:CC:DD:EE:01", "slot3_mac" to "", "slot2_name" to "Bad"))

    @Test
    fun slots1And3GiveSlotSwap() {
        assertEquals("SLOT SWAP 1 3", SlotSwapCommand.line(1, 3))
        assertEquals("SLOT SWAP 10 2", SlotSwapCommand.line(10, 2))
    }

    @Test
    fun equalOrOutOfRangeSlotsGiveNoLine() {
        assertNull(SlotSwapCommand.line(3, 3))
        assertNull(SlotSwapCommand.line(0, 3))
        assertNull(SlotSwapCommand.line(3, 0))
        assertNull(SlotSwapCommand.line(11, 3))
        assertNull(SlotSwapCommand.line(3, 11))
        assertNull(SlotSwapCommand.line(-1, 2))
    }

    @Test
    fun fixedMacSlotCannotSwap() {
        assertFalse(SlotSwapCommand.canSwap(sensor(1), config))
        assertTrue(SlotSwapCommand.canSwap(sensor(2), config))
        assertTrue(SlotSwapCommand.canSwap(sensor(3), config))
        assertTrue(SlotSwapCommand.canSwap(sensor(10, "EMPTY"), config))
    }

    @Test
    fun onboardAndUnknownConfigCannotSwap() {
        assertFalse(SlotSwapCommand.canSwap(sensor(0), config))
        assertFalse(SlotSwapCommand.canSwap(sensor(3), PanelConfig()))
    }

    @Test
    fun confirmTextNamesBothSlots() {
        assertEquals("Platz 1 und 3 tauschen?", SlotSwapCommand.confirmText(1, 3))
    }

    @Test
    fun replyTexts() {
        assertEquals("Plätze 1 und 3 getauscht" to false, SlotSwapCommand.result(1, 3, ConsoleReply.Status("OK SLOT SWAP 1 3")))
        assertEquals(SlotSwapCommand.FIXED_MAC_TEXT to true, SlotSwapCommand.result(1, 3, ConsoleReply.Status("ERR FIXED_MAC")))
        assertEquals(SlotSwapCommand.SLOT_OFF_TEXT to true, SlotSwapCommand.result(1, 3, ConsoleReply.Status("ERR SLOT_OFF")))
        assertEquals(SlotSwapCommand.READ_ONLY_TEXT to true, SlotSwapCommand.result(1, 3, ConsoleReply.Status("ERR READ_ONLY")))
    }

    @Test
    fun otherRepliesGiveErrorText() {
        assertEquals("Panel meldet: ERR BAD_ARG" to true, SlotSwapCommand.result(1, 3, ConsoleReply.Status("ERR BAD_ARG")))
        assertEquals(SlotSwapCommand.UNKNOWN_TEXT to true, SlotSwapCommand.result(1, 3, ConsoleReply.Status("OK SLOT SWAP 3 1")))
        assertEquals(SlotSwapCommand.UNKNOWN_TEXT to true, SlotSwapCommand.result(1, 3, ConsoleReply.Frame(listOf("x"))))
        assertEquals(SlotSwapCommand.UNKNOWN_TEXT to true, SlotSwapCommand.result(1, 3, ConsoleReply.Corrupt(ReplyError.Timeout)))
        val line = "ERR " + "X".repeat(100)
        assertEquals("Panel meldet: ${line.take(80)}" to true, SlotSwapCommand.result(1, 3, ConsoleReply.Status(line)))
    }
}
