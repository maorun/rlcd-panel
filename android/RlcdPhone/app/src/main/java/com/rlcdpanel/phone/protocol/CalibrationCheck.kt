package com.rlcdpanel.phone.protocol

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * One applied offset proposal that the user can check later. Same rules as `CalibrationCheckRecord` of the macOS app.
 * [appliedAtMs] is the time of the apply in milliseconds since 1970.
 */
data class CalibrationCheckRecord(
    /** `onboard` or `slot<N>` (N 1 to 10). */
    val sensor: String,
    val source: Source,
    /** `CALIB` reference sensor 0 to 10, `SPREAD` window 6 to 24 hours, `SALT` sensor 0 to 10. */
    val argument: Int,
    val changes: List<Change>,
    val appliedAtMs: Long
) {
    enum class Source { CALIB, SPREAD, SALT }

    data class Change(val key: String, val old: String, val new: String)

    /** The console line that the check sends again; null for an argument outside the command range. */
    val commandLine: String?
        get() = try {
            when (source) {
                Source.CALIB -> "CALIB $argument".takeIf { argument in 0..SaltCommand.MAX_SENSOR }
                Source.SPREAD -> SpreadCommand.line(argument)
                Source.SALT -> SaltCommand.line(argument, false)
            }
        } catch (e: IllegalArgumentException) {
            null
        }

    /**
     * Hours to wait before the check. Every sample of the mean must come from after the apply, plus one hour because
     * the first new sample can land up to one hour later: `SALT` reads the newest 6 samples (7 h), `SPREAD` the
     * newest [argument] samples ([argument] + 1 h) and `CALIB` the whole common history of up to 24 samples (25 h).
     */
    val waitHours: Int
        get() = when (source) {
            Source.SALT -> 7
            Source.SPREAD -> maxOf(6, argument) + 1
            Source.CALIB -> 25
        }

    /** True when a changed key is a temperature offset; the check judges only the values that the apply changed. */
    val changesTemperature: Boolean get() = changes.any { it.key.endsWith("temp_offset_c") }

    /** True when a changed key is a humidity offset. */
    val changesHumidity: Boolean get() = changes.any { it.key.endsWith("rh_offset_pct") }

    /** A record is usable with a known sensor, a command that accepts the argument and at least one change. */
    val isValid: Boolean
        get() = sensorNumber(sensor) != null && commandLine != null && changes.isNotEmpty() &&
            changes.all { it.key.isNotEmpty() && it.new.isNotEmpty() }

    companion object {
        /** 0 for `onboard`, N for `slot<N>`; null for any other text. */
        fun sensorNumber(name: String): Int? {
            if (name == "onboard") return 0
            if (!name.startsWith("slot")) return null
            val n = CalibrationReply.hours(name.substring(4)) ?: return null
            return if (n in 1L..10L) n.toInt() else null
        }

        /** `temp_offset_c` and `rh_offset_pct` belong to `onboard`, `slotN_...` to `slotN`; null for another key. */
        fun sensorName(key: String): String? {
            for (suffix in listOf("temp_offset_c", "rh_offset_pct")) {
                if (!key.endsWith(suffix)) continue
                val prefix = key.dropLast(suffix.length)
                if (prefix.isEmpty()) return "onboard"
                if (!prefix.endsWith("_")) return null
                val name = prefix.dropLast(1)
                return name.takeIf { it != "onboard" && sensorNumber(it) != null }
            }
            return null
        }
    }
}

/** Verdict of a check. */
sealed class CalibrationCheckResult {
    object Good : CalibrationCheckResult()

    /** Deviations in C and %RH; null where the command does not measure the value (`SALT` has humidity only). */
    data class NotGood(val temp: Double?, val rh: Double?) : CalibrationCheckResult()

    data class TooEarly(val hours: Int) : CalibrationCheckResult()

    data class Unavailable(val reason: String) : CalibrationCheckResult()

