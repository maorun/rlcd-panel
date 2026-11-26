package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class EventStatsTest {
    private val zone = ZoneId.of("UTC")
    private fun row(time: String, event: String, detail: Int) = EventRow(time, event, detail)
    private fun ms(time: String) = AlertTimeline.parse(time, zone)!!
    private val now = ms("2026-03-10 00:00:00")
    private fun alerts(vararg rows: EventRow) =
        EventStats.alerts(AlertTimeline.build(rows.toList(), now, zone), zone)
    private fun badges(vararg rows: EventRow) =
        EventStats.badges(AlertTimeline.badges(rows.toList(), now, zone), zone)

    @Test
    fun countsRisesAndHoursPerAlertAndMonth() {
        val stats = alerts(
            row("2026-01-05 08:00:00", "ALERT_RISE", 1),
            row("2026-01-05 10:00:00", "ALERT_FALL", 1),
            row("2026-01-20 00:00:00", "ALERT_RISE", 1),
            row("2026-01-20 01:30:00", "ALERT_FALL", 1),
            row("2026-02-02 12:00:00", "ALERT_RISE", 128),
            row("2026-02-02 13:00:00", "ALERT_FALL", 128)
        )
        assertEquals(
            listOf(
                AlertMonthStat(1, "2026-01", 2, 3.5, 0),
                AlertMonthStat(128, "2026-02", 1, 1.0, 0)
            ),
            stats
        )
        assertEquals("FREEZER", stats[0].name)
    }

    @Test
    fun intervalAcrossMonthEndSplitsHoursAndCountsRiseInStartMonth() {
        val stats = alerts(
            row("2026-01-31 23:00:00", "ALERT_RISE", 2),
            row("2026-02-01 01:00:00", "ALERT_FALL", 2)
        )
        assertEquals(
            listOf(AlertMonthStat(2, "2026-01", 1, 1.0, 0), AlertMonthStat(2, "2026-02", 0, 1.0, 0)),
            stats
        )
    }

    @Test
    fun intervalAcrossYearEndSplitsIntoDecemberAndJanuary() {
        val stats = alerts(
            row("2025-12-31 22:00:00", "ALERT_RISE", 4),
            row("2026-01-01 02:00:00", "ALERT_FALL", 4)
        )
        assertEquals(listOf("2025-12", "2026-01"), stats.map { it.month })
        assertEquals(listOf(2.0, 2.0), stats.map { it.hours })
    }

    @Test
    fun unknownStartCountsOnlyAsUnknown() {
        val stats = alerts(
            row("2026-01-05 07:00:00", "SD_FAULT", 2),
            row("2026-01-05 09:00:00", "ALERT_FALL", 4)
        )
        assertEquals(listOf(AlertMonthStat(4, "2026-01", 0, 0.0, 1)), stats)
    }

    @Test
    fun openIntervalRunsUntilNow() {
        val stats = alerts(row("2026-03-09 12:00:00", "ALERT_RISE", 8))
        assertEquals(listOf(AlertMonthStat(8, "2026-03", 1, 12.0, 0)), stats)
    }

    @Test
    fun noIntervalsGiveNoStats() {
        assertTrue(alerts().isEmpty())
        assertTrue(badges().isEmpty())
    }

    @Test
    fun badgesCountPerTileAndMonth() {
        val stats = badges(
            row("2026-01-05 08:00:00", "WINDOW_OPEN", 0x0102),
            row("2026-01-05 08:30:00", "WINDOW_OPEN", 0x0002),
            row("2026-01-10 08:00:00", "WINDOW_OPEN", 0x0102),
            row("2026-01-10 09:00:00", "WINDOW_OPEN", 0x0002),
            row("2026-01-11 08:00:00", "DRY_AIR", 0x0100),
            row("2026-01-11 10:00:00", "DRY_AIR", 0x0000),
            row("2026-01-12 08:00:00", "WINDOW_OPEN", 0x0101),
            row("2026-01-12 08:15:00", "WINDOW_OPEN", 0x0001)
        )
        assertEquals(
            listOf(
                BadgeMonthStat("DRY_AIR", 0, "2026-01", 1, 2.0, 0),
                BadgeMonthStat("WINDOW_OPEN", 1, "2026-01", 1, 0.25, 0),
                BadgeMonthStat("WINDOW_OPEN", 2, "2026-01", 2, 1.5, 0)
            ),
            stats
        )
        assertEquals("D", stats.first { it.event == "DRY_AIR" }.badge)
        assertEquals("Slot 2", stats.first { it.event == "WINDOW_OPEN" }.room)
        assertEquals("Onboard", stats.first { it.event == "DRY_AIR" }.room)
    }

    @Test
    fun badgeIntervalAcrossMonthEndSplits() {
        val stats = badges(
            row("2026-01-31 23:30:00", "WALL_DAMP", 0x0101),
            row("2026-02-01 00:30:00", "WALL_DAMP", 0x0001)
        )
        assertEquals(listOf(1, 0), stats.map { it.rises })
        assertEquals(listOf(0.5, 0.5), stats.map { it.hours })
    }

    @Test
    fun lineTextShowsCountsAndHours() {
        assertEquals("2026-01 FREEZER: 2 x, 3.5 h", EventStats.lineText(AlertMonthStat(1, "2026-01", 2, 3.5, 0)))
        assertEquals(
            "2026-01 FREEZER: 1 x, 0.0 h, 1 ohne Start",
            EventStats.lineText(AlertMonthStat(1, "2026-01", 1, 0.0, 1))
        )
        assertEquals(
            "2026-02 W Slot 2: 3 x, 1.5 h",
            EventStats.badgeLineText(BadgeMonthStat("WINDOW_OPEN", 2, "2026-02", 3, 1.5, 0))
        )
    }

    @Test
    fun alertsInOneMonthSortByAlertBit() {
        val stats = alerts(
            row("2026-01-05 08:00:00", "ALERT_RISE", 128),
            row("2026-01-05 08:10:00", "ALERT_RISE", 1),
            row("2026-01-05 09:00:00", "ALERT_FALL", 128),
            row("2026-01-05 09:10:00", "ALERT_FALL", 1)
        )
        assertEquals(listOf(1, 128), stats.map { it.alert })
        assertEquals("HEAT", stats[1].name)
    }

    @Test
    fun intervalOverThreeMonthsSplitsIntoEachMonth() {
        val stats = alerts(
            row("2026-01-31 23:00:00", "ALERT_RISE", 16),
            row("2026-03-01 01:00:00", "ALERT_FALL", 16)
        )
        assertEquals(listOf("2026-01", "2026-02", "2026-03"), stats.map { it.month })
        assertEquals(listOf(1.0, 672.0, 1.0), stats.map { it.hours })
        assertEquals(listOf(1, 0, 0), stats.map { it.rises })
    }

    @Test
    fun monthFollowsTheGivenZone() {
        val intervals = AlertTimeline.build(
            listOf(
                row("2026-01-31 23:30:00", "ALERT_RISE", 2),
                row("2026-02-01 00:30:00", "ALERT_FALL", 2)
            ),
            now,
            zone
        )
        val stats = EventStats.alerts(intervals, ZoneId.of("Europe/Berlin"))
        assertEquals(listOf(AlertMonthStat(2, "2026-02", 1, 1.0, 0)), stats)
    }

    @Test
    fun knownAndUnknownStartShareOneMonthRow() {
        val stats = alerts(
            row("2026-01-05 07:00:00", "SD_FAULT", 2),
            row("2026-01-05 09:00:00", "ALERT_FALL", 4),
            row("2026-01-05 10:00:00", "ALERT_RISE", 4),
            row("2026-01-05 11:00:00", "ALERT_FALL", 4)
        )
        assertEquals(listOf(AlertMonthStat(4, "2026-01", 1, 1.0, 1)), stats)
    }

    @Test
    fun sampleFixtureGivesOneOpenHeatInterval() {
        val lines = Fixtures.file("events_reply_sample.txt").readLines().filter { !it.startsWith("CRC32") }
        val rows = EventsReply.parse(lines).rows
        val fixtureNow = ms("2026-11-18 11:07:00")
        val stats = EventStats.alerts(AlertTimeline.build(rows, fixtureNow, zone), zone)
        assertEquals(listOf(AlertMonthStat(128, "2026-11", 1, 2.0, 0)), stats)
        assertEquals("HEAT", stats[0].name)
        assertEquals(
            listOf(BadgeMonthStat("WINDOW_OPEN", 1, "2026-11", 1, 2.0 - 15.0 / 60.0, 0)),
            EventStats.badges(AlertTimeline.badges(rows, fixtureNow, zone), zone)
        )
    }

    @Test
    fun zeroLengthIntervalCountsOneRiseAndNoHours() {
        val stats = alerts(
            row("2026-01-05 08:00:00", "ALERT_RISE", 1),
            row("2026-01-05 08:00:00", "ALERT_FALL", 1)
        )
        assertEquals(listOf(AlertMonthStat(1, "2026-01", 1, 0.0, 0)), stats)
    }

    @Test
    fun lineTextRoundsHoursToOneDecimal() {
        assertEquals("2026-01 HEAT: 1 x, 0.3 h", EventStats.lineText(AlertMonthStat(128, "2026-01", 1, 0.25, 0)))
    }
}
