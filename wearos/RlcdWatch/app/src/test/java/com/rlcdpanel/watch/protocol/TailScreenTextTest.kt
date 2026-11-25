package com.rlcdpanel.watch.protocol

import com.rlcdpanel.watch.TailRoomLine
import com.rlcdpanel.watch.TailScreenText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TailScreenTextTest {
    private val empty = TailSensor(null, null, null)

    private fun row(time: String, onboard: TailSensor, slot1: TailSensor = empty) =
        TailRow(time, null, null, null, onboard, listOf(slot1) + List(TailReply.SLOT_COUNT - 1) { empty })

    @Test
    fun commandReadsSixRows() {
        assertEquals("TAIL 6", TailScreenText.COMMAND)
    }

    @Test
    fun roomLineShowsTheNewestRowWithAValue() {
        val rows = listOf(
            row("2026-03-01 03:00:00", TailSensor(20.0, 40.0, null), TailSensor(-3.5, 80.0, 90)),
            row("2026-03-01 04:00:00", TailSensor(21.5, 45.0, null))
        )
        val lines = TailScreenText.roomLines(rows, listOf("Wohnzimmer", "Keller"))
        assertEquals(
            listOf(
                TailRoomLine("Wohnzimmer", "03-01 04:00", "21.5 °C  45.0 %"),
                TailRoomLine("Keller", "03-01 03:00", "-3.5 °C  80.0 %")
            ),
            lines
        )
    }

    @Test
    fun roomWithoutValuesGivesNoLineAndMissingNameShowsSlot() {
        val rows = listOf(row("2026-03-01 04:00:00", TailSensor(21.5, null, null)))
        val lines = TailScreenText.roomLines(rows, emptyList())
        assertEquals(listOf(TailRoomLine("Onboard", "03-01 04:00", "21.5 °C  -- %")), lines)
        assertEquals(emptyList<TailRoomLine>(), TailScreenText.roomLines(emptyList(), listOf("A")))
    }

    @Test
    fun coverageTextNamesTheRowCount() {
        assertEquals("Letzte 6 Zeilen", TailScreenText.coverageText(6))
    }

    @Test
    fun errorTexts() {
        assertEquals("Keine MicroSD-Karte im Panel", TailScreenText.errorText("ERR NO_CARD"))
        assertNull(TailScreenText.errorText("ERR OTHER"))
    }
}
