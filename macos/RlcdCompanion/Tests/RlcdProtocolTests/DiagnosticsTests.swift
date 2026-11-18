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

    @Test func parsesRtcTrimLine() throws {
        let d = try DiagnosticsReply.parse(otherLines: ["RTC": "RTC trim=-8 drift_ppm=--"])
        #expect(d.rtcTrim == RtcTrimMetrics(trim: -8, driftPpm: nil))
        #expect(d.rtcTrim?.canTrim == false)
        let figure = try RtcTrimMetrics.parse(line: "RTC trim=63 drift_ppm=-12")
        #expect(figure == RtcTrimMetrics(trim: 63, driftPpm: -12) && figure.canTrim)
        #expect(try RtcTrimMetrics.parse(line: "RTC trim=-- drift_ppm=35") == RtcTrimMetrics(trim: nil, driftPpm: 35))
        #expect(try RtcTrimMetrics.parse(line: "RTC trim=-64 drift_ppm=0").trim == -64)
        #expect(try !RtcTrimMetrics.parse(line: "RTC trim=0 drift_ppm=2").canTrim)
        #expect(try RtcTrimMetrics.parse(line: "RTC trim=0 drift_ppm=-3").canTrim)
    }

    @Test func rejectsBadRtcTrimLine() {
        for bad in ["RTC trim=x drift_ppm=--", "RTC trim=- drift_ppm=--", "RTC trim=64 drift_ppm=--",
                    "RTC trim=-65 drift_ppm=--", "RTC trim=1 drift_ppm=y", "RTC drift_ppm=--", "RTC trim=1",
                    "RTC trim=+1 drift_ppm=--"] {
            #expect(throws: ParseError.self) { try RtcTrimMetrics.parse(line: bad) }
        }
        #expect(ConsoleCommand.rtcTrim.line == "RTC TRIM")
    }

    @Test func missingLinesGiveNilWithoutError() throws {
        let d = try DiagnosticsReply.parse(otherLines: [:])
        #expect(d == DiagnosticsReply(shtc3: nil, ble: nil, reset: nil, runtime: nil, awake: nil, reception: nil))
        // An older firmware reply without the AWAKE line.
        let s = try StatusReply.parse(lines: lines(statusText))
        let old = try DiagnosticsReply.parse(status: s)
        #expect(old.awake == nil)
        #expect(old.shtc3 != nil)
    }

    @Test func receptionLineParses() throws {
        let m = try ReceptionMetrics.parse(line: "RECEPTION s1=12/24 s2=-- s3=24/24 s4=-- s5=1/3")
        #expect(m.slots == [
            .init(received: 12, scans: 24), nil, .init(received: 24, scans: 24), nil, .init(received: 1, scans: 3),
        ])
        #expect(m.slots[0]?.isWeak == false)  // exactly 50 %
        #expect(m.slots[4]?.isWeak == true)
        #expect(m.slots[2]?.percent == 100)
        let d = try DiagnosticsReply.parse(otherLines: ["RECEPTION": "RECEPTION s1=-- s2=-- s3=-- s4=-- s5=--"])
        #expect(d.reception?.slots.allSatisfy { $0 == nil } == true)
    }

    @Test func receptionLineRejectsBadValues() {
        #expect(throws: ParseError.self) { try ReceptionMetrics.parse(line: "RECEPTION s1=x/24 s2=-- s3=-- s4=-- s5=--") }
        #expect(throws: ParseError.self) { try ReceptionMetrics.parse(line: "RECEPTION s1=1/25 s2=-- s3=-- s4=-- s5=--") }
        #expect(throws: ParseError.self) { try ReceptionMetrics.parse(line: "RECEPTION s1=4/3 s2=-- s3=-- s4=-- s5=--") }
        #expect(throws: ParseError.self) { try ReceptionMetrics.parse(line: "RECEPTION s1=1/0 s2=-- s3=-- s4=-- s5=--") }
        #expect(throws: ParseError.self) { try ReceptionMetrics.parse(line: "RECEPTION s1=-- s2=-- s3=-- s4=--") }
    }

    @Test func shtc3RetriedField() throws {
        let with = try Shtc3Stats.parse(line: "SHTC3 nack=0 short=0 crc=0 run=0 retried=4 usb_offset=0.0")
        #expect(with.retried == 4)
        let old = try Shtc3Stats.parse(line: "SHTC3 nack=0 short=0 crc=0 run=0")
        #expect(old.retried == nil)
        #expect(throws: ParseError.self) { try Shtc3Stats.parse(line: "SHTC3 nack=0 short=0 crc=0 run=0 retried=x") }
    }

    @Test func bleRejectThermoproField() throws {
        let with = try BleRejectMetrics.parse(line: "BLE rejected=5 last=11:22:33:44:55:66 woio=1 meter=1 bthome=1 atc=1 thermopro=1")
        #expect(with.thermopro == 1)
        #expect(with.rejected == 5)
        let legacy = try BleRejectMetrics.parse(line: "BLE rejected=2 last=-- woio=1 meter=1 bthome=0 atc=0")
        #expect(legacy.thermopro == nil)
        #expect(throws: ParseError.self) { try BleRejectMetrics.parse(line: "BLE rejected=2 last=-- woio=1 meter=1 bthome=0 atc=0 thermopro=x") }
    }

    @Test func bleRejectUnassignedField() throws {
        let with = try BleRejectMetrics.parse(line: "BLE rejected=5 last=11:22:33:44:55:66 woio=1 meter=1 bthome=1 atc=1 thermopro=1 unassigned=3")
        #expect(with.unassigned == 3 && with.thermopro == 1 && with.rejected == 5)
        let old = try BleRejectMetrics.parse(line: "BLE rejected=5 last=11:22:33:44:55:66 woio=1 meter=1 bthome=1 atc=1 thermopro=1")
        #expect(old.unassigned == nil)
        #expect(throws: ParseError.self) { try BleRejectMetrics.parse(line: "BLE rejected=2 last=-- woio=1 meter=1 bthome=0 atc=0 thermopro=0 unassigned=x") }
    }

    @Test func bleRejectJumpsField() throws {
        let with = try BleRejectMetrics.parse(line: "BLE rejected=5 last=-- woio=1 meter=1 bthome=1 atc=1 thermopro=1 unassigned=3 jumps=7")
        #expect(with.jumps == 7 && with.unassigned == 3)
        let old = try BleRejectMetrics.parse(line: "BLE rejected=5 last=-- woio=1 meter=1 bthome=1 atc=1 thermopro=1 unassigned=3")
        #expect(old.jumps == nil)
        #expect(throws: ParseError.self) { try BleRejectMetrics.parse(line: "BLE rejected=2 last=-- woio=1 meter=1 bthome=0 atc=0 thermopro=0 unassigned=0 jumps=x") }
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
