package com.rlcdpanel.watch.protocol

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
    fun dryDelayRangeIs0To12WholeHours() {
        for (ok in listOf("0", "1", "12")) assertNull(ok, error("dry_alert_delay_h", ok))
        for (bad in listOf("13", "-1", "1.5")) assertNotNull(bad, error("dry_alert_delay_h", bad))
    }

    @Test
    fun dryDelayWireTextAndOrderBelowDryLimit() {
        assertEquals(
            listOf("SET dry_alert_delay_h=4"),
            commands(AlertLimits.plan(mapOf("dry_alert_delay_h" to "4"), PanelConfig()))
        )
        val keys = AlertLimits.LIMITS.map { it.key }
        assertEquals(keys.indexOf("dry_alert_limit_rh") + 1, keys.indexOf("dry_alert_delay_h"))
        assertEquals("Verzögerung Trockenalarm (h)", limit("dry_alert_delay_h").label)
    }

    @Test
    fun dryDelayRangeEqualsConfigSchemaEntry() {
        val entry = ConfigSchema.entry("dry_alert_delay_h")!!
        val l = limit("dry_alert_delay_h")
        val kind = entry.kind as ConfigKind.Integer
        assertEquals(kind.off, l.min.toInt())
        assertEquals(kind.max, l.max.toInt())
        assertEquals(entry.defaultValue, l.default.toInt().toString())
    }

    @Test
    fun heatDelayAcceptsZeroOneAndTwelve() {
        for (ok in listOf("0", "1", "12")) assertNull(ok, error("heat_alert_delay_h", ok))
    }

    @Test
    fun heatDelayRejectsThirteenAndNonNumeric() {
        for (bad in listOf("13", "x", "", "1.5", "-1")) assertNotNull(bad, error("heat_alert_delay_h", bad))
        val plan = AlertLimits.plan(mapOf("heat_alert_delay_h" to "13", "mold_alert_limit_rh" to "75"), PanelConfig())
        assertTrue(plan is AlertLimits.Plan.Invalid)
        assertTrue(AlertLimits.plan(mapOf("heat_alert_delay_h" to "x"), PanelConfig()) is AlertLimits.Plan.Invalid)
    }

    @Test
    fun heatDelayWireTextAndSchemaOrderBelowHeatLimit() {
        assertEquals(
            listOf("SET heat_alert_delay_h=2"),
            commands(AlertLimits.plan(mapOf("heat_alert_delay_h" to "2"), PanelConfig()))
        )
        val keys = AlertLimits.LIMITS.map { it.key }
        assertEquals(keys.indexOf("heat_alert_limit_c") + 1, keys.indexOf("heat_alert_delay_h"))
        assertEquals(
            listOf("SET heat_alert_limit_c=30", "SET heat_alert_delay_h=2"),
            commands(AlertLimits.plan(mapOf("heat_alert_delay_h" to "2", "heat_alert_limit_c" to "30"), PanelConfig()))
        )
    }

    @Test
    fun heatDelayShowsAusForZeroAndUnitHours() {
        val l = limit("heat_alert_delay_h")
        assertEquals("h", l.unit)
        assertEquals("Aus", AlertLimits.valueText(l, "0"))
        assertEquals("3 h", AlertLimits.valueText(l, "3"))
        assertEquals("Aus", AlertLimits.valueText(limit("dry_alert_delay_h"), "0"))
        assertEquals("Aus", AlertLimits.valueText(limit("mold_alert_delay_h"), "0"))
    }

    @Test
    fun heatDelayRangeEqualsConfigSchemaEntry() {
        val entry = ConfigSchema.entry("heat_alert_delay_h")!!
        val l = limit("heat_alert_delay_h")
        val kind = entry.kind as ConfigKind.Integer
        assertEquals(kind.off, l.min.toInt())
        assertEquals(kind.max, l.max.toInt())
        assertEquals(entry.defaultValue, l.default.toInt().toString())
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
            "mold_alert_limit_rh" to 70.0, "mold_alert_delay_h" to 0.0, "dry_alert_limit_rh" to 0.0, "dry_alert_delay_h" to 0.0, "freeze_alert_limit_c" to 0.0,
            "heat_alert_limit_c" to 0.0, "heat_alert_delay_h" to 0.0, "ventilate_limit_rh" to 60.0, "sensor_battery_limit_pct" to 10.0
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
        var result: AlertLimits.Result? = null
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

    private fun stepper(key: String) = AlertLimitStepper.steps(limit(key))

    @Test
    fun stepperOfOffLimitStartsWithOffThenMinimum() {
        val steps = stepper("dry_alert_limit_rh")
        assertEquals(listOf("0", "20", "21"), steps.take(3))
        assertEquals("45", steps.last())
    }

    @Test
    fun stepperOfPlainLimitUsesFullRangeInWholeSteps() {
        val steps = stepper("mold_alert_limit_rh")
        assertEquals("40", steps.first())
        assertEquals("95", steps.last())
        assertEquals(56, steps.size)
    }

    @Test
    fun stepperOfFreezeLimitUsesHalfDegreeSteps() {
        val steps = stepper("freeze_alert_limit_c")
        assertEquals(listOf("-20.0", "-19.5"), steps.take(2))
        assertEquals("15.0", steps.last())
        assertEquals(71, steps.size)
    }

    @Test
    fun delayStepperStartsAtOffZero() {
        assertEquals(13, stepper("mold_alert_delay_h").size)
        assertEquals("0", stepper("heat_alert_delay_h").first())
    }

    @Test
    fun everyStepIsValidAndSendsAsItIs() {
        for (limit in AlertLimits.LIMITS) {
            for (step in AlertLimitStepper.steps(limit)) {
                assertNull("${limit.key}=$step", AlertLimits.error(limit, step))
                assertEquals(step, AlertLimits.wire(limit, step))
            }
        }
    }

    @Test
    fun stepMovesAndStopsAtTheEnds() {
        val dry = limit("dry_alert_limit_rh")
        assertEquals("20", AlertLimitStepper.move(dry, "0", 1))
        assertEquals("0", AlertLimitStepper.move(dry, "0", -1))
        assertEquals("45", AlertLimitStepper.move(dry, "45", 1))
        assertEquals("0", AlertLimitStepper.move(dry, "20", -1))
    }

    @Test
    fun stepFromCachedValueOffTheGridSnapsToNearest() {
        val freeze = limit("freeze_alert_limit_c")
        assertEquals("-1.0", AlertLimitStepper.move(freeze, "-1.5", 1))
        assertEquals("2.5", AlertLimitStepper.move(freeze, "2.0", 1))
        assertEquals("-0.5", AlertLimitStepper.move(freeze, "0.0", -1))
    }

    @Test
    fun startTextShowsCachedValueElseDefault() {
        val cfg = PanelConfig(values = mapOf("dry_alert_limit_rh" to "35", "freeze_alert_limit_c" to "-2"))
        assertEquals("35", AlertLimitStepper.startText(limit("dry_alert_limit_rh"), cfg))
        assertEquals("-2.0", AlertLimitStepper.startText(limit("freeze_alert_limit_c"), cfg))
        assertEquals("70", AlertLimitStepper.startText(limit("mold_alert_limit_rh"), cfg))
    }

    @Test
    fun singleLimitBatchSendsOneSetThenConfig() {
        val cfg = PanelConfig(values = mapOf("mold_alert_limit_rh" to "70", "ble_console" to "2"))
        val plan = AlertLimits.plan(mapOf("mold_alert_limit_rh" to "75"), cfg)
        assertEquals(listOf("SET mold_alert_limit_rh=75"), commands(plan))
        val sent = mutableListOf<String>()
        AlertLimits.run(commands(plan), { c, cb ->
            sent.add(c)
            cb(if (c == "CONFIG") ConsoleReply.Frame(listOf("SOURCE SD")) else ConsoleReply.Status("OK SET x"))
        }) { }
        assertEquals(listOf("SET mold_alert_limit_rh=75", "CONFIG"), sent)
    }

    @Test
    fun readOnlyReplyMapsToConsoleHint() {
        var result: AlertLimits.Result? = null
        AlertLimits.run(listOf("SET mold_alert_limit_rh=75"), { _, cb -> cb(ConsoleReply.Status("ERR READ_ONLY")) }) { result = it }
        assertEquals("Set ble_console=2 to apply", result!!.failure)
    }

    @Test
    fun unusableReplyStopsBatchWithGenericText() {
        var result: AlertLimits.Result? = null
        val sent = mutableListOf<String>()
        AlertLimits.run(listOf("SET mold_alert_limit_rh=75"), { c, cb -> sent.add(c); cb(ConsoleReply.Frame(listOf("x"))) }) { result = it }
        assertEquals("Keine gültige Antwort vom Panel", result!!.failure)
        assertEquals(listOf("SET mold_alert_limit_rh=75"), sent)
        assertTrue(!result!!.ok)
    }

    @Test
    fun failedConfigReadKeepsAppliedCount() {
        var result: AlertLimits.Result? = null
        AlertLimits.run(listOf("SET mold_alert_limit_rh=75"), { c, cb ->
            cb(if (c == "CONFIG") ConsoleReply.Status("ERR BUSY") else ConsoleReply.Status("OK SET x"))
        }) { result = it }
        assertTrue(result!!.configFailed)
        assertEquals("Übernommen: 1. CONFIG lesen fehlgeschlagen", result!!.summary)
    }

    @Test
    fun successSummaryCountsAppliedCommands() {
        var result: AlertLimits.Result? = null
        AlertLimits.run(listOf("SET a=1", "SET b=2"), { c, cb ->
            cb(if (c == "CONFIG") ConsoleReply.Frame(listOf("SOURCE SD")) else ConsoleReply.Status("OK SET x"))
        }) { result = it }
        assertTrue(result!!.ok)
        assertEquals("Übernommen: 2", result!!.summary)
    }
}
