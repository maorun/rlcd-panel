package com.rlcdpanel.watch.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test

class DaysReplyTest {
    private fun throwsParse(block: () -> Unit) {
        try {
            block()
            fail("expected ParseException")
        } catch (_: ParseException) {
        }
    }

    /** A row: onboard 18.0 to 24.5 C, 40.0 to 55.5 %RH, mold 3 h; slot 1 with -3.5 to 2.0 C only. */
    private fun row(date: String = "2026-01-02", onboardMold: String = "3", columns: Int = DaysReply.COLUMNS): String {
        val c = MutableList(columns) { "" }
        c[0] = date
        c[1] = "18.0"; c[2] = "24.5"; c[3] = "21.2"; c[4] = "40.0"; c[5] = "55.5"; c[6] = "47.8"
        c[7] = "-3.5"; c[8] = "2.0"
        c[1 + 6 * DaysReply.SENSOR_COUNT + 5] = onboardMold
        return c.joinToString(",")
    }

    private fun reply(vararg rows: String) = listOf("BEGIN DAYS ${rows.size}", DaysReply.HEADER) + rows

    private fun replyV5(vararg rows: String) = listOf("BEGIN DAYS ${rows.size}", DaysReply.HEADER_V5) + rows

    private fun replyV6(vararg rows: String) = listOf("BEGIN DAYS ${rows.size}", DaysReply.HEADER_V6) + rows

    @Test
    fun headersHave105And94And83Columns() {
        assertEquals(105, DaysReply.COLUMNS)
        assertEquals(94, DaysReply.COLUMNS_V6)
        assertEquals(83, DaysReply.COLUMNS_V5)
        assertEquals(DaysReply.COLUMNS, DaysReply.HEADER.split(",").size)
        assertEquals(DaysReply.COLUMNS_V6, DaysReply.HEADER_V6.split(",").size)
        assertEquals(DaysReply.COLUMNS_V5, DaysReply.HEADER_V5.split(",").size)
        assertEquals(DaysReply.COLUMNS, row().split(",").size)
        assertTrue(DaysReply.HEADER.startsWith(DaysReply.HEADER_V6 + ",heat_h_0,"))
        assertTrue(DaysReply.HEADER.endsWith(",heat_h_10"))
    }

    @Test
    fun parsesDryHoursAndEmptyFieldIsNull() {
        val c = row().split(",").toMutableList()
        c[DaysReply.COLUMNS_V5 + 2] = "5"
        val r = DaysReply.parse(reply(c.joinToString(","))).single()
        assertEquals(5, r.sensors[2].dryHours)
        assertNull(r.sensors[0].dryHours)
    }

    @Test
    fun parsesHeatHoursAndEmptyFieldIsNull() {
        val c = row().split(",").toMutableList()
        c[DaysReply.COLUMNS_V6] = "0"
        c[DaysReply.COLUMNS_V6 + 3] = "12"
        c[DaysReply.COLUMNS_V6 + 10] = "24"
        val r = DaysReply.parse(reply(c.joinToString(","))).single()
        assertEquals(0, r.sensors[0].heatHours)
        assertNull(r.sensors[1].heatHours)
        assertEquals(12, r.sensors[3].heatHours)
        assertEquals(24, r.sensors[10].heatHours)
        assertEquals(false, r.sensors[3].isEmpty)
        assertNull(r.sensors[3].dryHours)
    }

    @Test
    fun daily5ReplyHasNullDryAndHeatHours() {
        val r = DaysReply.parse(replyV5(row(columns = DaysReply.COLUMNS_V5))).single()
        r.sensors.forEach { assertNull(it.dryHours) }
        r.sensors.forEach { assertNull(it.heatHours) }
        assertEquals(3, r.sensors[0].moldHours)
    }

    @Test
    fun daily6ReplyKeepsDryHoursAndHasNullHeatHours() {
        val c = row(columns = DaysReply.COLUMNS_V6).split(",").toMutableList()
        c[DaysReply.COLUMNS_V5 + 1] = "6"
        val r = DaysReply.parse(replyV6(c.joinToString(","))).single()
        assertEquals(6, r.sensors[1].dryHours)
        r.sensors.forEach { assertNull(it.heatHours) }
    }

