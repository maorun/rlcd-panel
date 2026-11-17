package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyAssemblerTest {

    private fun crcLine(body: String): String =
        "CRC32 %08x\n".format(Checksums.crc32(body.toByteArray()))

    @Test
    fun assemblesReplyFromThreeChunks() {
        val body = "SENSOR 0 Onboard temp=1 rh=2 bat=-- rssi=-- age=1 state=OK\nPOWER src=USB v=4.0 pct=80 alerts=00 muted=0\n"
        val bytes = (body + crcLine(body)).toByteArray()
        val a = bytes.size / 3
        val assembler = ReplyAssembler()
        assertNull(assembler.append(bytes.copyOfRange(0, a)))
        assertNull(assembler.append(bytes.copyOfRange(a, 2 * a)))
        val reply = assembler.append(bytes.copyOfRange(2 * a, bytes.size))
        assertEquals(
            ConsoleReply.Frame(body.trimEnd('\n').split('\n')),
            reply
        )
    }

    @Test
    fun badCrcIsAnError() {
        val body = "SENSOR 0 Onboard temp=1 rh=2 bat=-- rssi=-- age=1 state=OK\n"
        val reply = ReplyAssembler().append((body + "CRC32 00000000\n").toByteArray())
        assertTrue(reply is ConsoleReply.Corrupt)
        assertTrue((reply as ConsoleReply.Corrupt).error is ReplyError.BadCrc)
    }

    @Test
    fun malformedCrcLineIsAnError() {
        val reply = ReplyAssembler().append("SENSOR 0 A\nCRC32 xyz\n".toByteArray())
        assertTrue((reply as ConsoleReply.Corrupt).error is ReplyError.MalformedCrcLine)
    }

    @Test
    fun missingCrcLineGivesTimeoutError() {
        val assembler = ReplyAssembler()
        assertNull(assembler.append("SENSOR 0 Onboard temp=1\n".toByteArray()))
        val reply = assembler.timeout()
        assertEquals(ConsoleReply.Corrupt(ReplyError.Timeout), reply)
    }

    @Test
    fun errLineIsACompleteReply() {
        val reply = ReplyAssembler().append("ERR NOT_OVER_BLE\n".toByteArray())
        assertEquals(ConsoleReply.Status("ERR NOT_OVER_BLE"), reply)
    }

    @Test
    fun oversizedLineIsAnError() {
        val reply = ReplyAssembler().append(ByteArray(ReplyAssembler.MAX_LINE_BYTES + 1) { 'a'.code.toByte() })
        assertEquals(ConsoleReply.Corrupt(ReplyError.TooLarge), reply)
    }

    @Test
    fun splitsMultiByteChunkBoundaryWithoutLosingBytes() {
        val body = "SENSOR 1 Küche temp=1 rh=1 bat=1 rssi=-1 age=1 state=OK\n"
        val bytes = (body + crcLine(body)).toByteArray()
        val assembler = ReplyAssembler()
        var reply: ConsoleReply? = null
        for (b in bytes) reply = assembler.append(byteArrayOf(b)) ?: reply
        assertTrue(reply is ConsoleReply.Frame)
    }
}