    val text: String
        get() = when (this) {
            Good -> "Good"
            is NotGood -> "Not good: " + listOfNotNull(
                temp?.let { String.format(java.util.Locale.ROOT, "%+.1f °C", it) },
                rh?.let { String.format(java.util.Locale.ROOT, "%+.1f %%RH", it) }
            ).joinToString(" / ")
            is TooEarly -> "Too early: $hours h"
            is Unavailable -> reason
        }
}

object CalibrationCheck {
    /** Limits of a good result in tenths: 0.2 C and 1.0 %RH. A deviation exactly at the limit is good. */
    const val TEMP_LIMIT_TENTHS = 2L
    const val RH_LIMIT_TENTHS = 10L

    private const val HOUR_MS = 3_600_000L

    /** Whole hours still to wait (rounded up, 1 to `record.waitHours`), or null when the check may run. A clock that ran back gives the full wait. */
    fun hoursToWait(record: CalibrationCheckRecord, nowMs: Long): Int? {
        val wait = record.waitHours
        val elapsed = nowMs - record.appliedAtMs
        if (elapsed >= wait * HOUR_MS) return null
        if (elapsed < 0) return wait
        val left = wait * HOUR_MS - elapsed
        return ((left + HOUR_MS - 1) / HOUR_MS).toInt().coerceIn(1, wait)
    }

    private fun tenths(value: Double): Long = (abs(value) * 10).roundToLong()

    /** Verdict of the deviations; a null value is not measured and does not count. */
    fun verdict(temp: Double?, rh: Double?): CalibrationCheckResult {
        val tempOk = temp == null || tenths(temp) <= TEMP_LIMIT_TENTHS
        val rhOk = rh == null || tenths(rh) <= RH_LIMIT_TENTHS
        return if (tempOk && rhOk) CalibrationCheckResult.Good else CalibrationCheckResult.NotGood(temp, rh)
    }

    /** Compares the new reply of the record's command with the limits. */
    fun evaluate(
        record: CalibrationCheckRecord,
        nowMs: Long,
        calib: CalibrationReply? = null,
        spread: SpreadReply? = null,
        salt: SaltReply? = null
    ): CalibrationCheckResult {
        hoursToWait(record, nowMs)?.let { return CalibrationCheckResult.TooEarly(it) }
        return when (record.source) {
            CalibrationCheckRecord.Source.CALIB -> {
                if (calib == null) return CalibrationCheckResult.Unavailable("No CALIB reply")
                val row = calib.sensors.firstOrNull { it.name == record.sensor }
                    ?: return CalibrationCheckResult.Unavailable("${record.sensor} is not in the CALIB reply")
                verdict(if (record.changesTemperature) row.tempDiff else null, if (record.changesHumidity) row.rhDiff else null)
            }
            CalibrationCheckRecord.Source.SPREAD -> {
                if (spread == null) return CalibrationCheckResult.Unavailable("No SPREAD reply")
                when (val o = spread.outcome) {
                    is SpreadReply.Outcome.Skipped ->
                        CalibrationCheckResult.Unavailable("Only ${o.sensors} sensors have data (3 needed)")
                    is SpreadReply.Outcome.Measured -> {
                        val row = o.rows.firstOrNull { it.name == record.sensor }
                            ?: return CalibrationCheckResult.Unavailable("${record.sensor} is not in the SPREAD reply")
                        verdict(if (record.changesTemperature) row.tempDev else null, if (record.changesHumidity) row.rhDev else null)
                    }
                }
            }
            CalibrationCheckRecord.Source.SALT -> {
                if (salt == null) return CalibrationCheckResult.Unavailable("No SALT reply")
                when (val o = salt.outcome) {
                    is SaltReply.Outcome.Measured ->
                        if (o.measurement.name != record.sensor) {
                            CalibrationCheckResult.Unavailable("The SALT reply is for ${o.measurement.name}")
                        } else {
                            verdict(null, o.measurement.rhMean - o.measurement.reference)
                        }
                    is SaltReply.Outcome.Skipped ->
                        CalibrationCheckResult.Unavailable("${o.name}: ${o.hours} h, too few samples (6 needed)")
                    is SaltReply.Outcome.Unstable ->
                        CalibrationCheckResult.Unavailable("${o.name}: humidity span ${String.format(java.util.Locale.ROOT, "%.1f", o.span)} %RH is above 1.0 %RH")
                }
            }
        }
    }
}

