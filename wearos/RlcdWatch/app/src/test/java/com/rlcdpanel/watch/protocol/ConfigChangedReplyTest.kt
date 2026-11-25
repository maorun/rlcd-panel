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
}
