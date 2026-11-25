package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartLimitsTest {
    private fun config(vararg pairs: Pair<String, String>) = PanelConfig(mapOf(*pairs))

    @Test
    fun fourActiveLimitsGiveFourLines() {
        val limits = ChartLimits.from(
            config(
                "mold_alert_limit_rh" to "70", "dry_alert_limit_rh" to "30",
                "freeze_alert_limit_c" to "-18.0", "heat_alert_limit_c" to "32"
            )
        )
        assertEquals(
            listOf("mold_alert_limit_rh", "dry_alert_limit_rh", "freeze_alert_limit_c", "heat_alert_limit_c"),
            limits.map { it.key }
        )
        assertEquals(listOf(70.0, 30.0, -18.0, 32.0), limits.map { it.value })
        assertEquals(listOf("Mold", "Dry", "Freeze", "Heat (feels-like)"), limits.map { it.label })
    }

    @Test
    fun zeroGivesNoDryAndNoHeatLine() {
        val limits = ChartLimits.from(
            config(
                "mold_alert_limit_rh" to "70", "dry_alert_limit_rh" to "0",
                "freeze_alert_limit_c" to "5", "heat_alert_limit_c" to "0"
            )
        )
        assertEquals(listOf("mold_alert_limit_rh", "freeze_alert_limit_c"), limits.map { it.key })
    }

    @Test
    fun freezeLimitZeroStaysAValidLine() {
        val limits = ChartLimits.from(config("freeze_alert_limit_c" to "0.0"))
        assertEquals(listOf("freeze_alert_limit_c"), limits.map { it.key })
        assertEquals(0.0, limits.first().value, 0.0)
    }

    @Test
    fun valueOutsideRangeGivesNoLine() {
        val limits = ChartLimits.from(
            config(
                "mold_alert_limit_rh" to "99", "dry_alert_limit_rh" to "50",
                "freeze_alert_limit_c" to "-30", "heat_alert_limit_c" to "50"
            )
        )
        assertTrue(limits.isEmpty())
        assertTrue(ChartLimits.from(config("mold_alert_limit_rh" to "39")).isEmpty())
        assertEquals(1, ChartLimits.from(config("mold_alert_limit_rh" to "40")).size)
        assertEquals(1, ChartLimits.from(config("mold_alert_limit_rh" to "95")).size)
        assertTrue(ChartLimits.from(config("mold_alert_limit_rh" to "abc")).isEmpty())
    }

    @Test
    fun withoutReplyOrKeysTheListIsEmpty() {
        assertTrue(ChartLimits.from(null).isEmpty())
        assertTrue(ChartLimits.from(PanelConfig()).isEmpty())
        assertTrue(ChartLimits.from(config("temp_unit" to "C")).isEmpty())
    }

    @Test
    fun splitIntoHumidityAndTemperature() {
        val limits = ChartLimits.from(
            config(
                "mold_alert_limit_rh" to "70", "dry_alert_limit_rh" to "30",
                "freeze_alert_limit_c" to "-5", "heat_alert_limit_c" to "30"
            )
        )
        assertEquals(listOf("Mold", "Dry"), ChartLimits.humidity(limits).map { it.label })
        assertEquals(listOf("Freeze", "Heat (feels-like)"), ChartLimits.temperature(limits).map { it.label })
    }
}