/** Minimal text store, so [CalibrationCheckStore] runs in host unit tests (`SharedPreferences` in the app). */
interface CheckPrefs {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

/**
 * Keeps one record for each sensor in one text value. A corrupt line gives no record; a record that fails the
 * checks is dropped. The clock is injected.
 */
class CalibrationCheckStore(private val prefs: CheckPrefs, private val clock: () -> Long) {
    fun nowMs(): Long = clock()

    /** All stored records, sorted by sensor name. */
    fun records(): List<CalibrationCheckRecord> {
        val text = prefs.getString(KEY) ?: return emptyList()
        val byName = linkedMapOf<String, CalibrationCheckRecord>()
        for (line in text.split('\n')) {
            val record = parse(line) ?: continue
            byName[record.sensor] = record
        }
        return byName.values.sortedBy { it.sensor }
    }

    fun record(sensor: String): CalibrationCheckRecord? = records().firstOrNull { it.sensor == sensor }

    /** Stores the record and replaces the record of the same sensor. */
    fun save(record: CalibrationCheckRecord) {
        if (!record.isValid) return
        write(records().filter { it.sensor != record.sensor } + record)
    }

    /** One record for each sensor of the applied changes. [old] are the active values before the apply. */
    fun recordApplied(
        source: CalibrationCheckRecord.Source,
        argument: Int,
        applied: List<CalibrationProposal>,
        old: Map<String, String>
    ) {
        val bySensor = linkedMapOf<String, MutableList<CalibrationCheckRecord.Change>>()
        for (p in applied) {
            val sensor = CalibrationCheckRecord.sensorName(p.key) ?: continue
            // The cached config can be behind the panel after an earlier apply; the stored new value is newer.
            val before = record(sensor)?.changes?.firstOrNull { it.key == p.key }?.new ?: old[p.key].orEmpty()
            bySensor.getOrPut(sensor) { mutableListOf() }.add(CalibrationCheckRecord.Change(p.key, before, p.value))
        }
        for ((sensor, changes) in bySensor) save(CalibrationCheckRecord(sensor, source, argument, changes, clock()))
    }

    private fun write(all: List<CalibrationCheckRecord>) {
        prefs.putString(KEY, all.takeIf { it.isNotEmpty() }?.joinToString("\n") { format(it) })
    }

    companion object {
        const val KEY = "calibration_check_records"
        const val PREFS_NAME = "rlcd_calibration_check"

        // Fields are separated by a tab, changes by `;` and the parts of a change by `,`. Keys and values never hold these characters.
        private fun format(r: CalibrationCheckRecord): String =
            listOf(r.sensor, r.source.name, r.argument.toString(), r.appliedAtMs.toString(),
                r.changes.joinToString(";") { "${it.key},${it.old},${it.new}" }).joinToString("\t")

        internal fun parse(line: String): CalibrationCheckRecord? {
            val f = line.split('\t')
            if (f.size != 5) return null
            val source = CalibrationCheckRecord.Source.values().firstOrNull { it.name == f[1] } ?: return null
            val argument = f[2].toIntOrNull() ?: return null
            val at = f[3].toLongOrNull() ?: return null
            val changes = f[4].split(';').map { part ->
                val p = part.split(',')
                if (p.size != 3 || p[0].isEmpty() || p[2].isEmpty()) return null
                CalibrationCheckRecord.Change(p[0], p[1], p[2])
            }
            return CalibrationCheckRecord(f[0], source, argument, changes, at).takeIf { it.isValid }
        }
    }
}
