package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class WeekCsvTest {
    private fun fixtureRows(): List<WeekRow> {
        val lines = Fixtures.file("week_reply_sample.txt").readLines().filter { !it.startsWith("CRC32") }
        return WeekReply.parse(lines)
    }

    private val config = PanelConfig.parseText("onboard_name=Office\nslot2_name=Keller, unten\n")

    @Test
    fun headerAddsName() {
        val lines = WeekCsv.format(fixtureRows(), config).lines()
        assertEquals("sensor,day,date,temp_min_c,temp_max_c,rh_min_pct,rh_max_pct,mold_h,name", lines[0])
    }

    @Test
    fun firstRowHasOneDecimalAndName() {
        val lines = WeekCsv.format(fixtureRows(), config).lines()
        assertEquals("0,0,2026-11-17,15.0,19.5,40.0,55.0,0,Office", lines[1])
    }

    @Test
    fun gapDayHasEmptyFields() {
        val lines = WeekCsv.format(fixtureRows(), config).lines()
        assertEquals("2,3,2026-11-14,,,,,,\"Keller, unten\"", lines.first { it.startsWith("2,3,") })
    }

    @Test
    fun oneLinePerRow() {
        val rows = fixtureRows()
        assertEquals(rows.size + 1, WeekCsv.format(rows, config).trimEnd('\n').lines().size)
    }

    @Test
    fun emptyListGivesOnlyHeader() {
        assertEquals(WeekCsv.HEADER + "\n", WeekCsv.format(emptyList(), config))
    }
}
