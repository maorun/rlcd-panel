package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertLimitsTest {
    private fun limit(key: String) = AlertLimits.LIMITS.first { it.key == key }
    private fun error(key: String, text: String) = AlertLimits.error(limit(key), text)
    private fun commands(plan: AlertLimits.Plan) = (plan as AlertLimits.Plan.Commands).commands

    @Test
    fun moldRangeIs40To95() {
        assertNotNull(error("mold_alert_limit_rh", "39"))
        assertNull(error("mold_alert_limit_rh", "40"))
        assertNull(error("mold_alert_limit_rh", "95"))
        assertNotNull(error("mold_alert_limit_rh", "96"))
    }

    @Test
    fun moldDelayRangeIs0To12WholeHours() {
        assertNull(error("mold_alert_delay_h", "0"))
        assertNull(error("mold_alert_delay_h", "12"))
        assertNotNull(error("mold_alert_delay_h", "13"))
        assertNotNull(error("mold_alert_delay_h", "-1"))
        assertNotNull(error("mold_alert_delay_h", "2.5"))
    }

    @Test
    fun moldDelayWireTextAndUnchangedValue() {
        assertEquals(
            listOf("SET mold_alert_delay_h=3"),
            commands(AlertLimits.plan(mapOf("mold_alert_delay_h" to "3"), PanelConfig()))
        )
        val config = PanelConfig(mapOf("mold_alert_delay_h" to "3"))
        assertTrue(commands(AlertLimits.plan(mapOf("mold_alert_delay_h" to "3"), config)).isEmpty())
    }

    @Test
    fun heatAcceptsZeroOffAndRange27To45() {
        assertNull(error("heat_alert_limit_c", "0"))
        assertNotNull(error("heat_alert_limit_c", "26"))
        assertNull(error("heat_alert_limit_c", "27"))
        assertNull(error("heat_alert_limit_c", "45"))
        assertNotNull(error("heat_alert_limit_c", "46"))
    }

    @Test
    fun dryAcceptsZeroOffAndRange20To45() {
        assertNull(error("dry_alert_limit_rh", "0"))
        assertNotNull(error("dry_alert_limit_rh", "19"))
        assertNull(error("dry_alert_limit_rh", "20"))
        assertNull(error("dry_alert_limit_rh", "45"))
        assertNotNull(error("dry_alert_limit_rh", "46"))
    }

    @Test
    fun freezeRangeIsMinus20To15WithOneDecimal() {
        assertNotNull(error("freeze_alert_limit_c", "-20.1"))
        assertNull(error("freeze_alert_limit_c", "-20"))
        assertNull(error("freeze_alert_limit_c", "2,5"))
        assertNull(error("freeze_alert_limit_c", "15.0"))
        assertNotNull(error("freeze_alert_limit_c", "15.1"))
        assertNotNull(error("freeze_alert_limit_c", "1.25"))
    }

    @Test
    fun ventilateAndBatteryRanges() {
        assertNotNull(error("ventilate_limit_rh", "49"))
        assertNull(error("ventilate_limit_rh", "50"))
        assertNull(error("ventilate_limit_rh", "80"))
        assertNotNull(error("ventilate_limit_rh", "81"))
        assertNull(error("sensor_battery_limit_pct", "0"))
        assertNull(error("sensor_battery_limit_pct", "50"))
        assertNotNull(error("sensor_battery_limit_pct", "51"))
        assertNotNull(error("sensor_battery_limit_pct", "-1"))
    }

    @Test
    fun nonNumbersAreRefused() {
        assertNotNull(error("mold_alert_limit_rh", ""))
        assertNotNull(error("mold_alert_limit_rh", "abc"))
        assertNotNull(error("mold_alert_limit_rh", "70.5"))
    }

    @Test
    fun twoChangedKeysGiveFixedOrder() {
        // The edit map order is reversed on purpose: the command order follows LIMITS.
        val edits = linkedMapOf("sensor_battery_limit_pct" to "20", "mold_alert_limit_rh" to "80")
        assertEquals(
            listOf("SET mold_alert_limit_rh=80", "SET sensor_battery_limit_pct=20"),
            commands(AlertLimits.plan(edits, PanelConfig()))
        )
    }

    @Test
    fun freezeValueIsSentWithOneDecimal() {
        assertEquals(
            listOf("SET freeze_alert_limit_c=2.5"),
            commands(AlertLimits.plan(mapOf("freeze_alert_limit_c" to "2,5"), PanelConfig()))
        )
    }

    @Test
    fun unchangedValueGivesNoCommand() {
        val config = PanelConfig(
            mapOf("mold_alert_limit_rh" to "75", "freeze_alert_limit_c" to "-2.0", "heat_alert_limit_c" to "0")
        )
        val edits = mapOf("mold_alert_limit_rh" to "75", "freeze_alert_limit_c" to "-2", "heat_alert_limit_c" to "0")
        assertTrue(commands(AlertLimits.plan(edits, config)).isEmpty())
    }

    @Test
    fun missingCachedKeyIsUnknownAndAlwaysSends() {
        assertEquals(
            listOf("SET heat_alert_limit_c=0"),
            commands(AlertLimits.plan(mapOf("heat_alert_limit_c" to "0"), PanelConfig()))
        )
    }

    @Test
    fun inputWithSpacesIsTrimmedAndOddFormsAreRefused() {
        assertNull(error("mold_alert_limit_rh", " 80 "))
        assertEquals("SET mold_alert_limit_rh=80", commands(AlertLimits.plan(mapOf("mold_alert_limit_rh" to " 080 "), PanelConfig()))[0])
        assertNotNull(error("mold_alert_limit_rh", "8 0"))
        assertNotNull(error("mold_alert_limit_rh", "+80"))
    }

    @Test
    fun shownValueFallsBackToFirmwareDefaults() {
        val expected = mapOf(
            "mold_alert_limit_rh" to 70.0, "mold_alert_delay_h" to 0.0, "dry_alert_limit_rh" to 0.0, "freeze_alert_limit_c" to 0.0,
            "heat_alert_limit_c" to 0.0, "ventilate_limit_rh" to 60.0, "sensor_battery_limit_pct" to 10.0
        )
        for (limit in AlertLimits.LIMITS) assertEquals(limit.key, expected[limit.key]!!, AlertLimits.shown(limit, PanelConfig()), 0.0)
    }

    @Test
    fun editControlsNeedConsoleLevel2() {
        assertTrue(!AlertLimits.canEdit(PanelConfig()))
        assertTrue(!AlertLimits.canEdit(PanelConfig(mapOf("ble_console" to "1"))))
        assertTrue(AlertLimits.canEdit(PanelConfig(mapOf("ble_console" to "2"))))
    }

    @Test
    fun invalidValueRefusesTheWholeList() {
        val plan = AlertLimits.plan(mapOf("mold_alert_limit_rh" to "39", "ventilate_limit_rh" to "70"), PanelConfig())
        assertTrue(plan is AlertLimits.Plan.Invalid)
    }

    @Test
    fun errBadValueStopsBeforeTheNextSetAndConfig() {
        val sent = mutableListOf<String>()
        var result: com.rlcdpanel.phone.finder.SlotAssignment.Result? = null
        AlertLimits.run(
            listOf("SET mold_alert_limit_rh=80", "SET ventilate_limit_rh=70", "SET heat_alert_limit_c=30"),
            { command, cb ->
                sent.add(command)
                cb(if (sent.size == 2) ConsoleReply.Status("ERR BAD_VALUE") else ConsoleReply.Status("OK SET x"))
            }
        ) { result = it }
        assertEquals(listOf("SET mold_alert_limit_rh=80", "SET ventilate_limit_rh=70"), sent)
        assertEquals("ERR BAD_VALUE", result!!.failure)
        assertEquals(1, result!!.applied.size)
        assertNull(result!!.config)
    }

    @Test
    fun configFollowsLastSet() {
        val sent = mutableListOf<String>()
        AlertLimits.run(listOf("SET mold_alert_limit_rh=80"), { command, cb ->
            sent.add(command)
            cb(if (command == "CONFIG") ConsoleReply.Frame(listOf("SOURCE SD")) else ConsoleReply.Status("OK SET x"))
        }) { }
        assertEquals(listOf("SET mold_alert_limit_rh=80", "CONFIG"), sent)
    }
}
