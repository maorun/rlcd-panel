package com.rlcdpanel.watch.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Same cases as `OutdoorProposalTests.swift` of the macOS app and the phone test. */
class OutdoorProposalTest {
    private fun rows(sensor: Int, days: Int, min: Double, max: Double) =
        (0 until days).map { WeekRow(sensor, it, "", min, max, null, null) }

    /** Onboard 19 to 22 C, slot 1 18 to 21 C, slot 2 outdoors with -2 to 12 C. */
    private val week = rows(0, 5, 19.0, 22.0) + rows(1, 5, 18.0, 21.0) + rows(2, 5, -2.0, 12.0)

    private fun config(outdoor: String = "0") = PanelConfig(mapOf("outdoor_slot" to outdoor, "ble_console" to "2"))

    @Test
    fun clearProposal() {
        val p = OutdoorProposal.propose(week, config())
        assertNotNull(p)
        p!!
        assertEquals(2, p.slot)
        assertEquals(14.0, p.rangeC, 1e-9)
        assertEquals(3.0, p.nextRangeC, 1e-9)
        assertEquals(-2.0, p.minC, 1e-9)
        assertEquals(19.0, p.onboardMinC, 1e-9)
        assertEquals("Outdoor slot 2 (Slot 2): range 14.0 C, next 3.0 C, minimum -2.0 C, onboard minimum 19.0 C", p.text)
        assertEquals("SET outdoor_slot=2", p.setLine)
    }

    @Test
    fun usesConfiguredSlotName() {
        val c = PanelConfig(mapOf("outdoor_slot" to "0", "slot2_name" to "GARTEN"))
        assertEquals("GARTEN", OutdoorProposal.propose(week, c)?.name)
    }

    @Test
    fun nullForSecondRangeAboveFactor() {
        val w = rows(0, 5, 19.0, 22.0) + rows(1, 5, -2.0, 12.0) + rows(2, 5, -2.0, 12.0)
        assertNull(OutdoorProposal.propose(w, config()))
        val below = rows(0, 5, 19.0, 22.0) + rows(1, 5, 10.0, 14.5) + rows(2, 5, -2.0, 6.0)
        assertNull(OutdoorProposal.propose(below, config()))
    }

    @Test
    fun rangeExactlyTwiceTheNextQualifies() {
        val w = rows(0, 5, 19.0, 22.0) + rows(1, 5, 10.0, 14.0) + rows(2, 5, -2.0, 6.0)
        assertEquals(2, OutdoorProposal.propose(w, config())?.slot)
    }

    @Test
    fun nullForWarmSlot() {
        val w = rows(0, 5, 15.0, 22.0) + rows(1, 5, 18.0, 21.0) + rows(2, 5, 16.0, 30.0)
        assertNull(OutdoorProposal.propose(w, config()))
    }

    @Test
    fun nullForSlotWithTwoDays() {
        val w = rows(0, 5, 19.0, 22.0) + rows(2, 2, -2.0, 12.0)
        assertNull(OutdoorProposal.propose(w, config()))
    }

    @Test
    fun nullWithoutOnboardRange() {
        assertNull(OutdoorProposal.propose(rows(2, 5, -2.0, 12.0), config()))
    }

    @Test
    fun nullWhenOutdoorSlotHoldsTheSlot() {
        assertNull(OutdoorProposal.propose(week, config("2")))
    }

    @Test
    fun nullWithoutConfigReply() {
        assertNull(OutdoorProposal.propose(week, null))
    }

}
