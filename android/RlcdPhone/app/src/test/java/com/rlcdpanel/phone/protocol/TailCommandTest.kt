package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TailCommandTest {
    @Test
    fun textGivesCommandForValidCounts() {
        assertEquals("TAIL 6", TailCommand.text(6))
        assertEquals("TAIL 1", TailCommand.text(1))
        assertEquals("TAIL 72", TailCommand.text(72))
    }

    @Test
    fun textGivesNullForOtherCounts() {
        assertNull(TailCommand.text(0))
        assertNull(TailCommand.text(73))
        assertNull(TailCommand.text(-1))
    }

    @Test
    fun eachChoiceGivesCommand() {
        assertEquals(listOf(6, 24, 72), TailCommand.CHOICES)
        TailCommand.CHOICES.forEach { assertEquals("TAIL $it", TailCommand.text(it)) }
    }

    @Test
    fun errorLinesGiveClearText() {
        assertEquals("Keine MicroSD-Karte im Panel", TailCommand.errorText("ERR NO_CARD"))
        assertEquals("Keine Logdatei oder keine gültige Uhrzeit im Panel", TailCommand.errorText("ERR NO_FILE"))
        assertEquals("Die MicroSD-Karte lässt sich nicht lesen", TailCommand.errorText("ERR READ_FAILED"))
        assertNull(TailCommand.errorText("ERR BAD_ARG"))
    }

    @Test
    fun valueTextShowsEmptyFieldsAsDashes() {
        assertEquals("21.5 °C  45.0 %", TailCommand.valueText(TailSensor(21.5, 45.0, null)))
        assertEquals("-- °C  80.0 %", TailCommand.valueText(TailSensor(null, 80.0, 90)))
        assertEquals("-3.5 °C  -- %", TailCommand.valueText(TailSensor(-3.5, null, null)))
        assertEquals("-- °C  -- %", TailCommand.valueText(TailSensor(null, null, null)))
        assertEquals("0.0 °C  0.0 %", TailCommand.valueText(TailSensor(-0.04, 0.0, null)))
    }

    @Test
    fun timeTextCutsYearAndSeconds() {
        assertEquals("03-01 04:00", TailCommand.timeText("2026-03-01 04:00:00"))
        assertEquals("bad", TailCommand.timeText("bad"))
    }

    private fun row(time: String) = TailRow(
        time, null, null, null,
        TailSensor(21.5, 45.0, null),
        List(10) { TailSensor(it + 1.0, null, null) }
    )

    @Test
    fun newestFirstReversesTheRows() {
        val rows = listOf(row("2026-03-01 03:00:00"), row("2026-03-01 04:00:00"))
        assertEquals(listOf("2026-03-01 04:00:00", "2026-03-01 03:00:00"), TailCommand.newestFirst(rows).map { it.timestamp })
    }

    @Test
    fun rowLinesShowTimeOnboardAndNamedSlotsInOrder() {
        val lines = TailCommand.rowLines(row("2026-03-01 04:00:00"), "Büro", listOf(3 to "Keller", 1 to "Bad"))
        assertEquals(
            listOf("03-01 04:00", "Büro: 21.5 °C  45.0 %", "Keller: 3.0 °C  -- %", "Bad: 1.0 °C  -- %"),
            lines
        )
    }

    @Test
    fun rowLinesGiveNoLineForSlotOutsideOneToTen() {
        val lines = TailCommand.rowLines(row("2026-03-01 04:00:00"), "Büro", listOf(0 to "A", 11 to "B"))
        assertEquals(listOf("03-01 04:00", "Büro: 21.5 °C  45.0 %"), lines)
    }
}
