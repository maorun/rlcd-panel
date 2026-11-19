package com.rlcdpanel.watch.protocol

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/** Parsed `TIME` reply. [epochMillis] is null when [timeString] is no valid local time. */
data class TimeReply(val timeString: String, val epochMillis: Long?, val valid: Boolean, val osf: Boolean)

/**
 * Local RTC clock conversions for the panel. The panel RTC runs on local time in Europe/Berlin (CET or CEST).
 * Port of `PanelClock.swift` of the macOS app.
 */
object PanelClock {
    val zone: ZoneId = ZoneId.of("Europe/Berlin")
    val validYears = 2025..2099

    private val TEXT = Regex("^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})$")

    /** `YYYY-MM-DDTHH:MM:SS` in Europe/Berlin local time, or null for a year outside 2025 to 2099. */
    fun format(epochMillis: Long): String? {
        val t = try {
            LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone)
        } catch (e: DateTimeException) {
            return null
        }
        if (t.year !in validYears) return null
        return String.format(Locale.ROOT, "%04d-%02d-%02dT%02d:%02d:%02d", t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second)
    }

    /** Epoch milliseconds of a `YYYY-MM-DDTHH:MM:SS` text in Europe/Berlin local time, or null for a bad text. A time in the repeated autumn hour takes the earlier offset. */
    fun parse(text: String): Long? {
        val m = TEXT.matchEntire(text) ?: return null
        val n = m.groupValues.drop(1).map { it.toInt() }
        return try {
            LocalDateTime.of(n[0], n[1], n[2], n[3], n[4], n[5]).atZone(zone).toInstant().toEpochMilli()
        } catch (e: DateTimeException) {
            null
        }
    }

    /** Reads `TIME <YYYY-MM-DDTHH:MM:SS> valid=<0|1> osf=<0|1>` from [lines]. A missing or bad line throws [ParseException]. */
    fun parseTimeReply(lines: List<String>): TimeReply {
        val line = lines.firstOrNull { it.startsWith("TIME ") } ?: throw ParseException(lines.firstOrNull() ?: "TIME")
        val parts = line.split(" ").filter { it.isNotEmpty() }
        if (parts.size != 4 || parts[0] != "TIME") throw ParseException(line)
        val time = parts[1]
        val epoch = parse(time) ?: throw ParseException(line)
        val valid = flag(parts[2], "valid", line)
        val osf = flag(parts[3], "osf", line)
        return TimeReply(time, epoch, valid, osf)
    }

    private fun flag(part: String, key: String, line: String): Boolean = when (part) {
        "$key=1" -> true
        "$key=0" -> false
        else -> throw ParseException(line)
    }

    /** Seconds from the phone time to the panel time. Positive when the panel is ahead. */
    fun offsetSeconds(panelEpochMillis: Long, phoneEpochMillis: Long): Long =
        (panelEpochMillis - phoneEpochMillis) / 1000

    /** True when `TIME SET` works over BLE: `ble_console` 2. */
    fun isTimeSetAllowed(bleConsoleLevel: Int): Boolean = bleConsoleLevel >= 2
}
