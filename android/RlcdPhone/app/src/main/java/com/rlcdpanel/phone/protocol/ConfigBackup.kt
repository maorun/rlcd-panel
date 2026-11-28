package com.rlcdpanel.phone.protocol

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Value rule of one `config.ini` key. The rules follow `DeviceConfigParser` and `ConfigSchema` of the macOS app. */
sealed class ConfigKind {
    /** Whole number in [min]..[max]; [off] is one more accepted value. */
    data class Integer(val min: Int, val max: Int, val off: Int? = null) : ConfigKind()
    data class Decimal(val min: Double, val max: Double, val off: Double? = null) : ConfigKind()
    data class Enumeration(val values: List<String>) : ConfigKind()
    data class BitMask(val bits: Int) : ConfigKind()
    /** 1 to 12 characters of A-Z, 0-9, space and `-`. */
    object Name : ConfigKind()
    /** `AA:BB:CC:DD:EE:FF`, not all zero, or empty. */
    object Mac : ConfigKind()
}

data class ConfigEntry(val key: String, val kind: ConfigKind, val defaultValue: String)

/** One difference between the current values and a backup. */
data class ConfigChange(val key: String, val old: String, val new: String) {
    val text: String get() = "$key: ${old.ifEmpty { "(empty)" }} -> ${new.ifEmpty { "(empty)" }}"
}

/** One line of a backup file that the parser rejects. */
data class ConfigBackupRejectedLine(val line: Int, val key: String?, val reason: String) {
    val text: String get() = "line $line: $reason"
}

class ConfigBackupException(val reason: String) : Exception(reason)

/**
 * Key schema of `config.ini`. The list order is the fixed order of the batch `SET` and equals the `ConfigSchema.entries`
 * order of the macOS app. `ble_console` stands last: a lower level makes the console read-only, so no `SET` may follow it.
 */
object ConfigKeys {
    const val MAX_LINE_BYTES = 40

    private fun int(key: String, min: Int, max: Int, def: String, off: Int? = null) =
        ConfigEntry(key, ConfigKind.Integer(min, max, off), def)

    private fun dec(key: String, min: Double, max: Double, def: String, off: Double? = null) =
        ConfigEntry(key, ConfigKind.Decimal(min, max, off), def)

