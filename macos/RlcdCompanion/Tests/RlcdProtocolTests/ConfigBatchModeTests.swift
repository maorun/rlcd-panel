import Foundation
import Testing
@testable import RlcdProtocol

/// The firmware batch mode of `SET`: `SET BEGIN`, queued `SET` lines, `SET COMMIT` (or `SET ABORT`). An `ERR` reply to
/// `SET BEGIN` selects the old single `SET` lines.
@Suite struct ConfigBatchModeTests {
    static let changes = [
        ConfigChange(key: "temp_unit", old: "C", new: "F"),
        ConfigChange(key: "wake_interval_s", old: "60", new: "90"),
    ]

    @Test func commandLines() {
        #expect(ConsoleCommand.setBegin.line == "SET BEGIN")
        #expect(ConsoleCommand.setCommit.line == "SET COMMIT")
        #expect(ConsoleCommand.setAbort.line == "SET ABORT")
        #expect(ConsoleCommand.setBegin.data == Data("SET BEGIN\n".utf8))
    }

    @Test func beginOkQueuesThenCommits() {
        var b = ConfigBatch(changes: Self.changes)
        #expect(b.next() == .setBegin)
        #expect(b.next() == nil) // a reply is open
        b.receive(status: "OK SET BEGIN")
        #expect(b.usesBatchMode)
        #expect(b.next()?.line == "SET temp_unit=F")
        b.receive(status: "OK QUEUED temp_unit")
        #expect(b.applied.isEmpty) // nothing is on the card before SET COMMIT
        #expect(b.next()?.line == "SET wake_interval_s=90")
        b.receive(status: "OK QUEUED wake_interval_s")
        #expect(b.next() == .setCommit)
        #expect(b.applied.isEmpty)
        b.receive(status: "OK SET COMMIT 2")
        #expect(b.applied == ["temp_unit", "wake_interval_s"])
        #expect(b.next() == .config)
        #expect(!b.isFinished)
        b.receiveFrame()
        #expect(b.next() == nil)
        #expect(b.isFinished)
        #expect(b.failure == nil)
        #expect(b.summary == "Applied 2 settings")
    }

    @Test func beginErrFallsBackToSingleSetLines() {
        for reply in ["ERR READ_ONLY", "ERR BAD_KEY", "ERR UNKNOWN"] {
            var b = ConfigBatch(changes: Self.changes)
            #expect(b.next() == .setBegin)
            b.receive(status: reply)
            #expect(!b.usesBatchMode)
            #expect(b.failure == nil) // the ERR belongs to no key
            #expect(b.next()?.line == "SET temp_unit=F")
            b.receive(status: "OK SET temp_unit")
            #expect(b.next()?.line == "SET wake_interval_s=90")
            b.receive(status: "OK SET wake_interval_s")
            #expect(b.next() == .config) // no SET COMMIT
            b.receiveFrame()
            #expect(b.isFinished)
            #expect(b.applied == ["temp_unit", "wake_interval_s"])
        }
    }

    @Test func singleSetFallbackKeepsStopAtFirstErr() {
        var b = ConfigBatch(changes: Self.changes)
        _ = b.next()
        b.receive(status: "ERR READ_ONLY")
        #expect(b.next()?.line == "SET temp_unit=F")
        b.receive(status: "ERR READ_ONLY")
        #expect(b.next() == .config)
        #expect(b.failure?.key == "temp_unit")
        #expect(b.failure?.reply == "ERR READ_ONLY")
        #expect(b.applied.isEmpty)
    }

    @Test func errWhileQueuingAbortsTheQueueAndReadsConfig() {
        var b = ConfigBatch(changes: Self.changes)
        _ = b.next()
        b.receive(status: "OK SET BEGIN")
        #expect(b.next()?.line == "SET temp_unit=F")
        b.receive(status: "OK QUEUED temp_unit")
        #expect(b.next()?.line == "SET wake_interval_s=90")
        b.receive(status: "ERR BAD_VALUE")
        #expect(b.next() == .setAbort) // no SET COMMIT: the queued key is not applied
        b.receive(status: "OK SET ABORT")
        #expect(b.next() == .config)
        b.receiveFrame()
        #expect(b.isFinished)
        #expect(b.applied.isEmpty)
        #expect(b.failure?.key == "wake_interval_s")
        #expect(b.failure?.reply == "ERR BAD_VALUE")
        #expect(b.summary == "Stopped at wake_interval_s: ERR BAD_VALUE. Applied: 0")
    }

    @Test func fullQueueAborts() {
        var b = ConfigBatch(changes: Self.changes)
        _ = b.next()
        b.receive(status: "OK SET BEGIN")
        _ = b.next()
        b.receive(status: "ERR BATCH_FULL")
        #expect(b.next() == .setAbort)
        #expect(b.failure?.reply == "ERR BATCH_FULL")
    }

