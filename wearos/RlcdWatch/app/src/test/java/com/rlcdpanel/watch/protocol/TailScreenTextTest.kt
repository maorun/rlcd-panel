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

    private fun battery(dayOffset: Int, pct: Int, slot: Int = 1): TailRow {
        val start = PanelClock.parse("2026-09-01T12:00:00")!!
        val slots = MutableList(TailReply.SLOT_COUNT) { empty }
        slots[slot - 1] = TailSensor(20.0, 50.0, pct)
        val stamp = PanelClock.format(start + dayOffset * 86_400_000L)!!.replace('T', ' ')
        return TailRow(stamp, null, null, null, empty, slots)
    }

    @Test
    fun forecastLineShowsNameAndDateForEachSlotWithAForecast() {
        val rows = (0..9).map { battery(it, 80 - it) }
        val lines = TailScreenText.forecastLines(rows, listOf("Onboard", "Keller"), 10.0)
        assertEquals(listOf("Keller: change near 2026-11-10"), lines)
    }

    @Test
    fun forecastLineUsesTheSlotNumberWithoutAName() {
        val rows = (0..9).map { battery(it, 80 - it, slot = 3) }
        assertEquals(listOf("Slot 3: change near 2026-11-10"), TailScreenText.forecastLines(rows, emptyList(), 10.0))
    }

    @Test
    fun noForecastLinesForShortOrFlatRows() {
        assertEquals(emptyList<String>(), TailScreenText.forecastLines(emptyList(), emptyList(), 10.0))
        assertEquals(emptyList<String>(), TailScreenText.forecastLines((0..5).map { battery(it, 80 - it) }, emptyList(), 10.0))
    }

    @Test
    fun hintOnlyForRowsOfLessThanSevenDays() {
        assertEquals("Battery forecast: read more rows", TailScreenText.forecastHint(listOf(battery(0, 80), battery(3, 79))))
        assertEquals("Battery forecast: read more rows", TailScreenText.forecastHint(emptyList()))
        assertNull(TailScreenText.forecastHint(listOf(battery(0, 80), battery(7, 76))))
    }

    @Test
    fun forecastLinesKeepTheSlotOrder() {
        val rows = (0..9).map { d ->
            val base = battery(d, 80 - d, slot = 3)
            base.copy(slots = base.slots.toMutableList().also { it[0] = TailSensor(20.0, 50.0, 70 - d) })
        }
        val lines = TailScreenText.forecastLines(rows, listOf("Onboard", "Eins", "Zwei", "Drei"), 10.0)
        assertEquals(listOf("Eins: change near 2026-10-31", "Drei: change near 2026-11-10"), lines)
    }
}
