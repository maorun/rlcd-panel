package com.rlcdpanel.watch.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationReplyTest {
    private fun sample(): CalibrationReply {
        val frame = ReplyAssembler().append(Fixtures.file("calib_reply_sample.txt").readBytes()) as ConsoleReply.Frame
        return CalibrationReply.parse(frame.lines)
    }

    @Test
    fun sharedFixtureGivesTheRowTexts() {
        val reply = sample()
        val rows = CalibrationText.rows(reply)
        assertEquals(2, rows.size)
        assertEquals(CalibrationText.Row("onboard", "12 h", "-1.6", "0.5"), rows[0])
        assertEquals("onboard: 12 h, T -1.6 C, RH 0.5 %", CalibrationText.line(rows[0]))
        assertEquals("Referenz: slot1", CalibrationText.reference(reply))
    }

    @Test
    fun skipLineShowsDashes() {
        val skipped = CalibrationText.rows(sample())[1]
        assertEquals("slot2", skipped.name)
        assertEquals("-- h", skipped.hours)
        assertEquals("--", skipped.tempDiff)
        assertEquals("--", skipped.rhDiff)
        assertEquals("slot2: -- h, T --, RH --", CalibrationText.line(skipped))
    }

    @Test
    fun usbLineExistsOnlyWithTheLine() {
        assertEquals("USB 6 h, Batterie 6 h, usb_temp_offset -2.0, Diff -1.2", CalibrationText.usb(sample().usb!!))
        val without = CalibrationReply.parse(listOf("REFERENCE onboard"))
        assertNull(without.usb)
        assertTrue(CalibrationText.rows(without).isEmpty())
        val noDiff = CalibrationReply.UsbSplit(1, 2, -1.0, null)
        assertEquals("USB 1 h, Batterie 2 h, usb_temp_offset -1.0, Diff --", CalibrationText.usb(noDiff))
    }

    @Test
    fun pickerListsOnboardAndOccupiedSlots() {
        val config = PanelConfig(mapOf("slot1_mac" to "AA:BB:CC:DD:EE:01", "slot4_name" to "Keller"))
        assertEquals(listOf(0, 1, 4), CalibrationText.picker(config).map { it.first })
        assertEquals(listOf("Onboard", "Slot 1", "Keller"), CalibrationText.picker(config).map { it.second })
        assertEquals(listOf(0), CalibrationText.picker(PanelConfig()).map { it.first })
        // The firmware answers CALIB only for 0 to 5, so slot 6 and above stay out of the picker.
        val high = PanelConfig(mapOf("slot1_name" to "A", "slot5_name" to "E", "slot6_name" to "F", "slot10_mac" to "AA:BB:CC:DD:EE:0A"))
        assertEquals(listOf(0, 1, 5), CalibrationText.picker(high).map { it.first })
        val off = PanelConfig(mapOf("slot2_name" to "Bad", "slot2_enabled" to "0"))
        assertEquals(listOf(0), CalibrationText.picker(off).map { it.first })
    }

    @Test
    fun commandLine() {
        assertEquals("CALIB 4", CalibrationText.command(4))
    }

    @Test
    fun batchSendsTheSetCommandsInProposalOrderThenConfig() {
        val reply = sample()
        assertEquals(listOf("temp_offset_c", "usb_temp_offset_c"), reply.proposals.map { it.key })
        val sent = mutableListOf<String>()
        var result: CalibrationBatch.Result? = null
        CalibrationBatch(reply.proposals).run(
            send = { cmd, done ->
                sent.add(cmd)
                done(if (cmd == "CONFIG") ConsoleReply.Frame(emptyList()) else ConsoleReply.Status("OK SET"))
            },
            done = { result = it }
        )
        assertEquals(listOf("SET temp_offset_c=-0.4", "SET usb_temp_offset_c=-0.8", "CONFIG"), sent)
        assertEquals(listOf("temp_offset_c", "usb_temp_offset_c"), result!!.applied)
    }

    @Test
    fun readOnlyMapsToTheConsoleLevelHint() {
        var result: CalibrationBatch.Result? = null
        CalibrationBatch(sample().proposals).run(
            send = { _, done -> done(ConsoleReply.Status("ERR READ_ONLY")) },
            done = { result = it }
        )
        assertEquals("Set ble_console=2 to apply", CalibrationBatch.failureText(result!!.failure!!.second))
        assertTrue(result!!.summary.startsWith("Set ble_console=2 to apply"))
    }

    private val configFrame = ConsoleReply.Frame(emptyList())

    @Test
    fun applyOutcomeAfterSuccessKeepsNothingStale() {
        val ok = CalibrationBatch.Result(listOf("temp_offset_c", "usb_temp_offset_c"), null, configFrame, false)
        val o = CalibrationText.applyOutcome(ok)
        assertTrue(o.changed)
        assertTrue(!o.isError)
        assertEquals("Übernommen: temp_offset_c, usb_temp_offset_c", o.message)
    }

    @Test
    fun applyOutcomeFirstErrorWithoutAppliedKeyKeepsTheRows() {
        val failed = CalibrationBatch.Result(emptyList(), "temp_offset_c" to "ERR READ_ONLY", null, false)
        val o = CalibrationText.applyOutcome(failed)
        assertTrue(!o.changed)
        assertTrue(o.isError)
        assertTrue(o.message.startsWith("Set ble_console=2 to apply"))
    }

    @Test
    fun applyOutcomeErrorAfterAnAppliedKeyDropsTheRows() {
        val failed = CalibrationBatch.Result(listOf("temp_offset_c"), "usb_temp_offset_c" to "ERR BAD_VALUE", null, false)
        val o = CalibrationText.applyOutcome(failed)
        assertTrue(o.changed)
        assertTrue(o.isError)
    }

    @Test
    fun applyOutcomeUnknownStateDropsTheRows() {
        val unknown = CalibrationBatch.Result(emptyList(), "temp_offset_c" to CalibrationBatch.UNKNOWN_STATE, null, false)
        val o = CalibrationText.applyOutcome(unknown)
        assertTrue(o.changed)
        assertTrue(o.isError)
    }

    @Test
    fun applyOutcomeConfigFailureStillCountsAsChanged() {
        val cfg = CalibrationBatch.Result(listOf("temp_offset_c"), null, null, true)
        val o = CalibrationText.applyOutcome(cfg)
        assertTrue(o.changed)
        assertTrue(o.isError)
    }
}