    @Test
    fun badDryValuesThrow() {
        for (bad in listOf("25", "x", "-1")) {
            val c = row().split(",").toMutableList()
            c[DaysReply.COLUMNS_V5] = bad
            throwsParse { DaysReply.parse(reply(c.joinToString(","))) }
        }
    }

    @Test
    fun badHeatValuesThrow() {
        for (bad in listOf("25", "x", "-1", "007", "100")) {
            val c = row().split(",").toMutableList()
            c[DaysReply.COLUMNS_V6] = bad
            throwsParse { DaysReply.parse(reply(c.joinToString(","))) }
        }
    }

    @Test
    fun v6FixtureParsesWithEmptyDryFields() {
        val lines = Fixtures.file("days_reply_v6_sample.txt").readLines().filter { !it.startsWith("CRC32") }
        val rows = DaysReply.parse(lines)
        assertEquals(3, rows.size)
        rows.forEach { r -> r.sensors.forEach { assertNull(it.dryHours) } }
        rows.forEach { r -> r.sensors.forEach { assertNull(it.heatHours) } }
    }

    @Test
    fun v7FixtureParsesWithEmptyHeatFields() {
        val lines = Fixtures.file("days_reply_v7_sample.txt").readLines().filter { !it.startsWith("CRC32") }
        assertEquals(DaysReply.HEADER, lines[1])
        val rows = DaysReply.parse(lines)
        assertEquals(listOf("2025-12-31", "2026-01-01", "2026-01-02"), rows.map { it.date })
        rows.forEach { r ->
            assertEquals(DaysSensor(18.0, 24.5, 40.0, 55.5, null), r.sensors[0])
            r.sensors.forEach { assertNull(it.heatHours) }
        }
    }

    @Test
    fun rowsWithAWrongColumnCountForTheHeaderThrow() {
        throwsParse { DaysReply.parse(reply(row(columns = 104))) }
        throwsParse { DaysReply.parse(reply(row(columns = 106))) }
        // A v7 row under the v6 or v5 header, a v6 row under the v7 header and a v5 row under the v7 header are all wrong.
        throwsParse { DaysReply.parse(replyV6(row())) }
        throwsParse { DaysReply.parse(replyV5(row())) }
        throwsParse { DaysReply.parse(reply(row(columns = DaysReply.COLUMNS_V6))) }
        throwsParse { DaysReply.parse(reply(row(columns = DaysReply.COLUMNS_V5))) }
    }

    @Test
    fun parsesValuesAndEmptyPairs() {
        val r = DaysReply.parse(reply(row())).single()
        assertEquals("2026-01-02", r.date)
        assertEquals(DaysSensor(18.0, 24.5, 40.0, 55.5, 3), r.sensors[0])
        assertEquals(-3.5, r.sensors[1].temperatureMinC!!, 0.0)
        assertNull(r.sensors[1].humidityMinPct)
        assertNull(r.sensors[1].humidityMaxPct)
        assertNull(r.sensors[1].moldHours)
        assertTrue(r.sensors[2].isEmpty)
        assertEquals(DaysReply.SENSOR_COUNT, r.sensors.size)
    }

    @Test
    fun emptyReplyGivesNoRows() {
        assertTrue(DaysReply.parse(reply()).isEmpty())
    }

    @Test
    fun wrongColumnCountThrows() {
        throwsParse { DaysReply.parse(reply(row() + ",1")) }
        throwsParse { DaysReply.parse(reply(row().substringBeforeLast(","))) }
        throwsParse { DaysReply.parse(reply("2026-01-02,18.0")) }
    }

