package com.rlcdpanel.watch.protocol

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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

    /** Configured `slotN_name` of slot [slot] (1..10), null without one. */
    fun slotName(slot: Int): String? =
        if (slot in 1..slotCount) values["slot${slot}_name"]?.takeIf { it.isNotBlank() } else null

    /** `slot_auto_assign`: a new MAC takes a free slot (default on). */
    val slotAutoAssign: Boolean get() = values["slot_auto_assign"]?.trim() != "0"

    /** `ble_console` level (0 off, 1 read only, 2 read and write; default 0). */
    val bleConsole: Int get() = values["ble_console"]?.trim()?.toIntOrNull() ?: 0

    /** True only when `ble_console` is 2: the panel accepts `SET` over BLE. */
    val canWrite: Boolean get() = bleConsole == 2

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
     * A slot without a known MAC shows the slotN_name of that index, then the default title ("Slot N").
     */
    fun tileName(index: Int, activeMac: String? = slotMac(index)): String {
        if (index == 0) {
            return values["onboard_name"]?.takeIf { it.isNotBlank() } ?: TelemetryFrame.DEFAULT_TILE_NAMES[0]
        }
        if (index in 1..slotCount) {
            return nameForMac(activeMac) ?: slotName(index) ?: TelemetryFrame.DEFAULT_TILE_NAMES[index]
        }
        return "Tile $index"
    }

    val tileNames: List<String>
        get() = (0..slotCount).map { tileName(it) }

    companion object {
        const val CONFIG_FILE_NAME = "config.ini"
        /** Limit of the firmware (`DeviceConfigParser`): a larger reply is no config. */
        const val MAX_CONFIG_BYTES = 8192
        private val KEY_VALUE = Regex("^[a-z0-9_]{1,40}=[^\\u0000-\\u001f]{0,120}$")

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

        /** Parses the lines of a `CONFIG` reply (CRC-32 already verified by `ReplyAssembler`). */
        fun parseConfigReply(lines: List<String>): ConfigReply {
            val settings = mutableListOf<String>()
            val rejected = mutableListOf<Int>()
            var source: String? = null
            var size = 0
            for (raw in lines) {
                val line = raw.trim()
                size += line.length + 1
                if (size > MAX_CONFIG_BYTES) return ConfigReply("", null, emptyList())
                when {
                    line.startsWith("SOURCE ") -> source = line.removePrefix("SOURCE ").trim()
                    line.startsWith("REJECTED line=") ->
                        line.removePrefix("REJECTED line=").toIntOrNull()?.let { rejected.add(it) }
                    line.startsWith("REJECTED ") -> {}
                    KEY_VALUE.matches(line) -> settings.add(line)
                }
            }
            return ConfigReply(settings.joinToString("\n", postfix = "\n").takeIf { settings.isNotEmpty() } ?: "", source, rejected)
        }

        /** Reads the cache first, then the external file, then the bundled asset. */
        fun loadFrom(cache: File?, external: File?, asset: () -> InputStream?): PanelConfig {
            // 1. Cache of the last successful CONFIG read over the BLE console
            try {
                if (cache != null && cache.isFile && cache.canRead()) {
                    val cached = cache.bufferedReader().use { parse(it) }
                    if (cached.values.isNotEmpty()) return cached
                }
            } catch (_: Exception) {}

            // 2. External files directory (e.g. user placed custom config.ini)
            try {
                if (external != null && external.exists() && external.canRead()) {
                    external.bufferedReader().use { return parse(it) }
                }
            } catch (_: Exception) {}

            // 3. Bundled assets (packaged from docs/config.ini)
            try {
                asset()?.bufferedReader()?.use { return parse(it) }
            } catch (_: Exception) {}

            return PanelConfig()
        }

        fun load(context: Context): PanelConfig = loadFrom(
            cache = ConfigCache.file(context.filesDir),
            external = File(context.getExternalFilesDir(null), CONFIG_FILE_NAME),
            asset = { context.assets.open(CONFIG_FILE_NAME) }
        )
    }
}

/** A parsed `CONFIG` reply: the `key=value` text, the `SOURCE` word and the numbers of the rejected lines. */
data class ConfigReply(val text: String, val source: String?, val rejectedLines: List<Int>)

/** Cache state for the screen: when the panel config was read and which lines the panel rejected. */
data class ConfigCacheInfo(val readAtMillis: Long, val rejectedLines: List<Int>)

/**
 * Cache of the last `CONFIG` reply in `filesDir/config.ini`. A write goes through `config.ini.tmp` and a rename, so a
 * reader sees the old or the new file. Only a [ConsoleReply.Frame] with `SOURCE SD` and at least one setting replaces the cache;
 * `ERR` replies, a bad CRC-32 and a timeout leave it intact. The rejected lines live in the file as `# REJECTED line=N`
 * comments, which the config parser ignores. The read time lives in a `# READ_AT=<ms>` comment.
 */
object ConfigCache {
    private const val REJECTED_PREFIX = "# REJECTED line="
    private const val READ_AT_PREFIX = "# READ_AT="

    fun file(dir: File): File = File(dir, PanelConfig.CONFIG_FILE_NAME)

    /** Stores a good reply. Returns the stored info, or null when [reply] is not a usable config. */
    fun store(dir: File, reply: ConsoleReply, nowMillis: Long): ConfigCacheInfo? {
        val frame = reply as? ConsoleReply.Frame ?: return null
        val parsed = PanelConfig.parseConfigReply(frame.lines)
        // A panel without a card answers with the defaults. They must not replace a good cache.
        if (parsed.text.isEmpty() || parsed.source != "SD") return null
        val body = parsed.text + parsed.rejectedLines.joinToString("") { "$REJECTED_PREFIX$it\n" }
        val tmp = File(dir, PanelConfig.CONFIG_FILE_NAME + ".tmp")
        val target = file(dir)
        return try {
            java.io.FileOutputStream(tmp).use { out ->
                out.write(("$READ_AT_PREFIX$nowMillis\n" + body).toByteArray())
                out.fd.sync()
            }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            ConfigCacheInfo(nowMillis, parsed.rejectedLines)
        } catch (_: Exception) {
            tmp.delete()
            null
        }
    }

    /** Reads the cache info, null without a cache. */
    fun info(dir: File): ConfigCacheInfo? {
        val f = file(dir)
        if (!f.isFile || f.length() > PanelConfig.MAX_CONFIG_BYTES + 1024) return null
        val lines = try {
            f.readLines().map { it.trim() }
        } catch (_: Exception) {
            return null
        }
        val rejected = lines.filter { it.startsWith(REJECTED_PREFIX) }.mapNotNull { it.removePrefix(REJECTED_PREFIX).toIntOrNull() }
        val readAt = lines.firstOrNull { it.startsWith(READ_AT_PREFIX) }?.removePrefix(READ_AT_PREFIX)?.toLongOrNull() ?: f.lastModified()
        return ConfigCacheInfo(readAt, rejected)
    }

    /** Short German age text for the screen. */
    fun ageText(info: ConfigCacheInfo, nowMillis: Long): String {
        val min = ((nowMillis - info.readAtMillis) / 60_000L).coerceAtLeast(0)
        return when {
            min < 1 -> "gerade eben"
            min < 60 -> "vor $min min"
            min < 48 * 60 -> "vor ${min / 60} h"
            else -> "vor ${min / (24 * 60)} Tagen"
        }
    }
}