    val entries: List<ConfigEntry> = buildList {
        add(dec("temp_offset_c", -10.0, 10.0, "-1.9"))
        add(dec("usb_temp_offset_c", -5.0, 0.0, "0.0"))
        add(dec("rh_offset_pct", -10.0, 10.0, "0.0"))
        add(dec("rh_gain", 0.9, 1.1, "1.0"))
        add(ConfigEntry("temp_unit", ConfigKind.Enumeration(listOf("C", "F")), "C"))
        add(int("display_invert", 0, 1, "0"))
        add(dec("freeze_alert_limit_c", -20.0, 15.0, "0.0"))
        add(int("mold_alert_limit_rh", 40, 95, "70"))
        add(int("mold_alert_delay_h", 1, 12, "0", off = 0))
        add(int("reception_low_pct", 30, 80, "50", off = 0))
        add(int("dry_alert_delay_h", 1, 12, "0", off = 0))
        add(dec("jump_limit_c", 3.0, 20.0, "8.0", off = 0.0))
        add(int("jump_limit_rh", 10, 50, "25", off = 0))
        add(dec("drift_limit_c", 0.3, 2.0, "0.5"))
        add(dec("drift_limit_rh", 2.0, 10.0, "3.0"))
        add(int("heat_alert_delay_h", 1, 12, "0", off = 0))
        add(int("freeze_alert_delay_h", 1, 12, "0", off = 0))
        add(int("stuck_alert_h", 6, 48, "0", off = 0))
        add(ConfigEntry("boot_display_mode", ConfigKind.Enumeration(listOf("0", "1", "2", "4", "5")), "0"))
        add(int("heat_alert_limit_c", 27, 45, "0", off = 0))
        add(int("dry_alert_limit_rh", 20, 45, "0", off = 0))
        add(dec("window_drop_c", 1.0, 5.0, "0", off = 0.0))
        add(int("wall_frsi_pct", 50, 95, "0", off = 0))
        add(int("ventilate_limit_rh", 50, 80, "60"))
        add(int("noise_alert_limit_db", 40, 100, "0", off = 0))
        add(ConfigEntry("chime_alert_mask", ConfigKind.BitMask(8), "255"))
        add(int("chime_volume_pct", 0, 100, "30"))
        add(int("chime_repeat_h", 0, 24, "0"))
        add(int("quiet_hours_start_hour", 0, 23, "22"))
        add(int("quiet_hours_end_hour", 0, 23, "7"))
        add(ConfigEntry("climate_alert_tiles", ConfigKind.BitMask(11), "2047"))
        add(int("sensor_battery_limit_pct", 0, 50, "10"))
        add(int("wake_interval_s", 30, 900, "60"))
        add(int("night_wake_interval_s", 60, 1800, "0", off = 0))
        add(int("battery_offset_mv", -300, 300, "0"))
        add(int("sensor_stale_min", 5, 240, "30"))
        add(int("slot_release_h", 0, 168, "0"))
        add(int("ntp_sync_interval_h", 6, 168, "24"))
        add(int("log_retention_months", 0, 120, "0"))
        add(dec("mic_spl_offset_db", -20.0, 20.0, "0.0"))
        add(int("outdoor_slot", 0, 10, "0"))
        add(int("door_ref_slot", 0, 10, "0"))
        for (n in 1..10) add(dec("slot${n}_temp_offset_c", -5.0, 5.0, "0.0"))
        for (n in 1..10) add(dec("slot${n}_rh_offset_pct", -10.0, 10.0, "0.0"))
        for (n in 1..10) add(dec("slot${n}_rh_gain", 0.9, 1.1, "1.0"))
        add(ConfigEntry("onboard_name", ConfigKind.Name, ""))
        for (n in 1..10) add(ConfigEntry("slot${n}_name", ConfigKind.Name, ""))
        for (n in 1..10) add(ConfigEntry("slot${n}_mac", ConfigKind.Mac, ""))
        add(int("slot_auto_assign", 0, 1, "1"))
        add(int("auto_assign_min_rssi", -100, -40, "0", off = 0))
        for (n in 1..10) add(int("slot${n}_enabled", 0, 1, "1"))
        add(int("ble_broadcast_enabled", 0, 1, "1"))
        add(int("ble_console", 0, 2, "0"))
    }

    val keys: List<String> = entries.map { it.key }
    private val byKey: Map<String, ConfigEntry> = entries.associateBy { it.key }
    private val order: Map<String, Int> = keys.withIndex().associate { it.value to it.index }
    private val emptyAllowed = Regex("^slot([1-9]|10)_mac$")

    fun entry(key: String): ConfigEntry? = byKey[key]

    private fun isMac(s: String): Boolean =
        s.length == 17 && s.indices.all { i ->
            if (i % 3 == 2) s[i] == ':' else s[i] in '0'..'9' || s[i] in 'a'..'f' || s[i] in 'A'..'F'
        }

    private fun isName(s: String): Boolean =
        s.length in 1..12 && s.first() != ' ' && s.last() != ' ' &&
            s.all { it in 'A'..'Z' || it in '0'..'9' || it == ' ' || it == '-' }

    private fun fmt(d: Double): String = if (d == Math.rint(d)) d.toLong().toString() else d.toString()

