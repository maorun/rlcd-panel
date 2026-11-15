import Foundation
import Testing
@testable import RlcdProtocol

struct ClimateLogTests {
    /// Header of `SdLogCodec::formatHeader` (v5).
    private static let headerV5 = "timestamp,battery_pct,battery_v,usb,onboard_temp_c,onboard_rh,"
        + "s1_temp_c,s1_rh,s1_battery_pct,s2_temp_c,s2_rh,s2_battery_pct,s3_temp_c,s3_rh,s3_battery_pct,"
        + "s4_temp_c,s4_rh,s4_battery_pct,s5_temp_c,s5_rh,s5_battery_pct,"
        + "noise_rms,noise_db,noise_band,noise_events,noise_l10,noise_l90"
    private static let headerV4 = "timestamp,battery_pct,battery_v,usb,onboard_temp_c,onboard_rh,"
        + "s1_temp_c,s1_rh,s1_battery_pct,s2_temp_c,s2_rh,s2_battery_pct,s3_temp_c,s3_rh,s3_battery_pct,"
        + "noise_rms,noise_db,noise_band,noise_events,noise_l10,noise_l90"
    private static let headerV3 = headerV4.replacingOccurrences(of: ",noise_l10,noise_l90", with: "")
    private static let headerV2 = headerV3.replacingOccurrences(of: ",noise_events", with: "")

    /// Row of `SdLogCodec::formatRow` (v5): onboard and slot 1 with values, slots 2 to 5 empty.
    private static let rowV5 = "2026-11-14 10:00:00,85,4.05,1,21.5,45.0,19.0,50,87,,,,,,,,,,,,,1200,48,QUIET,3,52,40"
    private static let rowV4 = "2026-10-01 09:00:00,70,3.90,0,20.0,40.5,18.5,55,,,,,,,,1000,45,QUIET,2,50,38"

    @Test func parsesClimateV5() throws {
        let log = try ClimateLog.parse(text: Self.headerV5 + "\n" + Self.rowV5 + "\n")
        #expect(log.format == .v5)
        #expect(log.rows.count == 1)
        let r = log.rows[0]
        #expect(r.timestamp == "2026-11-14 10:00:00")
        #expect(r.batteryPct == 85)
        #expect(r.batteryV == 4.05)
        #expect(r.usb == true)
        #expect(r.sensors.count == 6)
        #expect(r.sensors[0] == ClimateSensorReading(tempC: 21.5, rh: 45.0, batteryPct: nil))
        #expect(r.sensors[1] == ClimateSensorReading(tempC: 19.0, rh: 50, batteryPct: 87))
        #expect(r.sensors[2] == ClimateSensorReading(tempC: nil, rh: nil, batteryPct: nil))
        #expect(r.sensors[5].tempC == nil)
        #expect(r.noiseRms == 1200)
        #expect(r.noiseDb == 48)
        #expect(r.noiseBand == "QUIET")
        #expect(r.noiseEvents == 3)
        #expect(r.noiseL10 == 52)
        #expect(r.noiseL90 == 40)
    }

    @Test func parsesClimateV4() throws {
        let log = try ClimateLog.parse(text: Self.headerV4 + "\n" + Self.rowV4 + "\n")
        #expect(log.format == .v4)
        let r = log.rows[0]
        #expect(r.usb == false)
        #expect(r.sensors.count == 4)
        #expect(r.sensors[1].tempC == 18.5)
        #expect(r.sensors[1].batteryPct == nil) // empty field
        #expect(r.noiseDb == 45)
        #expect(r.noiseL10 == 50)
        #expect(r.noiseL90 == 38)
    }

    @Test func parsesClimateV3AndV2() throws {
        let v3 = "2026-09-01 09:00:00,70,3.90,0,20.0,40.5,,,,,,,,,,1000,45,QUIET,2"
        let l3 = try ClimateLog.parse(text: Self.headerV3 + "\n" + v3)
        #expect(l3.format == .v3)
        #expect(l3.rows[0].noiseEvents == 2)
        #expect(l3.rows[0].noiseL10 == nil)
        let v2 = "2026-08-01 09:00:00,70,3.90,0,20.0,40.5,,,,,,,,,,1000,45,QUIET"
        let l2 = try ClimateLog.parse(text: Self.headerV2 + "\n" + v2)
        #expect(l2.format == .v2)
        #expect(l2.rows[0].noiseEvents == nil)
        #expect(l2.rows[0].noiseBand == "QUIET")
    }

    @Test func emptyRowFieldsGiveNil() throws {
        let line = "2026-11-14 11:00:00" + String(repeating: ",", count: 26)
        let r = try ClimateLog.parse(text: Self.headerV5 + "\n" + line).rows[0]
        #expect(r.batteryPct == nil)
        #expect(r.usb == nil)
        #expect(r.sensors.allSatisfy { $0.tempC == nil && $0.rh == nil && $0.batteryPct == nil })
        #expect(r.noiseDb == nil)
        #expect(r.noiseBand == nil)
    }

    private func expectBadLine(_ text: String) {
        do {
            _ = try ClimateLog.parse(text: text)
            Issue.record("expected badLine")
        } catch let error as ParseError {
            guard case .badLine = error else { Issue.record("unexpected error \(error)"); return }
        } catch {
            Issue.record("unexpected error \(error)")
        }
    }

