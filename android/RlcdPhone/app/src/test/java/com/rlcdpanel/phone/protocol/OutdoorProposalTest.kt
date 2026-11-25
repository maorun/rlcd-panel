package com.rlcdpanel.phone.protocol

import com.rlcdpanel.phone.finder.FoundSensor
import com.rlcdpanel.phone.finder.SensorAdvertisement
import com.rlcdpanel.phone.finder.SensorFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Same cases as `OutdoorProposalTests.swift` of the macOS app. */
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

    // MARK: Assistant

    private val newMac = "AA:BB:CC:DD:EE:09"

    private fun statusLines() = listOf(
        "SENSOR 0 Onboard temp=21.4 rh=45.0 bat=-- rssi=-- age=120 state=OK",
        "SENSOR 1 Bad temp=22.1 rh=48.5 bat=87 rssi=-61 age=14 state=OK mac=AA:BB:CC:DD:EE:01",
        "SENSOR 2 Kueche temp=20.0 rh=48.0 bat=90 rssi=-55 age=3 state=OK mac=$newMac",
        "POWER src=USB v=4.05 pct=88 alerts=00 muted=0"
    )

    private fun calibrating(): SetupAssistant {
        val cfg = PanelConfig(mapOf("slot1_mac" to "AA:BB:CC:DD:EE:01", "ble_console" to "2"))
        val a = SetupAssistant()
        a.scanned(listOf(FoundSensor(SensorAdvertisement(SensorFormat.WOIO, newMac, 20.0, 48, 90), -55, 0L)), cfg)
        a.choose(newMac, "Kueche", cfg)
        val status = StatusReply.parse(
            listOf("SENSOR 0 Onboard temp=21.4 rh=45.0 bat=-- rssi=-- age=120 state=OK",
                "SENSOR 1 Bad temp=22.1 rh=48.5 bat=87 rssi=-61 age=14 state=OK mac=AA:BB:CC:DD:EE:01",
                "SENSOR 2 K temp=-- rh=-- bat=-- rssi=-- age=-- state=EMPTY",
                "POWER src=USB v=4.05 pct=88 alerts=00 muted=0")
        )
        a.beginAssign(status, cfg)
        a.receive(ConsoleReply.Status("OK SET slot2_mac"))
        a.nextCommand()
        a.receive(ConsoleReply.Status("OK SET slot2_name"))
        a.nextCommand()
        a.receive(ConsoleReply.Frame(listOf("slot2_mac=$newMac")))
        a.checkReception()
        a.receive(ConsoleReply.Frame(statusLines()))
        return a
    }

    private val weekFrame = ConsoleReply.Frame(
        listOf("BEGIN WEEK", WeekReply.HEADER) + week.map {
            "${it.sensor},${it.day},,${it.temperatureMinC},${it.temperatureMaxC},,,,"
        }
    )

    @Test
    fun weekIsReadOnlyOnUserAction() {
        val a = calibrating()
        assertNull(a.nextCommand())
        assertNull(a.outdoorProposal(config()))
        assertEquals("WEEK", a.beginReadWeek())
        a.receive(weekFrame)
        assertEquals(2, a.outdoorProposal(config())?.slot)
    }

    @Test
    fun applyButtonSendsSetThenConfigAndStaysInCalibrate() {
        val a = calibrating()
        a.beginReadWeek()
        a.receive(weekFrame)
        assertEquals("SET outdoor_slot=2", a.beginApplyOutdoor(config()))
        a.receive(ConsoleReply.Status("OK SET outdoor_slot"))
        assertEquals("CONFIG", a.nextCommand())
        a.receive(ConsoleReply.Frame(listOf("outdoor_slot=2")))
        assertEquals(SetupAssistant.Step.CALIBRATE, a.step)
        assertNull(a.stop)
        assertEquals("SALT 2", a.beginCalibrate())
    }

    @Test
    fun applyWithoutProposalSendsNothing() {
        val a = calibrating()
        assertNull(a.beginApplyOutdoor(config()))
    }

    @Test
    fun readOnlyConsoleStopsBeforeTheSet() {
        val a = calibrating()
        a.beginReadWeek()
        a.receive(weekFrame)
        val readOnly = PanelConfig(mapOf("outdoor_slot" to "0", "ble_console" to "1"))
        assertNull(a.beginApplyOutdoor(readOnly))
        assertEquals(SetupAssistant.Stop.ReadOnly, a.stop)
        assertNull(a.nextCommand())
    }

    @Test
    fun noProposalOutsideCalibrateAndAfterRestart() {
        val fresh = SetupAssistant()
        assertNull(fresh.outdoorProposal(config()))
        assertNull(fresh.beginReadWeek())
        val a = calibrating()
        a.beginReadWeek()
        a.receive(weekFrame)
        a.restart()
        assertEquals(emptyList<WeekRow>(), a.week)
        assertNull(a.outdoorProposal(config()))
    }

    @Test
    fun badWeekReplyStopsWithReplyText() {
        val a = calibrating()
        a.beginReadWeek()
        a.receive(ConsoleReply.Frame(listOf("BEGIN WEEK", "bad,header")))
        assertNotNull(a.stop)
    }

    @Test
    fun errorOnSetStopsTheAssistant() {
        val a = calibrating()
        a.beginReadWeek()
        a.receive(weekFrame)
        a.beginApplyOutdoor(config())
        a.receive(ConsoleReply.Status("ERR BAD_VALUE"))
        assertNotNull(a.stop)
        assertNull(a.nextCommand())
    }
}
