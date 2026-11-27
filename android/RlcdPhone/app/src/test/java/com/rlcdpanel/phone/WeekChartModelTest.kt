package com.rlcdpanel.phone

import com.rlcdpanel.phone.protocol.ConsoleReply
import com.rlcdpanel.phone.protocol.Fixtures
import com.rlcdpanel.phone.protocol.ReplyAssembler
import com.rlcdpanel.phone.protocol.WeekReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeekChartModelTest {
    private fun fixtureRows() = WeekReply.parse(
        (ReplyAssembler().append(Fixtures.file("week_reply_sample.txt").readBytes()) as ConsoleReply.Frame).lines
    )

    @Test
    fun moldGivesSevenValuesOldestFirst() {
        // Day 0 to 6 of sensor 0 in the fixture: 0, 7, 2, 3, 4, 0, 1.
        assertEquals(listOf<Int?>(1, 0, 4, 3, 2, 7, 0), WeekChartModel.mold(fixtureRows(), 0))
    }

    @Test
    fun moldGivesNullForAGapDayAndAnEmptyField() {
        val rows = fixtureRows()
        // Sensor 2 day 3 is a gap day: position 3 from the left.
        assertEquals(null, WeekChartModel.mold(rows, 2)[3])
        // Sensor 1 day 2 has an empty mold field: position 4.
        assertEquals(null, WeekChartModel.mold(rows, 1)[4])
        assertEquals(24, WeekChartModel.mold(rows, 5)[0])
    }

    @Test
    fun moldIsNullForAReplyWithTheSevenColumnHeader() {
        val rows = WeekReply.parse(
            listOf("BEGIN WEEK", WeekReply.LEGACY_HEADER) + (0..6).map { "0,$it,2026-11-1$it,15.0,19.5,40.0,55.0" }
        )
        assertEquals(List<Int?>(7) { null }, WeekChartModel.mold(rows, 0))
    }

    private fun moldHeaderRows() = WeekReply.parse(
        listOf("BEGIN WEEK", WeekReply.MOLD_HEADER) + (0..6).map { "0,$it,2026-11-1$it,15.0,19.5,40.0,55.0,$it" }
    )

    @Test
    fun dryGivesSevenValuesOldestFirst() {
        // Day 0 to 6 of sensor 0 in the fixture: 0, 2, 4, 6, 0, 2, 4.
        assertEquals(listOf<Int?>(4, 2, 0, 6, 4, 2, 0), WeekChartModel.dry(fixtureRows(), 0))
    }

    @Test
    fun dryGivesNullForAGapDayAndAMissingRow() {
        val rows = fixtureRows()
        // Sensor 2 day 3 is a gap day: position 3 from the left.
        assertEquals(null, WeekChartModel.dry(rows, 2)[3])
        // Sensor 1 day 2 has an empty dry field: position 4.
        assertEquals(null, WeekChartModel.dry(rows, 1)[4])
        // Sensor 9 has no row at all.
        assertEquals(List<Int?>(7) { null }, WeekChartModel.dry(rows, 9))
        // A missing day row of a sensor with other rows.
        val missing = rows.filterNot { it.sensor == 0 && it.day == 3 }
        assertEquals(null, WeekChartModel.dry(missing, 0)[3])
    }

    @Test
    fun dryIsNullForAReplyWithTheEightColumnHeader() {
        assertEquals(List<Int?>(7) { null }, WeekChartModel.dry(moldHeaderRows(), 0))
    }

    @Test
    fun dryLabelShowsTheMaximum() {
        assertEquals("0 bis 24, max. 6 h", WeekChartModel.dryLabel(WeekChartModel.dry(fixtureRows(), 0)))
        assertEquals("0 bis 24, max. 7 h", WeekChartModel.dryLabel(WeekChartModel.dry(fixtureRows(), 5)))
    }

    @Test
    fun aSensorWithoutADryValueDrawsNoBarRow() {
        assertNull(WeekChartModel.dryLabel(WeekChartModel.dry(moldHeaderRows(), 0)))
        assertNull(WeekChartModel.dryLabel(WeekChartModel.dry(fixtureRows(), 9)))
    }

    @Test
    fun moldLabelUsesTheSameRuleAsTheDryLabel() {
        assertEquals("0 bis 24, max. 7 h", WeekChartModel.moldLabel(WeekChartModel.mold(fixtureRows(), 0)))
        assertNull(WeekChartModel.moldLabel(WeekChartModel.mold(fixtureRows(), 9)))
    }

    private fun heatRows() = WeekReply.parse(
        (ReplyAssembler().append(Fixtures.file("week_heat_reply_sample.txt").readBytes()) as ConsoleReply.Frame).lines
    )

    @Test
    fun heatLabelSumsFixtureDays() {
        assertEquals("HEAT 20 h", WeekChartModel.heatLabel(heatRows(), 0))
        // Sensor 1 has an empty field on day 2.
        assertEquals("HEAT 16 h", WeekChartModel.heatLabel(heatRows(), 1))
    }

    @Test
    fun heatLabelIsNullForNineColumnFixture() {
        assertNull(WeekChartModel.heatLabel(fixtureRows(), 0))
        assertNull(WeekChartModel.heatLabel(emptyList(), 0))
    }
}
