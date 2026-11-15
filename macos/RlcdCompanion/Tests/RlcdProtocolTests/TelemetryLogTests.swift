import Foundation
import Testing
@testable import RlcdProtocol

struct TelemetryLogTests {
    private let t0 = Date(timeIntervalSince1970: 1_800_000_000)

    private func frame(seq: UInt8, temp: Double? = 21.5, cell: Int? = 80, alerts: UInt8 = 0) -> TelemetryFrame {
        var tiles = (0..<TelemetryFrame.tileCount).map { _ in TelemetryTile(temperatureC: nil, humidityPct: nil) }
        if let temp { tiles[0] = TelemetryTile(temperatureC: temp, humidityPct: 45.5) }
        return TelemetryFrame(sequence: seq, cellPercent: cell, alerts: AlertMask(rawValue: alerts), tiles: tiles)
    }

    private func tempFile() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("rlcd-log-\(UUID().uuidString)", isDirectory: true)
            .appendingPathComponent("telemetry.json")
    }

    @Test func repeatedSequenceAddsOneRow() {
        var log = TelemetryLog()
        let first = log.add(frame(seq: 7), at: t0)
        let repeated = log.add(frame(seq: 7), at: t0.addingTimeInterval(5))
        #expect(first)
        #expect(!repeated)
        #expect(log.rows.count == 1)
        let next = log.add(frame(seq: 8), at: t0.addingTimeInterval(60))
        #expect(next)
        #expect(log.rows.count == 2)
    }

    @Test func dropsRowsOlderThanSevenDays() {
        var log = TelemetryLog()
        _ = log.add(frame(seq: 1), at: t0)
        _ = log.add(frame(seq: 2), at: t0.addingTimeInterval(3 * 86_400))
        let later = t0.addingTimeInterval(7 * 86_400 + 1)
        _ = log.add(frame(seq: 3), at: later)
        #expect(log.rows.map(\.sequence) == [2, 3])
    }

    @Test func capsLogAt20000Rows() {
        var log = TelemetryLog()
        for i in 0..<(TelemetryLog.maxRows + 5) {
            _ = log.add(frame(seq: UInt8(i % 2 == 0 ? 1 : 2)), at: t0.addingTimeInterval(Double(i)))
        }
        #expect(log.rows.count == TelemetryLog.maxRows)
        #expect(log.rows.first?.time == t0.addingTimeInterval(5))
    }

    @Test func roundTripsJsonFile() throws {
        let url = tempFile()
        var log = TelemetryLog()
        _ = log.add(frame(seq: 1, alerts: 3), at: t0)
        _ = log.add(frame(seq: 2, temp: nil, cell: nil), at: t0.addingTimeInterval(60))
        try log.save(to: url)
        #expect(TelemetryLog.load(from: url) == log)
        #expect(!FileManager.default.fileExists(atPath: url.path + ".tmp"))
    }

    @Test func corruptOrMissingFileGivesEmptyLog() throws {
        let url = tempFile()
        #expect(TelemetryLog.load(from: url).rows.isEmpty)
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data("{not json".utf8).write(to: url)
        #expect(TelemetryLog.load(from: url).rows.isEmpty)
    }

    @Test func storeWritesAtMostOncePerMinute() {
        let url = tempFile()
        var clock = t0
        let store = TelemetryLogStore(url: url, now: { clock })
        store.record(frame(seq: 1))
        #expect(TelemetryLog.load(from: url).rows.count == 1)
        clock = t0.addingTimeInterval(30)
        store.record(frame(seq: 2))
        #expect(TelemetryLog.load(from: url).rows.count == 1)
        #expect(store.log.rows.count == 2)
        clock = t0.addingTimeInterval(61)
        store.record(frame(seq: 3))
        #expect(TelemetryLog.load(from: url).rows.count == 3)
    }

    @Test func csvOfFixedLog() {
        var log = TelemetryLog()
        _ = log.add(frame(seq: 1, alerts: 3), at: Date(timeIntervalSince1970: 1_800_000_000))
        _ = log.add(frame(seq: 2, temp: nil, cell: nil), at: Date(timeIntervalSince1970: 1_800_000_060))
        let expected = """
        time,sequence,cell_pct,alerts,t0_c,rh0_pct,t1_c,rh1_pct,t2_c,rh2_pct,t3_c,rh3_pct,t4_c,rh4_pct,t5_c,rh5_pct
        2027-01-15T08:00:00Z,1,80,3,21.50,45.5,,,,,,,,,,
        2027-01-15T08:01:00Z,2,,0,,,,,,,,,,,,

        """
        #expect(CsvExport.telemetry(log) == expected)
    }

    @Test func csvOfHistoryAndEvents() {
        let history = [
            HistoryRow(sensor: 0, ageHours: 0, temperatureC: 21.25, humidityPct: 45, noiseDb: nil),
            HistoryRow(sensor: 1, ageHours: 3, temperatureC: nil, humidityPct: nil, noiseDb: 38.5),
        ]
        #expect(CsvExport.history(history) == """
        sensor,age_h,temp_c,rh_pct,noise_db
        0,0,21.25,45.0,
        1,3,,,38.5

        """)
        let events = [EventRow(time: "2026-11-14T10:00:00", event: "RESET", detail: 2), EventRow(time: "", event: "SLOT_RELEASE", detail: 1)]
        #expect(CsvExport.events(events) == """
        time,event,detail
        2026-11-14T10:00:00,RESET,2
        ,SLOT_RELEASE,1

        """)
    }

    @Test func loadPrunesOldRowsAndDropsBadTileCounts() throws {
        let url = tempFile()
        var log = TelemetryLog()
        _ = log.add(frame(seq: 1), at: t0)
        _ = log.add(frame(seq: 2), at: t0.addingTimeInterval(8 * 86_400))
        try log.save(to: url)
        let loaded = TelemetryLog.load(from: url, now: t0.addingTimeInterval(8 * 86_400))
        #expect(loaded.rows.map(\.sequence) == [2])
        try Data(#"{"rows":[{"time":0,"sequence":1,"cellPercent":null,"alerts":0,"tiles":[]}]}"#.utf8).write(to: url)
        #expect(TelemetryLog.load(from: url, now: Date(timeIntervalSinceReferenceDate: 0)).rows.isEmpty)
    }

    @Test func flushWritesPendingRowsAndFailedSaveLeavesNoTempFile() throws {
        let url = tempFile()
        var clock = t0
        let store = TelemetryLogStore(url: url, now: { clock })
        store.record(frame(seq: 1))
        clock = t0.addingTimeInterval(10)
        store.record(frame(seq: 2))
        store.flush()
        #expect(TelemetryLog.load(from: url, now: clock).rows.count == 2)

        let blocked = FileManager.default.temporaryDirectory.appendingPathComponent("rlcd-blocked-\(UUID().uuidString)")
        try Data().write(to: blocked)
        let bad = blocked.appendingPathComponent("telemetry.json")
        #expect(throws: (any Error).self) { try store.log.save(to: bad) }
        #expect(!FileManager.default.fileExists(atPath: bad.path + ".tmp"))
    }

    @Test func downsamplingKeepsNewestRow() {
        var log = TelemetryLog()
        for i in 0..<10 { _ = log.add(frame(seq: UInt8(i)), at: t0.addingTimeInterval(Double(i))) }
        let rows = log.downsampled(maxRows: 4)
        #expect(rows.count <= 4)
        #expect(rows.last?.sequence == 9)
        #expect(log.downsampled(maxRows: 20).count == 10)
    }

    @Test func failedSaveStillStartsTheWriteInterval() throws {
        let blocked = FileManager.default.temporaryDirectory.appendingPathComponent("rlcd-blocked-\(UUID().uuidString)")
        try Data().write(to: blocked)
        let url = blocked.appendingPathComponent("telemetry.json")
        var clock = t0
        let store = TelemetryLogStore(url: url, now: { clock })
        #expect(store.record(frame(seq: 1)))
        clock = t0.addingTimeInterval(10)
        #expect(store.record(frame(seq: 2)))
        #expect(!store.record(frame(seq: 2)))
        #expect(store.log.rows.count == 2)
    }

    @Test func loadCapsRowsAndPrunesUnsortedRows() throws {
        let url = tempFile()
        var log = TelemetryLog()
        for i in 0..<(TelemetryLog.maxRows + 10) { _ = log.add(frame(seq: UInt8(i % 2)), at: t0.addingTimeInterval(Double(i))) }
        try log.save(to: url)
        let end = t0.addingTimeInterval(Double(TelemetryLog.maxRows + 10))
        #expect(TelemetryLog.load(from: url, now: end).rows.count == TelemetryLog.maxRows)

        // A backward clock step leaves an old row after a newer one; age pruning still drops it.
        var mixed = TelemetryLog()
        _ = mixed.add(frame(seq: 1), at: t0.addingTimeInterval(8 * 86_400))
        _ = mixed.add(frame(seq: 2), at: t0)
        mixed.prune(now: t0.addingTimeInterval(8 * 86_400))
        #expect(mixed.rows.map(\.sequence) == [1])
    }
}
