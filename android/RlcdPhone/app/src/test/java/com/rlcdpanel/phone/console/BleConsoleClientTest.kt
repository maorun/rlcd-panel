package com.rlcdpanel.phone.console

import com.rlcdpanel.phone.protocol.ConsoleWire
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BleConsoleClientTest {

    @Test
    fun sendWritesCommandPlusNewline() {
        assertArrayEquals("CONFIG\n".toByteArray(), ConsoleWire.frame("CONFIG"))
        assertArrayEquals("STATUS\n".toByteArray(), ConsoleWire.frame("STATUS"))
    }

    @Test
    fun commandWithLineBreakOrEmptyIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { ConsoleWire.frame("") }
        assertThrows(IllegalArgumentException::class.java) { ConsoleWire.frame("STATUS\nCONFIG") }
        assertThrows(IllegalArgumentException::class.java) { ConsoleWire.frame("STATUS\r") }
    }

    @Test
    fun chunksFollowMtuMinusThree() {
        val frame = ConsoleWire.frame("SET slot1_name=Wohnzimmer") // 26 bytes
        val chunks = ConsoleWire.chunks(frame, 10) // 7 bytes per write
        assertEquals(listOf(7, 7, 7, 5), chunks.map { it.size })
        assertArrayEquals(frame, chunks.fold(ByteArray(0)) { acc, c -> acc + c })
    }

    @Test
    fun shortCommandFitsOneChunkAtMtu185() {
        val chunks = ConsoleWire.chunks(ConsoleWire.frame("CONFIG"), 185)
        assertEquals(1, chunks.size)
        assertArrayEquals("CONFIG\n".toByteArray(), chunks[0])
    }

    @Test
    fun tinyMtuStillWritesOneByteAtATime() {
        assertEquals(7, ConsoleWire.chunks(ConsoleWire.frame("CONFIG"), 3).size)
        assertEquals(7, ConsoleWire.chunks(ConsoleWire.frame("CONFIG"), 0).size)
    }

    @Test
    fun defaultMtuGivesTwentyByteChunks() {
        val chunks = ConsoleWire.chunks(ConsoleWire.frame("A".repeat(44)), ConsoleWire.DEFAULT_MTU)
        assertEquals(listOf(20, 20, 5), chunks.map { it.size })
    }
}