    @Test func failedCommitAbortsAndApplyingNothing() {
        var b = ConfigBatch(changes: Self.changes)
        _ = b.next()
        b.receive(status: "OK SET BEGIN")
        _ = b.next()
        b.receive(status: "OK QUEUED temp_unit")
        _ = b.next()
        b.receive(status: "OK QUEUED wake_interval_s")
        #expect(b.next() == .setCommit)
        b.receive(status: "ERR WRITE_FAILED")
        #expect(b.applied.isEmpty)
        #expect(b.failure?.key == "SET COMMIT")
        #expect(b.failure?.reply == "ERR WRITE_FAILED")
        #expect(b.next() == .setAbort) // the failed write keeps the queue on the panel
        b.receive(status: "OK SET ABORT")
        #expect(b.next() == .config)
        b.receiveFrame()
        #expect(b.isFinished)
        #expect(b.summary == "Stopped at SET COMMIT: ERR WRITE_FAILED. Applied: 0")
    }

    @Test func lostBeginReplyAbortsInsteadOfFallingBack() {
        for reply in ["ERR CORRUPT", "ERR UNEXPECTED_REPLY"] {
            var b = ConfigBatch(changes: Self.changes)
            #expect(b.next() == .setBegin)
            b.receive(status: reply)
            #expect(b.failure?.key == "SET BEGIN")
            #expect(b.next() == .setAbort) // the panel may hold a queue: no single SET lines
            b.receive(status: "OK SET ABORT")
            #expect(b.next() == .config)
            #expect(b.applied.isEmpty)
        }
    }

    @Test func plainOkInBatchModeMeansTheQueueExpired() {
        var b = ConfigBatch(changes: Self.changes)
        _ = b.next()
        b.receive(status: "OK SET BEGIN")
        _ = b.next()
        b.receive(status: "OK SET temp_unit") // written alone: no queue on the panel
        #expect(b.failure?.key == "temp_unit")
        #expect(b.failure?.reply == "ERR BATCH_STATE")
        #expect(b.next() == .setAbort)
        #expect(b.applied.isEmpty)
    }

    @Test func queuedReplyInSingleModeMeansAStaleQueue() {
        var b = ConfigBatch(changes: Self.changes)
        _ = b.next()
        b.receive(status: "ERR READ_ONLY") // falls back to single lines
        #expect(b.next()?.line == "SET temp_unit=F")
        b.receive(status: "OK QUEUED temp_unit") // nothing was written
        #expect(b.failure?.reply == "ERR BATCH_STATE")
        #expect(b.applied.isEmpty)
        #expect(b.next() == .setAbort)
    }

    @Test func repeatedKeyCountsOnce() {
        let commands: [ConsoleCommand] = [.set(key: "temp_unit", value: "C"), .set(key: "temp_unit", value: "F")]
        var b = ConfigBatch(commands: commands)
        _ = b.next()
        b.receive(status: "OK SET BEGIN")
        _ = b.next()
        b.receive(status: "OK QUEUED temp_unit")
        _ = b.next()
        b.receive(status: "OK QUEUED temp_unit")
        #expect(b.next() == .setCommit)
        b.receive(status: "OK SET COMMIT 1")
        #expect(b.applied == ["temp_unit"])
    }

    @Test func oneKeyUsesNoBatchMode() {
        var b = ConfigBatch(changes: [ConfigChange(key: "temp_unit", old: "C", new: "F")])
        #expect(b.next()?.line == "SET temp_unit=F")
        b.receive(status: "OK SET temp_unit")
        #expect(b.next() == .config)
    }

    @Test func noChangesOnlyReadsConfig() {
        var b = ConfigBatch(changes: [])
        #expect(b.next() == .config)
    }

    @Test func moreThanTheQueueSizeUsesSingleLines() {
        let commands = (0..<(ConfigBatch.maxBatchKeys + 1)).map { _ in ConsoleCommand.set(key: "temp_unit", value: "C") }
        var b = ConfigBatch(commands: commands)
        #expect(b.next()?.line == "SET temp_unit=C")
        let exact = (0..<ConfigBatch.maxBatchKeys).map { _ in ConsoleCommand.set(key: "temp_unit", value: "C") }
        var full = ConfigBatch(commands: exact)
        #expect(full.next() == .setBegin)
    }

    @Test func batchModeCanBeSwitchedOff() {
        var b = ConfigBatch(changes: Self.changes, batchMode: false)
        #expect(b.next()?.line == "SET temp_unit=F")
        #expect(!b.usesBatchMode)
    }

    @Test func linkLostAfterBeginLeavesNoApplied() {
        // The app drops the batch on a lost link; the panel drops its queue. Nothing counts as applied.
        var b = ConfigBatch(changes: Self.changes)
        _ = b.next()
        b.receive(status: "OK SET BEGIN")
        _ = b.next()
        b.receive(status: "OK QUEUED temp_unit")
        #expect(b.applied.isEmpty)
    }
}
