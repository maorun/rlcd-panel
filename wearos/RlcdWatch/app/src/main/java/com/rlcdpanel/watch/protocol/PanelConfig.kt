package com.rlcdpanel.watch.protocol

import android.content.Context
import java.io.BufferedReader
import java.io.File

/**
 * Parsed configuration from config.ini (MicroSD card format).
 */
data class PanelConfig(
    val values: Map<String, String> = emptyMap()
) {
    val tileNames: List<String>
        get() = listOf(
            values["onboard_name"]?.takeIf { it.isNotBlank() } ?: TelemetryFrame.DEFAULT_TILE_NAMES[0],
            values["slot1_name"]?.takeIf { it.isNotBlank() } ?: TelemetryFrame.DEFAULT_TILE_NAMES[1],
            values["slot2_name"]?.takeIf { it.isNotBlank() } ?: TelemetryFrame.DEFAULT_TILE_NAMES[2],
            values["slot3_name"]?.takeIf { it.isNotBlank() } ?: TelemetryFrame.DEFAULT_TILE_NAMES[3],
            values["slot4_name"]?.takeIf { it.isNotBlank() } ?: TelemetryFrame.DEFAULT_TILE_NAMES[4],
            values["slot5_name"]?.takeIf { it.isNotBlank() } ?: TelemetryFrame.DEFAULT_TILE_NAMES[5]
        )

    fun tileName(index: Int): String {
        val names = tileNames
        return if (index in names.indices) names[index] else "Tile $index"
    }

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
