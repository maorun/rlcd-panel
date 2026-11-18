import Foundation
import Testing
@testable import RlcdProtocol

@Suite struct AlertStatsTests {
    private let header = "time,event,detail\n"

    private func makeFolder(_ files: [String: String]) throws -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("alertstats-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        for (name, text) in files { try text.write(to: dir.appendingPathComponent(name), atomically: true, encoding: .utf8) }
        return dir
    }

    private func stat(_ r: AlertStatsResult, _ alert: Int, _ month: String) -> AlertMonthStat? {
        r.stats.first { $0.alert == alert && $0.month == month }
    }

    @Test func countsAndHoursPerMonthWithBoundarySplit() throws {
        let folder = try makeFolder([
            "events_v1_2026.csv": header + """
            2026-01-10 08:00:00,ALERT_RISE,1
            2026-01-10 09:30:00,ALERT_FALL,1
            2026-01-31 23:00:00,ALERT_RISE,2
            2026-02-01 01:00:00,ALERT_FALL,2
            2026-02-05 10:00:00,ALERT_RISE,1
            2026-02-05 11:00:00,ALERT_FALL,1
            2026-02-06 10:00:00,ALERT_RISE,1
            2026-02-06 12:00:00,ALERT_FALL,1

            """,
            "events_v1_2025.csv": header + "2025-12-01 00:00:00,ALERT_RISE,4\n2025-12-01 06:00:00,ALERT_FALL,4\n",
        ])
        defer { try? FileManager.default.removeItem(at: folder) }
        let now = AlertTimeline.parse(time: "2026-03-01 00:00:00")!
        let r = AlertStats.load(from: folder, now: now)
        #expect(r.skippedFiles.isEmpty)
        #expect(stat(r, 4, "2025-12")?.rises == 1)
        #expect(stat(r, 4, "2025-12")?.hours == 6)
        #expect(stat(r, 1, "2026-01")?.rises == 1)
        #expect(stat(r, 1, "2026-01")?.hours == 1.5)
        #expect(stat(r, 1, "2026-02")?.rises == 2)
        #expect(stat(r, 1, "2026-02")?.hours == 3)
        // 31 January 23:00 to 1 February 01:00: one hour in each month, one rise in January.
        #expect(stat(r, 2, "2026-01")?.hours == 1)
        #expect(stat(r, 2, "2026-01")?.rises == 1)
        #expect(stat(r, 2, "2026-02")?.hours == 1)
        #expect(stat(r, 2, "2026-02")?.rises == 0)
    }

    @Test func startUnknownCountsApart() throws {
        let folder = try makeFolder([
            "events_v1_2026.csv": header + """
            2026-03-01 07:00:00,EVENTS_DROPPED,3
            2026-03-01 08:00:00,ALERT_FALL,1
            2026-03-02 08:00:00,ALERT_RISE,1
            2026-03-02 09:00:00,ALERT_FALL,1

            """,
        ])
        defer { try? FileManager.default.removeItem(at: folder) }
        let r = AlertStats.load(from: folder, now: AlertTimeline.parse(time: "2026-04-01 00:00:00")!)
        let s = try #require(stat(r, 1, "2026-03"))
        #expect(s.unknownStarts == 1)
        #expect(s.rises == 1)
        #expect(s.hours == 1)
    }

    @Test func corruptFileIsSkippedAndOtherFileCounts() throws {
        let folder = try makeFolder([
            "events_v1_2026.csv": header + "2026-01-10 08:00:00,ALERT_RISE,1\n2026-01-10 09:00:00,ALERT_FALL,1\n",
            "events_v1_2025.csv": header + "not a row\n",
            "events_v9_2024.csv": header,
        ])
        defer { try? FileManager.default.removeItem(at: folder) }
        let r = AlertStats.load(from: folder, now: AlertTimeline.parse(time: "2026-02-01 00:00:00")!)
        #expect(r.skippedFiles == ["events_v1_2025.csv", "events_v9_2024.csv"])
        #expect(stat(r, 1, "2026-01")?.hours == 1)
    }

    @Test func openIntervalRunsUntilNowAndYearChangeSplits() throws {
        let folder = try makeFolder([
            "events_v1_2025.csv": header + "2025-12-31 22:00:00,ALERT_RISE,1\n",
            "events_v1_2026.csv": header + "2026-01-01 02:00:00,ALERT_FALL,1\n2026-01-05 00:00:00,ALERT_RISE,2\n",
        ])
        defer { try? FileManager.default.removeItem(at: folder) }
        let r = AlertStats.load(from: folder, now: AlertTimeline.parse(time: "2026-01-05 03:00:00")!)
        #expect(stat(r, 1, "2025-12")?.hours == 2)
        #expect(stat(r, 1, "2025-12")?.rises == 1)
        #expect(stat(r, 1, "2026-01")?.hours == 2)
        #expect(stat(r, 1, "2026-01")?.rises == 0)
        #expect(stat(r, 2, "2026-01")?.hours == 3)
        #expect(stat(r, 2, "2026-01")?.rises == 1)
    }
}
