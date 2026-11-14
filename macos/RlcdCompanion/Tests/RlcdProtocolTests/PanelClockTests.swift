import Foundation
import Testing
@testable import RlcdProtocol

struct PanelClockTests {
    private var utcCalendar: Calendar {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(secondsFromGMT: 0)!
        cal.locale = Locale(identifier: "en_US_POSIX")
        return cal
    }

    @Test func formatWinterAndSummerAsCetAndCest() throws {
        // Fixed winter date: 2026-01-15 12:00:00 UTC -> CET (UTC+1) is 13:00:00
        let winterUtc = try #require(utcCalendar.date(from: DateComponents(year: 2026, month: 1, day: 15, hour: 12, minute: 0, second: 0)))
        #expect(PanelClock.format(date: winterUtc) == "2026-01-15T13:00:00")

        // Fixed summer date: 2026-07-15 12:00:00 UTC -> CEST (UTC+2) is 14:00:00
        let summerUtc = try #require(utcCalendar.date(from: DateComponents(year: 2026, month: 7, day: 15, hour: 12, minute: 0, second: 0)))
        #expect(PanelClock.format(date: summerUtc) == "2026-07-15T14:00:00")
    }

    @Test func handleTwoDstChangeHours() throws {
        // Spring forward: 2026-03-29. At 02:00 CET, the clock jumps to 03:00 CEST.
        // 00:59:59 UTC -> 01:59:59 CET (+1h)
        let springBefore = try #require(utcCalendar.date(from: DateComponents(year: 2026, month: 3, day: 29, hour: 0, minute: 59, second: 59)))
        #expect(PanelClock.format(date: springBefore) == "2026-03-29T01:59:59")
        // 01:00:00 UTC -> 03:00:00 CEST (+2h)
        let springAfter = try #require(utcCalendar.date(from: DateComponents(year: 2026, month: 3, day: 29, hour: 1, minute: 0, second: 0)))
        #expect(PanelClock.format(date: springAfter) == "2026-03-29T03:00:00")

        // Fall back: 2026-10-25. At 03:00 CEST, the clock falls back to 02:00 CET.
        // 00:59:59 UTC -> 02:59:59 CEST (+2h)
        let fallBefore = try #require(utcCalendar.date(from: DateComponents(year: 2026, month: 10, day: 25, hour: 0, minute: 59, second: 59)))
        #expect(PanelClock.format(date: fallBefore) == "2026-10-25T02:59:59")
        // 01:00:00 UTC -> 02:00:00 CET (+1h)
        let fallAfter = try #require(utcCalendar.date(from: DateComponents(year: 2026, month: 10, day: 25, hour: 1, minute: 0, second: 0)))
        #expect(PanelClock.format(date: fallAfter) == "2026-10-25T02:00:00")
    }

    @Test func refuseYears2024And2100() throws {
        let year2024 = try #require(utcCalendar.date(from: DateComponents(year: 2024, month: 6, day: 15, hour: 12, minute: 0, second: 0)))
        #expect(PanelClock.format(date: year2024) == nil)
        #expect(ConsoleCommand.timeSet(year2024).line == nil)

        let year2100 = try #require(utcCalendar.date(from: DateComponents(year: 2100, month: 6, day: 15, hour: 12, minute: 0, second: 0)))
        #expect(PanelClock.format(date: year2100) == nil)
        #expect(ConsoleCommand.timeSet(year2100).line == nil)

        // Valid boundaries: 2025 and 2099
        let year2025 = try #require(utcCalendar.date(from: DateComponents(year: 2025, month: 1, day: 1, hour: 12, minute: 0, second: 0)))
        #expect(PanelClock.format(date: year2025) != nil)
        #expect(ConsoleCommand.timeSet(year2025).line?.hasPrefix("TIME SET 2025-01-01T") == true)

        let year2099 = try #require(utcCalendar.date(from: DateComponents(year: 2099, month: 12, day: 31, hour: 12, minute: 0, second: 0)))
        #expect(PanelClock.format(date: year2099) != nil)
        #expect(ConsoleCommand.timeSet(year2099).line?.hasPrefix("TIME SET 2099-12-31T") == true)
    }

    @Test func parseRecordedTimeReplyWithValidZeroAndOsfOne() throws {
        let reply = try TimeReply.parse("TIME 2026-01-01T00:00:00 valid=0 osf=1")
        #expect(reply.timeString == "2026-01-01T00:00:00")
        #expect(!reply.valid)
        #expect(reply.osf)
        #expect(reply.date != nil)

        let macDate = try #require(PanelClock.parse(text: "2026-01-01T00:00:15"))
        let offset = try #require(PanelClock.offsetSeconds(panelTime: reply, macDate: macDate))
        #expect(offset == -15)
    }

    @Test func buildEachBeepAndPatternLine() {
        for bit in 0...7 {
            #expect(ConsoleCommand.beep(bit).line == "BEEP \(bit)")
            #expect(ConsoleCommand.beep(bit: bit).line == "BEEP \(bit)")
        }
        for n in 0...2 {
            #expect(ConsoleCommand.pattern(n).line == "PATTERN \(n)")
            #expect(ConsoleCommand.pattern(n: n).line == "PATTERN \(n)")
        }
        #expect(ConsoleCommand.time.line == "TIME")
    }

    @Test func refuseBeep8AndPattern3() {
        #expect(ConsoleCommand.beep(8).line == nil)
        #expect(ConsoleCommand.beep(-1).line == nil)
        #expect(ConsoleCommand.pattern(3).line == nil)
        #expect(ConsoleCommand.pattern(-1).line == nil)
    }

    @Test func refuseTimeSetOnBleWithBleConsole1() {
        #expect(!PanelClock.isTimeSetAllowed(onUsb: false, bleConsoleLevel: 1))
        #expect(!PanelClock.isTimeSetAllowed(onUsb: false, bleConsoleLevel: 0))
        #expect(PanelClock.isTimeSetAllowed(onUsb: false, bleConsoleLevel: 2))
        #expect(PanelClock.isTimeSetAllowed(onUsb: true, bleConsoleLevel: 1))
        #expect(PanelClock.isTimeSetAllowed(onUsb: true, bleConsoleLevel: 0))
    }

    @Test func replyAssemblerConsumesTimeStatus() {
        var a = ReplyAssembler()
        let r = a.append(Data("TIME 2026-11-14T19:50:00 valid=1 osf=0\n".utf8))
        #expect(r == [.status("TIME 2026-11-14T19:50:00 valid=1 osf=0")])
    }

    @Test func appBundleInfoPlistHasRequiredKeys() throws {
        let testFile = URL(fileURLWithPath: #filePath)
        let pkgDir = testFile.deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let plistUrl = pkgDir.appendingPathComponent("Info.plist")
        let data = try Data(contentsOf: plistUrl)
        let plist = try #require(try PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Any])
        #expect(plist["CFBundleIdentifier"] as? String == "com.maorun.rlcd-companion")
        #expect(plist["CFBundleExecutable"] as? String == "RlcdCompanion")
        #expect(plist["CFBundlePackageType"] as? String == "APPL")
        #expect(plist["NSHighResolutionCapable"] as? Bool == true)
        let btDesc = try #require(plist["NSBluetoothAlwaysUsageDescription"] as? String)
        #expect(!btDesc.isEmpty)
    }
}
