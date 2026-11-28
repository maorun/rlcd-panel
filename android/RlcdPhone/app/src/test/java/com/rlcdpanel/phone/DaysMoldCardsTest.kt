package com.rlcdpanel.phone

import com.rlcdpanel.phone.protocol.DaysReply
import com.rlcdpanel.phone.protocol.DaysRow
import com.rlcdpanel.phone.protocol.DaysSensor
import com.rlcdpanel.phone.protocol.Fixtures
import com.rlcdpanel.phone.protocol.MoldRoomSummary
import com.rlcdpanel.phone.protocol.MoldSummary
import com.rlcdpanel.phone.protocol.PanelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DaysMoldCardsTest {
    @Test
    fun roomWithoutMoldHoursShowsOneLine() {
        val room = MoldRoomSummary(0, "Wohnzimmer", 0, 0, null, null)
        assertEquals(listOf("Keine Schimmelstunden"), DaysMoldCards.lines(room))
    }

    @Test
    fun roomWithMoldHoursShowsTotalDaysAndWorstDay() {
        val room = MoldRoomSummary(0, "Keller", 13, 3, "2026-01-01", 5)
        assertEquals(
            listOf("Schimmelstunden: 13 h", "Tage mit Schimmel: 3 Tage", "Schlimmster Tag: 01-01 5 h"),
            DaysMoldCards.lines(room)
        )
    }

    @Test
    fun roomWithoutMoldValueHasNoCard() {
        val rows = DaysReply.parse(Fixtures.file("days_reply_sample.txt").readLines().filter { !it.startsWith("CRC32") })
        assertTrue(DaysMoldCards.cards(rows, PanelConfig()).isEmpty())
    }

    @Test
    fun roomWithMoldValueHasOneCard() {
        val moldColumn = 1 + 6 * DaysReply.SENSOR_COUNT + 5
        val lines = Fixtures.file("days_reply_sample.txt").readLines().filter { !it.startsWith("CRC32") }
            .mapIndexed { i, line ->
                if (i < 2) line else line.split(",").toMutableList().also { it[moldColumn] = "0" }.joinToString(",")
            }
        val cards = DaysMoldCards.cards(DaysReply.parse(lines), PanelConfig())
        assertEquals(1, cards.size)
        assertEquals(listOf("Keine Schimmelstunden"), DaysMoldCards.lines(cards[0]))
    }

    private fun sensorWith(dry: Int?) = DaysSensor(null, null, null, null, 0, dry)

    private fun dayRow(date: String, dry: Int?) =
        DaysRow(date, List(DaysReply.SENSOR_COUNT) { if (it == 0) sensorWith(dry) else sensorWith(null) })

    @Test
    fun dryLineSumsTheDryHours() {
        val rows = listOf(dayRow("2026-01-01", 3), dayRow("2026-01-02", null), dayRow("2026-01-03", 5))
        assertEquals("Trocken 8 h", DaysMoldCards.dryLine(rows, 0))
        assertEquals("Trocken 0 h", DaysMoldCards.dryLine(listOf(dayRow("2026-01-01", 0)), 0))
    }

    @Test
    fun dryLineIsNullWithoutDryValue() {
        assertNull(DaysMoldCards.dryLine(listOf(dayRow("2026-01-01", null)), 0))
        assertNull(DaysMoldCards.dryLine(listOf(dayRow("2026-01-01", 4)), 1))
        assertNull(DaysMoldCards.dryLine(emptyList(), 0))
    }

    @Test
    fun roomWithHeatValueAndNoMoldValueHasACardWithoutMoldLines() {
        val rows = listOf(
            DaysRow("2026-01-01", List(DaysReply.SENSOR_COUNT) { if (it == 0) DaysSensor(null, null, null, null, null, null, 4) else DaysSensor(null, null, null, null, null) })
        )
        val cards = DaysMoldCards.cards(rows, PanelConfig())
        assertEquals(1, cards.size)
        assertEquals(4, cards[0].heatHours)
        assertTrue(DaysMoldCards.lines(cards[0]).isEmpty())
        assertEquals("Hitzestunden: 4 h", DaysMoldCards.heatLine(cards[0]))
    }

    private fun roomWithHeat(heat: Int?) = MoldRoomSummary(0, "Keller", 0, 0, null, null, heat)

    @Test
    fun heatLineShowsTheSumOfTheHeatHours() {
        assertEquals("Hitzestunden: 5 h", DaysMoldCards.heatLine(roomWithHeat(5)))
        assertEquals("Hitzestunden: 0 h", DaysMoldCards.heatLine(roomWithHeat(0)))
    }

    @Test
    fun heatLineShowsDashesWithoutHeatValue() {
        assertEquals("Hitzestunden: --", DaysMoldCards.heatLine(roomWithHeat(null)))
    }

    @Test
    fun heatLineIsDashesForTheV6AndV7Fixtures() {
        for (name in listOf("days_reply_v6_sample.txt", "days_reply_v7_sample.txt")) {
            val lines = Fixtures.file(name).readLines().filter { !it.startsWith("CRC32") }
            val room = MoldSummary.build(DaysReply.parse(lines), PanelConfig()).single()
            assertEquals("Hitzestunden: --", DaysMoldCards.heatLine(room))
        }
    }
}
