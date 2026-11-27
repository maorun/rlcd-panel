package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PanelConfigTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sampleReply(): ConsoleReply {
        val assembler = ReplyAssembler()
        return assembler.append(Fixtures.file("config_reply_sample.txt").readBytes())
            ?: throw AssertionError("Fixture gave no reply")
    }

    @Test
    fun toleratesMalformedRejectedLine() {
        val parsed = PanelConfig.parseConfigReply(
            listOf("temp_unit=C", "REJECTED line=9 reason=", "REJECTED line=x", "REJECTED line=11 reason=BAD_VALUE extra=1"),
        )
        assertEquals(listOf(9, 11), parsed.rejectedLines)
        assertEquals(listOf<String?>(null, "BAD_VALUE"), parsed.rejectedReasons)
    }

    @Test
    fun parsesOldAndNewRejectedLineForms() {
        val parsed = PanelConfig.parseConfigReply(
            listOf("temp_unit=C", "SOURCE SD", "REJECTED line=7", "REJECTED line=9 reason=UNKNOWN_KEY", "REJECTED line=12 reason=DUPLICATE_MAC"),
        )
        assertEquals(listOf(7, 9, 12), parsed.rejectedLines)
        assertEquals(listOf<String?>(null, "UNKNOWN_KEY", "DUPLICATE_MAC"), parsed.rejectedReasons)
        assertEquals("temp_unit=C\n", parsed.text)
    }

    @Test
    fun parsesConfigReply() {
        val reply = sampleReply() as ConsoleReply.Frame
        val parsed = PanelConfig.parseConfigReply(reply.lines)
        assertEquals("SD", parsed.source)
        assertEquals(listOf(12, 31), parsed.rejectedLines)
        assertEquals(listOf<String?>("BAD_VALUE", "DUPLICATE_MAC"), parsed.rejectedReasons)
        assertFalse(parsed.text.contains("REJECTED"))
        val config = PanelConfig.parseText(parsed.text)
        assertEquals("Office", config.tileName(0))
        assertEquals("Wohnzimmer", config.tileName(1))
        assertEquals("Keller", config.tileName(2))
        assertEquals("Balkon", config.tileName(3))
        assertEquals("B0:E9:FE:00:00:02", config.slotMac(2))
        assertEquals("3", config.values["outdoor_slot"])
    }

    @Test
    fun cachePreferredOverAsset() {
        val dir = tmp.newFolder()
        assertEquals(null, ConfigCache.info(dir))
        val info = ConfigCache.store(dir, sampleReply(), 1_000L)
        assertEquals(listOf(12, 31), info?.rejectedLines)
        val external = File(tmp.newFolder(), "config.ini").apply { writeText("slot1_name=Extern\nslot1_mac=B0:E9:FE:00:00:01\n") }
        val asset = { "slot1_name=Asset\nslot1_mac=B0:E9:FE:00:00:01\n".byteInputStream() }

        val config = PanelConfig.loadFrom(ConfigCache.file(dir), external, asset)
        assertEquals("Wohnzimmer", config.tileName(1))
        assertEquals(1_000L, ConfigCache.info(dir)?.readAtMillis)
        assertEquals(listOf(12, 31), ConfigCache.info(dir)?.rejectedLines)
        assertFalse(File(dir, "config.ini.tmp").exists())
    }

    @Test
    fun withoutCacheExternalThenAssetWins() {
        val dir = tmp.newFolder()
        val external = File(tmp.newFolder(), "config.ini").apply { writeText("slot1_name=Extern\nslot1_mac=B0:E9:FE:00:00:01\n") }
        val asset = { "slot1_name=Asset\nslot1_mac=B0:E9:FE:00:00:01\n".byteInputStream() }
        assertEquals("Extern", PanelConfig.loadFrom(ConfigCache.file(dir), external, asset).tileName(1))
        assertEquals("Asset", PanelConfig.loadFrom(ConfigCache.file(dir), File(dir, "none.ini"), asset).tileName(1))
    }

    @Test
    fun badCrcAndErrKeepOldCache() {
        val dir = tmp.newFolder()
        ConfigCache.store(dir, sampleReply(), 1_000L)
        val before = ConfigCache.file(dir).readText()

        val bad = ReplyAssembler().append(
            Fixtures.file("config_reply_sample.txt").readText().replace("CRC32 70a35dc5", "CRC32 00000000").toByteArray()
        )
        assertTrue(bad is ConsoleReply.Corrupt)
        assertEquals(null, ConfigCache.store(dir, bad!!, 2_000L))

        val err = ReplyAssembler().append("ERR READ_ONLY\n".toByteArray())!!
        assertEquals(null, ConfigCache.store(dir, err, 3_000L))
        assertEquals(null, ConfigCache.store(dir, ReplyAssembler().timeout(), 4_000L))
        assertEquals(null, ConfigCache.store(dir, ConsoleReply.Frame(listOf("SOURCE SD")), 5_000L))

        assertEquals(before, ConfigCache.file(dir).readText())
        assertEquals(1_000L, ConfigCache.info(dir)?.readAtMillis)
    }

    @Test
    fun emptyCacheFallsThroughAndForeignLinesAreDropped() {
        val dir = tmp.newFolder()
        ConfigCache.file(dir).writeText("# REJECTED line=1\n")
        val asset = { "slot1_name=Asset\nslot1_mac=B0:E9:FE:00:00:01\n".byteInputStream() }
        assertEquals("Asset", PanelConfig.loadFrom(ConfigCache.file(dir), null, asset).tileName(1))

        val parsed = PanelConfig.parseConfigReply(listOf("slot1_name=Ok", "Evil Key=1", "x=\u0001", "SOURCE SD"))
        assertEquals("slot1_name=Ok\n", parsed.text)
        val huge = List(600) { "slot1_name=" + "a".repeat(20) }
        assertEquals("", PanelConfig.parseConfigReply(huge).text)
    }

    @Test
    fun defaultSourceReplyKeepsOldCache() {
        val dir = tmp.newFolder()
        ConfigCache.store(dir, sampleReply(), 1_000L)
        val before = ConfigCache.file(dir).readText()
        val defaults = ConsoleReply.Frame(listOf("slot1_name=Slot 1", "SOURCE DEFAULT"))
        assertEquals(null, ConfigCache.store(dir, defaults, 2_000L))
        assertEquals(before, ConfigCache.file(dir).readText())
    }

    @Test
    fun infoFallsBackToFileTimeWithoutReadAtLine() {
        val dir = tmp.newFolder()
        assertEquals(null, ConfigCache.info(dir))
        val file = ConfigCache.file(dir)
        file.writeText("slot1_name=A\n# REJECTED line=7\n")
        file.setLastModified(5_000L)
        val info = ConfigCache.info(dir)
        assertEquals(5_000L, info?.readAtMillis)
        assertEquals(listOf(7), info?.rejectedLines)
    }

    @Test
    fun ageTextUsesMinutesHoursDays() {
        val info = ConfigCacheInfo(0L, emptyList())
        assertEquals("gerade eben", ConfigCache.ageText(info, 30_000L))
        assertEquals("vor 5 min", ConfigCache.ageText(info, 5 * 60_000L))
        assertEquals("vor 3 h", ConfigCache.ageText(info, 3 * 3_600_000L))
        assertEquals("vor 3 Tagen", ConfigCache.ageText(info, 3 * 86_400_000L))
    }

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
    fun unknownMacFallsBackToSlotNameThenDefaultTitle() {
        val config = PanelConfig.parseText(macIni)
        assertNull(config.nameForMac("B0:E9:FE:00:00:99"))
        assertNull(config.nameForMac(null))
        assertNull(config.nameForMac("00:00:00:00:00:00"))
        // an unknown MAC falls back to the slotN_name of the index
        assertEquals("BAD", config.tileName(1, "B0:E9:FE:00:00:99"))
        assertEquals("BAD", config.tileName(1, null))
        // a slot without any name keeps the default title
        assertEquals("Slot 4", config.tileName(4, "B0:E9:FE:00:00:99"))
    }

    @Test
    fun slotNameWithoutFixedMacShowsDirectly() {
        val config = PanelConfig.parseText(macIni)
        // slot3_name has no slot3_mac and still shows
        assertEquals("KINDER", config.tileName(3))
        assertEquals("KINDER", config.tileNames[3])
    }

    @Test
    fun reassignedSensorKeepsItsName() {
        val config = PanelConfig.parseText(macIni)
        // The sensor with the KUECHE MAC moves from slot 2 to slot 1
        assertEquals("KUECHE", config.tileName(1, "B0:E9:FE:00:00:02"))
        assertEquals("BAD", config.tileName(2, "B0:E9:FE:00:00:01"))
    }

    @Test
    fun slotEnabledDefaultsToTrueAndReadsZero() {
        val config = PanelConfig(mapOf("slot1_enabled" to "0", "slot2_enabled" to "1"))
        assertFalse(config.slotEnabled(1))
        assertTrue(config.slotEnabled(2))
        assertTrue(config.slotEnabled(3))
        assertTrue(PanelConfig().slotEnabled(10))
        assertFalse(config.slotEnabled(0))
        assertFalse(config.slotEnabled(11))
    }

    @Test
    fun fixedMacOfTheSlotIsTheDefaultActiveMac() {
        val config = PanelConfig.parseText(macIni)
        assertEquals("BAD", config.tileName(1))
        assertEquals("KUECHE", config.tileName(2))
        assertEquals("BAD", config.tileNames[1])
    }
}
