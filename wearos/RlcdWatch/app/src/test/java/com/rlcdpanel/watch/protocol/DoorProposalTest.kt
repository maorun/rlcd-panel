package com.rlcdpanel.watch.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Same cases as `DoorProposalTests.swift` of the macOS app and the phone test. */
class DoorProposalTest {
    private fun rows(sensor: Int, days: Int, max: Double) =
        (0 until days).map { WeekRow(sensor, it, "", null, null, max - 10, max) }

    /** Slot 1 is the door room; the slots 2, 3 and 4 have the mean maxima 70, 52 and 60 %RH. */
    private val week = rows(1, 5, 65.0) + rows(2, 5, 70.0) + rows(3, 5, 52.0) + rows(4, 5, 60.0)

    private fun config(outdoor: String = "0", door: String = "0", extra: Map<String, String> = emptyMap()) =
        PanelConfig(mapOf("outdoor_slot" to outdoor, "door_ref_slot" to door, "ble_console" to "2") + extra)

    @Test
    fun proposesTheDriestMaximum() {
        val p = DoorProposal.propose(week, config())
        assertNotNull(p)
        p!!
        assertEquals(3, p.slot)
        assertEquals(52.0, p.meanMaxPct, 1e-9)
        assertEquals(60.0, p.nextMaxPct, 1e-9)
        assertEquals("Door reference slot 3 (Slot 3): 52.0 %RH, next 60.0 %RH", p.text)
        assertEquals("SET door_ref_slot=3", p.setLine)
    }

    @Test
    fun nullForADifferenceOfTwoPercent() {
        val w = rows(1, 5, 65.0) + rows(2, 5, 54.0) + rows(3, 5, 52.0)
        assertNull(DoorProposal.propose(w, config()))
    }

    @Test
    fun differenceOfExactlyThreePercentQualifies() {
        val w = rows(1, 5, 65.0) + rows(2, 5, 55.0) + rows(3, 5, 52.0)
        assertEquals(3, DoorProposal.propose(w, config())?.slot)
    }

    @Test
    fun outdoorSlotIsNoCandidate() {
        val p = DoorProposal.propose(week, config(outdoor = "3"))
        assertEquals(4, p?.slot)
        assertEquals(70.0, p!!.nextMaxPct, 1e-9)
    }

    @Test
    fun nullWithTwoDaysOfData() {
        val w = rows(1, 5, 65.0) + rows(2, 5, 70.0) + rows(3, 2, 40.0)
        assertNull(DoorProposal.propose(w, config()))
    }

    @Test
    fun nullWithoutReadingOfSlotOne() {
        assertNull(DoorProposal.propose(rows(2, 5, 70.0) + rows(3, 5, 52.0), config()))
        assertNull(DoorProposal.propose(rows(1, 2, 65.0) + rows(2, 5, 70.0) + rows(3, 5, 52.0), config()))
    }

    @Test
    fun nullForASingleCandidate() {
        assertNull(DoorProposal.propose(rows(1, 5, 65.0) + rows(2, 5, 70.0), config()))
    }

    @Test
    fun gapDaysWithoutHumidityPairDoNotCount() {
        val gap = rows(3, 5, 52.0).mapIndexed { i, r -> if (i < 3) r else WeekRow(3, r.day, "", null, null, null, null) }
        assertEquals(3, DoorProposal.propose(rows(1, 5, 65.0) + rows(2, 5, 70.0) + gap, config())?.slot)
        assertNull(DoorProposal.propose(rows(1, 5, 65.0) + rows(2, 5, 70.0) + rows(3, 2, 52.0), config()))
    }

    @Test
    fun nullWhenDoorRefSlotHoldsTheSlot() {
        assertNull(DoorProposal.propose(week, config(door = "3")))
    }

    @Test
    fun nullWithoutConfigReply() {
        assertNull(DoorProposal.propose(week, null))
    }

    @Test
    fun nullForACandidateWetterThanSlotOne() {
        assertNull(DoorProposal.propose(rows(1, 5, 50.0) + rows(2, 5, 70.0) + rows(3, 5, 60.0), config()))
    }

    @Test
    fun nullForATie() {
        assertNull(DoorProposal.propose(rows(1, 5, 65.0) + rows(2, 5, 52.0) + rows(3, 5, 52.0), config()))
    }

    @Test
    fun paddedActiveValueCountsAsActive() {
        assertNull(DoorProposal.propose(week, config(door = " 3 ")))
    }

    @Test
    fun usesConfiguredSlotName() {
        assertEquals("KELLER", DoorProposal.propose(week, config(extra = mapOf("slot3_name" to "KELLER")))?.name)
    }

    @Test
    fun weekFixtureGivesNoProposalForCloseSlots() {
        val reply = ReplyAssembler().append(Fixtures.file("week_reply_sample.txt").readBytes())
        val rows = WeekReply.parse((reply as ConsoleReply.Frame).lines)
        // The sample slots differ by 2 %RH and the ranges are equal, so neither proposal qualifies.
        assertNull(DoorProposal.propose(rows, config()))
        assertNull(OutdoorProposal.propose(rows, config()))
    }
}
