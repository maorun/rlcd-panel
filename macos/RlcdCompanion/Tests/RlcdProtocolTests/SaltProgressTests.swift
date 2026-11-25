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

    @Test func historyHoursLine() {
        #expect(ConsoleCommand.historySensorHours(4, 6).line == "HISTORY 4 6")
        #expect(ConsoleCommand.historySensorHours(0, 1).line == "HISTORY 0 1")
        #expect(ConsoleCommand.historySensorHours(10, 24).line == "HISTORY 10 24")
        #expect(ConsoleCommand.historySensorHours(11, 6).line == nil)
        #expect(ConsoleCommand.historySensorHours(-1, 6).line == nil)
        #expect(ConsoleCommand.historySensorHours(4, 0).line == nil)
        #expect(ConsoleCommand.historySensorHours(4, 25).line == nil)
    }

    @Test func sixRowsOfShortReadGiveFullCount() throws {
        var p = SaltProgress()
        p.start()
        p.record(rows: try Self.reply([75.2, 75.4, 75.0, 75.6, 75.3, 75.2]), sensor: 2)
        #expect(p.historyCommand(sensor: 2) == .historySensorHours(2, SaltProgress.requiredHours))
        #expect(p.text == "Stable 6/6 h" && p.isReady)
    }

    @Test func fallsBackToFullHistoryAfterBadArg() {
        var p = SaltProgress()
        p.start()
        #expect(p.historyCommand(sensor: 4) == .historySensorHours(4, 6))
        let r1 = p.fallBackToFullHistory()
        #expect(r1)
        #expect(p.historyCommand(sensor: 4) == .historySensor(4))
        #expect(p.historyCommand(sensor: 4).line == "HISTORY 4")
        let r2 = p.fallBackToFullHistory()
        #expect(!r2) // a second BAD_ARG does not loop
        p.start()
        #expect(p.historyCommand(sensor: 4) == .historySensorHours(4, 6)) // a new watch tries the short read again
    }

    @Test func retryAfterErrorOnlyForBadArgOfShortRead() {
        var p = SaltProgress()
        p.start()
        let short = ConsoleCommand.historySensorHours(4, 6)
        let otherError = p.retryAfterError(short, text: "ERR NOT_OVER_BLE", sensor: 4)
        let fullCommand = p.retryAfterError(.historySensor(4), text: "ERR BAD_ARG", sensor: 4)
        #expect(otherError == nil)
        #expect(fullCommand == nil)
        #expect(p.usesHourWindow)
        let first = p.retryAfterError(short, text: "ERR BAD_ARG", sensor: 4)
        let second = p.retryAfterError(short, text: "ERR BAD_ARG", sensor: 4)
        #expect(first == .historySensor(4))
        #expect(second == nil)
    }

    @Test func retryAfterErrorRejectsOtherSensorStoppedWatchAndNearMissText() {
        var p = SaltProgress()
        p.start()
        let otherSensor = p.retryAfterError(.historySensorHours(5, 6), text: "ERR BAD_ARG", sensor: 4)
        let trailingSpace = p.retryAfterError(.historySensorHours(4, 6), text: "ERR BAD_ARG ", sensor: 4)
        let lowerCase = p.retryAfterError(.historySensorHours(4, 6), text: "err bad_arg", sensor: 4)
        #expect(otherSensor == nil)
        #expect(trailingSpace == nil)
        #expect(lowerCase == nil)
        #expect(p.usesHourWindow)
        p.stop(.user)
        let stopped = p.retryAfterError(.historySensorHours(4, 6), text: "ERR BAD_ARG", sensor: 4)
        #expect(stopped == nil)
    }

    @Test func noFallbackWhenWatchIsNotRunning() {
        var p = SaltProgress()
        let r11 = p.fallBackToFullHistory()
        #expect(!r11)
        #expect(p.usesHourWindow)
    }
}