    @Test func wrongColumnCountThrowsBadLine() {
        let short = Self.rowV5.split(separator: ",", omittingEmptySubsequences: false).dropLast().joined(separator: ",")
        #expect(short.split(separator: ",", omittingEmptySubsequences: false).count == 26)
        expectBadLine(Self.headerV5 + "\n" + short)
        expectBadLine(Self.headerV4 + "\n" + Self.rowV5)
    }

    @Test func badFieldsThrowBadLine() {
        expectBadLine(Self.headerV5 + "\n" + Self.rowV5.replacingOccurrences(of: ",21.5,", with: ",abc,"))
        expectBadLine(Self.headerV5 + "\n" + Self.rowV5.replacingOccurrences(of: ",4.05,1,", with: ",4.05,2,"))
        expectBadLine(Self.headerV5 + "\n" + Self.rowV5.replacingOccurrences(of: "2026-11-14 10:00:00", with: "yesterday"))
    }

    @Test func acceptsBomAndCrlf() throws {
        let text = "\u{FEFF}" + Self.headerV5 + "\r\n" + Self.rowV5 + "\r\n"
        #expect(try ClimateLog.parse(text: text).rows.count == 1)
    }

    @Test func unknownHeaderThrowsMissingLine() {
        for text in ["a,b,c\n1,2,3\n", "a,b,c\n", ""] {
            do {
                _ = try ClimateLog.parse(text: text)
                Issue.record("expected missingLine")
            } catch let error as ParseError {
                #expect(error == .missingLine("header"))
            } catch {
                Issue.record("unexpected error \(error)")
            }
        }
    }

    @Test func recognizesFileNames() {
        for v in 2...5 { #expect(ClimateLog.isClimateFileName("climate_v\(v)_202611.csv")) }
        #expect(!ClimateLog.isClimateFileName("daily_v3_2026.csv"))
        #expect(!ClimateLog.isClimateFileName("climate_v1_202611.csv"))
        #expect(!ClimateLog.isClimateFileName("climate_v5_202613.csv"))
        #expect(!ClimateLog.isClimateFileName("../climate_v5_202611.csv"))
        #expect(ClimateLog.month(of: "climate_v4_202610.csv") == "202610")
        #expect(ClimateLog.month(of: "events_v1_2026.csv") == nil)
    }

    private func makeFolder() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("climate-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    @Test func loadReadsOneMonthAndNewerFormatWins() throws {
        let folder = try makeFolder()
        defer { try? FileManager.default.removeItem(at: folder) }
        let sameHourV4 = "2026-11-14 10:00:00,60,3.80,0,10.0,30.0,,,,,,,,,,1000,45,QUIET,2,50,38"
        let earlierV4 = "2026-11-14 09:00:00,60,3.80,0,11.0,31.0,,,,,,,,,,1000,45,QUIET,2,50,38"
        try (Self.headerV4 + "\n" + earlierV4 + "\n" + sameHourV4 + "\n")
            .write(to: folder.appendingPathComponent("climate_v4_202611.csv"), atomically: true, encoding: .utf8)
        try (Self.headerV5 + "\n" + Self.rowV5 + "\n")
            .write(to: folder.appendingPathComponent("climate_v5_202611.csv"), atomically: true, encoding: .utf8)
        try (Self.headerV4 + "\n" + Self.rowV4 + "\n")
            .write(to: folder.appendingPathComponent("climate_v4_202610.csv"), atomically: true, encoding: .utf8)
        try "broken".write(to: folder.appendingPathComponent("climate_v3_202611.csv"), atomically: true, encoding: .utf8)
        try Data([0xFF, 0xFE, 0x00]).write(to: folder.appendingPathComponent("climate_v2_202611.csv")) // not UTF-8

        let november = ClimateLog.load(from: folder, month: "202611")
        #expect(november.rows.map(\.timestamp) == ["2026-11-14 09:00:00", "2026-11-14 10:00:00"])
        #expect(november.rows[1].sensors[0].tempC == 21.5) // v5 wins
        #expect(november.rows[0].sensors[0].tempC == 11.0)
        #expect(november.failedFiles.sorted() == ["climate_v2_202611.csv", "climate_v3_202611.csv"])
        #expect(ClimateLog.load(from: folder, month: "202610").rows.count == 1)
        #expect(ClimateLog.load(from: folder, month: "202612").rows.isEmpty)
        #expect(ClimateLog.availableMonths(in: folder) == ["202610", "202611"])
    }

    @Test func loadListsTruncatedAndOversizedFilesAsFailed() throws {
        let folder = try makeFolder()
        defer { try? FileManager.default.removeItem(at: folder) }
        let truncated = String(Self.rowV5.dropLast(8)) // a power loss cut the last row
        try (Self.headerV5 + "\n" + Self.rowV5 + "\n" + truncated)
            .write(to: folder.appendingPathComponent("climate_v5_202611.csv"), atomically: true, encoding: .utf8)
        let big = folder.appendingPathComponent("climate_v4_202611.csv")
        FileManager.default.createFile(atPath: big.path, contents: nil)
        let handle = try FileHandle(forWritingTo: big)
        try handle.truncate(atOffset: UInt64(ReplyAssembler.maxFileBytes + 1)) // sparse file
        try handle.close()
        let result = ClimateLog.load(from: folder, month: "202611")
        #expect(result.rows.isEmpty)
        #expect(result.failedFiles.sorted() == ["climate_v4_202611.csv", "climate_v5_202611.csv"])
    }
}
