package com.rlcdpanel.phone.protocol

import com.rlcdpanel.phone.finder.FoundSensor
import com.rlcdpanel.phone.finder.SensorAdvertisement
import com.rlcdpanel.phone.finder.SensorFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Same cases as `DoorProposalTests.swift` of the macOS app. */
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

    // MARK: Assistant

    private val newMac = "AA:BB:CC:DD:EE:09"

    private fun calibrating(): SetupAssistant {
        val cfg = PanelConfig(mapOf("slot1_mac" to "AA:BB:CC:DD:EE:01", "ble_console" to "2"))
        val a = SetupAssistant()
        a.scanned(listOf(FoundSensor(SensorAdvertisement(SensorFormat.WOIO, newMac, 20.0, 48, 90), -55, 0L)), cfg)
        a.choose(newMac, "Kueche", cfg)
        a.beginAssign(
            StatusReply.parse(
                listOf("SENSOR 0 Onboard temp=21.4 rh=45.0 bat=-- rssi=-- age=120 state=OK",
                    "SENSOR 1 Bad temp=22.1 rh=48.5 bat=87 rssi=-61 age=14 state=OK mac=AA:BB:CC:DD:EE:01",
                    "SENSOR 2 K temp=-- rh=-- bat=-- rssi=-- age=-- state=EMPTY",
                    "POWER src=USB v=4.05 pct=88 alerts=00 muted=0")
            ), cfg
        )
        a.receive(ConsoleReply.Status("OK SET slot2_mac"))
        a.nextCommand()
        a.receive(ConsoleReply.Status("OK SET slot2_name"))
        a.nextCommand()
        a.receive(ConsoleReply.Frame(listOf("slot2_mac=$newMac")))
        a.checkReception()
        a.receive(
            ConsoleReply.Frame(
                listOf("SENSOR 0 Onboard temp=21.4 rh=45.0 bat=-- rssi=-- age=120 state=OK",
                    "SENSOR 1 Bad temp=22.1 rh=48.5 bat=87 rssi=-61 age=14 state=OK mac=AA:BB:CC:DD:EE:01",
                    "SENSOR 2 Kueche temp=20.0 rh=48.0 bat=90 rssi=-55 age=3 state=OK mac=$newMac",
                    "POWER src=USB v=4.05 pct=88 alerts=00 muted=0")
            )
        )
        return a
    }

    private val weekFrame = ConsoleReply.Frame(
        listOf("BEGIN WEEK", WeekReply.HEADER) + week.map { "${it.sensor},${it.day},,,,${it.humidityMinPct},${it.humidityMaxPct},," }
    )

    @Test
    fun weekIsReadOnlyOnUserAction() {
        val a = calibrating()
        assertNull(a.doorProposal(config()))
        assertEquals("WEEK", a.beginReadWeek())
        a.receive(weekFrame)
        assertEquals(3, a.doorProposal(config())?.slot)
    }

    @Test
    fun applyButtonSendsSetThenConfigAndStaysInCalibrate() {
        val a = calibrating()
        a.beginReadWeek()
        a.receive(weekFrame)
        assertEquals("SET door_ref_slot=3", a.beginApplyDoor(config()))
        a.receive(ConsoleReply.Status("OK SET door_ref_slot"))
        assertEquals("CONFIG", a.nextCommand())
        a.receive(ConsoleReply.Frame(listOf("door_ref_slot=3")))
        assertEquals(SetupAssistant.Step.CALIBRATE, a.step)
        assertNull(a.stop)
        assertEquals("SALT 2", a.beginCalibrate())
    }

    @Test
    fun applyWithoutProposalSendsNothing() {
        assertNull(calibrating().beginApplyDoor(config()))
    }

    @Test
    fun readOnlyConsoleStopsBeforeTheSet() {
        val a = calibrating()
        a.beginReadWeek()
        a.receive(weekFrame)
        assertNull(a.beginApplyDoor(config(extra = mapOf("ble_console" to "1"))))
        assertEquals(SetupAssistant.Stop.ReadOnly, a.stop)
    }

    @Test
    fun noProposalOutsideCalibrateAndAfterRestart() {
        assertNull(SetupAssistant().doorProposal(config()))
        val a = calibrating()
        a.beginReadWeek()
        a.receive(weekFrame)
        a.restart()
        assertNull(a.doorProposal(config()))
    }

    @Test
    fun errorOnSetStopsTheAssistant() {
        val a = calibrating()
        a.beginReadWeek()
        a.receive(weekFrame)
        a.beginApplyDoor(config())
        a.receive(ConsoleReply.Status("ERR BAD_VALUE"))
        assertNotNull(a.stop)
        assertNull(a.nextCommand())
    }
}