    /** Error text for a value that `DeviceConfigParser` rejects; null for a valid value. */
    fun validate(key: String, value: String): String? {
        val entry = byKey[key] ?: return "Unknown key"
        return when (val kind = entry.kind) {
            is ConfigKind.Integer -> validateInteger(value, kind.min, kind.max, kind.off)
            is ConfigKind.BitMask -> validateInteger(value, 0, (1 shl kind.bits) - 1, null)
            is ConfigKind.Decimal -> {
                val v = CalibrationKeys.parseNumber(value) ?: return "Not a number"
                if (v in kind.min..kind.max || (kind.off != null && v == kind.off)) null
                else {
                    val range = "${fmt(kind.min)} to ${fmt(kind.max)}"
                    "Allowed: " + (kind.off?.let { "${fmt(it)} or $range" } ?: range)
                }
            }
            is ConfigKind.Enumeration -> if (value in kind.values) null else "Allowed: " + kind.values.joinToString(", ")
            ConfigKind.Name -> if (isName(value)) null else "1 to 12 characters of A-Z, 0-9, space and -"
            ConfigKind.Mac -> when {
                value.isEmpty() -> null
                !isMac(value) -> "Format AA:BB:CC:DD:EE:FF"
                value.uppercase() == "00:00:00:00:00:00" -> "MAC must not be all zero"
                else -> null
            }
        }
    }

    private fun validateInteger(value: String, lo: Int, hi: Int, off: Int?): String? {
        val v = CalibrationKeys.parseNumber(value) ?: return "Not a number"
        if (v != Math.rint(v)) return "Whole number needed"
        val i = v.toInt()
        if (i in lo..hi || (off != null && i == off)) return null
        return "Allowed: " + (off?.let { "$it or $lo to $hi" } ?: "$lo to $hi")
    }

    private fun current(key: String, values: Map<String, String>): String = values[key] ?: byKey[key]?.defaultValue ?: ""

    private fun same(entry: ConfigEntry, a: String, b: String): Boolean = when (entry.kind) {
        is ConfigKind.Integer, is ConfigKind.Decimal, is ConfigKind.BitMask -> {
            val x = CalibrationKeys.parseNumber(a)
            val y = CalibrationKeys.parseNumber(b)
            if (x != null && y != null) x == y else a == b
        }
        ConfigKind.Mac -> a.uppercase() == b.uppercase()
        else -> a == b
    }

    /** Differences from [old] to [new] in schema order. Keys outside the schema are ignored. */
    fun diff(old: Map<String, String>, new: Map<String, String>): List<ConfigChange> =
        entries.mapNotNull { e ->
            val o = current(e.key, old)
            val n = current(e.key, new)
            if (same(e, o, n)) null else ConfigChange(e.key, o, n)
        }

    /** True when the console can take `SET key=value` (empty value only for `slotN_mac`, no white space, 40 bytes at most). */
    fun isSendable(key: String, value: String): Boolean {
        if (key.isEmpty() || '=' in key) return false
        if (value.isEmpty() && !emptyAllowed.matches(key)) return false
        val bad = { s: String -> s.any { it.isWhitespace() || it.isISOControl() } }
        return !bad(key) && !bad(value) && "SET $key=$value".toByteArray(Charsets.UTF_8).size <= MAX_LINE_BYTES
    }

    /** Problem text by key for the changes. [allValues] are the current values (duplicate MAC replay in batch order). */
    fun problems(changes: List<ConfigChange>, allValues: Map<String, String> = emptyMap()): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (c in changes) {
            val err = validate(c.key, c.new)
            when {
                err != null -> out[c.key] = err
                !isSendable(c.key, c.new) -> out[c.key] = when {
                    ' ' in c.new -> "A space in the value is not supported over the console"
                    c.new.isEmpty() -> "Empty value not supported"
                    else -> "Command line not valid or above $MAX_LINE_BYTES bytes"
                }
            }
        }
        val macs = (1..10).associate { "slot${it}_mac" to (allValues["slot${it}_mac"] ?: "").uppercase() }.toMutableMap()
        for (n in 1..10) {
            val key = "slot${n}_mac"
            val c = changes.firstOrNull { it.key == key } ?: continue
            val value = c.new.uppercase()
            if (value.isNotEmpty() && validate(key, value) == null && macs.any { it.key != key && it.value == value }) {
                out[key] = "Another slot has this MAC. Clear the other slot first."
            }
            macs[key] = value
        }
        return out
    }

    /** `SET` proposals for the changes in the fixed schema order (`ble_console` last); keys outside the schema drop out. */
    fun batch(changes: List<ConfigChange>): List<CalibrationProposal> =
        changes.filter { order[it.key] != null }
            .sortedBy { order[it.key] }
            .map { CalibrationProposal(it.key, it.new) }
}