    @Test
    fun badValuesThrow() {
        throwsParse { DaysReply.parse(reply(row("2026-02-30"))) }
        throwsParse { DaysReply.parse(reply(row("1999-01-01"))) }
        throwsParse { DaysReply.parse(reply(row(onboardMold = "25"))) }
        throwsParse { DaysReply.parse(reply(row(onboardMold = "x"))) }
        throwsParse { DaysReply.parse(reply(row().replaceFirst("40.0", "140.0"))) }
        // A half pair rejects the row, like the firmware.
        throwsParse { DaysReply.parse(reply(row().replaceFirst(",24.5,", ",,"))) }
    }

    @Test
    fun badFramingThrows() {
        throwsParse { DaysReply.parse(listOf("BEGIN WEEK", DaysReply.HEADER)) }
        throwsParse { DaysReply.parse(listOf("BEGIN DAYS 0", "date")) }
        throwsParse { DaysReply.parse(listOf("BEGIN DAYS 2", DaysReply.HEADER, row())) }
        throwsParse { DaysReply.parse(listOf("BEGIN DAYS 0", DaysReply.HEADER, row())) }
        throwsParse { DaysReply.parse(listOf("BEGIN DAYS 05", DaysReply.HEADER)) }
    }

    @Test
    fun commandAndErrorTexts() {
        assertEquals("DAYS 31", DaysReply.command(31))
        assertNull(DaysReply.command(0))
        assertNull(DaysReply.command(32))
        assertEquals("Keine MicroSD-Karte im Panel", DaysReply.errorText("ERR NO_CARD"))
        assertTrue(DaysReply.errorText("ERR NO_FILE")!!.startsWith("Keine Tagesdatei"))
        assertEquals("Die MicroSD-Karte lässt sich nicht lesen", DaysReply.errorText("ERR READ_FAILED"))
        assertNull(DaysReply.errorText("ERR BUSY"))
    }

    @Test
    fun maxRowCountAndDayLabel() {
        throwsParse { DaysReply.parse(listOf("BEGIN DAYS 32", DaysReply.HEADER)) }
        assertEquals("01-02", DaysReply.dayLabel("2026-01-02"))
        assertEquals("bad", DaysReply.dayLabel("bad"))
    }

    @Test
    fun csvHasOneLinePerSensorWithData() {
        val rows = DaysReply.parse(reply(row()))
        val lines = DaysReply.csv(rows, PanelConfig()).trim().lines()
        assertEquals("date,sensor,name,temp_min_c,temp_max_c,rh_min_pct,rh_max_pct,mold_h,dry_h", lines[0])
        assertEquals(3, lines.size)
        assertTrue(lines[1].startsWith("2026-01-02,0,"))
        assertTrue(lines[1].endsWith("18.0,24.5,40.0,55.5,3,"))
        assertTrue(lines[2].endsWith("-3.5,2.0,,,,"))
    }

    private fun fixtureLines(): List<String> =
        Fixtures.file("days_reply_sample.txt").readLines().filter { !it.startsWith("CRC32") }

    @Test
    fun sharedFixtureParses() {
        val rows = DaysReply.parse(fixtureLines())
        assertEquals(3, rows.size)
        assertEquals(listOf("2025-12-31", "2026-01-01", "2026-01-02"), rows.map { it.date })
        rows.forEach { r ->
            assertEquals(DaysSensor(18.0, 24.5, 40.0, 55.5, null), r.sensors[0])
            r.sensors.drop(1).forEach { assertTrue(it.isEmpty) }
        }
        assertEquals(listOf(0), DaysReply.sensors(rows))
    }

    @Test
    fun sharedFixtureCrcMatches() {
        val text = Fixtures.file("days_reply_sample.txt").readText()
        assertEquals(ConsoleReply.Frame(fixtureLines()), ReplyAssembler().append(text.toByteArray()))
    }

    // --- MoldSummary ---

    /** A row of [date] with the mold hours of the onboard sensor and slots 1 to 3 (null is an empty field). */
    private fun moldRow(date: String, vararg mold: Int?): DaysRow {
        val empty = DaysSensor(null, null, null, null, null)
        return DaysRow(date, List(DaysReply.SENSOR_COUNT) { s -> if (s < mold.size) empty.copy(moldHours = mold[s]) else empty })
    }

