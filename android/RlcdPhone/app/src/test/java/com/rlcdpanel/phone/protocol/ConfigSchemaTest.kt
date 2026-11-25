package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigSchemaTest {
    private fun template(): List<String> =
        Fixtures.file("config_backup_sample.ini").parentFile.parentFile.parentFile
            .resolve("docs/config.example.ini").readLines()

    private fun replyStatus(line: String): ConsoleReply = ConsoleReply.Status(line)

    @Test
    fun eachTemplateKeyIsInTheSchemaOnceWithTheTemplateDefault() {
        val active = template().filter { it.matches(Regex("^[a-z0-9_]+=.*")) }
            .associate { it.substringBefore('=') to it.substringAfter('=').trim() }
        val commented = template().filter { it.matches(Regex("^# [a-z0-9_]+=.*")) }.map { it.removePrefix("# ").substringBefore('=') }
        assertTrue(active.size > 20)
        for (key in active.keys + commented) {
            assertEquals("$key must occur once in the schema", 1, ConfigSchema.entries.count { it.key == key })
        }
        for ((key, value) in active) {
            val entry = ConfigSchema.entry(key)!!
            assertNull("$key=$value rejected", ConfigSchema.validate(key, value))
            val a = CalibrationKeys.parseNumber(value)
            val b = CalibrationKeys.parseNumber(entry.defaultValue)
            assertTrue("$key default ${entry.defaultValue} differs from template $value", if (a != null && b != null) a == b else value == entry.defaultValue)
        }
    }

    @Test
    fun schemaHasOneEntryForEachKeyInMacOsOrderWithTexts() {
        val macKeys = Fixtures.file("config_backup_sample.ini").readLines().map { it.removePrefix("# ").substringBefore('=') }
        assertEquals(macKeys, ConfigSchema.keys)
        assertEquals(ConfigSchema.keys.size, ConfigSchema.keys.toSet().size)
        assertEquals(ConfigKeys.keys, ConfigSchema.keys)
        assertTrue(ConfigSchema.entries.all { it.description.isNotBlank() })
        assertEquals(ConfigSchema.entries.size, ConfigCategory.values().sumOf { ConfigSchema.entries(it).size })
    }

    @Test
    fun rangesMatchMacOsSchemaForFiveSampleKeys() {
        // Read the ranges from the macOS source, so a change on one side fails this test.
        val swift = Fixtures.file("config_backup_sample.ini").parentFile.parentFile.parentFile
            .resolve("macos/RlcdCompanion/Sources/RlcdProtocol/ConfigSchema.swift").readText()
        fun mac(key: String): String = Regex("""e\("$key", \.\w+, (\.[^"]*?), "[^"]*", "[^"]*", "[^"]*"\)""")
            .find(swift)?.groupValues?.get(1) ?: throw AssertionError("$key not found in ConfigSchema.swift")
        fun int(key: String) = ConfigSchema.entry(key)!!.kind as ConfigKind.Integer
        val samples = listOf("wake_interval_s", "chime_volume_pct", "mold_alert_limit_rh", "battery_offset_mv", "night_wake_interval_s")
        for (key in samples) {
            val k = int(key)
            val text = mac(key)
            val range = Regex("""min: (-?\d+), max: (-?\d+)""").find(text)!!.groupValues
            assertEquals("$key min", range[1].toInt(), k.min)
            assertEquals("$key max", range[2].toInt(), k.max)
            val off = Regex("""off: (-?\d+)""").find(text)?.groupValues?.get(1)?.toInt()
            assertEquals("$key off", off, k.off)
        }
        val tiles = ConfigSchema.entry("climate_alert_tiles")!!.kind as ConfigKind.BitMask
        assertEquals(11, tiles.bits)
        assertTrue(mac("climate_alert_tiles").contains("bitMask(bits: 11)"))
    }

    @Test
    fun receptionLowPctAcceptsOffAndThirtyToEighty() {
        assertNull(ConfigSchema.validate("reception_low_pct", "0"))
        assertNull(ConfigSchema.validate("reception_low_pct", "30"))
        assertNull(ConfigSchema.validate("reception_low_pct", "80"))
        assertNotNull(ConfigSchema.validate("reception_low_pct", "29"))
        assertNotNull(ConfigSchema.validate("reception_low_pct", "81"))
        assertNotNull(ConfigSchema.validate("reception_low_pct", "x"))
        assertEquals("50", ConfigSchema.entry("reception_low_pct")!!.defaultValue)
    }

    @Test
    fun valueOutsideTheRangeGivesErrorTextAndNoCommand() {
        assertNotNull(ConfigSchema.validate("wake_interval_s", "29"))
        assertNull(ConfigSchema.validate("wake_interval_s", "30"))
        assertNotNull(ConfigSchema.validate("wake_interval_s", "901"))
        assertNotNull(ConfigSchema.validate("wake_interval_s", "abc"))
        assertEquals("Unknown key", ConfigSchema.validate("wifi_ssid", "x"))
        val plan = ConfigSchema.plan(mapOf("wake_interval_s" to "5000"), mapOf("wake_interval_s" to "60"))
        assertTrue(plan is ConfigPlan.Invalid)
        assertEquals("Allowed: 30 to 900", (plan as ConfigPlan.Invalid).problems["wake_interval_s"])
    }

    @Test
    fun oneInvalidValueRefusesTheWholeList() {
        val plan = ConfigSchema.plan(mapOf("wake_interval_s" to "120", "chime_volume_pct" to "101"), emptyMap())
        assertEquals(setOf("chime_volume_pct"), (plan as ConfigPlan.Invalid).problems.keys)
    }

    @Test
    fun unchangedValueGivesNoCommand() {
        val plan = ConfigSchema.plan(mapOf("wake_interval_s" to "060"), mapOf("wake_interval_s" to "60"))
        assertEquals(emptyList<String>(), (plan as ConfigPlan.Commands).commands)
    }

    @Test
    fun batchKeepsSchemaOrderAndPutsBleConsoleLast() {
        val current = emptyMap<String, String>()
        val edits = mapOf(
            "ble_console" to "2",
            "slot3_name" to "HALL",
            "wake_interval_s" to "120",
            "temp_offset_c" to "-1.0"
        )
        val plan = ConfigSchema.plan(edits, current) as ConfigPlan.Commands
        assertEquals(
            listOf("SET temp_offset_c=-1.0", "SET wake_interval_s=120", "SET slot3_name=HALL", "SET ble_console=2"),
            plan.commands
        )
        assertEquals("SET ble_console=2", plan.commands.last())
        // The input order of the changes does not matter.
        val shuffled = ConfigSchema.batch(plan.changes.reversed())
        assertEquals(plan.commands, shuffled)
    }

    @Test
    fun lineAbove40BytesIsRefusedBeforeTheSend() {
        // No valid schema value gives a line above 40 bytes, so the guard is defense in depth.
        assertFalse(ConfigKeys.isSendable("slot10_name", "A".repeat(30)))
        assertTrue(ConfigKeys.isSendable("slot10_name", "ABCDEFGHIJKL"))
        val sent = mutableListOf<String>()
        var result: ConfigRunResult? = null
        ConfigSchema.run(listOf("SET slot10_name=" + "A".repeat(30)),
            { line, cb -> sent.add(line); cb(if (line == "CONFIG") ConsoleReply.Frame(listOf("wake_interval_s=60")) else replyStatus("OK")) },
            { result = it })
        assertEquals(listOf("CONFIG"), sent)
        assertEquals("slot10_name", result!!.failureKey)
        assertTrue(result!!.failure!!.contains("40"))
        assertEquals(40, ConfigSchema.MAX_LINE_BYTES)
    }

    @Test
    fun firstErrReplyStopsTheBatchAndConfigStillFollows() {
        val sent = mutableListOf<String>()
        var result: ConfigRunResult? = null
        ConfigSchema.run(
            listOf("SET temp_offset_c=-1.0", "SET wake_interval_s=120", "SET ble_console=2"),
            { line, cb ->
                sent.add(line)
                cb(when (line) {
                    "SET wake_interval_s=120" -> replyStatus("ERR BAD_VALUE")
                    "CONFIG" -> ConsoleReply.Frame(listOf("wake_interval_s=60"))
                    else -> replyStatus("OK SET")
                })
            },
            { result = it })
        assertEquals(listOf("SET temp_offset_c=-1.0", "SET wake_interval_s=120", "CONFIG"), sent)
        assertEquals(listOf("temp_offset_c"), result!!.applied)
        assertEquals("wake_interval_s", result!!.failureKey)
        assertEquals("ERR BAD_VALUE", result!!.failure)
        assertFalse(result!!.ok)
        assertNotNull(result!!.config)
    }

    @Test
    fun errReadOnlyShowsAClearText() {
        var result: ConfigRunResult? = null
        ConfigSchema.run(listOf("SET wake_interval_s=120"),
            { line, cb -> cb(if (line == "CONFIG") ConsoleReply.Frame(listOf("ble_console=1")) else replyStatus("ERR READ_ONLY")) },
            { result = it })
        assertTrue(result!!.readOnly)
        assertTrue(result!!.summary.contains("ble_console"))
        assertEquals(ConfigSchema.READ_ONLY_TEXT, result!!.failure)
    }

    @Test
    fun successfulBatchSendsConfigAfterTheLastSet() {
        val sent = mutableListOf<String>()
        var result: ConfigRunResult? = null
        ConfigSchema.run(listOf("SET wake_interval_s=120"),
            { line, cb -> sent.add(line); cb(if (line == "CONFIG") ConsoleReply.Frame(listOf("wake_interval_s=120")) else replyStatus("OK SET wake_interval_s")) },
            { result = it })
        assertEquals(listOf("SET wake_interval_s=120", "CONFIG"), sent)
        assertTrue(result!!.ok)
        assertEquals("Übernommen: 1", result!!.summary)
    }

    @Test
    fun corruptReplyInTheMiddleStopsTheBatchAndMarksStateUnknown() {
        val sent = mutableListOf<String>()
        var result: ConfigRunResult? = null
        ConfigSchema.run(listOf("SET temp_offset_c=-1.0", "SET wake_interval_s=120"),
            { line, cb ->
                sent.add(line)
                cb(when (line) {
                    "SET temp_offset_c=-1.0" -> ConsoleReply.Corrupt(ReplyError.Timeout)
                    "CONFIG" -> ConsoleReply.Frame(listOf("wake_interval_s=60"))
                    else -> replyStatus("OK")
                })
            },
            { result = it })
        assertEquals(listOf("SET temp_offset_c=-1.0", "CONFIG"), sent)
        assertTrue(result!!.applied.isEmpty())
        assertTrue(result!!.failure!!.contains("unbekannt"))
        assertFalse(result!!.ok)
    }

    @Test
    fun failedConfigReadAfterTheBatchIsReported() {
        var result: ConfigRunResult? = null
        ConfigSchema.run(listOf("SET wake_interval_s=120"),
            { line, cb -> cb(if (line == "CONFIG") replyStatus("ERR NO_CARD") else replyStatus("OK")) },
            { result = it })
        assertEquals(listOf("wake_interval_s"), result!!.applied)
        assertNull(result!!.config)
        assertFalse(result!!.ok)
        assertTrue(result!!.summary.contains("CONFIG lesen fehlgeschlagen"))
    }

    @Test
    fun pureConfigReadHasAReadText() {
        var result: ConfigRunResult? = null
        ConfigSchema.run(emptyList(), { _, cb -> cb(ConsoleReply.Frame(listOf("wake_interval_s=60"))) }, { result = it })
        assertTrue(result!!.ok)
        assertEquals("CONFIG gelesen", result!!.summary)
    }
}
