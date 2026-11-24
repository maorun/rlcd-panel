package com.rlcdpanel.phone.protocol

import com.rlcdpanel.phone.finder.FoundSensor
import com.rlcdpanel.phone.finder.SensorAdvertisement
import com.rlcdpanel.phone.finder.SensorFormat
import com.rlcdpanel.phone.protocol.SetupAssistant.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same step order and cases as `SetupAssistantTests.swift` of the macOS app. */
class SetupAssistantTest {
    private val newMac = "AA:BB:CC:DD:EE:09"

    private fun row(mac: String, rssi: Int) =
        FoundSensor(SensorAdvertisement(SensorFormat.WOIO, mac, 20.0, 48, 90), rssi, 0L)

    private val rows = listOf(
        row("AA:BB:CC:DD:EE:01", -40), // slot 1 holds it as fixed MAC
        row("AA:BB:CC:DD:EE:08", -70),
        row(newMac, -55)
    )

    private fun config(canWrite: Boolean = true, extra: Map<String, String> = emptyMap()) =
        PanelConfig(
            mapOf("slot1_mac" to "AA:BB:CC:DD:EE:01", "ble_console" to if (canWrite) "2" else "1") + extra
        )

    private fun sensorLine(n: Int, name: String, state: String, mac: String?) =
        "SENSOR $n $name temp=20.0 rh=48.0 bat=90 rssi=-55 age=3 state=$state" + (mac?.let { " mac=$it" } ?: "")

    private fun statusLines(slot2: String): List<String> = listOf(
        "SENSOR 0 Onboard temp=21.4 rh=45.0 bat=-- rssi=-- age=120 state=OK",
        "SENSOR 1 Bad temp=22.1 rh=48.5 bat=87 rssi=-61 age=14 state=OK mac=AA:BB:CC:DD:EE:01",
        slot2,
        "POWER src=USB v=4.05 pct=88 alerts=00 muted=0"
    )

    private fun emptyStatus() = StatusReply.parse(
        statusLines("SENSOR 2 Kueche temp=-- rh=-- bat=-- rssi=-- age=-- state=EMPTY")
    )

    private fun okFrame(mac: String = newMac) =
        ConsoleReply.Frame(statusLines(sensorLine(2, "Kueche", "OK", mac)))

    private val saltFrame = ConsoleReply.Frame(
        listOf(
            "SALT slot2 hours=12 rh_mean=73.0 rh_span=0.4 temp_mean=21.0 reference=75.4",
            "slot2_rh_offset_pct=2.4"
        )
    )

    private fun chosen(name: String = "Kueche"): SetupAssistant {
        val a = SetupAssistant()
        assertTrue(a.scanned(rows, config()))
        assertTrue(a.choose(newMac, name, config()))
        return a
    }

    /** Runs the assistant up to the step VERIFY. */
    private fun assigned(): SetupAssistant {
        val a = chosen()
        a.beginAssign(emptyStatus(), config())
        a.receive(ConsoleReply.Status("OK SET slot2_mac"))
        a.nextCommand()
        a.receive(ConsoleReply.Status("OK SET slot2_name"))
        a.nextCommand()
        a.receive(ConsoleReply.Frame(listOf("slot2_mac=$newMac")))
        return a
    }

    private fun calibrating(): SetupAssistant {
        val a = assigned()
        a.checkReception()
        a.receive(okFrame())
        return a
    }

