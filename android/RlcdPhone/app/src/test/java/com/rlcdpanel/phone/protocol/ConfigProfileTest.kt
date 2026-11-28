package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigProfileTest {

    private class MemoryStorage : ConfigProfileStorage {
        var text: String? = null
        override fun read(): String? = text
        override fun write(text: String) { this.text = text }
    }

    private val defaults: Map<String, String> = ConfigKeys.entries.associate { it.key to it.defaultValue }

    private fun changes(vararg edits: Pair<String, String>) = ConfigSchema.diff(defaults, defaults + edits)

    private fun made(name: String, c: List<ConfigChange>): ConfigProfile =
        (ConfigProfile.make(name, c) as ConfigProfile.Made.Ok).profile

    @Test
    fun saveKeepsChangedAlertKeys() {
        val p = made("Winter", changes("freeze_alert_limit_c" to "2.0", "mold_alert_limit_rh" to "65"))
        assertEquals(mapOf("freeze_alert_limit_c" to "2.0", "mold_alert_limit_rh" to "65"), p.values)
        val store = ConfigProfileStore(MemoryStorage())
        assertTrue(store.save(p))
        assertEquals(listOf(p), store.profiles())
        store.remove("Winter")
        assertTrue(store.profiles().isEmpty())
    }

    @Test
    fun saveReplacesSameNameAndLimitsCount() {
        val store = ConfigProfileStore(MemoryStorage())
        assertTrue(store.save(ConfigProfile("A", mapOf("mold_alert_limit_rh" to "60"))))
        assertTrue(store.save(ConfigProfile("A", mapOf("mold_alert_limit_rh" to "61"))))
        assertEquals(1, store.profiles().size)
        assertEquals("61", store.profile("A")?.values?.get("mold_alert_limit_rh"))
        for (i in 1 until ConfigProfileStore.MAX_PROFILES) assertTrue(store.save(ConfigProfile("P$i", mapOf("mold_alert_limit_rh" to "60"))))
        assertFalse(store.save(ConfigProfile("Extra", mapOf("mold_alert_limit_rh" to "60"))))
    }

    @Test
    fun excludedKeysAreNotSaved() {
        val c = changes(
            "slot1_mac" to "AA:BB:CC:DD:EE:01", "slot1_name" to "KITCHEN", "onboard_name" to "ROOM", "temp_offset_c" to "-1.0",
            "usb_temp_offset_c" to "-1.0", "slot2_rh_offset_pct" to "2.0", "battery_offset_mv" to "10", "rh_gain" to "1.02",
            "slot3_rh_gain" to "1.02", "heat_alert_limit_c" to "30"
        )
        assertEquals(mapOf("heat_alert_limit_c" to "30"), made("Summer", c).values)
        val onlyName = ConfigProfile.make("X", changes("slot1_name" to "A"))
        assertTrue(onlyName is ConfigProfile.Made.Failed)
    }

    @Test
    fun badNamesAreRefused() {
        val c = changes("mold_alert_limit_rh" to "65")
        for (n in listOf("", " A", "A ", "A\tB", "A\nB", "x".repeat(25))) {
            assertTrue(n, ConfigProfile.make(n, c) is ConfigProfile.Made.Failed)
        }
    }

    @Test
    fun invalidValueIsNotSaved() {
        assertTrue(ConfigProfile.make("Bad", changes("mold_alert_limit_rh" to "99")) is ConfigProfile.Made.Failed)
    }

    @Test
    fun tabOrLineBreakInValueIsRefused() {
        for (v in listOf("6\t0", "6\n0")) {
            assertTrue(ConfigProfile.make("Bad", listOf(ConfigChange("temp_unit", "C", v))) is ConfigProfile.Made.Failed)
        }
    }

    @Test
    fun profilesComeSortedByNameAndNameRulesMatchMacOs() {
        val store = ConfigProfileStore(MemoryStorage())
        for (n in listOf("Winter", "Autumn", "Summer")) store.save(ConfigProfile(n, mapOf("mold_alert_limit_rh" to "60")))
        assertEquals(listOf("Autumn", "Summer", "Winter"), store.profiles().map { it.name })
        assertTrue(ConfigProfile.isValidName("\uD83D\uDE00".repeat(24))) // 24 code points
        assertFalse(ConfigProfile.isValidName("\uD83D\uDE00".repeat(25)))
        assertTrue(ConfigProfile.isValidName("A\u00A0")) // only U+0020 is an edge blank
    }

    @Test
    fun planIsTheDiffAgainstCurrentConfig() {
        val p = ConfigProfile("Winter", mapOf("freeze_alert_limit_c" to "2.0", "mold_alert_limit_rh" to "70"))
        val plan = p.plan(defaults)
        assertEquals(listOf(ConfigChange("freeze_alert_limit_c", "0.0", "2.0")), plan.changes) // mold already 70
        assertTrue(plan.skipped.isEmpty() && plan.problems.isEmpty() && plan.canApply)
        assertEquals(listOf("SET freeze_alert_limit_c=2.0"), plan.commands)
        assertFalse(p.plan(defaults + ("freeze_alert_limit_c" to "2")).canApply) // nothing to do
    }

    @Test
    fun unknownAndExcludedKeysInStoredProfileAreSkipped() {
        val p = ConfigProfile(
            "Old",
            mapOf("future_key" to "1", "slot1_mac" to "AA:BB:CC:DD:EE:01", "temp_offset_c" to "3", "mold_alert_limit_rh" to "65")
        )
        val plan = p.plan(defaults)
        assertEquals(listOf("future_key", "slot1_mac", "temp_offset_c"), plan.skipped)
        assertEquals(listOf("mold_alert_limit_rh"), plan.changes.map { it.key })
        assertTrue(plan.canApply)
    }

    @Test
    fun valueOutsideSchemaRangeBlocksApply() {
        val plan = ConfigProfile("Bad", mapOf("mold_alert_limit_rh" to "99", "freeze_alert_limit_c" to "2.0")).plan(defaults)
        assertNotNull(plan.problems["mold_alert_limit_rh"])
        assertNull(plan.problems["freeze_alert_limit_c"])
        assertFalse(plan.canApply)
    }

    @Test
    fun sharedFixtureDecodesLikeMacOs() {
        val profiles = ConfigProfileStore.decode(Fixtures.file("config_profiles_sample.txt").readText())
        assertEquals(listOf("Winter", "Summer"), profiles.map { it.name })
        assertEquals(mapOf("heat_alert_limit_c" to "30", "future_key" to "1", "mold_alert_limit_rh" to "99"), profiles[1].values)
        val plan = profiles[1].plan(defaults)
        assertEquals(listOf("future_key"), plan.skipped)
        assertTrue("mold_alert_limit_rh" in plan.problems)
        assertEquals(profiles, ConfigProfileStore.decode(ConfigProfileStore.encode(profiles)))
    }

    @Test
    fun corruptStoredLinesDropOnlyThemselves() {
        val text = "junk\nP\t\nK\tx\t1\nP\tOk\nK\t\t1\nK\tmold_alert_limit_rh\t65\nK\tbroken\n"
        assertEquals(listOf(ConfigProfile("Ok", mapOf("mold_alert_limit_rh" to "65"))), ConfigProfileStore.decode(text))
    }
}
