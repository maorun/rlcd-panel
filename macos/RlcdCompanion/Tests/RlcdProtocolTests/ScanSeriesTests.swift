import Foundation
import Testing
@testable import RlcdProtocol

@Suite struct ScanSeriesTests {
    final class Clock { var date = Date(timeIntervalSince1970: 1_000_000) }

    static func reply(_ rows: [(String, Int)]) throws -> ScanReply {
        try ScanReply.parse(lines: ["BEGIN SCAN", ScanReply.header] + rows.map { "\($0.0),WOIO,\($0.1),-,21.5,45.0,87" })
    }
    static let a = "AA:BB:CC:DD:EE:01"
    static let b = "AA:BB:CC:DD:EE:02"

    @Test func minMeanMax() throws {
        var s = ScanSeries()
        let started = s.start(onUsb: true)
        #expect(started)
        for r in [-60, -70, -80] { s.record(try Self.reply([(Self.a, r)])) }
        let st = try #require(s.stats(for: Self.a))
        #expect(st.min == -80 && st.max == -60 && st.mean == -70 && st.count == 3 && st.missed == 0)
    }

    @Test func keepsTenValues() throws {
        var s = ScanSeries()
        s.start(onUsb: true)
        for r in 1...12 { s.record(try Self.reply([(Self.a, -r)])) }
        let st = try #require(s.stats(for: Self.a))
        #expect(st.count == 10)
        #expect(st.min == -12 && st.max == -3) // the values -1 and -2 are gone
    }

    @Test func countsMissedScans() throws {
        var s = ScanSeries()
        s.start(onUsb: true)
        s.record(try Self.reply([(Self.a, -60), (Self.b, -50)]))
        s.record(try Self.reply([(Self.a, -62)]))
        s.record(try Self.reply([(Self.a, -64), (Self.b, -52)]))
        #expect(s.stats(for: Self.a)?.missed == 0)
        #expect(s.stats(for: Self.b)?.missed == 1)
        #expect(s.stats(for: Self.b)?.count == 2)
    }

    @Test func stopsAfterTenMinutes() throws {
        let clock = Clock()
        var s = ScanSeries(now: { clock.date })
        s.start(onUsb: true)
        clock.date.addTimeInterval(599)
        let early = s.shouldScan()
        #expect(early)
        clock.date.addTimeInterval(1)
        let late = s.shouldScan()
        #expect(!late)
        #expect(!s.isRunning)
        #expect(s.stopReason == .timeout)
    }

    @Test func stopReasonScanFailedEndsTheSeries() throws {
        var s = ScanSeries()
        s.start(onUsb: true)
        s.record(try Self.reply([(Self.a, -60)]))
        s.stop(.scanFailed) // an ERR reply, a timeout or a bad reply
        #expect(!s.isRunning)
        #expect(s.stopReason == .scanFailed)
        let again = s.shouldScan()
        #expect(!again)
    }

    @Test func recordAfterTenMinutesStopsTheSeries() throws {
        let clock = Clock()
        var s = ScanSeries(now: { clock.date })
        s.start(onUsb: true)
        clock.date.addTimeInterval(600)
        s.record(try Self.reply([(Self.a, -60)]))
        #expect(!s.isRunning)
        #expect(s.stopReason == .timeout)
    }

    @Test func failedStartClearsOldValues() throws {
        var s = ScanSeries()
        s.start(onUsb: true)
        s.record(try Self.reply([(Self.a, -60)]))
        s.stop(.user)
        let started = s.start(onUsb: false)
        #expect(!started)
        #expect(s.stats.isEmpty)
        #expect(s.stopReason == .notUsb)
    }

    @Test func doesNotStartOnBle() {
        var s = ScanSeries()
        let started = s.start(onUsb: false)
        #expect(!started)
        #expect(!s.isRunning)
        #expect(s.stopReason == .notUsb)
    }

    @Test func firstStopReasonStays() {
        var s = ScanSeries()
        s.start(onUsb: true)
        s.stop(.linkLost)
        #expect(s.stopReason == .linkLost)
        s.stop(.user) // the first reason stays
        #expect(s.stopReason == .linkLost)
    }

    @Test func userStopKeepsTheValues() throws {
        var s = ScanSeries()
        s.start(onUsb: true)
        s.record(try Self.reply([(Self.a, -60)]))
        s.stop(.user)
        #expect(s.stopReason == .user)
        #expect(s.stats.count == 1)
    }

    @Test func foldsMacCase() throws {
        var s = ScanSeries()
        s.start(onUsb: true)
        s.record(try Self.reply([(Self.a, -60)]))
        s.record(try Self.reply([(Self.a.lowercased(), -70)]))
        #expect(s.stats.count == 1)
        #expect(s.stats(for: Self.a.lowercased())?.count == 2)
    }

    @Test func ignoresRepliesAfterStop() throws {
        var s = ScanSeries()
        s.start(onUsb: true)
        s.stop(.user)
        s.record(try Self.reply([(Self.a, -60)]))
        #expect(s.stats.isEmpty)
    }
}
