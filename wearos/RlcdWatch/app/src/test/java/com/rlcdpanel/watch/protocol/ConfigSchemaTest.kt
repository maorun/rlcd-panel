package com.rlcdpanel.watch.protocol

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks `ConfigSchema` against `docs/config.example.ini` and `DeviceConfigParser::KEY_COUNT`. */
class ConfigSchemaTest {
    private val root: File = Fixtures.file("config_backup_sample.ini").parentFile.parentFile.parentFile

    private fun template(): List<String> = root.resolve("docs/config.example.ini").readLines()

    private fun active(): Map<String, String> = template()
        .filter { it.matches(Regex("^[a-z0-9_]+=.*")) }
        .associate { it.substringBefore('=') to it.substringAfter('=').trim() }

    private fun commented(): List<String> = template()
        .filter { it.matches(Regex("^# [a-z0-9_]+=.*")) }
        .map { it.removePrefix("# ").substringBefore('=') }

    private fun firmwareKeyCount(): Int {
        val header = root.resolve("rlcd_panel/device_config.h").readText()
        return Regex("""KEY_COUNT\s*=\s*(\d+)""").find(header)!!.groupValues[1].toInt()
    }

    @Test
    fun schemaKeyCountEqualsFirmwareKeyCount() {
        assertEquals(firmwareKeyCount(), ConfigSchema.entries.size)
        assertEquals(firmwareKeyCount(), ConfigSchema.keys.toSet().size)
    }

    @Test
    fun templateHoldsEachSchemaKeyExactlyOnce() {
        val templateKeys = active().keys.toList() + commented()
        assertEquals("duplicate key in the template", templateKeys.size, templateKeys.toSet().size)
        assertEquals(ConfigSchema.keys.toSet(), templateKeys.toSet())
        assertEquals(firmwareKeyCount(), templateKeys.size)
    }

    @Test
    fun eachActiveTemplateValueIsValidAndEqualsTheSchemaDefault() {
        val active = active()
        assertTrue(active.size > 20)
        for ((key, value) in active) {
            val entry = ConfigSchema.entry(key)
            assertNotNull("$key missing in the schema", entry)
            assertNull("$key=$value rejected", ConfigSchema.validate(key, value))
            val a = CalibrationKeys.parseNumber(value)
            val b = CalibrationKeys.parseNumber(entry!!.defaultValue)
            assertTrue(
                "$key default ${entry.defaultValue} differs from template $value",
                if (a != null && b != null) a == b else value == entry.defaultValue
            )
        }
    }

    @Test
    fun commentedTemplateKeysHaveAnEmptyDefaultOrAValidOne() {
        for (key in commented()) {
            val entry = ConfigSchema.entry(key)
            assertNotNull("$key missing in the schema", entry)
            assertTrue("$key default not valid", entry!!.defaultValue.isEmpty() || ConfigSchema.validate(key, entry.defaultValue) == null)
        }
    }

    @Test
    fun schemaKeysFollowTheMacOsOrder() {
        val macKeys = Fixtures.file("config_backup_sample.ini").readLines().map { it.removePrefix("# ").substringBefore('=') }
        assertEquals(macKeys, ConfigSchema.keys)
        assertEquals(ConfigKeys.keys, ConfigSchema.keys)
        assertTrue(ConfigSchema.entries.all { it.description.isNotBlank() })
    }

    @Test
    fun delayKeysAcceptOffAndOneToTwelve() {
        for (key in listOf("heat_alert_delay_h", "dry_alert_delay_h", "mold_alert_delay_h")) {
            for (ok in listOf("0", "1", "12")) assertNull("$key=$ok", ConfigSchema.validate(key, ok))
            for (bad in listOf("13", "-1", "x")) assertNotNull("$key=$bad", ConfigSchema.validate(key, bad))
            assertEquals("0", ConfigSchema.entry(key)!!.defaultValue)
        }
    }
}
