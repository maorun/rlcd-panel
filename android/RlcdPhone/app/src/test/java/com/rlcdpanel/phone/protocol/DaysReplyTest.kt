package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DaysReplyTest {
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

    /** First heat column of sensor 0. */
    private val heatFirst = DaysReply.COLUMNS_V6

    /** First dry column of sensor 0. */
    private val dryFirst = DaysReply.COLUMNS_V5

    @Test
    fun headersHave105And94And83Columns() {
        assertEquals(105, DaysReply.COLUMNS)
        assertEquals(94, DaysReply.COLUMNS_V6)
        assertEquals(83, DaysReply.COLUMNS_V5)
        assertEquals(DaysReply.COLUMNS, DaysReply.HEADER.split(",").size)
        assertEquals(DaysReply.COLUMNS_V6, DaysReply.HEADER_V6.split(",").size)
        assertEquals(DaysReply.COLUMNS_V5, DaysReply.HEADER_V5.split(",").size)
        assertEquals(DaysReply.COLUMNS, row().split(",").size)
        assertTrue(DaysReply.HEADER_V6.startsWith(DaysReply.HEADER_V5 + ",dry_h_0,"))
        assertTrue(DaysReply.HEADER_V6.endsWith(",dry_h_10"))
        assertTrue(DaysReply.HEADER.startsWith(DaysReply.HEADER_V6 + ",heat_h_0,"))
        assertTrue(DaysReply.HEADER.endsWith(",heat_h_10"))
    }

    @Test
    fun parsesDryHoursOfASensor() {
        val c = row().split(",").toMutableList()
        c[dryFirst + 2] = "5"
        c[dryFirst] = "0"
        val r = DaysReply.parse(reply(c.joinToString(","))).single()
        assertEquals(0, r.sensors[0].dryHours)
        assertNull(r.sensors[1].dryHours)
        assertEquals(5, r.sensors[2].dryHours)
        assertEquals(false, r.sensors[2].isEmpty)
    }

    @Test
    fun emptyDryFieldIsNull() {
        val r = DaysReply.parse(reply(row())).single()
        r.sensors.forEach { assertNull(it.dryHours) }
    }

    @Test
    fun daily5ReplyHasNullDryAndHeatHours() {
        val r = DaysReply.parse(replyV5(row(columns = DaysReply.COLUMNS_V5))).single()
        assertEquals(DaysSensor(18.0, 24.5, 40.0, 55.5, 3), r.sensors[0])
        r.sensors.forEach { assertNull(it.dryHours) }
        r.sensors.forEach { assertNull(it.heatHours) }
    }

    @Test
    fun daily6ReplyKeepsDryHoursAndHasNullHeatHours() {
        val c = row(columns = DaysReply.COLUMNS_V6).split(",").toMutableList()
        c[dryFirst + 1] = "6"
        val r = DaysReply.parse(replyV6(c.joinToString(","))).single()
        assertEquals(6, r.sensors[1].dryHours)
        r.sensors.forEach { assertNull(it.heatHours) }
    }

    @Test
    fun parsesHeatHoursOfASensor() {
        val c = row().split(",").toMutableList()
        c[heatFirst] = "0"
        c[heatFirst + 3] = "12"
        c[heatFirst + 10] = "24"
        val r = DaysReply.parse(reply(c.joinToString(","))).single()
        assertEquals(0, r.sensors[0].heatHours)
        assertNull(r.sensors[1].heatHours)
        assertEquals(12, r.sensors[3].heatHours)
        assertEquals(24, r.sensors[10].heatHours)
        assertEquals(false, r.sensors[3].isEmpty)
        assertNull(r.sensors[3].dryHours)
    }

    @Test
    fun emptyHeatFieldIsNull() {
        val r = DaysReply.parse(reply(row())).single()
        r.sensors.forEach { assertNull(it.heatHours) }
    }

    @Test
    fun badHeatValuesThrow() {
        for (bad in listOf("25", "x", "-1", "007", "100")) {
            val c = row().split(",").toMutableList()
            c[heatFirst] = bad
            assertThrows(ParseException::class.java) { DaysReply.parse(reply(c.joinToString(","))) }
        }
    }

    @Test
    fun rowsWithAWrongColumnCountForTheHeaderThrow() {
        assertThrows(ParseException::class.java) { DaysReply.parse(reply(row(columns = 104))) }
        assertThrows(ParseException::class.java) { DaysReply.parse(reply(row(columns = 106))) }
        // A v7 row under the v6 or v5 header, a v6 row under the v7 header and a v5 row under the v7 header are all wrong.
        assertThrows(ParseException::class.java) { DaysReply.parse(replyV6(row())) }
        assertThrows(ParseException::class.java) { DaysReply.parse(replyV5(row())) }
        assertThrows(ParseException::class.java) { DaysReply.parse(reply(row(columns = DaysReply.COLUMNS_V6))) }
        assertThrows(ParseException::class.java) { DaysReply.parse(reply(row(columns = DaysReply.COLUMNS_V5))) }
    }

    @Test
    fun badDryValuesThrow() {
        for (bad in listOf("25", "x", "-1", "007")) {
            val c = row().split(",").toMutableList()
            c[dryFirst] = bad
            assertThrows(ParseException::class.java) { DaysReply.parse(reply(c.joinToString(","))) }
        }
    }

    @Test
    fun v6FixtureParsesWithEmptyDryFields() {
        val lines = Fixtures.file("days_reply_v6_sample.txt").readLines().filter { !it.startsWith("CRC32") }
        val rows = DaysReply.parse(lines)
        assertEquals(3, rows.size)
        rows.forEach { r ->
            assertEquals(DaysSensor(18.0, 24.5, 40.0, 55.5, null), r.sensors[0])
            r.sensors.forEach { assertNull(it.dryHours) }
            r.sensors.forEach { assertNull(it.heatHours) }
        }
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
    fun v7FixtureCrcMatches() {
        val text = Fixtures.file("days_reply_v7_sample.txt").readText()
        val lines = text.lines().filter { it.isNotEmpty() && !it.startsWith("CRC32") }
        assertEquals(ConsoleReply.Frame(lines), ReplyAssembler().append(text.toByteArray()))
    }

    @Test
    fun v6FixtureCrcMatches() {
        val text = Fixtures.file("days_reply_v6_sample.txt").readText()
        val lines = text.lines().filter { it.isNotEmpty() && !it.startsWith("CRC32") }
        assertEquals(ConsoleReply.Frame(lines), ReplyAssembler().append(text.toByteArray()))
    }

    @Test
    fun csvHasADryColumn() {
        val c = row().split(",").toMutableList()
        c[dryFirst] = "4"
        val lines = DaysReply.csv(DaysReply.parse(reply(c.joinToString(","))), PanelConfig()).trim().lines()
        assertEquals("date,sensor,name,temp_min_c,temp_max_c,rh_min_pct,rh_max_pct,mold_h,dry_h", lines[0])
        assertTrue(lines[1].endsWith("18.0,24.5,40.0,55.5,3,4"))
        assertTrue(lines[2].endsWith("-3.5,2.0,,,,"))
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
        assertThrows(ParseException::class.java) { DaysReply.parse(reply(row() + ",1")) }
        assertThrows(ParseException::class.java) { DaysReply.parse(reply(row().substringBeforeLast(","))) }
        assertThrows(ParseException::class.java) { DaysReply.parse(reply("2026-01-02,18.0")) }
    }

    @Test
    fun badValuesThrow() {
        assertThrows(ParseException::class.java) { DaysReply.parse(reply(row("2026-02-30"))) }
        assertThrows(ParseException::class.java) { DaysReply.parse(reply(row("1999-01-01"))) }
        assertThrows(ParseException::class.java) { DaysReply.parse(reply(row(onboardMold = "25"))) }
        assertThrows(ParseException::class.java) { DaysReply.parse(reply(row(onboardMold = "x"))) }
        assertThrows(ParseException::class.java) { DaysReply.parse(reply(row().replaceFirst("40.0", "140.0"))) }
        // A half pair rejects the row, like the firmware.
        assertThrows(ParseException::class.java) { DaysReply.parse(reply(row().replaceFirst(",24.5,", ",,"))) }
    }

    @Test
    fun badFramingThrows() {
        assertThrows(ParseException::class.java) { DaysReply.parse(listOf("BEGIN WEEK", DaysReply.HEADER)) }
        assertThrows(ParseException::class.java) { DaysReply.parse(listOf("BEGIN DAYS 0", "date")) }
        assertThrows(ParseException::class.java) { DaysReply.parse(listOf("BEGIN DAYS 2", DaysReply.HEADER, row())) }
        assertThrows(ParseException::class.java) { DaysReply.parse(listOf("BEGIN DAYS 0", DaysReply.HEADER, row())) }
        assertThrows(ParseException::class.java) { DaysReply.parse(listOf("BEGIN DAYS 05", DaysReply.HEADER)) }
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
    fun countAboveMaxAndDayLabel() {
        assertThrows(ParseException::class.java) { DaysReply.parse(listOf("BEGIN DAYS 32", DaysReply.HEADER)) }
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
}
