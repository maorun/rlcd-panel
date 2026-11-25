package com.rlcdpanel.watch.protocol

import com.rlcdpanel.watch.BadgeChips
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class AlertTimelineTest {
    private val zone = ZoneId.of("UTC")
    private fun row(time: String, event: String, detail: Int) = EventRow(time, event, detail)
    private fun ms(time: String) = AlertTimeline.parse(time, zone)!!
    private val now = ms("2026-01-01 12:00:00")
    private fun build(vararg rows: EventRow) = AlertTimeline.build(rows.toList(), now, zone)

    @Test
    fun riseAndFallGiveOneInterval() {
        val intervals = build(
            row("2026-01-01 08:00:00", "ALERT_RISE", 1),
            row("2026-01-01 09:30:00", "ALERT_FALL", 1)
        )
        assertEquals(
            listOf(AlertInterval(1, ms("2026-01-01 08:00:00"), ms("2026-01-01 09:30:00"), false, false)),
            intervals
        )
        assertEquals("FREEZER", intervals[0].name)
        assertEquals(90 * 60_000L, intervals[0].durationMs)
    }

    @Test
    fun riseWithoutFallStaysOpenUntilNow() {
        val intervals = build(row("2026-01-01 10:00:00", "ALERT_RISE", 2))
        assertEquals(listOf(AlertInterval(2, ms("2026-01-01 10:00:00"), now, true, false)), intervals)
    }

    @Test
    fun fallWithoutRiseStartsAtFirstRowAndIsUnknown() {
        val intervals = build(
            row("2026-01-01 07:00:00", "SD_FAULT", 2),
            row("2026-01-01 09:00:00", "ALERT_FALL", 4)
        )
        assertEquals(
            listOf(AlertInterval(4, ms("2026-01-01 07:00:00"), ms("2026-01-01 09:00:00"), false, true)),
            intervals
        )
    }

    @Test
    fun twoAlertBitsGiveTwoSeparateIntervals() {
        val intervals = build(
            row("2026-01-01 08:00:00", "ALERT_RISE", 1),
            row("2026-01-01 08:10:00", "ALERT_RISE", 128),
            row("2026-01-01 08:20:00", "ALERT_FALL", 1),
            row("2026-01-01 08:30:00", "ALERT_FALL", 128)
        )
        assertEquals(listOf(1, 128), intervals.map { it.alert })
        assertEquals(listOf(ms("2026-01-01 08:20:00"), ms("2026-01-01 08:30:00")), intervals.map { it.endMs })
        assertTrue(intervals.none { it.isOpen || it.startUnknown })
    }

    @Test
    fun secondRiseKeepsFirstStart() {
        val intervals = build(
            row("2026-01-01 08:00:00", "ALERT_RISE", 2),
            row("2026-01-01 08:05:00", "ALERT_RISE", 2)
        )
        assertEquals(1, intervals.size)
        assertEquals(ms("2026-01-01 08:00:00"), intervals[0].startMs)
    }

    @Test
    fun droppedRowMarksOpenIntervalUnknown() {
        val intervals = build(
            row("2026-01-01 06:00:00", "ALERT_RISE", 8),
            row("2026-01-01 07:00:00", "EVENTS_DROPPED", 3),
            row("2026-01-01 08:00:00", "ALERT_FALL", 8),
            row("2026-01-01 09:00:00", "ALERT_RISE", 8),
            row("2026-01-01 10:00:00", "ALERT_FALL", 8)
        )
        assertEquals(listOf(true, false), intervals.map { it.startUnknown })
    }

    @Test
    fun leadingDroppedRowDoesNotStartFallWithoutRise() {
        val intervals = build(
            row("2026-01-01 12:00:00", "EVENTS_DROPPED", 3),
            row("2026-01-01 07:00:00", "SD_FAULT", 2),
            row("2026-01-01 09:00:00", "ALERT_FALL", 4)
        )
        assertEquals(1, intervals.size)
        assertEquals(ms("2026-01-01 07:00:00"), intervals[0].startMs)
        assertTrue(intervals[0].startUnknown)
    }

    @Test
    fun badTimeAndEmptyRowsGiveNoInterval() {
        assertTrue(build().isEmpty())
        assertTrue(build(row("", "ALERT_RISE", 1)).isEmpty())
        val intervals = build(
            row("2026-01-01 08:00:00", "ALERT_RISE", 1),
            row("", "ALERT_FALL", 1)
        )
        assertTrue(intervals.isEmpty())
        assertNull(AlertTimeline.parse("garbage", zone))
    }

    @Test
    fun sharedFixtureGivesOneOpenHeatInterval() {
        val lines = Fixtures.file("events_reply_sample.txt").readLines().filter { !it.startsWith("CRC32") }
        val log = EventsReply.parse(lines)
        val intervals = AlertTimeline.build(log.rows, now, zone)
        assertEquals(1, intervals.size)
        assertEquals("HEAT", intervals[0].name)
        assertEquals(ms("2026-11-18 09:07:00"), intervals[0].startMs)
        assertTrue(intervals[0].isOpen)
    }

    @Test
    fun durationText() {
        assertEquals("< 1 min", AlertTimeline.durationText(30_000))
        assertEquals("45 min", AlertTimeline.durationText(45 * 60_000L))
        assertEquals("2 h 05 min", AlertTimeline.durationText(125 * 60_000L))
        assertEquals("1 d 3 h", AlertTimeline.durationText((24 + 3) * 3_600_000L))
        assertEquals("< 1 min", AlertTimeline.durationText(-5))
    }

    @Test
    fun durationTextBoundaries() {
        assertEquals("59 min", AlertTimeline.durationText(59 * 60_000L))
        assertEquals("1 h 00 min", AlertTimeline.durationText(60 * 60_000L))
        assertEquals("23 h 59 min", AlertTimeline.durationText((24 * 60 - 1) * 60_000L))
        assertEquals("1 d 0 h", AlertTimeline.durationText(24 * 3_600_000L))
    }

    @Test
    fun sameStartSortsByAlertBit() {
        val intervals = build(
            row("2026-01-01 08:00:00", "ALERT_RISE", 128),
            row("2026-01-01 08:00:00", "ALERT_RISE", 2)
        )
        assertEquals(listOf(2, 128), intervals.map { it.alert })
    }

    @Test
    fun lineTextShowsOpenAndGuessedStart() {
        val open = AlertInterval(128, ms("2026-01-01 08:00:00"), ms("2026-01-01 10:05:00"), true, false)
        assertEquals("HEAT: 01.01. 08:00 – läuft (2 h 05 min)", AlertTimeline.lineText(open, zone))
        val closed = AlertInterval(1, ms("2026-01-01 07:00:00"), ms("2026-01-01 07:45:00"), false, true)
        assertEquals("FREEZER: ~01.01. 07:00 – 01.01. 07:45 (45 min)", AlertTimeline.lineText(closed, zone))
        assertEquals("ALERT 3", AlertInterval(3, 0, 0, false, false).name)
    }

    @Test
    fun alertNamesMatchEventText() {
        for ((bit, name) in AlertTimeline.ALERT_NAMES) {
            assertEquals("$name alert on", EventText.describe(EventRow("", "ALERT_RISE", bit)))
        }
        assertEquals(8, AlertTimeline.ALERT_NAMES.size)
    }

    private fun badge(event: String, tile: Int, start: String, end: String?, unknown: Boolean = false) =
        BadgeInterval(event, tile, ms(start), end?.let { ms(it) } ?: now, end == null, unknown)

    @Test
    fun chipTextOfClosedWallDampInterval() {
        val chip = BadgeChips.text(badge("WALL_DAMP", 3, "2026-01-01 08:00:00", "2026-01-01 08:45:00"), zone)
        assertEquals("C Slot 3", chip.label)
        assertEquals("01.01. 08:00 · 45 min", chip.secondary)
    }

    @Test
    fun chipTextOfOpenDryAirIntervalShowsOffen() {
        val chip = BadgeChips.text(badge("DRY_AIR", 0, "2026-01-01 10:00:00", null), zone)
        assertEquals("D Onboard", chip.label)
        assertEquals("01.01. 10:00 · offen", chip.secondary)
    }

    @Test
    fun chipTextOfUnknownStartShowsQuestionMark() {
        val chip = BadgeChips.text(badge("SENSOR_DRIFT", 2, "2026-01-01 07:00:00", "2026-01-01 09:05:00", true), zone)
        assertEquals("S Slot 2", chip.label)
        assertEquals("? · 2 h 05 min", chip.secondary)
        val open = BadgeChips.text(badge("WINDOW_OPEN", 1, "2026-01-01 07:00:00", null, true), zone)
        assertEquals("? · offen", open.secondary)
    }

    @Test
    fun newestChipsAreNewestFirstAndCappedAtSix() {
        val rows = (1..8).flatMap { h ->
            val hh = "%02d".format(h)
            listOf(row("2026-01-01 $hh:00:00", "DRY_AIR", 0x100 + h), row("2026-01-01 $hh:10:00", "DRY_AIR", h))
        }
        val chips = BadgeChips.newest(rows, now, zone)
        assertEquals(6, chips.size)
        assertEquals(listOf(8, 7, 6, 5, 4, 3), chips.map { it.tile })
    }
}
