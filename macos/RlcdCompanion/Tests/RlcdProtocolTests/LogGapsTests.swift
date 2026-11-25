import Foundation
import Testing
@testable import RlcdProtocol

private func row(_ timestamp: String) -> ClimateRow {
    ClimateRow(
        timestamp: timestamp, batteryPct: nil, batteryV: nil, usb: nil,
        sensors: [ClimateSensorReading(tempC: 20, rh: 50, batteryPct: nil)],
        noiseRms: nil, noiseDb: nil, noiseBand: nil, noiseEvents: nil, noiseL10: nil, noiseL90: nil)
}

@Suite struct LogGapsTests {
    @Test func hourlyRowsGiveNoGap() {
        let rows = (0..<5).map { row(String(format: "2026-09-01 %02d:00:00", $0)) }
        let report = LogGaps.report(rows: rows, minGapMinutes: 90)
        #expect(report.gaps.isEmpty)
        #expect(report.rowCount == 5)
        #expect(report.missingHours == 0)
        #expect(report.coveragePct == 100)
    }

    @Test func threeHourStepGivesTwoMissingHours() throws {
        let rows = [row("2026-09-01 01:00:00"), row("2026-09-01 04:00:00")]
        let gaps = LogGaps.find(rows: rows, minGapMinutes: 90)
        let gap = try #require(gaps.first)
        #expect(gaps.count == 1)
        #expect(gap.missingHours == 2)
        #expect(gap.end.timeIntervalSince(gap.start) == 3 * 3600)
        let report = LogGaps.report(rows: rows, minGapMinutes: 90)
        #expect(report.missingHours == 2)
        #expect(abs(report.coveragePct - 50) < 0.001)
    }

    @Test func exactlyNinetyMinutesGivesNoGap() {
        let rows = [row("2026-09-01 01:00:00"), row("2026-09-01 02:30:00")]
        #expect(LogGaps.find(rows: rows, minGapMinutes: 90).isEmpty)
    }

    @Test func oneMinuteAboveLimitGivesOneGap() {
        let rows = [row("2026-09-01 01:00:00"), row("2026-09-01 02:31:00")]
        let gaps = LogGaps.find(rows: rows, minGapMinutes: 90)
        #expect(gaps.count == 1)
        #expect(gaps[0].missingHours == 1)
        #expect(gaps[0].end.timeIntervalSince(gaps[0].start) == 91 * 60)
    }

    @Test func monthWithoutRowsHasCoverageZero() {
        let report = LogGaps.report(rows: [])
        #expect(report.gaps.isEmpty)
        #expect(report.coveragePct == 0)
        #expect(report.rowCount == 0)
    }

    @Test func rowsOutOfOrderAreSortedFirst() {
        let rows = [row("2026-09-01 04:00:00"), row("2026-09-01 01:00:00"), row("2026-09-01 02:00:00")]
        let gaps = LogGaps.find(rows: rows, minGapMinutes: 90)
        #expect(gaps.count == 1)
        #expect(gaps[0].missingHours == 1)
        #expect(gaps[0].end.timeIntervalSince(gaps[0].start) == 2 * 3600)
        #expect(gaps[0].end > gaps[0].start)
    }

    @Test func octoberChangeNightGivesNoGap() {
        // Berlin repeats 02:00 to 03:00 on 2026-10-25; the log holds local time, so 02:00 appears twice.
        let rows = ["01:00:00", "02:00:00", "02:00:00", "03:00:00", "04:00:00"].map { row("2026-10-25 \($0)") }
        #expect(LogGaps.find(rows: rows, minGapMinutes: 90).isEmpty)
    }

    @Test func marchChangeNightGivesNoGap() {
        // Berlin skips 02:00 to 03:00 on 2026-03-29; 01:00 to 03:00 local time is a 1-hour step.
        let rows = ["00:00:00", "01:00:00", "03:00:00", "04:00:00"].map { row("2026-03-29 \($0)") }
        #expect(LogGaps.find(rows: rows, minGapMinutes: 90).isEmpty)
    }

    @Test func unreadableTimestampIsSkipped() {
        let rows = [row("bad"), row("2026-09-01 01:00:00")]
        let report = LogGaps.report(rows: rows)
        #expect(report.rowCount == 1)
        #expect(report.gaps.isEmpty)
        #expect(report.missingHours == 0)
        #expect(report.coveragePct == 100)
    }

    @Test func missingHoursRoundTheStep() {
        let long = [row("2026-09-01 01:00:00"), row("2026-09-01 03:30:00")]
        #expect(LogGaps.find(rows: long, minGapMinutes: 90).map(\.missingHours) == [2])
        let short = [row("2026-09-01 01:00:00"), row("2026-09-01 02:40:00")]
        #expect(LogGaps.find(rows: short, minGapMinutes: 90).map(\.missingHours) == [1])
    }

    @Test func limitBelowOneMinuteCountsAsOneMinute() {
        let rows = [row("2026-09-01 01:00:00"), row("2026-09-01 02:00:00")]
        #expect(LogGaps.find(rows: rows, minGapMinutes: 0).count == 1)
        #expect(LogGaps.find(rows: rows, minGapMinutes: -5).count == 1)
        #expect(LogGaps.find(rows: rows, minGapMinutes: 60).isEmpty)
    }
}