    @Test
    fun summaryGivesSumDaysAndWorstDay() {
        val rows = listOf(
            moldRow("2026-01-01", 2, 0, null),
            moldRow("2026-01-02", 0, 5, null),
            moldRow("2026-01-03", 7, 5, null),
            moldRow("2026-01-04", 1, 0, null)
        )
        val rooms = MoldSummary.build(rows, PanelConfig())
        val onboard = rooms.single { it.sensor == 0 }
        assertEquals(10, onboard.totalHours)
        assertEquals(3, onboard.moldDays)
        assertEquals("2026-01-03", onboard.worstDate)
        assertEquals(7, onboard.worstHours)
        // A tie keeps the newest day.
        val slot1 = rooms.single { it.sensor == 1 }
        assertEquals(10, slot1.totalHours)
        assertEquals(2, slot1.moldDays)
        assertEquals("2026-01-03", slot1.worstDate)
        assertEquals("01-03 7 h", MoldSummary.worstText(onboard))
        assertEquals("10 h", MoldSummary.totalText(onboard))
        assertEquals("3 Tage", MoldSummary.daysText(onboard))
    }

    @Test
    fun summarySortsBySumHighestFirstThenBySensor() {
        val rows = listOf(
            moldRow("2026-01-01", 1, 9, 4, 4),
            moldRow("2026-01-02", 1, 3, 0, 0)
        )
        val rooms = MoldSummary.build(rows, PanelConfig())
        assertEquals(listOf(1, 2, 3, 0), rooms.map { it.sensor })
        assertEquals(listOf(12, 4, 4, 2), rooms.map { it.totalHours })
    }

    @Test
    fun roomWithOnlyEmptyMoldFieldsShowsDashesAndComesLast() {
        // Slot 2 has a temperature pair but no mold field in any row; slot 1 has mold hours.
        val withTemp = DaysSensor(10.0, 12.0, null, null, null)
        fun day(date: String, slot1: Int): DaysRow {
            val base = moldRow(date, null, slot1)
            return base.copy(sensors = base.sensors.mapIndexed { s, v -> if (s == 2) withTemp else v })
        }
        val rooms = MoldSummary.build(listOf(day("2026-01-01", 0), day("2026-01-02", 4)), PanelConfig())
        assertEquals(listOf(1, 2), rooms.map { it.sensor })
        val dash = rooms.last()
        assertNull(dash.totalHours)
        assertEquals("--", MoldSummary.totalText(dash))
        assertEquals("--", MoldSummary.daysText(dash))
        assertEquals("--", MoldSummary.worstText(dash))
    }

    @Test
    fun roomWithMoldValuesButNoMoldHoursShowsZeroAndDash() {
        val rooms = MoldSummary.build(listOf(moldRow("2026-01-01", 0), moldRow("2026-01-02", 0)), PanelConfig())
        val room = rooms.single()
        assertEquals(0, room.totalHours)
        assertEquals("0 h", MoldSummary.totalText(room))
        assertEquals("0 Tage", MoldSummary.daysText(room))
        assertEquals("--", MoldSummary.worstText(room))
        assertEquals("1 Tag", MoldSummary.daysText(MoldRoomSummary(0, "x", 3, 1, "2026-01-01", 3)))
    }

    @Test
    fun summaryOfTheSharedFixtureHasDashesForTheOnboardRoom() {
        val rows = DaysReply.parse(fixtureLines())
        val rooms = MoldSummary.build(rows, PanelConfig())
        assertEquals(1, rooms.size)
        assertEquals(0, rooms[0].sensor)
        assertNull(rooms[0].totalHours)
        assertEquals("--", MoldSummary.totalText(rooms[0]))
        assertEquals("--", MoldSummary.worstText(rooms[0]))
    }

    @Test
    fun summaryOfNoRowsIsEmpty() {
        assertTrue(MoldSummary.build(emptyList(), PanelConfig()).isEmpty())
        assertEquals("DAYS 31", MoldSummary.COMMAND)
    }
}
