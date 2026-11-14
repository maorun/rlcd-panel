import Foundation
import Testing
@testable import RlcdProtocol

private let fullStatus = statusText.replacingOccurrences(
    of: "RESET reason=POWERON abnormal=0", with: "RESET reason=TASK_WDT abnormal=2")
    .replacingOccurrences(of: "SHTC3 nack=0 short=0 crc=0 run=0", with: "SHTC3 nack=4 short=1 crc=2 run=3")
    + "AWAKE last_ms=850 avg_ms=900 max_ms=1200 samples=24\n"

private func lines(_ text: String) -> [String] { text.split(separator: "\n").map(String.init) }

@Suite struct DiagnosticsTests {
    @Test func parsesAllFiveLines() throws {
        let s = try StatusReply.parse(lines: lines(fullStatus))
        let d = try DiagnosticsReply.parse(status: s)
        #expect(d.shtc3 == Shtc3Stats(nack: 4, short: 1, crc: 2, run: 3))
        #expect(d.ble == BleRejectMetrics(rejected: 2, lastMac: "AA:BB:CC:DD:EE:FF", woio: 1, meter: 1, bthome: 0, atc: 0))
        #expect(d.reset == ResetMetrics(reason: "TASK_WDT", abnormalCount: 2))
        #expect(d.runtime == RuntimeMetrics(hours: nil, samples: 3, cycles: 1.2, scanSavedMs: 1500))
        #expect(d.awake == AwakeMetrics(lastMs: 850, avgMs: 900, maxMs: 1200, samples: 24))
    }

    @Test func dashFieldsGiveNil() throws {
        let d = try DiagnosticsReply.parse(otherLines: [
            "BLE": "BLE rejected=0 last=-- woio=0 meter=0 bthome=0 atc=0",
            "RESET": "RESET reason=POWERON abnormal=--",
            "RUNTIME": "RUNTIME h=-- samples=0 cycles=-- scan_saved_ms=0",
            "AWAKE": "AWAKE last_ms=-- avg_ms=-- max_ms=-- samples=0",
            "SHTC3": "SHTC3 nack=0 short=0 crc=0 run=--",
        ])
        #expect(d.ble?.lastMac == nil)
        #expect(d.reset?.abnormalCount == nil)
        #expect(d.runtime?.hours == nil)
        #expect(d.runtime?.cycles == nil)
        #expect(d.awake?.lastMs == nil)
        #expect(d.awake?.avgMs == nil)
        #expect(d.awake?.maxMs == nil)
        #expect(d.shtc3?.run == nil)
        #expect(d.shtc3?.isAlert == false)
    }

    @Test func missingLinesGiveNilWithoutError() throws {
        let d = try DiagnosticsReply.parse(otherLines: [:])
        #expect(d == DiagnosticsReply(shtc3: nil, ble: nil, reset: nil, runtime: nil, awake: nil))
        // An older firmware reply without the AWAKE line.
        let s = try StatusReply.parse(lines: lines(statusText))
        let old = try DiagnosticsReply.parse(status: s)
        #expect(old.awake == nil)
        #expect(old.shtc3 != nil)
    }

    @Test func nonNumericCounterThrows() {
        #expect(throws: ParseError.self) { try Shtc3Stats.parse(line: "SHTC3 nack=x short=0 crc=0 run=0") }
        #expect(throws: ParseError.self) { try DiagnosticsReply.parse(otherLines: ["AWAKE": "AWAKE last_ms=1 avg_ms=abc max_ms=1 samples=1"]) }
        #expect(throws: ParseError.self) { try BleRejectMetrics.parse(line: "BLE rejected=-1 last=-- woio=0 meter=0 bthome=0 atc=0") }
        #expect(throws: ParseError.self) { try RuntimeMetrics.parse(line: "RUNTIME h=nan samples=1 cycles=1.0 scan_saved_ms=0") }
        #expect(throws: ParseError.self) { try Shtc3Stats.parse(line: "SHTC3 nack=0 short=0 crc=0") }
        #expect(throws: ParseError.self) { try Shtc3Stats.parse(line: "SHTC3 nack=+1 short=0 crc=0 run=0") }
        #expect(throws: ParseError.self) { try RuntimeMetrics.parse(line: "RUNTIME h=1e3 samples=1 cycles=1.0 scan_saved_ms=0") }
    }

    @Test func parsesVersionReply() throws {
        let v = try VersionReply.parse(lines: ["VERSION fw=1.0.0 git=abc1234-dirty core=3.3.12 state=47 built=2026-11-03T04:05:06"])
        #expect(v == VersionReply(firmware: "1.0.0", git: "abc1234-dirty", core: "3.3.12", state: "47", built: "2026-11-03T04:05:06"))
        #expect(throws: ParseError.self) { try VersionReply.parse(lines: ["VERSION fw=1.0.0 git=x"]) }
        #expect(throws: ParseError.self) { try VersionReply.parse(lines: []) }
        #expect(ConsoleCommand.version.line == "VERSION")
    }

    @Test func refreshSendsStatusThenVersion() {
        #expect(diagnosticsRefreshCommands.compactMap(\.line) == ["STATUS", "VERSION"])
    }

    @Test func classifiesResetReasons() {
        for r in ["PANIC", "INT_WDT", "TASK_WDT", "WDT", "BROWNOUT"] {
            #expect(ResetMetrics.isAbnormal(reason: r), "\(r)")
        }
        for r in ["POWERON", "SW", "DEEPSLEEP", "EXT", "USB", "UNKNOWN"] {
            #expect(!ResetMetrics.isAbnormal(reason: r), "\(r)")
        }
    }

    @Test func redMarkAtRunThree() {
        #expect(!Shtc3Stats(nack: 0, short: 0, crc: 0, run: 2).isAlert)
        #expect(Shtc3Stats(nack: 0, short: 0, crc: 0, run: 3).isAlert)
        #expect(Shtc3Stats(nack: 0, short: 0, crc: 0, run: 255).isAlert)
    }
}
