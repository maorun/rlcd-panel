package com.rlcdpanel.phone

import com.rlcdpanel.phone.protocol.DaysReply
import com.rlcdpanel.phone.protocol.Fixtures
import com.rlcdpanel.phone.protocol.MoldRoomSummary
import com.rlcdpanel.phone.protocol.PanelConfig
import org.junit.Assert.assertEquals
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
}
