package com.rlcdpanel.watch.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigChangedReplyTest {
    private fun fixtureLines(): List<String> =
        Fixtures.file("config_changed_reply_sample.txt").readLines().filter { !it.startsWith("CRC32") }

    @Test
    fun sharedFixtureParses() {
        assertEquals(
            listOf(
                ConfigChangedEntry("temp_offset_c", "2.25"),
                ConfigChangedEntry("mold_alert_limit_rh", "65"),
                ConfigChangedEntry("slot1_name", "Bad")
            ),
            ConfigChangedReply.parse(fixtureLines())
        )
    }

    @Test
    fun sharedFixtureCrcMatches() {
        val text = Fixtures.file("config_changed_reply_sample.txt").readText()
        assertEquals(ConsoleReply.Frame(fixtureLines()), ReplyAssembler().append(text.toByteArray()))
    }

    @Test
    fun emptyReplyGivesNoEntries() {
        assertEquals(emptyList<ConfigChangedEntry>(), ConfigChangedReply.parse(listOf("END CHANGED 0")))
    }

    @Test
    fun countThatDiffersFromTheLineCountThrows() {
        assertThrows(ParseException::class.java) {
            ConfigChangedReply.parse(listOf("temp_offset_c=2.25", "END CHANGED 2"))
        }
        assertThrows(ParseException::class.java) { ConfigChangedReply.parse(listOf("END CHANGED 1")) }
    }

    @Test
    fun badLinesThrow() {
        assertThrows(ParseException::class.java) { ConfigChangedReply.parse(listOf("nonsense_key=1", "END CHANGED 1")) }
        assertThrows(ParseException::class.java) { ConfigChangedReply.parse(listOf("temp_offset_c", "END CHANGED 1")) }
        assertThrows(ParseException::class.java) {
            ConfigChangedReply.parse(listOf("temp_offset_c=1", "temp_offset_c=2", "END CHANGED 2"))
        }
        assertThrows(ParseException::class.java) { ConfigChangedReply.parse(listOf("temp_offset_c=1")) }
        assertThrows(ParseException::class.java) { ConfigChangedReply.parse(emptyList()) }
    }

    @Test
    fun resetAllGivesOneSetForEachChangedKeyWithBleConsoleLast() {
        val changed = listOf(
            ConfigChangedEntry("ble_console", "2"),
            ConfigChangedEntry("wake_interval_s", "120"),
            ConfigChangedEntry("mold_alert_limit_rh", "65"),
            ConfigChangedEntry("temp_offset_c", "2.25")
        )
        val plan = ConfigSchema.resetPlan(changed)
        assertEquals(
            listOf(
                "SET temp_offset_c=-1.9",
                "SET mold_alert_limit_rh=70",
                "SET wake_interval_s=60",
                "SET ble_console=0"
            ),
            plan.commands
        )
        assertEquals("SET ble_console=0", plan.commands.last())
        assertTrue(plan.skipped.isEmpty())
    }

    @Test
    fun resetOfAnEmptyDefaultNameIsSkippedWithAReason() {
        val plan = ConfigSchema.resetPlan(ConfigChangedReply.parse(fixtureLines()))
        assertEquals(listOf("SET temp_offset_c=-1.9", "SET mold_alert_limit_rh=70"), plan.commands)
        assertEquals(setOf("slot1_name"), plan.skipped.keys)
    }

    @Test
    fun resetOfAMacGivesAnEmptySet() {
        val plan = ConfigSchema.resetPlan(listOf(ConfigChangedEntry("slot2_mac", "AA:BB:CC:DD:EE:01")))
        assertEquals(listOf("SET slot2_mac="), plan.commands)
    }

    @Test
    fun resetDropsUnknownKeysAndEqualValues() {
        val plan = ConfigSchema.resetPlan(
            listOf(ConfigChangedEntry("not_a_key", "1"), ConfigChangedEntry("temp_offset_c", "-1.90"))
        )
        assertTrue(plan.commands.isEmpty())
        assertTrue(plan.skipped.isEmpty())
    }

    @Test
    fun fixtureGivesOneResetCommandForEachKeyWithTheSchemaDefault() {
        val entries = ConfigChangedReply.parse(fixtureLines())
        assertEquals(listOf("temp_offset_c", "mold_alert_limit_rh", "slot1_name"), entries.map { it.key })
        val plans = entries.filter { it.key != "slot1_name" }.map { ConfigReset.planFor(it) }
        assertEquals(
            listOf(
                ConfigReset.RowPlan.Sendable("temp_offset_c", "SET temp_offset_c=-1.9", "-1.9"),
                ConfigReset.RowPlan.Sendable("mold_alert_limit_rh", "SET mold_alert_limit_rh=70", "70")
            ),
            plans
        )
        assertEquals("temp_offset_c auf -1.9 setzen?", ConfigReset.confirmText(plans[0] as ConfigReset.RowPlan.Sendable))
    }

    @Test
    fun keyWithEmptyDefaultLandsInSkipped() {
        val entry = ConfigChangedReply.parse(fixtureLines()).first { it.key == "slot1_name" }
        assertTrue(ConfigSchema.resetPlan(listOf(entry)).skipped.containsKey("slot1_name"))
        val plan = ConfigReset.planFor(entry)
        assertEquals(
            ConfigReset.RowPlan.Skipped("slot1_name", ConfigSchema.resetPlan(listOf(entry)).skipped.getValue("slot1_name")),
            plan
        )
        assertTrue((plan as ConfigReset.RowPlan.Skipped).reason.isNotEmpty())
    }

    @Test
    fun equalValueAndUnknownKeyGiveNoRowPlan() {
        assertEquals(ConfigReset.RowPlan.None, ConfigReset.planFor(ConfigChangedEntry("temp_offset_c", "-1.9")))
        assertEquals(ConfigReset.RowPlan.None, ConfigReset.planFor(ConfigChangedEntry("not_a_key", "1")))
    }

    @Test
    fun readOnlyErrorStopsTheRunAfterTheFirstCommand() {
        val sent = mutableListOf<String>()
        var result: ConfigReset.Result? = null
        ConfigReset.run(
            listOf("SET temp_offset_c=-1.9", "SET mold_alert_limit_rh=70"),
            { line, cb -> sent.add(line); cb(ConsoleReply.Status("ERR READ_ONLY")) },
            { result = it }
        )
        assertEquals(listOf("SET temp_offset_c=-1.9"), sent)
        assertEquals(ConfigReset.Result(0, ConfigReset.READ_ONLY_TEXT, true), result)
    }

    @Test
    fun firstOtherErrorStopsAndShowsItsText() {
        val sent = mutableListOf<String>()
        var result: ConfigReset.Result? = null
        ConfigReset.run(
            listOf("SET a=1", "SET b=2"),
            { line, cb -> sent.add(line); cb(ConsoleReply.Status("ERR BAD_VALUE")) },
            { result = it }
        )
        assertEquals(1, sent.size)
        assertEquals(ConfigReset.Result(0, "ERR BAD_VALUE", false), result)
    }

    @Test
    fun frameReplyStopsTheRunAsUnknownState() {
        val sent = mutableListOf<String>()
        var result: ConfigReset.Result? = null
        ConfigReset.run(
            listOf("SET a=1", "SET b=2"),
            { line, cb -> sent.add(line); cb(ConsoleReply.Frame(listOf("x"))) },
            { result = it }
        )
        assertEquals(1, sent.size)
        assertTrue(result!!.unknownState)
        assertEquals(false, result!!.readOnly)
    }

    @Test
    fun errReplyKeepsTheKnownState() {
        var result: ConfigReset.Result? = null
        ConfigReset.run(listOf("SET a=1"), { _, cb -> cb(ConsoleReply.Status("ERR BAD_VALUE")) }, { result = it })
        assertEquals(false, result!!.unknownState)
    }

    @Test
    fun confirmTextNamesAnEmptyDefaultAsLeer() {
        assertEquals(
            "slot2_mac auf leer setzen?",
            ConfigReset.confirmText(ConfigReset.RowPlan.Sendable("slot2_mac", "SET slot2_mac=", ""))
        )
    }

    @Test
    fun commandAboveTheLineLimitIsRefusedBeforeTheSend() {
        val sent = mutableListOf<String>()
        var result: ConfigReset.Result? = null
        val long = "SET k=" + "x".repeat(ConfigSchema.MAX_LINE_BYTES)
        ConfigReset.run(listOf(long), { line, cb -> sent.add(line); cb(ConsoleReply.Status("OK")) }, { result = it })
        assertTrue(sent.isEmpty())
        assertTrue(result!!.failure != null)
    }

    @Test
    fun okRepliesSendAllCommands() {
        val sent = mutableListOf<String>()
        var result: ConfigReset.Result? = null
        ConfigReset.run(
            listOf("SET a=1", "SET b=2"),
            { line, cb -> sent.add(line); cb(ConsoleReply.Status("OK SET")) },
            { result = it }
        )
        assertEquals(listOf("SET a=1", "SET b=2"), sent)
        assertEquals(ConfigReset.Result(2, null, false), result)
    }
}