    @Test
    fun runsAllStepsInOrder() {
        val a = SetupAssistant()
        assertEquals(Step.SCAN, a.step)
        assertTrue(a.scanned(rows, config()))
        assertEquals(Step.CHOOSE, a.step)
        assertTrue(a.choose(newMac, "Kueche", config()))
        assertEquals(Step.ASSIGN, a.step)

        assertEquals("SET slot2_mac=$newMac", a.beginAssign(emptyStatus(), config()))
        assertEquals(2, a.slot)
        a.receive(ConsoleReply.Status("OK SET slot2_mac"))
        assertEquals("SET slot2_name=KUECHE", a.nextCommand())
        a.receive(ConsoleReply.Status("OK SET slot2_name"))
        assertEquals("CONFIG", a.nextCommand())
        a.receive(ConsoleReply.Frame(listOf("slot2_mac=$newMac")))
        assertEquals(Step.VERIFY, a.step)
        assertNull(a.nextCommand())

        assertEquals("STATUS", a.checkReception())
        a.receive(okFrame())
        assertEquals(Step.CALIBRATE, a.step)

        assertEquals("SALT 2", a.beginCalibrate())
        a.receive(saltFrame)
        assertEquals(CalibrationProposal("slot2_rh_offset_pct", "2.4"), a.proposal)
        assertEquals("SET slot2_rh_offset_pct=2.4", a.beginApply())
        a.receive(ConsoleReply.Status("OK SET slot2_rh_offset_pct"))
        assertEquals("CONFIG", a.nextCommand())
        a.receive(ConsoleReply.Frame(listOf("slot2_rh_offset_pct=2.4")))
        assertEquals(Step.DONE, a.step)
        assertNull(a.stop)
    }

    @Test
    fun chooseListHoldsOnlyRowsWithoutSlot() {
        val a = SetupAssistant()
        a.scanned(rows, config())
        assertEquals(listOf(newMac, "AA:BB:CC:DD:EE:08"), a.candidates(config()).map { it.advertisement.mac })
        assertFalse(a.choose("AA:BB:CC:DD:EE:01", "", config()))
        assertEquals(Step.CHOOSE, a.step)
    }

    @Test
    fun emptyScanGivesNoticeAndStaysInChoose() {
        val a = SetupAssistant()
        assertTrue(a.scanned(emptyList(), config()))
        assertEquals(Step.CHOOSE, a.step)
        assertNotNull(a.notice)
        assertFalse(a.isStopped)
        assertTrue(a.scanned(rows, config())) // a new scan is allowed
    }

    @Test
    fun badNameKeepsStepChoose() {
        val a = SetupAssistant()
        a.scanned(rows, config())
        assertFalse(a.choose(newMac, "Name with space", config()))
        assertEquals(Step.CHOOSE, a.step)
        assertNotNull(a.notice)
    }

    @Test
    fun assignPicksFirstSlotWithoutFixedMacAndReading() {
        val a = chosen()
        val busy = StatusReply.parse(
            statusLines(sensorLine(2, "Kueche", "OK", "AA:BB:CC:DD:EE:02"))
        )
        assertEquals("SET slot3_mac=$newMac", a.beginAssign(busy, config()))
        assertEquals(3, a.slot)
    }

    @Test
    fun disabledSlotIsNotFree() {
        assertEquals(3, SetupAssistant.freeSlot(emptyStatus(), config(extra = mapOf("slot2_enabled" to "0"))))
    }

    @Test
    fun noFreeSlotGivesNoCommand() {
        val a = chosen()
        val full = (1..10).associate { "slot${it}_mac" to "AA:BB:CC:00:00:%02X".format(it) } + ("ble_console" to "2")
        assertNull(a.beginAssign(emptyStatus(), PanelConfig(full)))
        assertEquals("Kein freier Slot", a.notice)
        assertFalse(a.isStopped)
    }

    @Test
    fun duplicateMacGivesNoCommand() {
        val a = chosen()
        assertNull(a.beginAssign(emptyStatus(), config(extra = mapOf("slot3_mac" to newMac))))
        assertTrue(a.notice!!.isNotEmpty())
        assertFalse(a.isStopped)
    }

    @Test
    fun assignNeedsStatusAndConfig() {
        val a = chosen()
        assertNull(a.beginAssign(null, config()))
        assertNull(a.beginAssign(emptyStatus(), null))
        assertEquals(Step.ASSIGN, a.step)
    }

    @Test
    fun readOnlyConsoleStopsBeforeFirstCommand() {
        val a = chosen()
        assertNull(a.beginAssign(emptyStatus(), config(canWrite = false)))
        assertEquals(SetupAssistant.Stop.ReadOnly, a.stop)
        assertEquals("Set ble_console=2 to apply", a.stop?.text)
    }

    @Test
    fun errReadOnlyReplyStopsWithClearText() {
        val a = chosen()
        a.beginAssign(emptyStatus(), config())
        a.receive(ConsoleReply.Status("ERR READ_ONLY"))
        assertEquals(SetupAssistant.Stop.ReadOnly, a.stop)
        assertEquals("Set ble_console=2 to apply", a.stop?.text)
        assertNull(a.nextCommand()) // neither the name SET nor CONFIG
        assertEquals(Step.ASSIGN, a.step)
    }

