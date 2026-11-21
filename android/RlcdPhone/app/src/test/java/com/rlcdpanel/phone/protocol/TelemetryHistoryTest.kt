package com.rlcdpanel.phone.protocol

import com.rlcdpanel.phone.BleScanService
import com.rlcdpanel.phone.HistoryChartGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TelemetryHistoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val t0 = 1_700_000_000_000L

    private fun tiles(temp: Double?, rh: Double?) =
        List(TelemetryFrame.TILE_COUNT) { i -> if (i == 0) TelemetryTile(temp, rh) else TelemetryTile(null, null) }

    private fun row(seq: Int, at: Long, temp: Double? = 21.5, rh: Double? = 45.0, cell: Int? = 80, alerts: Int = 0) =
        HistoryRow(at, seq, cell, alerts, tiles(temp, rh))

    @Test
    fun duplicateSequenceAddsOneRow() {
        val h = TelemetryHistory().add(row(5, t0)).add(row(5, t0 + 1000))
        assertEquals(1, h.rows.size)
        assertEquals(t0, h.rows[0].receivedAtMillis)
    }

    @Test
    fun newSequenceAddsRow() {
        val h = TelemetryHistory().add(row(5, t0)).add(row(6, t0 + 1000))
        assertEquals(listOf(5, 6), h.rows.map { it.sequence })
    }

    @Test
    fun ageLimitIsSevenDays() {
        val day = 24L * 60 * 60 * 1000
        val h = TelemetryHistory()
            .add(row(1, t0))
            .add(row(2, t0 + 3 * day))
            .add(row(3, t0 + 7 * day))
            .add(row(4, t0 + 7 * day + 1))
        // Row 1 is exactly 7 days old at row 3 and stays; it is older than 7 days at row 4.
        assertEquals(listOf(2, 3, 4), h.rows.map { it.sequence })
    }

    @Test
    fun rowLimitIs20000() {
        val full = TelemetryHistory((0 until TelemetryHistory.MAX_ROWS).map { row(it, t0 + it) })
        var h = full
        for (i in TelemetryHistory.MAX_ROWS until TelemetryHistory.MAX_ROWS + 5) h = h.add(row(i, t0 + i))
        assertEquals(TelemetryHistory.MAX_ROWS, h.rows.size)
        assertEquals(5, h.rows.first().sequence)
        assertEquals(TelemetryHistory.MAX_ROWS + 4, h.rows.last().sequence)
    }

    @Test
    fun jsonRoundTrip() {
        val h = TelemetryHistory()
            .add(row(1, t0, temp = 21.5, rh = 45.0, cell = null, alerts = 3))
            .add(row(2, t0 + 1000, temp = null, rh = null))
        val back = TelemetryHistory.fromJson(h.toJson())
        assertEquals(h.rows, back.rows)
    }

    @Test
    fun corruptJsonGivesEmptyHistory() {
        assertTrue(TelemetryHistory.fromJson("{not json").rows.isEmpty())
        assertTrue(TelemetryHistory.fromJson("").rows.isEmpty())
        assertTrue(TelemetryHistory.fromJson("{\"version\":1,\"rows\":[{\"t\":1}]}").rows.isEmpty())
        assertTrue(TelemetryHistory.fromJson("{\"version\":99,\"rows\":[]}").rows.isEmpty())
    }

    @Test
    fun csvHeaderAndRowWithEmptyFieldsForStaleTiles() {
        val h = TelemetryHistory().add(row(7, t0, temp = 21.456, rh = 45.04, cell = null, alerts = 2))
        val lines = h.toCsv().trimEnd('\n').split("\n")
        assertEquals(
            "time,sequence,cell_pct,alerts,t0_c,rh0_pct,t1_c,rh1_pct,t2_c,rh2_pct,t3_c,rh3_pct,t4_c,rh4_pct,t5_c,rh5_pct",
            lines[0]
        )
        assertEquals("2023-11-14T22:13:20Z,7,,2,21.46,45.0,,,,,,,,,,", lines[1])
    }

    @Test
    fun csvMaxRowsKeepsNewest() {
        val h = TelemetryHistory().add(row(1, t0)).add(row(2, t0 + 1000)).add(row(3, t0 + 2000))
        val lines = h.toCsv(2).trimEnd('\n').split("\n")
        assertEquals(3, lines.size)
        assertTrue(lines[1].contains(",2,"))
    }

    @Test
    fun windowHoldsLast24Hours() {
        val hour = 60L * 60 * 1000
        val now = t0 + 30 * hour
        val h = TelemetryHistory()
            .add(row(1, t0 + 5 * hour, temp = 10.0))
            .add(row(2, now - 24 * hour, temp = 11.0))
            .add(row(3, now - 2 * hour, temp = null))
            .add(row(4, now, temp = 13.0))
        val points = h.windowPoints(now, 0, HistoryMetric.TEMPERATURE)
        assertEquals(listOf(11.0, null, 13.0), points.map { it.value })
        assertEquals(now - 24 * hour, points.first().timeMillis)
    }

    @Test
    fun chartSegmentsBreakAtMissingValue() {
        val from = 0L
        val to = 100L
        val points = listOf(
            HistoryPoint(0, 1.0), HistoryPoint(10, 2.0), HistoryPoint(20, null), HistoryPoint(30, 3.0)
        )
        val segments = HistoryChartGeometry.segments(points, from, to, 1.0, 3.0)
        assertEquals(2, segments.size)
        assertEquals(2, segments[0].size)
        assertEquals(1, segments[1].size)
    }

    @Test
    fun chartSegmentsBreakAtLongPause() {
        val points = listOf(HistoryPoint(0, 1.0), HistoryPoint(1_000_000, 2.0))
        val segments = HistoryChartGeometry.segments(points, 0, 2_000_000, 1.0, 2.0, maxGapMillis = 60_000)
        assertEquals(2, segments.size)
    }

    @Test
    fun storeWritesAtMostOncePerMinuteAndLeavesNoTempFile() {
        val file = File(tmp.root, "telemetry.json")
        val store = TelemetryHistoryStore(file)
        val h1 = TelemetryHistory().add(row(1, t0))
        assertTrue(store.saveThrottled(h1, t0))
        val h2 = h1.add(row(2, t0 + 1000))
        assertFalse(store.saveThrottled(h2, t0 + 30_000))
        assertEquals(1, store.load().rows.size)
        assertTrue(store.saveThrottled(h2, t0 + 60_000))
        assertEquals(2, store.load().rows.size)
        assertFalse(File(tmp.root, "telemetry.json.tmp").exists())
    }

    @Test
    fun storeMissingOrCorruptFileGivesEmptyHistory() {
        val file = File(tmp.root, "telemetry.json")
        assertTrue(TelemetryHistoryStore(file).load().rows.isEmpty())
        file.writeText("garbage")
        assertTrue(TelemetryHistoryStore(file).load().rows.isEmpty())
    }

    @Test
    fun loadPrunesOldRowsAndSorts() {
        val day = 24L * 60 * 60 * 1000
        val json = TelemetryHistory(listOf(row(2, t0 + 10 * day), row(1, t0))).toJson()
        val back = TelemetryHistory.fromJson(json)
        assertEquals(listOf(2), back.rows.map { it.sequence })
    }

    @Test
    fun flatChartSitsInTheMiddle() {
        val segments = HistoryChartGeometry.segments(
            listOf(HistoryPoint(0, 5.0), HistoryPoint(10, 5.0)), 0, 100, 5.0, 5.0
        )
        assertEquals(0.5f, segments[0][0].second, 0.001f)
    }

    @Test
    fun onlyPageZeroFramesAreRecorded() {
        val tiles = tiles(1.0, 2.0)
        assertTrue(BleScanService.shouldRecord(TelemetryFrame(1, 50, AlertMask.NONE, tiles, page = 0)))
        assertFalse(BleScanService.shouldRecord(TelemetryFrame(1, 50, AlertMask.NONE, tiles, page = 1)))
    }

    @Test
    fun mergeKeepsStoredRowsThenLiveRows() {
        val stored = TelemetryHistory().add(row(1, t0)).add(row(2, t0 + 1000))
        val live = TelemetryHistory().add(row(3, t0 + 2000))
        assertEquals(listOf(1, 2, 3), BleScanService.mergeHistory(stored, live).rows.map { it.sequence })
    }

    @Test
    fun saveLatestWritesTheValueOfTheFlowAtRunTime() {
        val file = File(tmp.root, "telemetry.json")
        val store = TelemetryHistoryStore(file)
        val flow = kotlinx.coroutines.flow.MutableStateFlow(TelemetryHistory().add(row(3, t0 + 2000)))
        // The load merge replaces the value before the queued save runs.
        flow.value = BleScanService.mergeHistory(TelemetryHistory().add(row(1, t0)).add(row(2, t0 + 1000)), flow.value)
        BleScanService.saveLatest(store, flow, t0 + 3000, throttled = true)
        assertEquals(listOf(1, 2, 3), store.load().rows.map { it.sequence })
    }

    @Test
    fun comparePointsListsTilesWithValuesAndSharesTheRange() {
        fun two(t0v: Double?, t1v: Double?) = List(TelemetryFrame.TILE_COUNT) { i ->
            when (i) {
                0 -> TelemetryTile(t0v, 40.0)
                1 -> TelemetryTile(t1v, 60.0)
                else -> TelemetryTile(null, null)
            }
        }
        val h = TelemetryHistory()
            .add(HistoryRow(t0, 1, 80, 0, two(20.0, 25.5)))
            .add(HistoryRow(t0 + 60_000, 2, 80, 0, two(22.0, null)))
        val temp = h.comparePoints(t0 + 120_000, HistoryMetric.TEMPERATURE)
        assertEquals(listOf(0, 1), temp.series.keys.toList())
        assertEquals(listOf(20.0, 22.0), temp.series.getValue(0).map { it.value })
        assertEquals(listOf(25.5, null), temp.series.getValue(1).map { it.value })
        assertEquals(20.0, temp.min!!, 1e-9)
        assertEquals(25.5, temp.max!!, 1e-9)
        val rh = h.comparePoints(t0 + 120_000, HistoryMetric.HUMIDITY)
        assertEquals(40.0, rh.min!!, 1e-9)
        assertEquals(60.0, rh.max!!, 1e-9)
    }

    @Test
    fun comparePointsGivesNoEntryForEmptyTilesAndNoRangeWithoutValues() {
        val empty = TelemetryHistory().comparePoints(t0, HistoryMetric.HUMIDITY)
        assertTrue(empty.series.isEmpty())
        assertEquals(null, empty.min)
        assertEquals(null, empty.max)
        val h = TelemetryHistory().add(row(1, t0, temp = 21.0, rh = null))
        assertEquals(listOf(0), h.comparePoints(t0, HistoryMetric.TEMPERATURE).series.keys.toList())
        assertTrue(h.comparePoints(t0, HistoryMetric.HUMIDITY).series.isEmpty())
        // A row older than 24 hours leaves the window.
        val old = h.comparePoints(t0 + TelemetryHistory.WINDOW_MILLIS + 1, HistoryMetric.TEMPERATURE)
        assertTrue(old.series.isEmpty())
    }
}
