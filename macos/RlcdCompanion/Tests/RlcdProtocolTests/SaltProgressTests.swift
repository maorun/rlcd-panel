import Foundation
import Testing
@testable import RlcdProtocol

@Suite struct SaltProgressTests {
    final class Clock { var date = Date(timeIntervalSince1970: 1_000_000) }

    static func reply(_ values: [Double?], sensor: Int = 2) throws -> [HistoryRow] {
        let rows = values.enumerated().map { age, v in "\(sensor),\(age),21.0,\(v.map { String($0) } ?? ""),45.0" }
        return try HistoryReply.parse(lines: ["BEGIN HISTORY", HistoryReply.header] + rows)
    }

    @Test func countsStableHours() throws {
        // Newest first: five hours within 0.8 %RH, then a jump.
        let rows = try Self.reply([75.2, 75.4, 75.0, 75.6, 75.3, 70.0, 75.2])
        #expect(SaltProgress.stableHours(rows: rows, sensor: 2) == 5)
    }

    @Test func resetsAfterUnstableNewestHour() throws {
        // The newest hour is far from the older ones: the run holds only that hour.
        let rows = try Self.reply([60.0, 75.2, 75.3, 75.1, 75.2, 75.3, 75.2])
        #expect(SaltProgress.stableHours(rows: rows, sensor: 2) == 1)
    }

    @Test func spanLimitIsInclusiveAndGapEndsRun() throws {
        #expect(SaltProgress.stableHours(rows: try Self.reply([75.0, 76.0]), sensor: 2) == 2)
        #expect(SaltProgress.stableHours(rows: try Self.reply([75.0, 76.1]), sensor: 2) == 1)
        #expect(SaltProgress.stableHours(rows: try Self.reply([75.0, nil, 75.0]), sensor: 2) == 1)
        #expect(SaltProgress.stableHours(rows: try Self.reply([75.0]), sensor: 3) == 0)
    }

    @Test func textAndRemaining() throws {
        var p = SaltProgress()
        p.start()
        p.record(rows: try Self.reply([75.2, 75.3, 75.1]), sensor: 2)
        #expect(p.text == "Stable 3/6 h" && p.hoursRemaining == 3 && !p.isReady)
        p.record(rows: try Self.reply([75.2, 75.3, 75.1, 75.2, 75.3, 75.2, 75.1]), sensor: 2)
        #expect(p.text == "Stable 6/6 h" && p.hoursRemaining == 0 && p.isReady)
    }

    @Test func fiveStableHoursAreNotReady() throws {
        var p = SaltProgress()
        p.start()
        p.record(rows: try Self.reply([75.2, 75.3, 75.1, 75.2, 75.3]), sensor: 2)
        #expect(p.stableHours == 5 && !p.isReady && p.hoursRemaining == 1)
    }

    @Test func resetAfterUnstableHourInLaterReply() throws {
        var p = SaltProgress()
        p.start()
        p.record(rows: try Self.reply([75.2, 75.3, 75.1, 75.2]), sensor: 2)
        #expect(p.stableHours == 4)
        p.record(rows: try Self.reply([70.0, 75.2, 75.3, 75.1, 75.2]), sensor: 2)
        #expect(p.stableHours == 1)
    }

    @Test func stopsAfterEightHours() {
        let clock = Clock()
        var p = SaltProgress(now: { clock.date })
        p.start()
        clock.date.addTimeInterval(8 * 3600 - 1)
        let before = p.shouldPoll()
        #expect(before)
        clock.date.addTimeInterval(1)
        let after = p.shouldPoll()
        #expect(!after)
        #expect(!p.isRunning && p.stopReason == .timeout)
    }

    @Test func userAndLinkStopKeepFirstReasonAndIgnoreReplies() throws {
        var p = SaltProgress()
        p.start()
        p.stop(.linkLost)
        p.stop(.user)
        #expect(p.stopReason == .linkLost && !p.isRunning)
        p.record(rows: try Self.reply([75.0, 75.1]), sensor: 2)
        #expect(p.stableHours == 0)
        let polls = p.shouldPoll()
        #expect(!polls)
    }

    @Test func historyCommandLine() {
        #expect(ConsoleCommand.historySensor(4).line == "HISTORY 4")
        #expect(ConsoleCommand.historySensor(11).line == nil)
        #expect(ConsoleCommand.historySensor(-1).line == nil)
    }
}
