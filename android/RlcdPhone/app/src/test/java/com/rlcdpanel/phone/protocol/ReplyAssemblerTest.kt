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

    // --- Fuzz test: damaged replies of each fixture of tests/fixtures ---

    private val seeds = listOf(1L, 7L, 42L, 1234L, 99991L, 0xC0FFEEL, 0xBADF00DL, 20261128L)

    /** SplitMix64: same sequence on each host. */
    private class Rng(private var state: Long) {
        fun next(): Long {
            state += -0x61c8864680b583ebL
            var z = state
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }
        fun below(n: Int): Int = java.lang.Long.remainderUnsigned(next(), n.toLong()).toInt()
    }

    private fun framedFixtures(): List<Triple<String, ByteArray, List<String>>> {
        val dir = Fixtures.file("alerts_reply_sample.txt").parentFile
        return dir.listFiles { f -> f.name.endsWith(".txt") }!!.sortedBy { it.name }.mapNotNull { f ->
            val bytes = f.readBytes()
            val reply = ReplyAssembler().append(bytes)
            if (reply is ConsoleReply.Frame) Triple(f.name, bytes, reply.lines) else null
        }
    }

    /** Feeds the bytes in BLE chunks of 20 bytes and returns each reply. */
    private fun feed(bytes: ByteArray): List<ConsoleReply> {
        val assembler = ReplyAssembler()
        val out = mutableListOf<ConsoleReply>()
        var i = 0
        while (i < bytes.size) {
            val end = minOf(i + 20, bytes.size)
            assembler.append(bytes.copyOfRange(i, end))?.let { out.add(it) }
            i = end
        }
        return out
    }

    /** No reply (incomplete), a CRC error, or a frame with the original content. */
    private fun acceptable(replies: List<ConsoleReply>, original: List<String>): Boolean =
        replies.all { it is ConsoleReply.Corrupt || (it is ConsoleReply.Frame && it.lines == original) }

    @Test
    fun fuzzFixturesAreFoundAndPassUndamaged() {
        val fixtures = framedFixtures()
        assertTrue(fixtures.size >= 20)
        for ((name, bytes, lines) in fixtures) assertEquals(name, listOf<ConsoleReply>(ConsoleReply.Frame(lines)), feed(bytes))
    }

    @Test
    fun cutReplyIsIncompleteOrCorrupt() {
        for ((name, bytes, lines) in framedFixtures()) {
            for (position in 0 until minOf(200, bytes.size)) {
                assertTrue("$name cut at $position", acceptable(feed(bytes.copyOfRange(0, position)), lines))
            }
        }
    }

    @Test
    fun droppedByteGivesNoWrongFrame() {
        for ((name, bytes, lines) in framedFixtures()) {
            for (seed in seeds) {
                val rng = Rng(seed)
                repeat(40) {
                    val position = rng.below(bytes.size)
                    val damaged = bytes.copyOfRange(0, position) + bytes.copyOfRange(position + 1, bytes.size)
                    assertTrue("$name seed $seed drop $position", acceptable(feed(damaged), lines))
                }
            }
        }
    }

    @Test
    fun flippedByteGivesNoWrongFrame() {
        for ((name, bytes, lines) in framedFixtures()) {
            for (seed in seeds) {
                val rng = Rng(seed)
                repeat(40) {
                    val position = rng.below(bytes.size)
                    val damaged = bytes.copyOf()
                    damaged[position] = (damaged[position].toInt() xor (1 shl rng.below(8))).toByte()
                    assertTrue("$name seed $seed flip $position", acceptable(feed(damaged), lines))
                }
            }
        }
    }

    @Test
    fun flippedBodyByteGivesNoWrongFrame() {
        for ((name, bytes, lines) in framedFixtures()) {
            val first = bytes.indexOfFirst { it in 0x41..0x5A }
            if (first < 0) continue
            val damaged = bytes.copyOf()
            damaged[first] = (damaged[first].toInt() xor 1).toByte()
            val wrong = feed(damaged).any { it is ConsoleReply.Frame && it.lines != lines }
            assertTrue(name, !wrong)
        }
    }
}
