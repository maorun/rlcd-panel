package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotConfigTest {
    private val mac = "a4:c1:38:11:22:33"
    private val empty = PanelConfig()

    private fun commands(plan: SlotConfig.Plan) = (plan as SlotConfig.Plan.Commands).commands
    private fun reason(plan: SlotConfig.Plan) = (plan as SlotConfig.Plan.Invalid).reason

    @Test
    fun setEnabledSendsZeroOrOne() {
        assertEquals(listOf("SET slot3_enabled=0"), SlotConfig.setEnabled(3, false))
        assertEquals(listOf("SET slot3_enabled=1"), SlotConfig.setEnabled(3, true))
        assertTrue(SlotConfig.setEnabled(11, true).isEmpty())
    }

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
        // The slot that holds the MAC may keep it; only the name is sent.
        assertEquals(listOf("SET slot2_name=A"), commands(SlotConfig.define(2, mac, "A", config)))
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
        assertTrue(SlotConfig.define(1, "", "A B", empty) is SlotConfig.Plan.Invalid)
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

    @Test
    fun nameWithoutMacSetsNameOnly() {
        assertEquals(listOf("SET slot1_name=KITCHEN"), commands(SlotConfig.define(1, "", "kitchen", empty)))
    }

    @Test
    fun nameWithMacSetsBothCommands() {
        assertEquals(
            listOf("SET slot1_mac=A4:C1:38:11:22:33", "SET slot1_name=KITCHEN"),
            commands(SlotConfig.define(1, mac, "kitchen", empty))
        )
    }

    @Test
    fun nameWithoutMacClearsAStoredFixedMac() {
        val config = PanelConfig(mapOf("slot1_mac" to "A4:C1:38:11:22:33"))
        assertEquals(
            listOf("SET slot1_mac=", "SET slot1_name=KITCHEN"),
            commands(SlotConfig.define(1, "", "kitchen", config))
        )
    }

    @Test
    fun invalidNameWithoutMacIsRejected() {
        assertTrue(SlotConfig.define(1, "", "BAD NAME", empty) is SlotConfig.Plan.Invalid)
        assertTrue(SlotConfig.define(1, "", "ABCDEFGHIJKLM", empty) is SlotConfig.Plan.Invalid)
    }

    @Test
    fun emptyNameAndMacClearTheSlot() {
        assertEquals(listOf("SET slot1_mac="), commands(SlotConfig.define(1, "", "", empty)))
    }

    @Test
    fun canWriteOnlyForBleConsoleTwo() {
        assertEquals(false, empty.canWrite)
        assertEquals(0, empty.bleConsole)
        assertEquals(false, PanelConfig(mapOf("ble_console" to "1")).canWrite)
        assertEquals(true, PanelConfig(mapOf("ble_console" to " 2 ")).canWrite)
        assertEquals(false, PanelConfig(mapOf("ble_console" to "x")).canWrite)
    }

    private val stored = PanelConfig(mapOf("slot1_mac" to "A4:C1:38:11:22:33", "slot1_name" to "KITCHEN"))

    @Test
    fun onlyNameChangedSendsNameOnly() {
        assertEquals(listOf("SET slot1_name=BATH"), commands(SlotConfig.define(1, mac, "bath", stored)))
    }

    @Test
    fun onlyMacChangedSendsMacOnly() {
        assertEquals(
            listOf("SET slot1_mac=A4:C1:38:99:99:99"),
            commands(SlotConfig.define(1, "a4:c1:38:99:99:99", "kitchen", stored))
        )
    }

    @Test
    fun macAndNameChangedSendBoth() {
        assertEquals(
            listOf("SET slot1_mac=A4:C1:38:99:99:99", "SET slot1_name=BATH"),
            commands(SlotConfig.define(1, "a4:c1:38:99:99:99", "bath", stored))
        )
    }

    @Test
    fun nothingChangedSendsNothing() {
        assertEquals(emptyList<String>(), commands(SlotConfig.define(1, mac, "Kitchen", stored)))
        assertEquals(emptyList<String>(), commands(SlotConfig.define(1, mac, "", stored)))
    }

    @Test
    fun unchangedNameWithoutMacSendsNothing() {
        val named = PanelConfig(mapOf("slot1_name" to "KITCHEN"))
        assertEquals(emptyList<String>(), commands(SlotConfig.define(1, "", "kitchen", named)))
    }

    @Test
    fun swapSendsOneCommandForTwoFreeSlots() {
        assertEquals(listOf("SLOT SWAP 1 4"), commands(SlotConfig.swap(1, 4, empty)))
        assertEquals(listOf("SLOT SWAP 10 2"), commands(SlotConfig.swap(10, 2, empty)))
    }

    @Test
    fun swapRefusesFixedMacDisabledEqualAndUnknownSlots() {
        assertTrue(SlotConfig.swap(1, 4, PanelConfig(mapOf("slot4_mac" to mac.uppercase()))) is SlotConfig.Plan.Invalid)
        assertTrue(SlotConfig.swap(1, 4, PanelConfig(mapOf("slot1_mac" to mac.uppercase()))) is SlotConfig.Plan.Invalid)
        assertTrue(SlotConfig.swap(1, 2, PanelConfig(mapOf("slot2_enabled" to "0"))) is SlotConfig.Plan.Invalid)
        assertTrue(SlotConfig.swap(3, 3, empty) is SlotConfig.Plan.Invalid)
        assertTrue(SlotConfig.swap(0, 3, empty) is SlotConfig.Plan.Invalid)
        assertTrue(SlotConfig.swap(3, 11, empty) is SlotConfig.Plan.Invalid)
    }

    @Test
    fun swapReplyTexts() {
        assertTrue(SlotConfig.swapSucceeded("OK SLOT SWAP 1 4"))
        assertTrue(!SlotConfig.swapSucceeded("ERR FIXED_MAC"))
        assertEquals("Ein Slot hat eine feste MAC und lässt sich nicht tauschen", SlotConfig.swapResultText("ERR FIXED_MAC"))
        assertEquals("Slots getauscht", SlotConfig.swapResultText("OK SLOT SWAP 1 4"))
        assertEquals("Ein Slot ist deaktiviert", SlotConfig.swapResultText("ERR SLOT_OFF"))
        assertEquals("Set ble_console=2 to apply", SlotConfig.swapResultText("ERR READ_ONLY"))
        assertEquals("ERR BAD_ARG", SlotConfig.swapResultText("ERR BAD_ARG"))
    }

    @Test
    fun setOutdoorSlotSendsChangedValue() {
        assertEquals(listOf("SET outdoor_slot=2"), SlotConfig.setOutdoorSlot(2, empty))
        assertEquals(listOf("SET outdoor_slot=10"), SlotConfig.setOutdoorSlot(10, PanelConfig(mapOf("outdoor_slot" to "2"))))
        assertEquals(listOf("SET outdoor_slot=0"), SlotConfig.setOutdoorSlot(0, PanelConfig(mapOf("outdoor_slot" to "2"))))
    }

    @Test
    fun setOutdoorSlotSendsNothingForUnchangedValue() {
        assertEquals(emptyList<String>(), SlotConfig.setOutdoorSlot(2, PanelConfig(mapOf("outdoor_slot" to " 2 "))))
        assertEquals(emptyList<String>(), SlotConfig.setOutdoorSlot(0, empty))
    }

    @Test
    fun malformedOrOutOfRangeStoredValueCountsAsOff() {
        for (stored in listOf("abc", "11", "-3", "")) {
            val config = PanelConfig(mapOf("outdoor_slot" to stored))
            assertEquals(0, SlotConfig.outdoorSlot(config))
            assertEquals(listOf("SET outdoor_slot=2"), SlotConfig.setOutdoorSlot(2, config))
            assertEquals(emptyList<String>(), SlotConfig.setOutdoorSlot(0, config))
        }
    }

    @Test
    fun setOutdoorSlotRejectsSlotOutsideRange() {
        assertThrows(IllegalArgumentException::class.java) { SlotConfig.setOutdoorSlot(11, empty) }
        assertThrows(IllegalArgumentException::class.java) { SlotConfig.setOutdoorSlot(-1, empty) }
    }

    @Test
    fun assignRejectedSetsTheMacOfAFreeSlot() {
        assertEquals(
            listOf("SET slot3_mac=A4:C1:38:11:22:33"),
            commands(SlotConfig.assignRejected(3, mac, empty))
        )
    }

    @Test
    fun assignRejectedRefusesASlotWithAFixedMac() {
        val config = PanelConfig(mapOf("slot3_mac" to "AA:00:00:00:00:99"))
        assertEquals("Slot 3 hat schon eine feste MAC", reason(SlotConfig.assignRejected(3, mac, config)))
    }

    @Test
    fun assignRejectedRefusesAMacThatAnotherSlotHolds() {
        val config = PanelConfig(mapOf("slot1_mac" to "A4:C1:38:11:22:33"))
        assertTrue(reason(SlotConfig.assignRejected(3, mac, config)).startsWith("Slot 1 hält"))
    }

    @Test
    fun assignRejectedRefusesABadSlot() {
        assertEquals("Slot 11 gibt es nicht", reason(SlotConfig.assignRejected(11, mac, empty)))
    }

    @Test
    fun freeableSlotsSkipFixedMacSlots() {
        val config = PanelConfig(mapOf("slot2_mac" to "AA:00:00:00:00:02", "slot10_mac" to "AA:00:00:00:00:10"))
        assertEquals(listOf(1, 3, 4, 5, 6, 7, 8, 9), SlotConfig.freeableSlots(config))
        assertEquals((1..10).toList(), SlotConfig.freeableSlots(empty))
    }

    @Test
    fun clearRejectedSendsSlotClear() {
        assertEquals(listOf("SLOT CLEAR 4"), commands(SlotConfig.clearRejected(4, empty)))
    }

    @Test
    fun clearRejectedRefusesAFixedMacSlotAndABadSlot() {
        val config = PanelConfig(mapOf("slot4_mac" to "AA:00:00:00:00:04"))
        assertEquals("Slot 4 hat eine feste MAC", reason(SlotConfig.clearRejected(4, config)))
        assertEquals("Slot 0 gibt es nicht", reason(SlotConfig.clearRejected(0, empty)))
    }

    @Test
    fun assignRejectedRefusesADisabledSlot() {
        val config = PanelConfig(mapOf("slot3_enabled" to "0"))
        assertEquals("Slot 3 ist deaktiviert", reason(SlotConfig.assignRejected(3, mac, config)))
    }

    @Test
    fun assignRejectedFindsAnUpperCaseHolderForALowerCaseMac() {
        val config = PanelConfig(mapOf("slot2_mac" to "A4:C1:38:11:22:33"))
        assertTrue(reason(SlotConfig.assignRejected(3, "a4:c1:38:11:22:33", config)).startsWith("Slot 2 hält"))
    }
}