    @Test
    fun errFixedMacStopsBatchBeforeConfig() {
        val a = chosen()
        a.beginAssign(emptyStatus(), config())
        a.receive(ConsoleReply.Status("ERR FIXED_MAC"))
        assertEquals(SetupAssistant.Stop.FixedMac, a.stop)
        assertEquals(SetupAssistant.FIXED_MAC_TEXT, a.stop?.text)
        assertNull(a.nextCommand())
        assertNull(a.checkReception())
        assertEquals(Step.ASSIGN, a.step)
    }

    @Test
    fun otherErrOnNameSetStopsBeforeConfig() {
        val a = chosen()
        a.beginAssign(emptyStatus(), config())
        a.receive(ConsoleReply.Status("OK SET slot2_mac"))
        assertEquals("SET slot2_name=KUECHE", a.nextCommand())
        a.receive(ConsoleReply.Status("ERR BAD_VALUE"))
        assertEquals("ERR BAD_VALUE", a.stop?.text)
        assertNull(a.nextCommand())
    }

    @Test
    fun lostLinkEndsAssistantWithText() {
        val a = assigned()
        a.checkReception()
        a.linkLost("GATT getrennt")
        assertEquals("Verbindung verloren: GATT getrennt", a.stop?.text)
        assertNull(a.pending)
        assertNull(a.checkReception())
        assertEquals(Step.VERIFY, a.step)
    }

    @Test
    fun timeoutReplyCountsAsLostLink() {
        val a = assigned()
        a.checkReception()
        a.receive(ConsoleReply.Corrupt(ReplyError.Timeout))
        assertTrue(a.stop is SetupAssistant.Stop.LinkLost)
    }

    @Test
    fun commandNotSentEndsAssistant() {
        val a = chosen()
        a.beginAssign(emptyStatus(), config())
        a.commandNotSent()
        assertEquals("Verbindung verloren: Befehl nicht gesendet", a.stop?.text)
        assertNull(a.nextCommand())
    }

    @Test
    fun otherMacKeepsStepVerifyAndCountsTries() {
        val a = assigned()
        assertEquals("STATUS", a.checkReception())
        a.receive(okFrame("AA:BB:CC:DD:EE:77"))
        assertEquals(Step.VERIFY, a.step)
        assertEquals(1, a.verifyTries)
        assertNotNull(a.notice)
        assertFalse(a.isStopped)
        assertEquals("STATUS", a.checkReception()) // each click sends one STATUS
        a.receive(ConsoleReply.Frame(statusLines(sensorLine(2, "Kueche", "STALE", newMac))))
        assertEquals(Step.VERIFY, a.step)
        assertEquals(2, a.verifyTries)
    }

    @Test
    fun sensorAbsentAfterThreeTriesStops() {
        val a = assigned()
        repeat(3) {
            assertFalse(a.isStopped)
            assertEquals("STATUS", a.checkReception())
            a.receive(ConsoleReply.Frame(statusLines("SENSOR 2 Kueche temp=-- rh=-- bat=-- rssi=-- age=-- state=EMPTY")))
        }
        assertEquals(SetupAssistant.Stop.Absent(2, newMac), a.stop)
        assertTrue(a.stop!!.text.contains("3 Prüfungen"))
        assertNull(a.checkReception())
        assertEquals(Step.VERIFY, a.step)
    }

    @Test
    fun goodCheckAfterTwoMissesPasses() {
        val a = assigned()
        repeat(2) {
            a.checkReception()
            a.receive(ConsoleReply.Frame(statusLines("SENSOR 2 Kueche temp=-- rh=-- bat=-- rssi=-- age=-- state=EMPTY")))
        }
        a.checkReception()
        a.receive(okFrame())
        assertEquals(Step.CALIBRATE, a.step)
        assertNull(a.stop)
    }

