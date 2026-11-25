package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SaltProgressTest {
    private var clock = 1_000_000L

    private fun reply(values: List<Double?>, sensor: Int = 2): List<PanelHistoryRow> =
        values.mapIndexed { age, v -> PanelHistoryRow(sensor, age, 21.0, v, 45.0) }

    private fun fixtureRows(): List<PanelHistoryRow> {
        val r = ReplyAssembler().append(Fixtures.file("history_reply_sample.txt").readBytes())
        return HistoryReply.parse((r as ConsoleReply.Frame).lines)
    }

    @Test
    fun fixtureGivesTheStableHoursOfTheSwiftRules() {
        val rows = fixtureRows()
        // Sensor 0: 45.0, 46.5 -> span 1.5 above the limit, the run holds one hour.
        assertEquals(1, SaltProgress.stableHours(rows, 0))
        assertEquals(2, SaltProgress.stableHours(rows, 1))
        assertEquals(0, SaltProgress.stableHours(rows, 2))
        assertEquals(1, SaltProgress.stableHours(rows, 3))
    }

    @Test
    fun countsStableHoursAndStopsAtJump() {
        assertEquals(5, SaltProgress.stableHours(reply(listOf(75.2, 75.4, 75.0, 75.6, 75.3, 70.0, 75.2)), 2))
    }

    @Test
    fun spanAboveLimitResetsTheCount() {
        val p = SaltProgress { clock }
        p.start()
        p.record(reply(listOf(75.2, 75.3, 75.1, 75.2)), 2)
        assertEquals(4, p.stableHours)
        p.record(reply(listOf(70.0, 75.2, 75.3, 75.1, 75.2)), 2)
        assertEquals(1, p.stableHours)
        assertEquals(1, SaltProgress.stableHours(reply(listOf(60.0, 75.2, 75.3, 75.1, 75.2, 75.3)), 2))
    }

    @Test
    fun spanLimitIsInclusiveAndGapEndsRun() {
        assertEquals(2, SaltProgress.stableHours(reply(listOf(75.0, 76.0)), 2))
        assertEquals(1, SaltProgress.stableHours(reply(listOf(75.0, 76.1)), 2))
        assertEquals(1, SaltProgress.stableHours(reply(listOf(75.0, null, 75.0)), 2))
        assertEquals(0, SaltProgress.stableHours(reply(listOf(75.0)), 3))
    }

    @Test
    fun textAndReady() {
        val p = SaltProgress { clock }
        p.start()
        p.record(reply(listOf(75.2, 75.3, 75.1)), 2)
        assertEquals("3 von 6 Stunden stabil", p.text)
        assertFalse(p.isReady)
        assertEquals(3, p.hoursRemaining)
        p.record(reply(listOf(75.2, 75.3, 75.1, 75.2, 75.3, 75.2, 75.1)), 2)
        assertEquals("6 von 6 Stunden stabil", p.text)
        assertTrue(p.isReady)
    }

    @Test
    fun fiveStableHoursAreNotReady() {
        val p = SaltProgress { clock }
        p.start()
        p.record(reply(listOf(75.2, 75.3, 75.1, 75.2, 75.3)), 2)
        assertFalse(p.isReady)
        assertEquals(1, p.hoursRemaining)
    }

    @Test
    fun stopsAfterEightHours() {
        val p = SaltProgress { clock }
        p.start()
        clock += 8 * 3600 * 1000L - 1
        assertTrue(p.shouldPoll())
        clock += 1
        assertFalse(p.shouldPoll())
        assertFalse(p.isRunning)
        assertEquals(SaltProgress.StopReason.TIMEOUT, p.stopReason)
    }

    @Test
    fun errorReplyStopsAndStoppedWatchIgnoresReplies() {
        val p = SaltProgress { clock }
        p.start()
        p.stop(SaltProgress.StopReason.REPLY_FAILED)
        p.stop(SaltProgress.StopReason.USER)
        assertEquals(SaltProgress.StopReason.REPLY_FAILED, p.stopReason)
        assertFalse(p.isRunning)
        p.record(reply(listOf(75.0, 75.1)), 2)
        assertEquals(0, p.stableHours)
        assertFalse(p.shouldPoll())
    }

    @Test
    fun startClearsEarlierStop() {
        val p = SaltProgress { clock }
        p.start()
        p.stop(SaltProgress.StopReason.LINK_LOST)
        p.start()
        assertTrue(p.isRunning)
        assertNull(p.stopReason)
        assertEquals(0, p.replies)
    }

    @Test
    fun errReplyStopsTheWatchAndKeepsTheCount() {
        val p = SaltProgress { clock }
        p.start()
        p.record(reply(listOf(75.2, 75.3)), 2)
        p.record(ConsoleReply.Status("ERR BAD_ARG"), 2)
        assertFalse(p.isRunning)
        assertEquals(SaltProgress.StopReason.REPLY_FAILED, p.stopReason)
        assertEquals(2, p.stableHours)
    }

    @Test
    fun corruptReplyStopsAsLostLinkAndBadFrameAsFailedReply() {
        val lost = SaltProgress { clock }
        lost.start()
        lost.record(ConsoleReply.Corrupt(ReplyError.Timeout), 2)
        assertEquals(SaltProgress.StopReason.LINK_LOST, lost.stopReason)
        val bad = SaltProgress { clock }
        bad.start()
        bad.record(ConsoleReply.Frame(listOf("BEGIN HISTORY", "wrong header")), 2)
        assertEquals(SaltProgress.StopReason.REPLY_FAILED, bad.stopReason)
    }

    @Test
    fun frameReplyCountsStableHours() {
        val p = SaltProgress { clock }
        p.start()
        val lines = listOf("BEGIN HISTORY", HistoryReply.HEADER, "2,0,21.0,75.0,", "2,1,21.0,75.5,", "2,2,21.0,76.9,")
        assertEquals(2, p.record(ConsoleReply.Frame(lines), 2))
        assertTrue(p.isRunning)
    }

    @Test
    fun lateReplyAfterEightHoursStopsWithTimeout() {
        val p = SaltProgress { clock }
        p.start()
        clock += 8 * 3600 * 1000L
        p.record(reply(listOf(75.0, 75.1, 75.2)), 2)
        assertEquals(3, p.stableHours)
        assertFalse(p.isRunning)
        assertEquals(SaltProgress.StopReason.TIMEOUT, p.stopReason)
    }

    @Test
    fun sixStableRowsGiveTheFullCount() {
        val p = SaltProgress { clock }
        p.start()
        p.record(reply(listOf(75.2, 75.3, 75.1, 75.2, 75.4, 75.3)), 2)
        assertEquals(6, p.stableHours)
        assertTrue(p.isReady)
        assertEquals("6 von 6 Stunden stabil", p.text)
        // The short read asks for exactly the rows that the full count needs.
        assertEquals("HISTORY 2 ${SaltProgress.REQUIRED_HOURS}", p.historyLine(2))
    }

    @Test
    fun watchSendsTheShortReadAndFallsBackOnceOnBadArg() {
        val p = SaltProgress { clock }
        p.start()
        assertEquals("HISTORY 4 6", p.historyLine(4))
        assertFalse(p.fallBackToFullHistory("ERR UNKNOWN"))
        assertEquals("HISTORY 4 6", p.historyLine(4))
        assertTrue(p.fallBackToFullHistory("ERR BAD_ARG"))
        assertEquals("HISTORY 4", p.historyLine(4))
        assertFalse(p.fallBackToFullHistory("ERR BAD_ARG"))
        p.stop(SaltProgress.StopReason.USER)
        p.start()
        assertEquals("HISTORY 4 6", p.historyLine(4))
    }

    @Test
    fun stoppedWatchDoesNotFallBack() {
        val p = SaltProgress { clock }
        assertFalse(p.fallBackToFullHistory("ERR BAD_ARG"))
    }
}
