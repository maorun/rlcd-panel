package com.rlcdpanel.phone.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * One stored telemetry frame. Temperature is always in Celsius; a missing value is `null`.
 */
data class HistoryRow(
    val receivedAtMillis: Long,
    val sequence: Int,
    val cellPercent: Int?,
    val alerts: Int,
    val tiles: List<TelemetryTile>
)

/** One point of a 24-hour chart. `value` is `null` for a gap. */
data class HistoryPoint(val timeMillis: Long, val value: Double?)

enum class HistoryMetric { TEMPERATURE, HUMIDITY }

/** Curves of several tiles with one axis range. `min` and `max` are `null` without a value. */
data class ComparePoints(val series: Map<Int, List<HistoryPoint>>, val min: Double?, val max: Double?)

/**
 * Rows of the received telemetry frames, oldest first. The history is immutable: `add` returns a new one.
 */
class TelemetryHistory(val rows: List<HistoryRow> = emptyList()) {

    /**
     * Adds a row for a frame with a new sequence number. A row with the sequence of the last row adds nothing.
     * Rows older than 7 days (measured from the new row) and rows beyond 20,000 drop out, oldest first.
     */
    fun add(row: HistoryRow): TelemetryHistory {
        if (rows.lastOrNull()?.sequence == row.sequence) return this
        val oldest = row.receivedAtMillis - MAX_AGE_MILLIS
        val kept = rows.dropWhile { it.receivedAtMillis < oldest } + row
        return TelemetryHistory(if (kept.size > MAX_ROWS) kept.subList(kept.size - MAX_ROWS, kept.size).toList() else kept)
    }

    fun addFrame(frame: TelemetryFrame, receivedAtMillis: Long): TelemetryHistory = add(
        HistoryRow(
            receivedAtMillis = receivedAtMillis,
            sequence = frame.sequence,
            cellPercent = frame.cellPercent,
            alerts = frame.alerts.rawValue,
            tiles = List(TelemetryFrame.TILE_COUNT) { i -> frame.tiles.getOrNull(i) ?: TelemetryTile(null, null) }
        )
    )

    /** Points of one tile in the 24 hours before `nowMillis`, oldest first. */
    fun windowPoints(nowMillis: Long, tileIndex: Int, metric: HistoryMetric): List<HistoryPoint> {
        val from = nowMillis - WINDOW_MILLIS
        return rows.filter { it.receivedAtMillis in from..nowMillis }.map { r ->
            val tile = r.tiles.getOrNull(tileIndex)
            HistoryPoint(
                r.receivedAtMillis,
                when (metric) {
                    HistoryMetric.TEMPERATURE -> tile?.temperatureC
                    HistoryMetric.HUMIDITY -> tile?.humidityPct
                }
            )
        }
    }

    /**
     * `windowPoints` of each tile with at least one value in the window, ascending by tile index. A tile without a
     * value has no entry. `min` and `max` span the values of all listed tiles, so the curves share one axis.
     */
    fun comparePoints(nowMillis: Long, metric: HistoryMetric): ComparePoints {
        val series = (0 until TelemetryFrame.TILE_COUNT).associateWith { windowPoints(nowMillis, it, metric) }
            .filterValues { points -> points.any { it.value != null } }
        val values = series.values.flatMap { points -> points.mapNotNull { it.value } }
        return ComparePoints(series, values.minOrNull(), values.maxOrNull())
    }

    /**
     * CSV text with the columns of `CsvExport.telemetry` of the macOS app. A missing value gives an empty field.
     * `maxRows` keeps the newest rows only.
     */
    fun toCsv(maxRows: Int = Int.MAX_VALUE): String {
        val tileColumns = (0 until TelemetryFrame.TILE_COUNT).flatMap { listOf("t${it}_c", "rh${it}_pct") }
        val out = StringBuilder((listOf("time", "sequence", "cell_pct", "alerts") + tileColumns).joinToString(","))
        out.append('\n')
        for (r in rows.takeLast(maxRows)) {
            val fields = mutableListOf(
                Instant.ofEpochMilli(r.receivedAtMillis).truncatedTo(ChronoUnit.SECONDS).toString(),
                r.sequence.toString(),
                r.cellPercent?.toString() ?: "",
                r.alerts.toString()
            )
            for (i in 0 until TelemetryFrame.TILE_COUNT) {
                val tile = r.tiles.getOrNull(i)
                fields.add(number(tile?.temperatureC, 2))
                fields.add(number(tile?.humidityPct, 1))
            }
            out.append(fields.joinToString(",")).append('\n')
        }
        return out.toString()
    }