    @Test
    fun restartAfterStopBeginsAtScan() {
        val a = chosen()
        a.beginAssign(emptyStatus(), config())
        a.receive(ConsoleReply.Status("ERR FIXED_MAC"))
        assertTrue(a.isStopped)
        a.restart()
        assertNull(a.stop)
        assertEquals(Step.SCAN, a.step)
        assertNull(a.slot)
        assertNull(a.chosenMac)
        assertEquals(0, a.verifyTries)
        assertTrue(a.scanned(rows, config()))
        assertTrue(a.choose(newMac, "", config()))
        assertEquals("SET slot2_mac=$newMac", a.beginAssign(emptyStatus(), config()))
    }

    @Test
    fun restartAfterDoneBeginsAtScan() {
        val a = calibrating()
        a.finish()
        assertEquals(Step.DONE, a.step)
        a.restart()
        assertEquals(Step.SCAN, a.step)
    }

    @Test
    fun saltStatusErrorsStopWithText() {
        val a = calibrating()
        a.beginCalibrate()
        a.receive(ConsoleReply.Status("ERR BAD_TEMP"))
        assertEquals("ERR BAD_TEMP", a.stop?.text)
    }

    @Test
    fun skippedSaltGivesNoProposalAndNoApply() {
        val a = calibrating()
        a.beginCalibrate()
        a.receive(ConsoleReply.Frame(listOf("SKIP slot2 hours=3")))
        assertNull(a.proposal)
        assertNotNull(a.notice)
        assertNull(a.beginApply())
        assertFalse(a.isStopped)
    }

    @Test
    fun errOnApplyStopsBeforeConfig() {
        val a = calibrating()
        a.beginCalibrate()
        a.receive(saltFrame)
        assertNotNull(a.beginApply())
        a.receive(ConsoleReply.Status("ERR READ_ONLY"))
        assertEquals(SetupAssistant.Stop.ReadOnly, a.stop)
        assertNull(a.nextCommand())
    }

    @Test
    fun finishEndsAfterCalibrateStepOnly() {
        val a = assigned()
        a.finish() // still in VERIFY
        assertEquals(Step.VERIFY, a.step)
        a.checkReception()
        a.receive(okFrame())
        a.finish()
        assertEquals(Step.DONE, a.step)
    }

    @Test
    fun commandWaitsForReplyBeforeNextAction() {
        val a = assigned()
        assertEquals("STATUS", a.checkReception())
        assertNull(a.checkReception()) // reply is pending
    }

    @Test
    fun failureMapsReadErrorsToStopReasons() {
        val a = chosen()
        a.failure(ConsoleReply.Status("ERR READ_ONLY"))
        assertEquals(SetupAssistant.Stop.ReadOnly, a.stop)
        a.restart()
        a.failure(ConsoleReply.Corrupt(ReplyError.Timeout))
        assertTrue(a.stop is SetupAssistant.Stop.LinkLost)
        a.restart()
        a.failure(ConsoleReply.Status("ERR NOT_OVER_BLE"))
        assertEquals("ERR NOT_OVER_BLE", a.stop?.text)
    }

    @Test
    fun busyConsoleEndsAssistantWithoutHang() {
        val a = assigned()
        assertEquals("STATUS", a.checkReception())
        a.commandNotSent() // the activity calls this when the console already runs a command
        assertEquals("Verbindung verloren: Befehl nicht gesendet", a.stop?.text)
        assertNull(a.pending)
        a.restart()
        assertNull(a.stop)
        assertEquals(Step.SCAN, a.step)
    }

    @Test
    fun batLowSensorPassesVerify() {
        val a = assigned()
        a.checkReception()
        a.receive(ConsoleReply.Frame(statusLines(sensorLine(2, "Kueche", "BATLOW", newMac))))
        assertEquals(Step.CALIBRATE, a.step)
    }

    @Test
    fun restartInMiddleOfAssignBatchAllowsNewAssign() {
        val a = chosen()
        a.beginAssign(emptyStatus(), config())
        a.receive(ConsoleReply.Status("OK SET slot2_mac"))
        a.restart()
        assertNull(a.nextCommand()) // the old queue is gone
        a.scanned(rows, config())
        a.choose(newMac, "", config())
        assertEquals("SET slot2_mac=$newMac", a.beginAssign(emptyStatus(), config()))
        a.receive(ConsoleReply.Status("OK SET slot2_mac"))
        assertEquals("CONFIG", a.nextCommand())
    }
}
