import Foundation
import Testing
@testable import RlcdProtocol

struct MenuBarSummaryTests {
    let now = Date(timeIntervalSince1970: 1_800_000_000)

    func frame(valid: [Int], alerts: UInt8 = 0) -> TelemetryFrame {
        let tiles = (0..<TelemetryFrame.tileCount).map { i in
            valid.contains(i)
                ? TelemetryTile(temperatureC: 20.0 + Double(i), humidityPct: 40.5)
                : TelemetryTile(temperatureC: nil, humidityPct: nil)
        }
        return TelemetryFrame(sequence: 1, cellPercent: 80, alerts: AlertMask(rawValue: alerts), tiles: tiles)
    }

    func config(_ lines: [String]) throws -> ConfigReply {
        try ConfigReply.parse(lines: lines)
    }

    @Test func oneLinePerValidTile() throws {
        let cfg = try config(["onboard_name=Office", "slot1_name=Cellar", "temp_unit=C"])
        let s = MenuBarSummary.make(frame: frame(valid: [0, 1, 4]), frameDate: now, config: cfg, now: now)
        #expect(s.lines.count == 3)
        #expect(s.lines[0] == "Office: 20.0 °C, 40.5 %")
        #expect(s.lines[1] == "Cellar: 21.0 °C, 40.5 %")
        #expect(s.lines[2] == "Tile 5: 24.0 °C, 40.5 %")
    }

    @Test func fallbackNameWithoutConfig() {
        let s = MenuBarSummary.make(frame: frame(valid: [2]), frameDate: now, config: nil, now: now)
        #expect(s.lines == ["Tile 3: 22.0 °C, 40.5 %"])
    }

    @Test func fahrenheitConversion() throws {
        let cfg = try config(["slot3_name=Attic", "temp_unit=F"])
        let s = MenuBarSummary.make(frame: frame(valid: [3]), frameDate: now, config: cfg, now: now)
        #expect(s.lines == ["Attic: 73.4 °F, 40.5 %"])
    }

    @Test func alertSymbolForMask0x04() {
        let alert = MenuBarSummary.make(frame: frame(valid: [0], alerts: 0x04), frameDate: now, config: nil, now: now)
        #expect(alert.hasAlert)
        #expect(alert.symbolName == "exclamationmark.triangle.fill")
        #expect(alert.alertNames == ["NOISE HIGH"])
        let calm = MenuBarSummary.make(frame: frame(valid: [0]), frameDate: now, config: nil, now: now)
        #expect(!calm.hasAlert)
        #expect(calm.symbolName == "thermometer.medium")
    }

    @Test func staleAfterFifteenMinutes() {
        let fresh = MenuBarSummary.make(frame: frame(valid: [0]), frameDate: now.addingTimeInterval(-14 * 60), config: nil, now: now)
        #expect(!fresh.isStale)
        let old = MenuBarSummary.make(frame: frame(valid: [0]), frameDate: now.addingTimeInterval(-16 * 60), config: nil, now: now)
        #expect(old.isStale)
    }

    @Test func missingFrameIsStaleWithoutLines() {
        let s = MenuBarSummary.make(frame: nil, frameDate: nil, config: nil, now: now)
        #expect(s.lines.isEmpty)
        #expect(s.isStale)
    }

    @Test func staleBoundaryIsExactlyFifteenMinutes() {
        let edge = MenuBarSummary.make(frame: frame(valid: [0]), frameDate: now.addingTimeInterval(-15 * 60), config: nil, now: now)
        #expect(!edge.isStale)
    }

    @Test func frameWithoutValidTileHasNoLines() {
        let s = MenuBarSummary.make(frame: frame(valid: []), frameDate: now, config: nil, now: now)
        #expect(s.lines.isEmpty)
        #expect(s.labelText == "-- °C")
    }

    @Test func tileWithoutHumidityShowsTemperatureOnly() {
        var tiles = frame(valid: [0]).tiles
        tiles[0] = TelemetryTile(temperatureC: 21.0, humidityPct: nil)
        let f = TelemetryFrame(sequence: 1, cellPercent: nil, alerts: [], tiles: tiles)
        let s = MenuBarSummary.make(frame: f, frameDate: now, config: nil, now: now)
        #expect(s.lines == ["Tile 1: 21.0 °C"])
    }

    @Test func labelFollowsTempUnit() throws {
        let cfg = try config(["temp_unit=F"])
        let s = MenuBarSummary.make(frame: frame(valid: [0]), frameDate: now, config: cfg, now: now)
        #expect(s.labelText == "68.0 °F")
        let c = MenuBarSummary.make(frame: frame(valid: [0]), frameDate: now, config: nil, now: now)
        #expect(c.labelText == "20.0 °C")
    }

    @Test func missingFrameLabelFollowsTempUnit() throws {
        let cfg = try config(["temp_unit=F"])
        let s = MenuBarSummary.make(frame: nil, frameDate: nil, config: cfg, now: now)
        #expect(s.labelText == "-- °F")
    }

    @Test func labelExpiresAfterLongestWakeInterval() {
        let ok = MenuBarSummary.make(frame: frame(valid: [0]), frameDate: now.addingTimeInterval(-1800), config: nil, now: now)
        #expect(ok.labelText == "20.0 °C")
        let old = MenuBarSummary.make(frame: frame(valid: [0]), frameDate: now.addingTimeInterval(-1900), config: nil, now: now)
        #expect(old.labelText == "-- °C")
    }

    @Test func missingFrameDateIsStale() {
        let s = MenuBarSummary.make(frame: frame(valid: [0]), frameDate: nil, config: nil, now: now)
        #expect(s.isStale)
        #expect(s.labelText == "-- °C")
    }

    @Test func invalidTempUnitFallsBackToCelsius() throws {
        let cfg = try config(["temp_unit=K"])
        let s = MenuBarSummary.make(frame: frame(valid: [0]), frameDate: now, config: cfg, now: now)
        #expect(s.lines == ["Tile 1: 20.0 °C, 40.5 %"])
    }

    @Test func futureFrameDateIsNotStale() {
        let s = MenuBarSummary.make(frame: frame(valid: [0]), frameDate: now.addingTimeInterval(60), config: nil, now: now)
        #expect(!s.isStale)
    }

    @Test func labelCarriesStaleMark() {
        let fresh = MenuBarSummary.make(frame: frame(valid: [0]), frameDate: now, config: nil, now: now)
        #expect(fresh.labelWithMark == "20.0 °C")
        let old = MenuBarSummary.make(frame: frame(valid: [0]), frameDate: now.addingTimeInterval(-16 * 60), config: nil, now: now)
        #expect(old.labelWithMark == "20.0 °C ⏱")
    }
}
