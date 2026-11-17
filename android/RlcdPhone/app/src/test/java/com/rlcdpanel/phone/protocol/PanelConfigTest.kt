package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PanelConfigTest {

    @Test
    fun emptyConfigReturnsDefaultNames() {
        val config = PanelConfig.parseText("")
        assertEquals("Onboard", config.tileName(0))
        assertEquals("Slot 1", config.tileName(1))
        assertEquals("Slot 2", config.tileName(2))
        assertEquals("Slot 3", config.tileName(3))
        assertEquals("Slot 4", config.tileName(4))
        assertEquals("Slot 5", config.tileName(5))
        assertEquals("Slot 6", config.tileName(6))
        assertEquals("Slot 10", config.tileName(10))
        assertEquals("Tile 11", config.tileName(11))
    }

    @Test
    fun parsesSlotNamesAndIgnoresComments() {
        val iniText = """
            # config.ini for rlcd-panel
            temp_offset_c=-1.9
            slot1_name=BAD
            slot1_mac=B0:E9:FE:00:00:01
            ; another comment style
            slot2_name=KUECHE
            slot2_mac=B0:E9:FE:00:00:02
            slot3_name=KINDER
            slot3_mac=B0:E9:FE:00:00:03
            onboard_name=OFFICE
        """.trimIndent()

        val config = PanelConfig.parseText(iniText)
        assertEquals("OFFICE", config.tileName(0))
        assertEquals("BAD", config.tileName(1))
        assertEquals("KUECHE", config.tileName(2))
        assertEquals("KINDER", config.tileName(3))
        assertEquals("Slot 4", config.tileName(4))
        assertEquals("Slot 5", config.tileName(5))
    }

    @Test
    fun ignoresEmptyKeysOrValues() {
        val iniText = """
            slot1_name=
            =value
            slot2_name=KUECHE
            slot2_mac=B0:E9:FE:00:00:02
        """.trimIndent()

        val config = PanelConfig.parseText(iniText)
        assertEquals("Slot 1", config.tileName(1))
        assertEquals("KUECHE", config.tileName(2))
    }

    private val macIni = """
        slot1_name=BAD
        slot1_mac=B0:E9:FE:00:00:01
        slot2_name=KUECHE
        slot2_mac=b0:e9:fe:00:00:02
        slot3_name=KINDER
    """.trimIndent()

    @Test
    fun knownMacShowsItsNameInAnySlot() {
        val config = PanelConfig.parseText(macIni)
        assertEquals("KUECHE", config.nameForMac("B0:E9:FE:00:00:02"))
        assertEquals("KUECHE", config.nameForMac("b0-e9-fe-00-00-02"))
        for (slot in 1..10) {
            assertEquals("KUECHE", config.tileName(slot, "B0:E9:FE:00:00:02"))
        }
    }

    @Test
    fun unknownMacShowsDefaultTitle() {
        val config = PanelConfig.parseText(macIni)
        assertNull(config.nameForMac("B0:E9:FE:00:00:99"))
        assertNull(config.nameForMac(null))
        assertNull(config.nameForMac("00:00:00:00:00:00"))
        // slot3_name has no slot3_mac, so it never shows
        assertEquals("Slot 3", config.tileName(3))
        assertEquals("Slot 1", config.tileName(1, "B0:E9:FE:00:00:99"))
        assertEquals("Slot 1", config.tileName(1, null))
    }

    @Test
    fun reassignedSensorKeepsItsName() {
        val config = PanelConfig.parseText(macIni)
        // The sensor with the KUECHE MAC moves from slot 2 to slot 1
        assertEquals("KUECHE", config.tileName(1, "B0:E9:FE:00:00:02"))
        assertEquals("BAD", config.tileName(2, "B0:E9:FE:00:00:01"))
    }

    @Test
    fun fixedMacOfTheSlotIsTheDefaultActiveMac() {
        val config = PanelConfig.parseText(macIni)
        assertEquals("BAD", config.tileName(1))
        assertEquals("KUECHE", config.tileName(2))
        assertEquals("BAD", config.tileNames[1])
    }
}
