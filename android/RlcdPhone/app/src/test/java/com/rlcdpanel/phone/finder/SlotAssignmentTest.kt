package com.rlcdpanel.phone.finder

import com.rlcdpanel.phone.protocol.ConsoleReply
import com.rlcdpanel.phone.protocol.PanelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotAssignmentTest {
    private val mac = "a4:c1:38:11:22:33"

    @Test
    fun batchOrdersMacBeforeNameAndUpperCasesName() {
        assertEquals(
            listOf("SET slot3_mac=A4:C1:38:11:22:33", "SET slot3_name=KUECHE"),
            SlotAssignment.batch(3, mac, "kueche")
        )
    }

    @Test
    fun emptyNameSendsMacOnly() {
        assertEquals(listOf("SET slot10_mac=A4:C1:38:11:22:33"), SlotAssignment.batch(10, mac, null))
        assertEquals(listOf("SET slot1_mac=A4:C1:38:11:22:33"), SlotAssignment.batch(1, mac, ""))
    }

    @Test
    fun badInputGivesNull() {
        assertNull(SlotAssignment.batch(0, mac, null))
        assertNull(SlotAssignment.batch(11, mac, null))
        assertNull(SlotAssignment.batch(1, "A4:C1:38:11:22", null))
        assertNull(SlotAssignment.batch(1, "00:00:00:00:00:00", null))
        assertNull(SlotAssignment.batch(1, mac, "ABCDEFGHIJKLM")) // 13 characters
        assertNotNull(SlotAssignment.batch(1, mac, "ABCDEFGHIJKL")) // 12 characters
        assertNull(SlotAssignment.batch(1, mac, "A B"))
        assertNull(SlotAssignment.batch(1, mac, "A=B"))
    }

    @Test
    fun duplicateMacOfAnotherSlotIsReported() {
        val config = PanelConfig(mapOf("slot2_mac" to "A4:C1:38:11:22:33"))
        assertEquals(listOf(2), SlotAssignment.conflicts(1, mac, config))
        assertTrue(SlotAssignment.warning(1, mac, config)!!.contains("Slot 2"))
        // The slot that holds the MAC may take it again.
        assertTrue(SlotAssignment.conflicts(2, mac, config).isEmpty())
        assertNull(SlotAssignment.warning(2, mac, config))
    }

    @Test
    fun runSendsSetCommandsInOrderThenConfig() {
        val sent = mutableListOf<String>()
        var result: SlotAssignment.Result? = null
        SlotAssignment.run(
            SlotAssignment.batch(2, mac, "bad")!!,
            { cmd, cb -> sent += cmd; cb(if (cmd == "CONFIG") ConsoleReply.Frame(listOf("SOURCE SD")) else ConsoleReply.Status("OK SET")) }
        ) { result = it }
        assertEquals(listOf("SET slot2_mac=A4:C1:38:11:22:33", "SET slot2_name=BAD", "CONFIG"), sent)
        assertTrue(result!!.ok)
        assertEquals(2, result!!.applied.size)
    }

    @Test
    fun firstErrStopsTheBatchBeforeConfig() {
        val sent = mutableListOf<String>()
        var result: SlotAssignment.Result? = null
        SlotAssignment.run(
            SlotAssignment.batch(2, mac, "bad")!!,
            { cmd, cb -> sent += cmd; cb(ConsoleReply.Status("ERR BAD_VALUE")) }
        ) { result = it }
        assertEquals(listOf("SET slot2_mac=A4:C1:38:11:22:33"), sent)
        assertFalse(result!!.ok)
        assertEquals("ERR BAD_VALUE", result!!.failure)
        assertNull(result!!.config)
    }

    @Test
    fun readOnlyMapsToHint() {
        var result: SlotAssignment.Result? = null
        SlotAssignment.run(
            SlotAssignment.batch(1, mac, null)!!,
            { _, cb -> cb(ConsoleReply.Status("ERR READ_ONLY")) }
        ) { result = it }
        assertEquals("Set ble_console=2 to apply", result!!.failure)
        assertTrue(result!!.summary.startsWith("Set ble_console=2 to apply"))
    }

    @Test
    fun errOnNameStopsAfterMacAndReportsUnknownState() {
        val sent = mutableListOf<String>()
        var result: SlotAssignment.Result? = null
        SlotAssignment.run(
            SlotAssignment.batch(2, mac, "bad")!!,
            { cmd, cb -> sent += cmd; cb(ConsoleReply.Status(if (cmd.contains("_name")) "ERR BAD_VALUE" else "OK SET")) }
        ) { result = it }
        assertEquals(2, sent.size)
        assertEquals(1, result!!.applied.size)
        assertTrue(result!!.summary.contains("CONFIG lesen"))
    }

    @Test
    fun corruptConfigReplyIsReported() {
        var result: SlotAssignment.Result? = null
        SlotAssignment.run(
            SlotAssignment.batch(1, mac, null)!!,
            { cmd, cb ->
                cb(if (cmd == "CONFIG") ConsoleReply.Corrupt(com.rlcdpanel.phone.protocol.ReplyError.Timeout) else ConsoleReply.Status("OK SET"))
            }
        ) { result = it }
        assertTrue(result!!.configFailed)
        assertFalse(result!!.ok)
        assertTrue(result!!.summary.contains("CONFIG lesen fehlgeschlagen"))
    }
}
