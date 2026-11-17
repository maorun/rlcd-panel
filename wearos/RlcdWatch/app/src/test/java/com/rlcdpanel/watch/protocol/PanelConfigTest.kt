package com.rlcdpanel.watch.protocol

import org.junit.Assert.assertEquals
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
        assertEquals("Tile 6", config.tileName(6))
    }

    @Test
    fun parsesSlotNamesAndIgnoresComments() {
        val iniText = """
            # config.ini for rlcd-panel
            temp_offset_c=-1.9
            slot1_name=BAD
            ; another comment style
            slot2_name=KUECHE
            slot3_name=KINDER
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
        """.trimIndent()

        val config = PanelConfig.parseText(iniText)
        assertEquals("Slot 1", config.tileName(1))
        assertEquals("KUECHE", config.tileName(2))
    }
}
