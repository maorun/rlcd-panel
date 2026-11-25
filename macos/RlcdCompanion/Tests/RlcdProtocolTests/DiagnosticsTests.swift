import Foundation
import Testing
@testable import RlcdProtocol

private let fullStatus = statusText.replacingOccurrences(
    of: "RESET reason=POWERON abnormal=0", with: "RESET reason=TASK_WDT abnormal=2")
    .replacingOccurrences(of: "SHTC3 nack=0 short=0 crc=0 run=0", with: "SHTC3 nack=4 short=1 crc=2 run=3")
    + "AWAKE last_ms=850 avg_ms=900 max_ms=1200 samples=24\n"
    + "MEMORY free=212345 min=150000 largest=110592 psram=8300000\n"
    + "FRAME crc=1a2b3c4d skips=12\n"
    + "WAKE cause=TIMER boots=1234 battery_interval_s=600\n"

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

    @Test func parsesMemoryLine() throws {
        let s = try StatusReply.parse(lines: lines(fullStatus))
        let d = try DiagnosticsReply.parse(status: s)
        #expect(d.memory == MemoryMetrics(free: 212345, min: 150000, largest: 110592, psram: 8300000))
        #expect(d.memory?.isLow == false)
        let low = try MemoryMetrics.parse(line: "MEMORY free=1 min=1 largest=16383 psram=0")
        #expect(low.isLow)
        #expect(try MemoryMetrics.parse(line: "MEMORY free=1 min=1 largest=16384 psram=0").isLow == false)
    }

    @Test func missingMemoryLineGivesNil() throws {
        let d = try DiagnosticsReply.parse(otherLines: [:])
        #expect(d.memory == nil)
    }

    @Test func badMemoryFieldThrows() {
        #expect(throws: ParseError.self) { try MemoryMetrics.parse(line: "MEMORY free=x min=1 largest=1 psram=1") }
        #expect(throws: ParseError.self) { try DiagnosticsReply.parse(otherLines: ["MEMORY": "free=x"]) }
        #expect(throws: ParseError.self) { try MemoryMetrics.parse(line: "MEMORY free=1 min=1 largest=1") }
    }

    @Test func parsesFrameLine() throws {
        let s = try StatusReply.parse(lines: lines(fullStatus))
        let d = try DiagnosticsReply.parse(status: s)
        #expect(d.frame == FrameMetrics(crc: 0x1a2b3c4d, skips: 12))
        #expect(try FrameMetrics.parse(line: "FRAME crc=0000abcd skips=0") == FrameMetrics(crc: 0xabcd, skips: 0))
    }

    @Test func missingOrDashFrameLineGivesNil() throws {
        #expect(try DiagnosticsReply.parse(otherLines: [:]).frame == nil)
        #expect(try FrameMetrics.parse(line: "FRAME crc=-- skips=0") == nil)
        #expect(try DiagnosticsReply.parse(otherLines: ["FRAME": "FRAME crc=-- skips=0"]).frame == nil)
    }

    @Test func badFrameFieldThrows() {
        #expect(throws: ParseError.self) { try FrameMetrics.parse(line: "FRAME crc=1a2b3c4d skips=x") }
        #expect(throws: ParseError.self) { try FrameMetrics.parse(line: "FRAME crc=1a2b3c skips=1") }
        #expect(throws: ParseError.self) { try FrameMetrics.parse(line: "FRAME crc=zzzzzzzz skips=1") }
        #expect(throws: ParseError.self) { try FrameMetrics.parse(line: "FRAME crc=1a2b3c4d") }
        #expect(throws: ParseError.self) { try DiagnosticsReply.parse(otherLines: ["FRAME": "FRAME crc=1a2b3c4d skips=x"]) }
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

    @Test func ntpLine() throws {
        let d = try DiagnosticsReply.parse(otherLines: ["NTP": "NTP ok=0 failures=4 age_s=900 next_s=--"])
        #expect(d.ntp == NtpMetrics(ok: false, failures: 4, ageSeconds: 900, nextSeconds: nil))
        #expect(d.ntp?.isWarning == true)
        let good = try NtpMetrics.parse(line: "NTP ok=1 failures=0 age_s=60 next_s=86340")
        #expect(good == NtpMetrics(ok: true, failures: 0, ageSeconds: 60, nextSeconds: 86340) && good?.isWarning == false)
        #expect(try NtpMetrics.parse(line: "NTP ok=0 failures=2 age_s=1 next_s=1")?.isWarning == false)
        #expect(try NtpMetrics.parse(line: "NTP ok=-- failures=0 age_s=-- next_s=--") == nil)
        #expect(try DiagnosticsReply.parse(otherLines: ["NTP": "NTP ok=-- failures=0 age_s=-- next_s=--"]).ntp == nil)
        #expect(try DiagnosticsReply.parse(otherLines: [:]).ntp == nil)
        for bad in ["NTP ok=0 failures=x age_s=1 next_s=1", "NTP ok=2 failures=0 age_s=1 next_s=1",
                    "NTP ok=1 age_s=1 next_s=1", "NTP ok=1 failures=0 age_s=y next_s=1"] {
            #expect(throws: ParseError.badLine(bad)) { try NtpMetrics.parse(line: bad) }
        }
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

    @Test func parsesWakeLine() throws {
        let d = try DiagnosticsReply.parse(status: try StatusReply.parse(lines: lines(fullStatus)))
        #expect(d.wake == WakeMetrics(cause: "TIMER", boots: 1234, batteryIntervalS: 600))
    }

    @Test func wakeIsNilWithoutTheLine() throws {
        let d = try DiagnosticsReply.parse(status: try StatusReply.parse(lines: lines(statusText)))
        #expect(d.wake == nil)
        #expect(try WakeMetrics.parse(lines: lines(statusText)) == nil)
    }

    @Test func parsesLatchLine() throws {
        let m = try LatchMetrics.parse(line: "LATCH vent=002 dry=401 batlow=208 stale=004")
        #expect(m.ventilate == 0x002)
        #expect(m.dry == 0x401)
        #expect(m.batteryLow == 0x208)
        #expect(m.stale == 0x004)
        #expect(m.badges(forSensor: 0) == ["D"])
        #expect(m.badges(forSensor: 1) == ["V"])
        #expect(m.badges(forSensor: 3) == ["STALE"])
        #expect(m.badges(forSensor: 4) == ["BAT LOW"])
        #expect(m.badges(forSensor: 10) == ["D", "BAT LOW"])
        #expect(m.badges(forSensor: 11).isEmpty)
        let d = try DiagnosticsReply.parse(otherLines: ["LATCH": "LATCH vent=000 dry=000 batlow=000 stale=000"])
        #expect(d.latch?.hasBadges == false)
    }

    @Test func latchOnboardIgnoresSlotOnlyMasksAndKeepsOrder() throws {
        let m = try LatchMetrics.parse(line: "LATCH vent=000 dry=000 batlow=001 stale=001")
        #expect(m.badges(forSensor: 0).isEmpty)
        #expect(m.badges(forSensor: 1) == ["BAT LOW", "STALE"])
        let all = try LatchMetrics.parse(line: "LATCH vent=7FF dry=7ff batlow=3ff stale=3FF")
        #expect(all.badges(forSensor: 10) == ["V", "D", "BAT LOW", "STALE"])
        #expect(all.badges(forSensor: 0) == ["V", "D"])
    }

    @Test func parsesWallAndWindowMasks() throws {
        let m = try LatchMetrics.parse(line: "LATCH vent=002 dry=000 batlow=008 stale=004 wall=002 window=402")
        #expect(m.wall == 0x002)
        #expect(m.window == 0x402)
        #expect(m.badges(forSensor: 1) == ["V", "Wall", "Window"])
        #expect(m.badges(forSensor: 10) == ["Window"])
        #expect(m.badges(forSensor: 0).isEmpty)
        let only = try LatchMetrics.parse(line: "LATCH vent=000 dry=000 batlow=000 stale=000 wall=000 window=001")
        #expect(only.hasBadges)
        #expect(only.badges(forSensor: 0) == ["Window"])
        let wallOnly = try LatchMetrics.parse(line: "LATCH vent=000 dry=000 batlow=000 stale=000 wall=004 window=000")
        #expect(wallOnly.hasBadges)
        let none = try LatchMetrics.parse(line: "LATCH vent=000 dry=000 batlow=000 stale=000 wall=000 window=000")
        #expect(!none.hasBadges)
    }

    private func statusFixtureText() throws -> String {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { url.deleteLastPathComponent() }
        return try String(contentsOf: url.appendingPathComponent("tests/fixtures/status_reply_sample.txt"), encoding: .utf8)
    }

    @Test func parsesWallAndWindowFromSharedFixture() throws {
        let m = try #require(try LatchMetrics.parse(lines: lines(try statusFixtureText())))
        #expect(m.wall == 0x002)
        #expect(m.window == 0x402)
    }

    @Test func wallAndWindowAreNilForOlderFirmware() throws {
        let m = try LatchMetrics.parse(line: "LATCH vent=002 dry=000 batlow=008 stale=004")
        #expect(m.wall == nil)
        #expect(m.window == nil)
        #expect(m.badges(forSensor: 1) == ["V"])
    }

    @Test func wallOrWindowAloneParses() throws {
        let base = "LATCH vent=000 dry=000 batlow=000 stale=000"
        let wallOnly = try LatchMetrics.parse(line: base + " wall=002")
        #expect(wallOnly.wall == 0x002)
        #expect(wallOnly.window == nil)
        #expect(wallOnly.badges(forSensor: 1) == ["Wall"])
        let windowOnly = try LatchMetrics.parse(line: base + " window=7FF")
        #expect(windowOnly.wall == nil)
        #expect(windowOnly.window == 0x7ff)
        #expect(windowOnly.hasBadges)
        #expect(windowOnly.badges(forSensor: 10) == ["Window"])
    }

    @Test func wallAndWindowRejectBadFields() {
        let base = "LATCH vent=000 dry=000 batlow=000 stale=000"
        for bad in [base + " wall=0x2 window=000", base + " wall=800 window=000", base + " wall=000 window=800",
                    base + " wall=00 window=000", base + " wall=000 window=zzz", base + " wall= window=000"] {
            #expect(throws: ParseError.badLine(bad)) { try LatchMetrics.parse(line: bad) }
        }
    }

    @Test func parsesReceptionLowFromSharedFixture() throws {
        let m = try #require(try LatchMetrics.parse(lines: lines(try statusFixtureText())))
        #expect(m.receptionLow == 0x202)
        #expect(m.hasWeakSignal(slot: 2))
        #expect(m.hasWeakSignal(slot: 10))
        #expect(!m.hasWeakSignal(slot: 1))
        #expect(!m.hasWeakSignal(slot: 0))
        #expect(!m.hasWeakSignal(slot: 11))
        #expect(m.badges(forSensor: 2) == ["RX LOW"])
        #expect(m.badges(forSensor: 0).isEmpty)
    }

    @Test func receptionLowIsNilForOlderFirmware() throws {
        let m = try LatchMetrics.parse(line: "LATCH vent=002 dry=000 batlow=008 stale=004 wall=002 window=402")
        #expect(m.receptionLow == nil)
        #expect(!m.hasWeakSignal(slot: 1))
    }

    @Test func receptionLowAloneSetsHasBadges() throws {
        let base = "LATCH vent=000 dry=000 batlow=000 stale=000 wall=000 window=000"
        let only = try LatchMetrics.parse(line: base + " rxlow=001")
        #expect(only.hasBadges)
        #expect(only.badges(forSensor: 1) == ["RX LOW"])
        #expect(only.badges(forSensor: 0).isEmpty)
        let none = try LatchMetrics.parse(line: base + " rxlow=000")
        #expect(none.receptionLow == 0)
        #expect(!none.hasBadges)
        #expect(try LatchMetrics.parse(line: base + " rxlow=3FF").receptionLow == 0x3ff)
    }

    @Test func parsesDriftFromSharedFixture() throws {
        let m = try #require(try LatchMetrics.parse(lines: lines(try statusFixtureText())))
        #expect(m.drift == 0x002)
        #expect(m.hasDrift(sensor: 1))
        #expect(!m.hasDrift(sensor: 0))
        #expect(!m.hasDrift(sensor: 11))
    }

    @Test func driftBoundsAndSensorRange() throws {
        let base = "LATCH vent=000 dry=000 batlow=000 stale=000 wall=000 window=000 rxlow=000"
        let all = try LatchMetrics.parse(line: base + " drift=7FF")
        #expect(all.drift == 0x7ff)
        #expect((0...10).allSatisfy { all.hasDrift(sensor: $0) })
        #expect(!all.hasDrift(sensor: -1))
        #expect(!all.hasDrift(sensor: 11))
    }

    @Test func driftIsNilWithoutField() throws {
        let m = try LatchMetrics.parse(line: "LATCH vent=002 dry=000 batlow=008 stale=004 wall=002 window=402 rxlow=202")
        #expect(m.drift == nil)
        #expect(!m.hasDrift(sensor: 1))
    }

    @Test func driftRejectsBadFields() {
        let base = "LATCH vent=000 dry=000 batlow=000 stale=000 wall=000 window=000 rxlow=000"
        for bad in [base + " drift=zzz", base + " drift=800", base + " drift=00", base + " drift=0x1", base + " drift="] {
            #expect(throws: ParseError.badLine(bad)) { try LatchMetrics.parse(line: bad) }
        }
    }

    @Test func receptionLowRejectsBadFields() {
        let base = "LATCH vent=000 dry=000 batlow=000 stale=000 wall=000 window=000"
        for bad in [base + " rxlow=zzz", base + " rxlow=400", base + " rxlow=00", base + " rxlow=0x1", base + " rxlow="] {
            #expect(throws: ParseError.badLine(bad)) { try LatchMetrics.parse(line: bad) }
        }
    }

    @Test func latchIsNilWithoutTheLine() throws {
        let d = try DiagnosticsReply.parse(otherLines: [:])
        #expect(d.latch == nil)
        #expect(try LatchMetrics.parse(lines: ["WAKE cause=TIMER boots=1 battery_interval_s=600"]) == nil)
    }

    @Test func parsesNoiseLine() throws {
        let m = try NoiseMetrics.parse(line: "NOISE db=48 band=MODERATE peak_db=71 peak_hour=19 events_sum=12")
        #expect(m == NoiseMetrics(db: 48, band: "MODERATE", peakDb: 71, peakHour: 19, eventsSum: 12))
        let empty = try NoiseMetrics.parse(line: "NOISE db=-- band=QUIET peak_db=-- peak_hour=-- events_sum=0")
        #expect(empty.db == nil)
        #expect(empty.peakDb == nil)
        #expect(empty.peakHour == nil)
        let d = try DiagnosticsReply.parse(otherLines: ["NOISE": "NOISE db=48 band=LOUD peak_db=71 peak_hour=0 events_sum=1"])
        #expect(d.noise?.band == "LOUD")
        #expect(try DiagnosticsReply.parse(otherLines: [:]).noise == nil)
    }

    @Test func parsesChimeLine() throws {
        let m = try ChimeMetrics.parse(line: "CHIME repeat_h=2 bits=02 last_s=3600 next_s=3600")
        #expect(m == ChimeMetrics(repeatHours: 2, bits: 2, lastS: 3600, nextS: 3600))
        let noNext = try ChimeMetrics.parse(line: "CHIME repeat_h=2 bits=02 last_s=3600 next_s=--")
        #expect(noNext.nextS == nil)
        let snoozed = try ChimeMetrics.parse(line: "CHIME repeat_h=0 bits=00 last_s=-- next_s=-- snooze_s=3540")
        #expect(snoozed.snoozeS == 3540 && snoozed.snoozeText == "Muted for 0:59")
        #expect(try ChimeMetrics.parse(line: "CHIME repeat_h=0 bits=00 last_s=-- next_s=-- snooze_s=--").snoozeS == nil)
        #expect(m.snoozeS == nil && m.snoozeText == nil)
        #expect(try ChimeMetrics.parse(line: "CHIME repeat_h=0 bits=00 last_s=-- next_s=-- snooze_s=7200").snoozeText == "Muted for 2:00")
        #expect(try ChimeMetrics.parse(line: "CHIME repeat_h=0 bits=00 last_s=-- next_s=-- snooze_s=0").snoozeText == nil)
        let badSnooze = "CHIME repeat_h=0 bits=00 last_s=-- next_s=-- snooze_s=x"
        #expect(throws: ParseError.badLine(badSnooze)) { try ChimeMetrics.parse(line: badSnooze) }
        let noLast = try ChimeMetrics.parse(line: "CHIME repeat_h=0 bits=00 last_s=-- next_s=--")
        #expect(noLast.lastS == nil)
        let d = try DiagnosticsReply.parse(otherLines: ["CHIME": "CHIME repeat_h=2 bits=02 last_s=3600 next_s=3600"])
        #expect(d.chime?.nextS == 3600)
        #expect(try DiagnosticsReply.parse(otherLines: [:]).chime == nil)
    }

    @Test func chimeRejectsBadFields() {
        for bad in ["CHIME repeat_h=25 bits=02 last_s=1 next_s=1", "CHIME repeat_h=2 bits=2 last_s=1 next_s=1",
                    "CHIME repeat_h=2 bits=0g last_s=1 next_s=1", "CHIME repeat_h=2 bits=02 last_s=x next_s=1",
                    "CHIME repeat_h=2 bits=02 last_s=1 next_s=-1", "CHIME repeat_h=2 bits=02 last_s=1",
                    "CHIME repeat_h=-- bits=02 last_s=1 next_s=1", "CHIME repeat_h=2 last_s=1 next_s=1"] {
            #expect(throws: ParseError.badLine(bad)) { try ChimeMetrics.parse(line: bad) }
        }
    }

    @Test func chimeReminderTexts() throws {
        #expect(try ChimeMetrics.parse(line: "CHIME repeat_h=2 bits=02 last_s=100 next_s=5400").reminderText == "Next reminder in 1 h 30 min")
        #expect(try ChimeMetrics.parse(line: "CHIME repeat_h=0 bits=02 last_s=-- next_s=--").reminderText == "Reminder off")
        #expect(try ChimeMetrics.parse(line: "CHIME repeat_h=2 bits=00 last_s=-- next_s=--").reminderText == "No reminder due")
        #expect(try ChimeMetrics.parse(line: "CHIME repeat_h=2 bits=00 last_s=60 next_s=60").reminderText == "No reminder due")
        #expect(try ChimeMetrics.parse(line: "CHIME repeat_h=2 bits=02 last_s=-- next_s=--").reminderText == "No reminder due")
        // An overdue reminder has next_s 0: the nominal due time is now.
        #expect(try ChimeMetrics.parse(line: "CHIME repeat_h=2 bits=02 last_s=7200 next_s=0").reminderText == "Next reminder in 0 h 0 min")
        #expect(try ChimeMetrics.parse(line: "CHIME repeat_h=24 bits=FF last_s=0 next_s=86400").reminderText == "Next reminder in 24 h 0 min")
    }

    @Test func parsesQuietLine() throws {
        let m = try QuietMetrics.parse(line: "QUIET start_h=22 end_h=7 active=1 night_interval_s=900")
        #expect(m == QuietMetrics(startHour: 22, endHour: 7, active: true, nightIntervalS: 900))
        #expect(m.windowText == "22:00 to 07:00")
        #expect(m.activeText == "active now")
        #expect(m.nightIntervalText == "Night interval 900 s")
        let inactive = try QuietMetrics.parse(line: "QUIET start_h=22 end_h=7 active=0 night_interval_s=0")
        #expect(inactive.active == false)
        #expect(inactive.activeText == "not active")
        #expect(inactive.nightIntervalText == "Night interval off")
        let unknown = try QuietMetrics.parse(line: "QUIET start_h=22 end_h=7 active=-- night_interval_s=900")
        #expect(unknown.active == nil)
        #expect(unknown.activeText == "--")
        let d = try DiagnosticsReply.parse(otherLines: ["QUIET": "QUIET start_h=22 end_h=7 active=1 night_interval_s=900"])
        #expect(d.quiet == m)
        #expect(try DiagnosticsReply.parse(otherLines: [:]).quiet == nil)
    }

    @Test func quietRejectsBadFields() {
        for bad in ["QUIET start_h=24 end_h=7 active=1 night_interval_s=900",
                    "QUIET start_h=22 end_h=24 active=1 night_interval_s=900",
                    "QUIET start_h=22 end_h=7 active=2 night_interval_s=900",
                    "QUIET start_h=22 end_h=7 active=1 night_interval_s=x",
                    "QUIET start_h=-- end_h=7 active=1 night_interval_s=900",
                    "QUIET start_h=22 end_h=7 active=1"] {
            #expect(throws: ParseError.badLine(bad)) { try QuietMetrics.parse(line: bad) }
        }
    }

    @Test func noiseRejectsBadFields() {
        for bad in ["NOISE db=48 band=LOUDER peak_db=71 peak_hour=19 events_sum=12",
                    "NOISE db=141 band=LOUD peak_db=71 peak_hour=19 events_sum=12",
                    "NOISE db=48 band=LOUD peak_db=141 peak_hour=19 events_sum=12",
                    "NOISE db=48 band=LOUD peak_db=71 peak_hour=24 events_sum=12",
                    "NOISE db=4x band=LOUD peak_db=71 peak_hour=19 events_sum=12",
                    "NOISE db=48 band=LOUD peak_db=71 peak_hour=19 events_sum=--",
                    "NOISE db=48 band=LOUD peak_db=71 peak_hour=19"] {
            #expect(throws: ParseError.badLine(bad)) { try NoiseMetrics.parse(line: bad) }
        }
    }

    @Test func latchRejectsBadFields() {
        for bad in ["LATCH vent=80x dry=000 batlow=000 stale=000", "LATCH vent=000 dry=800 batlow=000 stale=000",
                    "LATCH vent=000 dry=000 batlow=400 stale=000", "LATCH vent=000 dry=000 batlow=000 stale=400",
                    "LATCH vent=00 dry=000 batlow=000 stale=000", "LATCH vent=000 dry=000 batlow=000"] {
            #expect(throws: ParseError.badLine(bad)) { try LatchMetrics.parse(line: bad) }
        }
    }

    @Test func wakeRejectsBadFields() {
        #expect(throws: ParseError.self) { try WakeMetrics.parse(line: "WAKE cause=SLEEP boots=1 battery_interval_s=600") }
        #expect(throws: ParseError.self) { try WakeMetrics.parse(line: "WAKE cause=TIMER boots=x battery_interval_s=600") }
        #expect(throws: ParseError.self) { try WakeMetrics.parse(line: "WAKE cause=TIMER boots=1") }
        #expect(throws: ParseError.self) { try WakeMetrics.parse(line: "WAKE cause=TIMER boots=-1 battery_interval_s=600") }
        #expect(throws: ParseError.self) { try WakeMetrics.parse(line: "WAKE boots=1 battery_interval_s=600") }
        #expect(throws: ParseError.self) { try DiagnosticsReply.parse(otherLines: ["WAKE": "WAKE cause=SLEEP boots=1 battery_interval_s=6"]) }
    }

    @Test func wakeNightInterval() throws {
        let config = ConfigReply(values: ["night_wake_interval_s": "1200", "wake_interval_s": "600"])
        let night = try WakeMetrics.parse(line: "WAKE cause=TIMER boots=1 battery_interval_s=1200")
        let day = try WakeMetrics.parse(line: "WAKE cause=TIMER boots=1 battery_interval_s=600")
        #expect(night.isNightInterval(config: config))
        #expect(!day.isNightInterval(config: config))
        #expect(!night.isNightInterval(config: nil))
        let off = ConfigReply(values: ["night_wake_interval_s": "0", "wake_interval_s": "600"])
        #expect(!night.isNightInterval(config: off))
        let noWake = ConfigReply(values: ["night_wake_interval_s": "1200"])
        #expect(!night.isNightInterval(config: noWake))
        let same = ConfigReply(values: ["night_wake_interval_s": "600", "wake_interval_s": "600"])
        #expect(!day.isNightInterval(config: same))
    }

    private func selfTestFixture() throws -> [String] {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { url.deleteLastPathComponent() }
        let text = try String(contentsOf: url.appendingPathComponent("tests/fixtures/selftest_reply_sample.txt"), encoding: .utf8)
        return lines(text).filter { !$0.hasPrefix("CRC32") }
    }

    @Test func parsesSelfTestFromSharedFixture() throws {
        let r = try SelfTestReply.parse(lines: try selfTestFixture())
        #expect(r.checks.map(\.name) == ["shtc3", "rtc", "sd", "battery", "mic", "ble"])
        #expect(r.checks.allSatisfy { $0.state == .ok })
        #expect(r.checks[3].detail == "mv=3900")
        #expect(r.checks[4].detail == "L=OK,R=OK")
        #expect(r.pass == 6 && r.fail == 0 && r.passed)
    }

    @Test func selfTestCountsFailAndSkip() throws {
        let r = try SelfTestReply.parse(lines: [
            "BEGIN SELFTEST", "CHECK shtc3 OK READ", "CHECK sd FAIL NO_CARD", "CHECK ble SKIP CONSOLE", "END SELFTEST pass=1 fail=1"])
        #expect(r.checks[1].state == .fail && r.checks[2].state == .skip)
        #expect(!r.passed)
    }

    @Test func selfTestRejectsUnknownStateAndBadCounts() throws {
        var bad = try selfTestFixture()
        bad[1] = "CHECK shtc3 MAYBE READ"
        #expect(throws: ParseError.badLine("CHECK shtc3 MAYBE READ")) { try SelfTestReply.parse(lines: bad) }
        var counts = try selfTestFixture()
        counts[counts.count - 1] = "END SELFTEST pass=5 fail=1"
        #expect(throws: ParseError.badLine("END SELFTEST pass=5 fail=1")) { try SelfTestReply.parse(lines: counts) }
        #expect(throws: ParseError.missingLine("END SELFTEST")) { try SelfTestReply.parse(lines: ["BEGIN SELFTEST"]) }
    }

    @Test func reportSummarizesSelfTest() throws {
        let fixture = try selfTestFixture()
        let text = DiagnosticsReport.build(appVersion: "1", link: .usb, includeNames: false,
            sections: [.init(command: "SELFTEST", result: .success(fixture + ["CRC32 232110fc"]))])
        #expect(text.contains("Self-test: passed, 6 ok, 0 failed"))
        #expect(!text.contains("CRC32"))
    }

    @Test func reportMarksFailedSelfTest() throws {
        let text = DiagnosticsReport.build(appVersion: "1", link: .usb, includeNames: false,
            sections: [.init(command: "SELFTEST", result: .success([
                "BEGIN SELFTEST", "CHECK shtc3 OK READ", "CHECK sd FAIL NO_CARD", "CHECK ble SKIP CONSOLE",
                "END SELFTEST pass=1 fail=1", "CRC32 00000000"]))])
        #expect(text.contains("Self-test: FAILED, 1 ok, 1 failed"))
        #expect(!text.contains("CRC32"))
    }
}
