package com.rlcdpanel.watch.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConsoleWireTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sampleBytes(): ByteArray = Fixtures.file("config_reply_sample.txt").readBytes()

    @Test
    fun frameAddsNewlineAndRejectsLineBreaks() {
        assertArrayEquals("CONFIG\n".toByteArray(), ConsoleWire.frame("CONFIG"))
        assertThrows(IllegalArgumentException::class.java) { ConsoleWire.frame("") }
        assertThrows(IllegalArgumentException::class.java) { ConsoleWire.frame("A\nB") }
        assertThrows(IllegalArgumentException::class.java) { ConsoleWire.frame("A\r") }
    }

    @Test
    fun chunksAtMtu23AreTwentyBytes() {
        val frame = ConsoleWire.frame("A".repeat(44)) // 45 bytes
        val chunks = ConsoleWire.chunks(frame, 23)
        assertEquals(listOf(20, 20, 5), chunks.map { it.size })
        assertArrayEquals(frame, chunks.fold(ByteArray(0)) { acc, c -> acc + c })
    }

    @Test
    fun chunksAtMtu185AreOneWriteForConfig() {
        val chunks = ConsoleWire.chunks(ConsoleWire.frame("CONFIG"), 185)
        assertEquals(1, chunks.size)
        assertArrayEquals("CONFIG\n".toByteArray(), chunks[0])
        val long = ConsoleWire.chunks(ConsoleWire.frame("A".repeat(400)), 185)
        assertEquals(listOf(182, 182, 37), long.map { it.size })
    }

    @Test
    fun tinyMtuWritesOneByteAtATime() {
        assertEquals(7, ConsoleWire.chunks(ConsoleWire.frame("CONFIG"), 3).size)
        assertEquals(7, ConsoleWire.chunks(ConsoleWire.frame("CONFIG"), 0).size)
    }

    @Test
    fun sampleReplyGivesTheNames() {
        val reply = ReplyAssembler().append(sampleBytes()) as ConsoleReply.Frame
        val parsed = PanelConfig.parseConfigReply(reply.lines)
        assertEquals("SD", parsed.source)
        val config = PanelConfig.parseText(parsed.text)
        assertEquals("Office", config.tileName(0))
        assertEquals("Wohnzimmer", config.tileName(1))
        assertEquals("Keller", config.tileName(2))
        assertEquals("Balkon", config.tileName(3))
        assertEquals("Slot 4", config.tileName(4))
    }

    @Test
    fun goodReplyFillsCacheAndTileNameUsesIt() {
        val dir = tmp.newFolder()
        val reply = ReplyAssembler().append(sampleBytes())!!
        assertTrue(ConfigCache.store(dir, reply, 1_000L) != null)
        val config = PanelConfig.loadFrom(ConfigCache.file(dir), null) { null }
        assertEquals("Wohnzimmer", config.tileName(1))
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
    fun ignoresWarnLines() {
        val parsed = PanelConfig.parseConfigReply(
            listOf("temp_unit=C", "SOURCE SD", "WARN key=wall_frsi_pct reason=WALL_WITHOUT_OUTDOOR"),
        )
        assertEquals("temp_unit=C\n", parsed.text)
        assertEquals(emptyList<Int>(), parsed.rejectedLines)
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
    fun badCrcKeepsOldCache() {
        val dir = tmp.newFolder()
        ConfigCache.store(dir, ReplyAssembler().append(sampleBytes())!!, 1_000L)
        val before = ConfigCache.file(dir).readText()

        val badText = String(sampleBytes()).replace("CRC32 70a35dc5", "CRC32 00000000").toByteArray()
        val bad = ReplyAssembler().append(badText)
        assertTrue(bad is ConsoleReply.Corrupt)
        assertNull(ConfigCache.store(dir, bad!!, 2_000L))
        assertNull(ConfigCache.store(dir, ReplyAssembler().timeout(), 3_000L))
        assertEquals(before, ConfigCache.file(dir).readText())
        assertFalse(java.io.File(dir, "config.ini.tmp").exists())
    }
}
