package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotConfigTest {
    private val mac = "a4:c1:38:11:22:33"
    private val empty = PanelConfig()

    private fun commands(plan: SlotConfig.Plan) = (plan as SlotConfig.Plan.Commands).commands
    private fun reason(plan: SlotConfig.Plan) = (plan as SlotConfig.Plan.Invalid).reason

    @Test
    fun defineSetsMacThenUpperCasedName() {
        assertEquals(
            listOf("SET slot4_mac=A4:C1:38:11:22:33", "SET slot4_name=KUECHE-1"),
            commands(SlotConfig.define(4, mac, "kueche-1", empty))
        )
    }

    @Test
    fun emptyNameSendsMacOnly() {
        assertEquals(
            listOf("SET slot10_mac=A4:C1:38:11:22:33"),
            commands(SlotConfig.define(10, mac, "", empty))
        )
    }

    @Test
    fun clearUsesEmptySetValues() {
        assertEquals(listOf("SET slot2_mac="), commands(SlotConfig.clear(2)))
        assertEquals(listOf("SET slot2_mac="), commands(SlotConfig.define(2, "", "", empty)))
    }

    @Test
    fun duplicateMacIsAConflict() {
        val config = PanelConfig(mapOf("slot2_mac" to "A4:C1:38:11:22:33"))
        assertTrue(reason(SlotConfig.define(1, mac, "A", config)).contains("Slot 2"))
        // The slot that holds the MAC may keep it.
        assertEquals(2, commands(SlotConfig.define(2, mac, "A", config)).size)
    }

    @Test
    fun badNamesAreRejected() {
        assertTrue(SlotConfig.define(1, mac, "ABCDEFGHIJKLM", empty) is SlotConfig.Plan.Invalid)
        assertTrue(SlotConfig.define(1, mac, "A B", empty) is SlotConfig.Plan.Invalid)
        assertTrue(SlotConfig.define(1, mac, "A=B", empty) is SlotConfig.Plan.Invalid)
        assertTrue(SlotConfig.define(1, mac, "KÜCHE", empty) is SlotConfig.Plan.Invalid)
        assertTrue(SlotConfig.define(1, mac, "ABCDEFGHIJKL", empty) is SlotConfig.Plan.Commands)
        assertNull(SlotConfig.nameError("A-1"))
    }

    @Test
    fun badMacSlotAndNameWithoutMacAreRejected() {
        assertTrue(SlotConfig.define(1, "A4:C1:38", "A", empty) is SlotConfig.Plan.Invalid)
        assertTrue(SlotConfig.define(1, "00:00:00:00:00:00", "A", empty) is SlotConfig.Plan.Invalid)
        assertTrue(SlotConfig.define(11, mac, "A", empty) is SlotConfig.Plan.Invalid)
        assertTrue(SlotConfig.define(1, "", "A", empty) is SlotConfig.Plan.Invalid)
        assertTrue(SlotConfig.clear(0) is SlotConfig.Plan.Invalid)
    }

    @Test
    fun autoAssignToggle() {
        assertEquals(listOf("SET slot_auto_assign=1"), SlotConfig.autoAssign(true))
        assertEquals(listOf("SET slot_auto_assign=0"), SlotConfig.autoAssign(false))
    }

    @Test
    fun configAccessors() {
        val config = PanelConfig(mapOf("slot3_name" to "BAD", "slot_auto_assign" to "0"))
        assertEquals("BAD", config.slotName(3))
        assertNull(config.slotName(4))
        assertEquals(false, config.slotAutoAssign)
        assertEquals(true, empty.slotAutoAssign)
    }
}
