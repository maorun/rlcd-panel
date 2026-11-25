package com.rlcdpanel.watch

import com.rlcdpanel.watch.protocol.ReceptionRow
import org.junit.Assert.assertEquals
import org.junit.Test

class ReceptionTextTest {
    private fun row(slot: Int, name: String, mask: String) =
        ReceptionRow(slot, "A1:A1:A1:A1:A1:A1", name, mask.length, mask.count { it == '1' }, mask)

    @Test
    fun emptyNameFallsBackToSlotNumber() {
        assertEquals("Slot 3", ReceptionText.title(row(3, "", "10")))
        assertEquals("Cellar", ReceptionText.title(row(2, "Cellar", "10")))
    }

    @Test
    fun countsShowWarningOnlyBelowHalf() {
        assertEquals("1/4 ⚠", ReceptionText.counts(row(1, "", "0001")))
        assertEquals("2/4", ReceptionText.counts(row(1, "", "0011")))
    }

    @Test
    fun stripMapsPacketsAndGaps() {
        assertEquals("█·██·█", ReceptionText.strip(row(2, "", "101101")))
        assertEquals("", ReceptionText.strip(row(2, "", "")))
    }

    @Test
    fun stripKeepsOnlyNewest12Cells() {
        assertEquals("··█·██·█··█·", ReceptionText.strip(row(1, "", "110100101101001011010010")))
    }
}
