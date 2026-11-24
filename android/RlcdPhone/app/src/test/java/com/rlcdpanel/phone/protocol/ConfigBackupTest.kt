package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ConfigBackupTest {

    /** Values of the shared `CONFIG` reply sample, read like the app reads a panel reply. */
    private fun sampleValues(): Map<String, String> {
        val reply = ReplyAssembler().append(Fixtures.file("config_reply_sample.txt").readBytes()) as ConsoleReply.Frame
        return PanelConfig.parseText(PanelConfig.parseConfigReply(reply.lines).text).values
    }

    private fun parse(text: String) = ConfigBackup.parse(text.toByteArray(Charsets.UTF_8))

    /** Defaults for every key, then [overrides]. */
    private fun full(vararg overrides: Pair<String, String>): Map<String, String> =
        ConfigKeys.entries.associate { it.key to it.defaultValue } + overrides

    // --- export ---

    @Test
    fun exportOfSampleEqualsSharedFixtureOfMacOs() {
        val expected = Fixtures.file("config_backup_sample.ini").readText()
        assertEquals(expected, ConfigBackup.render(sampleValues()))
    }

    @Test
    fun exportHasSchemaOrderLfAndNoCr() {
        val text = ConfigBackup.render(full("wake_interval_s" to "120"))
        assertFalse(text.contains("\r"))
        assertTrue(text.endsWith("\n"))
        val keys = text.trimEnd('\n').split('\n').map { it.removePrefix("# ").substringBefore('=') }
        assertEquals(ConfigKeys.keys, keys)
        assertEquals("ble_console", keys.last())
        assertTrue(text.toByteArray().size <= ConfigBackup.MAX_BYTES)
    }

    @Test
    fun exportHasNoWifiCredentials() {
        val text = ConfigBackup.render(full("wifi_ssid" to "HOME", "wifi_password" to "secret")).lowercase()
        assertFalse(text.contains("wifi"))
        assertFalse(text.contains("secret"))
    }

    @Test
    fun emptyNameIsACommentLine() {
        val text = ConfigBackup.render(full())
        assertTrue(text.contains("# slot1_name=\n"))
        assertTrue(text.contains("\nslot1_mac=\n"))
    }

    @Test
    fun exportParsesBackToTheSameValues() {
        val values = full("wake_interval_s" to "120", "slot1_name" to "KITCHEN", "slot2_mac" to "AA:BB:CC:DD:EE:01")
        val parsed = parse(ConfigBackup.render(values))
        assertTrue(parsed.rejected.isEmpty())
        assertEquals(values.filter { ConfigKeys.validate(it.key, it.value) == null }, parsed.values)
        assertTrue(parsed.changes(values).isEmpty())
    }

    // --- parse and rejects ---

    @Test
    fun badValueIsRejectedWithItsLineNumber() {
        val parsed = parse("# backup\n\nwake_interval_s=10\nmold_alert_limit_rh=70\n")
        assertEquals(listOf(3), parsed.rejected.map { it.line })
        assertEquals("wake_interval_s", parsed.rejected.first().key)
        assertNull(parsed.values["wake_interval_s"])
        assertEquals("70", parsed.values["mold_alert_limit_rh"])
    }

    @Test
    fun lineWithoutEqualsSignIsRejected() {
        assertEquals(listOf(2), parse("; note\nwake_interval_s 60\n").rejected.map { it.line })
    }

    @Test
    fun unknownKeyCommentsBlankLinesAndCrAreIgnored() {
        val parsed = parse("# c\r\n; c\r\n\r\nwifi_ssid=HOME\r\nunknown_key=1\r\nwake_interval_s=90\r\n")
        assertTrue(parsed.rejected.isEmpty())
        assertEquals(mapOf("wake_interval_s" to "90"), parsed.values)
    }

    @Test
    fun blanksAroundKeyAndValueAreTrimmedAndLastValidLineWins() {
        val parsed = parse("  wake_interval_s = 90 \n\tmold_alert_limit_rh=75\t\nwake_interval_s=120\n")
        assertEquals("120", parsed.values["wake_interval_s"])
        assertEquals("75", parsed.values["mold_alert_limit_rh"])
    }

    @Test
    fun byteOrderMarkIsSkippedSoLineNumbersStayEqual() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val ok = ConfigBackup.parse(bom + "wake_interval_s=90\n".toByteArray())
        assertEquals("90", ok.values["wake_interval_s"])
        val bad = ConfigBackup.parse(bom + "# note\nwake_interval_s=10\n".toByteArray())
        assertEquals(listOf(2), bad.rejected.map { it.line })
    }

    @Test
    fun fileAbove8192BytesIsRefused() {
        assertEquals(8192, ConfigBackup.MAX_BYTES)
        try {
            ConfigBackup.parse(ByteArray(ConfigBackup.MAX_BYTES + 1) { '#'.code.toByte() })
            fail("expected ConfigBackupException")
        } catch (e: ConfigBackupException) {
            assertEquals("File above 8192 bytes", e.reason)
        }
        ConfigBackup.parse(ByteArray(ConfigBackup.MAX_BYTES) { '#'.code.toByte() })
    }

    @Test
    fun invalidUtf8IsRefused() {
        try {
            ConfigBackup.parse(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x0A))
            fail("expected ConfigBackupException")
        } catch (e: ConfigBackupException) {
            assertEquals("File is not UTF-8 text", e.reason)
        }
    }

    // --- diff ---

    @Test
    fun equalBackupGivesNoChangeAndEmptyBatch() {
        val current = full("wake_interval_s" to "120")
        val plan = ConfigRestorePlan.build(parse(ConfigBackup.render(current)), current)
        assertTrue(plan.changes.isEmpty())
        assertTrue(plan.proposals.isEmpty())
        assertFalse(plan.canRestore(consoleWritable = true))
    }

    @Test
    fun onlyChangedKeysAreListedWithOldAndNewValue() {
        val current = full("wake_interval_s" to "120")
        val plan = ConfigRestorePlan.build(parse(ConfigBackup.render(full("wake_interval_s" to "300"))), current)
        assertEquals(listOf(ConfigChange("wake_interval_s", "120", "300")), plan.changes)
        assertEquals("wake_interval_s: 120 -> 300", plan.changes.first().text)
    }

    @Test
    fun keyAbsentFromFileKeepsCurrentValue() {
        val plan = ConfigRestorePlan.build(parse("wake_interval_s=300\n"), full("wake_interval_s" to "120", "slot1_name" to "KITCHEN"))
        assertEquals(listOf("wake_interval_s"), plan.changes.map { it.key })
    }

    @Test
    fun numbersAndMacsCompareByValue() {
        val current = full("temp_offset_c" to "-1.90", "slot1_mac" to "aa:bb:cc:dd:ee:01")
        val plan = ConfigRestorePlan.build(parse("temp_offset_c=-1.9\nslot1_mac=AA:BB:CC:DD:EE:01\n"), current)
        assertTrue(plan.changes.isEmpty())
    }

    // --- batch order ---

    @Test
    fun batchFollowsSchemaOrderWithBleConsoleLast() {
        val current = full()
        val text = "ble_console=2\nslot1_mac=AA:BB:CC:DD:EE:01\nwake_interval_s=300\ntemp_offset_c=0.5\n"
        val plan = ConfigRestorePlan.build(parse(text), current)
        assertEquals(
            listOf("temp_offset_c", "wake_interval_s", "slot1_mac", "ble_console"),
            plan.proposals.map { it.key }
        )
        assertEquals(CalibrationProposal("ble_console", "2"), plan.proposals.last())
    }

    @Test
    fun keyListEqualsMacOsSchemaOrder() {
        // The shared fixture is the macOS export: it holds every ConfigSchema key in schema order.
        val macKeys = Fixtures.file("config_backup_sample.ini").readLines().map { it.removePrefix("# ").substringBefore('=') }
        assertEquals(macKeys, ConfigKeys.keys)
        assertEquals(ConfigKeys.keys.size, ConfigKeys.keys.toSet().size)
        assertEquals("ble_console", ConfigKeys.keys.last())
    }

    @Test
    fun keysAndDefaultsMatchTheConfigTemplate() {
        val template = Fixtures.file("config_backup_sample.ini").parentFile.parentFile.parentFile.resolve("docs/config.example.ini").readLines()
        val active = template.filter { it.matches(Regex("^[a-z0-9_]+=.*")) }.associate { it.substringBefore('=') to it.substringAfter('=').trim() }
        val commented = template.filter { it.matches(Regex("^# [a-z0-9_]+=.*")) }.map { it.removePrefix("# ").substringBefore('=') }
        for (e in ConfigKeys.entries) {
            val listed = e.key in active || e.key in commented
            assertTrue("${e.key} missing in docs/config.example.ini", listed)
            val value = active[e.key] ?: continue
            assertNull("${e.key}=$value rejected", ConfigKeys.validate(e.key, value))
            val a = CalibrationKeys.parseNumber(value)
            val b = CalibrationKeys.parseNumber(e.defaultValue)
            assertTrue("${e.key} default ${e.defaultValue} differs from template $value", if (a != null && b != null) a == b else value == e.defaultValue)
        }
        assertEquals(emptyList<String>(), (active.keys + commented).filter { ConfigKeys.entry(it) == null }.filter { it !in TEMPLATE_ONLY_KEYS })
    }

    @Test
    fun batchDropsKeysOutsideTheSchema() {
        val changes = listOf(ConfigChange("wifi_ssid", "", "HOME"), ConfigChange("wake_interval_s", "60", "300"))
        assertEquals(listOf(CalibrationProposal("wake_interval_s", "300")), ConfigKeys.batch(changes))
    }

    @Test
    fun staleReviewIsRebuiltFromNewValues() {
        val parsed = parse("wake_interval_s=300\n")
        assertEquals(1, ConfigRestorePlan.build(parsed, full("wake_interval_s" to "60")).proposals.size)
        assertTrue(ConfigRestorePlan.build(parsed, full("wake_interval_s" to "300")).proposals.isEmpty())
    }

    private companion object {
        /** Template keys that the parser knows but the schema does not list. */
        val TEMPLATE_ONLY_KEYS = emptySet<String>()
    }

    // --- problems ---

    @Test
    fun badValueBlocksTheRestore() {
        val plan = ConfigRestorePlan.build(parse("wake_interval_s=10\nmold_alert_limit_rh=75\n"), full())
        assertEquals(1, plan.rejected.size)
        assertEquals("wake_interval_s: Allowed: 30 to 900", plan.rejected.first().reason)
        assertFalse(plan.canRestore(consoleWritable = true))
    }

    @Test
    fun consoleBelowLevel2BlocksTheRestore() {
        val plan = ConfigRestorePlan.build(parse("wake_interval_s=300\n"), full())
        assertTrue(plan.canRestore(consoleWritable = true))
        assertFalse(plan.canRestore(consoleWritable = false))
    }

    @Test
    fun valueWithSpaceIsAProblemBecauseTheConsoleCannotTakeIt() {
        val plan = ConfigRestorePlan.build(parse("slot1_name=KITCHEN SINK\n"), full())
        assertEquals("A space in the value is not supported over the console", plan.problems["slot1_name"])
        assertFalse(plan.canRestore(consoleWritable = true))
    }

    @Test
    fun macThatAnotherSlotHoldsIsAProblem() {
        val current = full("slot1_mac" to "AA:BB:CC:DD:EE:01")
        val plan = ConfigRestorePlan.build(parse("slot2_mac=AA:BB:CC:DD:EE:01\n"), current)
        assertEquals("Another slot has this MAC. Clear the other slot first.", plan.problems["slot2_mac"])
    }

    @Test
    fun clearedMacIsSentAsEmptyValue() {
        val plan = ConfigRestorePlan.build(parse("slot1_mac=\n"), full("slot1_mac" to "AA:BB:CC:DD:EE:01"))
        assertTrue(plan.problems.isEmpty())
        assertEquals(listOf(CalibrationProposal("slot1_mac", "")), plan.proposals)
    }

    // --- stop at the first ERR ---

    @Test
    fun batchStopsAtFirstErrAndNamesTheKey() {
        val plan = ConfigRestorePlan.build(
            parse("temp_offset_c=0.5\nwake_interval_s=300\nsensor_stale_min=60\nble_console=2\n"),
            full()
        )
        val sent = mutableListOf<String>()
        var result: CalibrationBatch.Result? = null
        CalibrationBatch(plan.proposals).run(
            send = { command, reply ->
                sent.add(command)
                reply(
                    if (command == "SET wake_interval_s=300") ConsoleReply.Status("ERR BAD_VALUE")
                    else ConsoleReply.Status("OK SET")
                )
            },
            done = { result = it }
        )
        // CONFIG is not sent after an ERR, and the keys after the failed key stay unsent.
        assertEquals(listOf("SET temp_offset_c=0.5", "SET wake_interval_s=300"), sent)
        val r = result!!
        assertEquals(listOf("temp_offset_c"), r.applied)
        assertEquals("wake_interval_s" to "ERR BAD_VALUE", r.failure)
        assertNull(r.config)
        assertTrue(r.summary.contains("wake_interval_s"))
    }

    @Test
    fun batchWithoutErrSendsEveryKeyThenConfig() {
        val plan = ConfigRestorePlan.build(parse("wake_interval_s=300\nble_console=2\n"), full())
        val sent = mutableListOf<String>()
        var result: CalibrationBatch.Result? = null
        CalibrationBatch(plan.proposals).run(
            send = { command, reply ->
                sent.add(command)
                reply(if (command == "CONFIG") ConsoleReply.Frame(listOf("SOURCE SD")) else ConsoleReply.Status("OK SET"))
            },
            done = { result = it }
        )
        assertEquals(listOf("SET wake_interval_s=300", "SET ble_console=2", "CONFIG"), sent)
        assertNull(result!!.failure)
        assertEquals(listOf("wake_interval_s", "ble_console"), result.applied)
    }
}
