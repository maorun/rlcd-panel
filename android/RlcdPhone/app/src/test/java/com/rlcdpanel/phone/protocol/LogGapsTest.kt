package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogGapsTest {
    private fun row(timestamp: String) =
        TailRow(timestamp, null, null, null, TailSensor(20.0, 50.0, null), List(TailReply.SLOT_COUNT) { TailSensor(null, null, null) })

    private fun rows(vararg t: String) = t.map { row(it) }

    @Test
    fun hourlyRowsGiveNoGap() {
        val r = LogGaps.report(rows("2026-09-01 00:00:00", "2026-09-01 01:00:00", "2026-09-01 02:00:00"))
        assertTrue(r.gaps.isEmpty())
        assertEquals(3, r.rowCount)
        assertEquals(100.0, r.coveragePct, 0.0)
    }

    @Test
    fun stepOf90MinutesGivesNoGap() {
        assertTrue(LogGaps.find(rows("2026-09-01 01:00:00", "2026-09-01 02:30:00")).isEmpty())
    }

    @Test
    fun stepOf91MinutesGivesOneGap() {
        val gaps = LogGaps.find(rows("2026-09-01 01:00:00", "2026-09-01 02:31:00"))
        assertEquals(1, gaps.size)
        assertEquals(1, gaps[0].missingHours)
    }

    @Test
    fun stepOf3HoursGivesOneGapWith2MissingHours() {
        val r = LogGaps.report(rows("2026-09-01 01:00:00", "2026-09-01 04:00:00"))
        assertEquals(1, r.gaps.size)
        assertEquals(LogGaps.Gap("2026-09-01 01:00:00", "2026-09-01 04:00:00", 2), r.gaps[0])
        assertEquals(2, r.missingHours)
        assertEquals(50.0, r.coveragePct, 0.001)
    }

    @Test
    fun repeatedHourOfOctoberChangeGivesNoGap() {
        val r = rows("2026-10-25 01:00:00", "2026-10-25 02:00:00", "2026-10-25 02:00:00", "2026-10-25 03:00:00", "2026-10-25 04:00:00")
        assertTrue(LogGaps.find(r).isEmpty())
    }

    @Test
    fun realGapAcrossOctoberChangeCountsOneHourLess() {
        // 22:00 CEST to 04:00 CET is 7 h real and 6 h wall clock; the smaller step gives 5 missing hours.
        val gaps = LogGaps.find(rows("2026-10-24 22:00:00", "2026-10-25 04:00:00"))
        assertEquals(listOf(5), gaps.map { it.missingHours })
    }

    @Test
    fun marchChangeGivesNoGap() {
        assertTrue(LogGaps.find(rows("2026-03-29 00:00:00", "2026-03-29 01:00:00", "2026-03-29 03:00:00", "2026-03-29 04:00:00")).isEmpty())
    }

    @Test
    fun unreadableTimestampIsSkipped() {
        val r = LogGaps.report(rows("bad", "2026-09-01 01:00:00", "2026-13-01 00:00:00", "2026-02-30 00:00:00"))
        assertEquals(1, r.rowCount)
        assertTrue(r.gaps.isEmpty())
    }

    @Test
    fun rowsOutOfOrderAreSortedFirst() {
        val gaps = LogGaps.find(rows("2026-09-01 04:00:00", "2026-09-01 01:00:00", "2026-09-01 02:00:00"))
        assertEquals(1, gaps.size)
        assertEquals("2026-09-01 02:00:00", gaps[0].start)
    }

    @Test
    fun noRowsGiveCoverageZero() {
        val r = LogGaps.report(emptyList())
        assertEquals(0.0, r.coveragePct, 0.0)
        assertEquals("Keine Lücken", LogGaps.summaryText(r))
    }

    @Test
    fun texts() {
        val r = LogGaps.report(rows("2026-09-01 01:00:00", "2026-09-01 04:00:00"))
        assertEquals("2 h fehlen in 1 Lücke", LogGaps.summaryText(r))
        assertEquals("2026-09-01 01:00:00 bis 2026-09-01 04:00:00: 2 h", LogGaps.gapLine(r.gaps[0]))
    }

    @Test
    fun fixtureSampleGivesOneGapWith666MissingHours() {
        val lines = Fixtures.file("tail_reply_sample.txt").readLines().filter { it.isNotEmpty() && !it.startsWith("CRC32") }
        val r = LogGaps.report(TailReply.parse(lines))
        assertEquals(3, r.rowCount)
        assertEquals(1, r.gaps.size)
        assertEquals(666, r.missingHours)
    }
}
