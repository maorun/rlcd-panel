package com.rlcdpanel.watch.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FrsiReplyTest {
    private val head = "FRSI room=onboard wall=slot2 hours=6 frsi=0.70 span=0.00 delta_c=20.0"

    private fun parse(vararg lines: String) = FrsiReply.parse(lines.toList())

    @Test
    fun sharedFixtureGivesFactorAndProposal() {
        val r = FrsiReply.parseBytes(Fixtures.file("frsi_reply_sample.txt").readBytes())
        val m = (r.outcome as FrsiReply.Outcome.Measured).measurement
        assertEquals("onboard", m.room)
        assertEquals("slot2", m.wall)
        assertEquals(6L, m.hours)
        assertEquals(0.70, m.frsi, 0.0)
        assertEquals(0.0, m.span, 0.0)
        assertEquals(20.0, m.deltaC, 0.0)
        assertEquals(70, m.proposalPct)
        assertEquals(CalibrationProposal("wall_frsi_pct", "70"), r.proposal)
    }

    @Test
    fun skipAndUnstable() {
        assertEquals(FrsiReply.Outcome.Skipped(4), parse("SKIP hours=4").outcome)
        assertEquals(FrsiReply.Outcome.Unstable(0.25), parse("UNSTABLE span=0.25").outcome)
        assertEquals(FrsiReply.Outcome.Unstable(null), parse("UNSTABLE span=--").outcome)
        assertNull(parse("SKIP hours=4").proposal)
        assertEquals(FrsiReply.Outcome.Skipped(4), parse("BEGIN FRSI", "SKIP hours=4").outcome)
    }

    @Test
    fun errorRepliesGiveTheirTexts() {
        assertTrue(FrsiText.status("ERR LOW_DELTA").contains("10 °C"))
        assertTrue(FrsiText.status("ERR NO_OUTDOOR").contains("outdoor_slot"))
        assertTrue(FrsiText.status("ERR BAD_ARG").contains("verschieden"))
        assertEquals("Set ble_console=2 to apply", FrsiText.status("ERR READ_ONLY"))
        assertTrue(FrsiText.outcome(parse("SKIP hours=4")).startsWith("SKIP"))
        assertTrue(FrsiText.outcome(parse("UNSTABLE span=--")).startsWith("UNSTABLE"))
    }

    @Test
    fun errorStatusLineIsNoFrame() {
        assertThrows(ParseException::class.java) { FrsiReply.parseBytes("ERR LOW_DELTA\n".toByteArray()) }
    }

    @Test
    fun equalRoomAndWallThrow() {
        assertThrows(IllegalArgumentException::class.java) { FrsiCommand.line(2, 2) }
        assertEquals("FRSI 0 2", FrsiCommand.line(0, 2))
        for ((r, w) in listOf(0 to 0, 0 to 11, 11 to 1, 1 to 0)) {
            assertThrows(IllegalArgumentException::class.java) { FrsiCommand.line(r, w) }
        }
    }

    @Test
    fun badCrcLineThrows() {
        val text = Fixtures.file("frsi_reply_sample.txt").readText().replace("CRC32 9843400e", "CRC32 00000000")
        assertThrows(ParseException::class.java) { FrsiReply.parseBytes(text.toByteArray()) }
        val noCrc = text.substringBefore("CRC32")
        assertThrows(ParseException::class.java) { FrsiReply.parseBytes(noCrc.toByteArray()) }
    }

    @Test
    fun malformedLinesThrowOrDropProposal() {
        assertThrows(ParseException::class.java) { parse("$head extra=1") }
        assertThrows(ParseException::class.java) { parse("OTHER") }
        assertThrows(ParseException::class.java) { parse() }
        assertNull(parse(head, "wall_frsi_pct=49").proposal)
        assertNull(parse(head, "wall_frsi_pct=96").proposal)
        assertNull(parse(head, "wall_frsi_pct=070").proposal)
        assertNull(parse(head).proposal)
    }

    @Test
    fun applyBatchNeedsAProposal() {
        assertNotNull(FrsiApply.batch(parse(head, "wall_frsi_pct=70")))
        assertNull(FrsiApply.batch(parse(head)))
        assertNull(FrsiApply.batch(parse("SKIP hours=4")))
    }

    @Test
    fun applyBatchSendsSetThenConfig() {
        val sent = mutableListOf<String>()
        var result: CalibrationBatch.Result? = null
        FrsiApply.batch(parse(head, "wall_frsi_pct=70"))!!.run({ cmd, cb ->
            sent.add(cmd)
            cb(if (cmd.startsWith("SET")) ConsoleReply.Status("OK SET wall_frsi_pct") else ConsoleReply.Frame(listOf("wall_frsi_pct=70")))
        }) { result = it }
        assertEquals(listOf("SET wall_frsi_pct=70", "CONFIG"), sent)
        assertEquals("Übernommen: wall_frsi_pct", FrsiApply.resultText(result!!))
    }

    @Test
    fun readOnlyReplyGivesTheHintAndNoConfig() {
        val sent = mutableListOf<String>()
        var result: CalibrationBatch.Result? = null
        FrsiApply.batch(parse(head, "wall_frsi_pct=70"))!!.run({ cmd, cb ->
            sent.add(cmd)
            cb(ConsoleReply.Status("ERR READ_ONLY"))
        }) { result = it }
        assertEquals(listOf("SET wall_frsi_pct=70"), sent)
        assertTrue(FrsiApply.isReadOnly(result!!))
        assertEquals("Set ble_console=2 to apply", FrsiApply.resultText(result!!))
    }

    @Test
    fun measuredTextShowsFactorHoursAndSpan() {
        val text = FrsiText.outcome(parse(head, "wall_frsi_pct=70"))
        assertTrue(text.contains("0.7"))
        assertTrue(text.contains("6 h"))
        assertTrue(text.contains("onboard"))
        assertTrue(text.contains("slot2"))
    }

    @Test
    fun pickersWrapAround() {
        assertEquals(1, com.rlcdpanel.watch.FrsiScreenText.nextRoom(0))
        assertEquals(0, com.rlcdpanel.watch.FrsiScreenText.nextRoom(10))
        assertEquals(2, com.rlcdpanel.watch.FrsiScreenText.nextWall(1))
        assertEquals(1, com.rlcdpanel.watch.FrsiScreenText.nextWall(10))
        assertEquals("Vorschlag: wall_frsi_pct=70", com.rlcdpanel.watch.FrsiScreenText.proposal(parse(head, "wall_frsi_pct=70")))
        assertNull(com.rlcdpanel.watch.FrsiScreenText.proposal(parse(head)))
    }

    @Test
    fun otherSetFailureShowsItsTextAndIsNotReadOnly() {
        var result: CalibrationBatch.Result? = null
        FrsiApply.batch(parse(head, "wall_frsi_pct=70"))!!.run({ _, cb -> cb(ConsoleReply.Status("ERR BAD_VALUE")) }) { result = it }
        assertTrue(!FrsiApply.isReadOnly(result!!))
        assertTrue(FrsiApply.resultText(result!!).contains("ERR BAD_VALUE"))
    }

    @Test
    fun configFailureAfterSetIsReported() {
        var result: CalibrationBatch.Result? = null
        FrsiApply.batch(parse(head, "wall_frsi_pct=70"))!!.run({ cmd, cb ->
            cb(if (cmd.startsWith("SET")) ConsoleReply.Status("OK SET wall_frsi_pct") else ConsoleReply.Status("ERR NO_CARD"))
        }) { result = it }
        assertTrue(result!!.configFailed)
        assertTrue(!FrsiApply.isReadOnly(result!!))
        assertTrue(FrsiApply.resultText(result!!).contains("CONFIG"))
    }
}
