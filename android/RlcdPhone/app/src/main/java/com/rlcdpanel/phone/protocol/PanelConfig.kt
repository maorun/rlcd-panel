package com.rlcdpanel.phone.protocol

import android.content.Context
import java.io.BufferedReader
import java.io.File

/**
 * Parsed configuration from config.ini (MicroSD card format).
 */
data class PanelConfig(
    val values: Map<String, String> = emptyMap()
) {
    /** Highest slot index (slot1 to slot10). */
    private val slotCount = 10

    /** Normalizes a MAC address to upper-case hex pairs separated by colons; null for a malformed or zero MAC. */
    private fun normalizeMac(mac: String?): String? {
        val hex = mac?.filter { it != ':' && it != '-' }?.uppercase() ?: return null
        if (hex.length != 12 || !hex.all { it in '0'..'9' || it in 'A'..'F' } || hex.all { it == '0' }) {
            return null
        }
        return hex.chunked(2).joinToString(":")
    }

    /** Fixed MAC of slot [slot] (1..10, key slotN_mac), null without one. */
    fun slotMac(slot: Int): String? =
        if (slot in 1..slotCount) normalizeMac(values["slot${slot}_mac"]) else null

    /**
     * Display name that is bound to the sensor [mac]: the slotN_name of the slot whose fixed MAC (slotN_mac)
     * equals [mac], whatever slot the sensor sits in. Null for an unknown MAC or a fixed MAC without a name.
     */
    fun nameForMac(mac: String?): String? {
        val wanted = normalizeMac(mac) ?: return null
        for (slot in 1..slotCount) {
            if (slotMac(slot) == wanted) {
                return values["slot${slot}_name"]?.takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    /**
     * Title of tile [index] (0 = onboard, 1..10 = registry slot). A slot tile shows the name of the sensor
     * with [activeMac]; the telemetry frame carries no MAC, so the default is the fixed MAC of the slot.
     * A slot without a known MAC shows the default title ("Slot N"), never the slotN_name of that index.
     */
    fun tileName(index: Int, activeMac: String? = slotMac(index)): String {
        if (index == 0) {
            return values["onboard_name"]?.takeIf { it.isNotBlank() } ?: TelemetryFrame.DEFAULT_TILE_NAMES[0]
        }
        if (index in 1..slotCount) {
            return nameForMac(activeMac) ?: TelemetryFrame.DEFAULT_TILE_NAMES[index]
        }
        return "Tile $index"
    }

    val tileNames: List<String>
        get() = (0..slotCount).map { tileName(it) }

    companion object {
        const val CONFIG_FILE_NAME = "config.ini"

        fun parse(reader: BufferedReader): PanelConfig {
            val map = mutableMapOf<String, String>()
            reader.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith(";")) {
                    val eqIndex = trimmed.indexOf('=')
                    if (eqIndex > 0) {
                        val key = trimmed.substring(0, eqIndex).trim()
                        val value = trimmed.substring(eqIndex + 1).trim()
                        if (key.isNotEmpty() && value.isNotEmpty()) {
                            map[key] = value
                        }
                    }
                }
            }
            return PanelConfig(map)
        }

        fun parseText(text: String): PanelConfig =
            parse(text.reader().buffered())

        fun load(context: Context): PanelConfig {
            // 1. External files directory (e.g. user placed custom config.ini)
            try {
                val externalFile = File(context.getExternalFilesDir(null), CONFIG_FILE_NAME)
                if (externalFile.exists() && externalFile.canRead()) {
                    externalFile.bufferedReader().use { return parse(it) }
                }
            } catch (_: Exception) {}

            // 2. Bundled assets (packaged from docs/config.ini)
            try {
                context.assets.open(CONFIG_FILE_NAME).bufferedReader().use { return parse(it) }
            } catch (_: Exception) {}

            return PanelConfig()
        }
    }
}