    fun toJson(): String {
        val array = JSONArray()
        for (r in rows) {
            val tiles = JSONArray()
            for (t in r.tiles) {
                tiles.put(JSONArray().put(t.temperatureC ?: JSONObject.NULL).put(t.humidityPct ?: JSONObject.NULL))
            }
            array.put(
                JSONObject()
                    .put("t", r.receivedAtMillis)
                    .put("s", r.sequence)
                    .put("c", r.cellPercent ?: JSONObject.NULL)
                    .put("a", r.alerts)
                    .put("tiles", tiles)
            )
        }
        return JSONObject().put("version", JSON_VERSION).put("rows", array).toString()
    }

    companion object {
        const val MAX_ROWS = 20_000
        const val MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000
        const val WINDOW_MILLIS = 24L * 60 * 60 * 1000
        const val JSON_VERSION = 1

        private fun number(v: Double?, digits: Int): String =
            v?.let { String.format(Locale.ROOT, "%.${digits}f", it) } ?: ""

        /** Parses `toJson` text. Corrupt text gives an empty history. */
        fun fromJson(text: String): TelemetryHistory = try {
            val root = JSONObject(text)
            if (root.getInt("version") != JSON_VERSION) {
                TelemetryHistory()
            } else {
                val array = root.getJSONArray("rows")
                val rows = (0 until array.length()).map { i ->
                    val o = array.getJSONObject(i)
                    val tiles = o.getJSONArray("tiles")
                    HistoryRow(
                        receivedAtMillis = o.getLong("t"),
                        sequence = o.getInt("s"),
                        cellPercent = if (o.isNull("c")) null else o.getInt("c"),
                        alerts = o.getInt("a"),
                        tiles = (0 until tiles.length()).map { j ->
                            val pair = tiles.getJSONArray(j)
                            TelemetryTile(
                                if (pair.isNull(0)) null else pair.getDouble(0),
                                if (pair.isNull(1)) null else pair.getDouble(1)
                            )
                        }
                    )
                }
                val sorted = rows.sortedBy { it.receivedAtMillis }
                val oldest = (sorted.lastOrNull()?.receivedAtMillis ?: 0L) - MAX_AGE_MILLIS
                TelemetryHistory(sorted.filter { it.receivedAtMillis >= oldest }.takeLast(MAX_ROWS))
            }
        } catch (_: Exception) {
            TelemetryHistory()
        }
    }
}

/**
 * Persists the history in one file. A write goes through `<file>.tmp` and a rename, at most once a minute.
 */
class TelemetryHistoryStore(private val file: File, private val minIntervalMillis: Long = 60_000L) {
    private var lastWriteMillis: Long? = null

    /** A missing or corrupt file gives an empty history. */
    @Synchronized
    fun load(): TelemetryHistory = try {
        TelemetryHistory.fromJson(file.readText())
    } catch (_: Exception) {
        TelemetryHistory()
    }

    /** Writes when the last write is at least one minute old. Returns true after a write. */
    @Synchronized
    fun saveThrottled(history: TelemetryHistory, nowMillis: Long): Boolean {
        val last = lastWriteMillis
        if (last != null && nowMillis - last in 0 until minIntervalMillis) return false
        return save(history, nowMillis)
    }

    /** Writes at once. A failed write keeps the old file. */
    @Synchronized
    fun save(history: TelemetryHistory, nowMillis: Long): Boolean = try {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(history.toJson())
        if (!tmp.renameTo(file)) {
            java.nio.file.Files.move(
                tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        }
        lastWriteMillis = nowMillis
        true
    } catch (_: Exception) {
        false
    }

    companion object {
        const val FILE_NAME = "telemetry.json"
    }
}