/** A parsed backup file: valid values of known keys and the rejected lines. */
data class ConfigBackupParsed(val values: Map<String, String>, val rejected: List<ConfigBackupRejectedLine>) {
    /** Changes against the current values, in schema order. A key absent from the file stays as it is. */
    fun changes(current: Map<String, String>): List<ConfigChange> = ConfigKeys.diff(current, current + values)
}

/**
 * Backup and restore of `config.ini`, same file format as `ConfigBackup` of the macOS app: each schema key once, in
 * schema order, `key=value`, LF line ends. The file holds schema keys only, so it never holds Wi-Fi credentials.
 */
object ConfigBackup {
    /** `DeviceConfigParser::MAX_FILE_SIZE` of the firmware. */
    const val MAX_BYTES = 8192

    /** File name that the share sheet offers. */
    const val FILE_NAME = "config.ini"

    /** Text of a backup. A key that [values] lacks gets the schema default; an invalid value (an empty name) is a comment `# key=`. */
    fun render(values: Map<String, String>): String = buildString {
        for (e in ConfigKeys.entries) {
            val value = values[e.key] ?: e.defaultValue
            if (ConfigKeys.validate(e.key, value) != null) append("# ")
            append(e.key).append('=').append(value).append('\n')
        }
    }

    private fun trimmed(s: String): String = s.trim { it == ' ' || it == '\t' || it == '\r' || it == '\n' }

    /** Parses a backup with the line rules of `DeviceConfigParser`: blank, `#` and `;` lines skipped, unknown keys ignored. */
    fun parse(data: ByteArray): ConfigBackupParsed {
        if (data.size > MAX_BYTES) throw ConfigBackupException("File above $MAX_BYTES bytes")
        val hasBom = data.size >= 3 && data[0] == 0xEF.toByte() && data[1] == 0xBB.toByte() && data[2] == 0xBF.toByte()
        val body = if (hasBom) data.copyOfRange(3, data.size) else data
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body)).toString()
        } catch (_: CharacterCodingException) {
            throw ConfigBackupException("File is not UTF-8 text")
        }
        val values = linkedMapOf<String, String>()
        val rejected = mutableListOf<ConfigBackupRejectedLine>()
        text.split('\n').forEachIndexed { i, raw ->
            val line = trimmed(raw)
            val number = i + 1
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) return@forEachIndexed
            val eq = line.indexOf('=')
            if (eq < 0) {
                rejected.add(ConfigBackupRejectedLine(number, null, "No key=value"))
                return@forEachIndexed
            }
            val key = trimmed(line.substring(0, eq))
            val value = trimmed(line.substring(eq + 1))
            if (ConfigKeys.entry(key) == null) return@forEachIndexed // unknown keys are no errors
            val err = ConfigKeys.validate(key, value)
            if (err != null) rejected.add(ConfigBackupRejectedLine(number, key, "$key: $err")) else values[key] = value
        }
        return ConfigBackupParsed(values, rejected)
    }
}

/** The review of a restore: the changes, the problems and the `SET` list in batch order. */
data class ConfigRestorePlan(
    val changes: List<ConfigChange>,
    val problems: Map<String, String>,
    val rejected: List<ConfigBackupRejectedLine>,
    /** `SET` proposals in macOS batch order. Run them with [CalibrationBatch]; the first `ERR` stops the rest. */
    val proposals: List<CalibrationProposal>
) {
    /** A rejected line, a problem, no change or a console below level 2 blocks the restore. */
    fun canRestore(consoleWritable: Boolean): Boolean =
        consoleWritable && rejected.isEmpty() && problems.isEmpty() && proposals.isNotEmpty()

    companion object {
        fun build(parsed: ConfigBackupParsed, current: Map<String, String>): ConfigRestorePlan {
            val changes = parsed.changes(current)
            return ConfigRestorePlan(changes, ConfigKeys.problems(changes, current), parsed.rejected, ConfigKeys.batch(changes))
        }
    }
}
