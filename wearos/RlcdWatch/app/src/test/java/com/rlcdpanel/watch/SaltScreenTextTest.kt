package com.rlcdpanel.watch

import com.rlcdpanel.watch.protocol.CalibrationBatch
import com.rlcdpanel.watch.protocol.SaltReply
import com.rlcdpanel.watch.protocol.TwoPointCalibration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SaltScreenTextTest {
    @Test
    fun proposalLineShowsKeyAndValue() {
        val reply = SaltReply.parse(
            listOf("SALT slot2 hours=6 rh_mean=73.0 rh_span=0.4 temp_mean=25.0 reference=75.3", "slot2_rh_offset_pct=2.3")
        )
        assertEquals("Vorschlag: slot2_rh_offset_pct=2.3", SaltScreenText.proposal(reply))
    }

    @Test
    fun skippedResultHasNoProposal() {
        assertNull(SaltScreenText.proposal(SaltReply.parse(listOf("SKIP slot1 hours=3"))))
    }

    @Test
    fun unstableResultHasNoProposal() {
        assertNull(SaltScreenText.proposal(SaltReply.parse(listOf("UNSTABLE slot1 span=1.5"))))
    }

    private fun reply(name: String, mean: Double, reference: Double): SaltReply =
        SaltReply.parse(listOf("SALT $name hours=6 rh_mean=$mean rh_span=0.2 temp_mean=25.0 reference=$reference"))

    @Test
    fun twoPointTextShowsGainAndOffset() {
        val r = TwoPointCalibration.compute(reply("slot3", 70.0, 75.4), reply("slot3", 30.0, 32.8), emptyMap())
        assertEquals("Gain 1.066, Offset 0.8", SaltScreenText.twoPoint(r))
    }

    @Test
    fun stateKeepsTheOtherSaltAndDropsTheFailedOne() {
        val na = reply("slot3", 70.0, 75.4)
        val mg = reply("slot3", 30.0, 32.8)
        val both = SaltState().withReply(false, na).reading().withReply(true, mg)
        assertEquals(na, both.naCl)
        assertEquals(mg, both.mgCl2)
        val failed = both.withFailure(true, "ERR")
        assertEquals(na, failed.naCl)
        assertNull(failed.mgCl2)
        assertEquals("ERR", failed.message)
    }

    @Test
    fun applyTextShowsAppliedKeysOrTheFailure() {
        val ok = CalibrationBatch.Result(listOf("slot3_rh_gain", "slot3_rh_offset_pct"), null, null, false)
        assertEquals("Übernommen: slot3_rh_gain, slot3_rh_offset_pct", SaltScreenText.applyText(ok))
        val readOnly = CalibrationBatch.Result(emptyList(), "slot3_rh_gain" to "ERR READ_ONLY", null, false)
        assertEquals("Set ble_console=2 to apply (slot3_rh_gain). Übernommen: 0", SaltScreenText.applyText(readOnly))
    }

    @Test
    fun afterApplyDropsBothResultsWhenASetMayHaveRun() {
        val na = reply("slot3", 70.0, 75.4)
        val mg = reply("slot3", 30.0, 32.8)
        val before = SaltState().withReply(false, na).withReply(true, mg)
        val partial = CalibrationBatch.Result(listOf("slot3_rh_gain"), "slot3_rh_offset_pct" to "ERR BAD_VALUE", null, false)
        val after = before.afterApply(partial)
        assertNull(after.naCl)
        assertNull(after.mgCl2)
        assertEquals(true, after.applyIsError)
        val unknown = CalibrationBatch.Result(emptyList(), "slot3_rh_gain" to CalibrationBatch.UNKNOWN_STATE, null, false)
        assertNull(before.afterApply(unknown).naCl)
        assertEquals(true, SaltScreenText.changedConfig(unknown))
    }

    @Test
    fun afterApplyKeepsTheResultsWhenNoSetRan() {
        val na = reply("slot3", 70.0, 75.4)
        val mg = reply("slot3", 30.0, 32.8)
        val before = SaltState().withReply(false, na).withReply(true, mg)
        val readOnly = CalibrationBatch.Result(emptyList(), "slot3_rh_gain" to "ERR READ_ONLY", null, false)
        val after = before.afterApply(readOnly)
        assertEquals(na, after.naCl)
        assertEquals(mg, after.mgCl2)
        assertEquals(false, SaltScreenText.changedConfig(readOnly))
    }

    @Test
    fun withoutReadKeepsBothResults() {
        val na = reply("slot3", 70.0, 75.4)
        val state = SaltState().withReply(false, na).reading().withoutRead("Panel-Verbindung ist belegt")
        assertEquals(na, state.naCl)
        assertEquals(false, state.isReading)
        assertEquals("Panel-Verbindung ist belegt", state.message)
    }
}
