import Foundation
import Testing
@testable import RlcdProtocol

private let formatter: DateFormatter = {
    let f = DateFormatter()
    f.calendar = Calendar(identifier: .gregorian)
    f.locale = Locale(identifier: "en_US_POSIX")
    f.timeZone = PanelClock.timeZone
    f.dateFormat = "yyyy-MM-dd HH:mm:ss"
    return f
}()

private let start = formatter.date(from: "2026-09-01 12:00:00")!

private func row(day: Int, pct: Int?, sensor: Int = 1) -> ClimateRow {
    var sensors = [ClimateSensorReading](repeating: ClimateSensorReading(tempC: 20, rh: 50, batteryPct: nil), count: 6)
    sensors[sensor] = ClimateSensorReading(tempC: 20, rh: 50, batteryPct: pct)
    return ClimateRow(
        timestamp: formatter.string(from: start.addingTimeInterval(Double(day) * 86_400)), batteryPct: nil, batteryV: nil,
        usb: nil, sensors: sensors, noiseRms: nil, noiseDb: nil, noiseBand: nil, noiseEvents: nil, noiseL10: nil, noiseL90: nil)
}

@Suite struct BatteryForecastTests {
    @Test func knownLineOnePercentPerWeek() throws {
        let rows = (0...4).map { row(day: $0 * 7, pct: 80 - $0) }
        let date = try #require(BatteryForecast.estimate(rows: rows, sensor: 1, limitPct: 10))
        let expected = start.addingTimeInterval(70 * 7 * 86_400)
        #expect(abs(date.timeIntervalSince(expected)) < 60)
    }

    @Test func usesRowsAfterRiseOnly() throws {
        // Old battery fell to 12 %, then a new one at 100 % falls 1 % per week.
        let old = (0...3).map { row(day: $0 * 7, pct: 40 - $0 * 9) }
        let new = (0...3).map { row(day: 28 + $0 * 7, pct: 100 - $0) }
        let date = try #require(BatteryForecast.estimate(rows: old + new, sensor: 1, limitPct: 10))
        let expected = start.addingTimeInterval((28 + 90 * 7) * 86_400)
        #expect(abs(date.timeIntervalSince(expected)) < 60)
    }

    @Test func nilForSixDays() {
        let rows = (0...6).map { row(day: $0, pct: 90 - $0) }
        #expect(BatteryForecast.estimate(rows: rows, sensor: 1, limitPct: 10) == nil)
    }

    @Test func nilForDropOfTwoPercent() {
        let rows = [row(day: 0, pct: 80), row(day: 10, pct: 79), row(day: 20, pct: 78)]
        #expect(BatteryForecast.estimate(rows: rows, sensor: 1, limitPct: 10) == nil)
    }

    @Test func nilForFlatOrRisingLine() {
        let flat = (0...4).map { row(day: $0 * 7, pct: 80) }
        let rising = (0...4).map { row(day: $0 * 7, pct: 70 + $0) }
        #expect(BatteryForecast.estimate(rows: flat, sensor: 1, limitPct: 10) == nil)
        #expect(BatteryForecast.estimate(rows: rising, sensor: 1, limitPct: 10) == nil)
    }

    @Test func nilForOnboardSensor() {
        let rows = (0...4).map { row(day: $0 * 7, pct: 80 - $0 * 5, sensor: 0) }
        #expect(BatteryForecast.estimate(rows: rows, sensor: 0, limitPct: 10) == nil)
    }

    @Test func limitMovesTheDate() throws {
        let rows = (0...4).map { row(day: $0 * 7, pct: 80 - $0) }
        let low = try #require(BatteryForecast.estimate(rows: rows, sensor: 1, limitPct: 10))
        let high = try #require(BatteryForecast.estimate(rows: rows, sensor: 1, limitPct: 20))
        #expect(high < low)
    }

    @Test func limitFromConfigElseDefault() throws {
        let config = try ConfigReply.parse(lines: ["sensor_battery_limit_pct=25"])
        #expect(BatteryForecast.limitPct(config: config) == 25)
        #expect(BatteryForecast.limitPct(config: nil) == 10)
        let other = try ConfigReply.parse(lines: ["wake_interval_s=60"])
        #expect(BatteryForecast.limitPct(config: other) == 10)
    }

    @Test func limitZeroUsesDefault() throws {
        let off = try ConfigReply.parse(lines: ["sensor_battery_limit_pct=0"])
        #expect(BatteryForecast.limitPct(config: off) == 10)
    }

    @Test func spanOfExactlySevenDaysCounts() {
        let rows = [row(day: 0, pct: 80), row(day: 7, pct: 76)]
        #expect(BatteryForecast.estimate(rows: rows, sensor: 1, limitPct: 10) != nil)
    }

    @Test func riseOfNinePercentIsNoNewBattery() throws {
        let rows = [row(day: 0, pct: 60), row(day: 7, pct: 50), row(day: 14, pct: 59), row(day: 21, pct: 49)]
        #expect(try #require(BatteryForecast.estimate(rows: rows, sensor: 1, limitPct: 10)) > start)
        // A rise of 10 % starts a new battery: only the last two rows remain, a span of 7 days.
        let reset = [row(day: 0, pct: 60), row(day: 7, pct: 50), row(day: 14, pct: 60), row(day: 21, pct: 55)]
        let date = try #require(BatteryForecast.estimate(rows: reset, sensor: 1, limitPct: 10))
        #expect(abs(date.timeIntervalSince(start.addingTimeInterval((14 + 7 * 10) * 86_400))) < 60)
    }

    @Test func rowsOlderThan60DaysAreIgnored() {
        // Old rows fall steeply, the 60-day window holds a flat line.
        let old = (0...3).map { row(day: $0, pct: 90 - $0 * 5) }
        let recent = (0...3).map { row(day: 100 + $0 * 7, pct: 70) }
        #expect(BatteryForecast.estimate(rows: old + recent, sensor: 1, limitPct: 10) == nil)
    }

    @Test func unsortedRowsAndGapsGiveTheSameDate() throws {
        let rows = (0...4).map { row(day: $0 * 7, pct: 80 - $0) }
        let gaps = rows + [row(day: 3, pct: nil)]
        let a = try #require(BatteryForecast.estimate(rows: rows, sensor: 1, limitPct: 10))
        let b = try #require(BatteryForecast.estimate(rows: gaps.reversed(), sensor: 1, limitPct: 10))
        #expect(a == b)
    }

    @Test func reachedLimitGivesNewestRow() throws {
        let rows = (0...4).map { row(day: $0 * 7, pct: 30 - $0 * 5) }
        let date = try #require(BatteryForecast.estimate(rows: rows, sensor: 1, limitPct: 25))
        #expect(date == start.addingTimeInterval(28 * 86_400))
    }

    @Test func lineAcrossDstChangeKeepsTheDate() throws {
        // Local timestamps from 2026-10-12 to 2026-11-09 cross the end of CEST on 2026-10-25.
        let begin = formatter.date(from: "2026-10-12 12:00:00")!
        let rows = (0...4).map { i -> ClimateRow in
            var r = row(day: 0, pct: 80 - i)
            r = ClimateRow(
                timestamp: formatter.string(from: begin.addingTimeInterval(Double(i * 7) * 86_400)), batteryPct: nil,
                batteryV: nil, usb: nil, sensors: r.sensors, noiseRms: nil, noiseDb: nil, noiseBand: nil,
                noiseEvents: nil, noiseL10: nil, noiseL90: nil)
            return r
        }
        let date = try #require(BatteryForecast.estimate(rows: rows, sensor: 1, limitPct: 10))
        let expected = begin.addingTimeInterval(70 * 7 * 86_400)
        #expect(abs(date.timeIntervalSince(expected)) < 3_700)
    }

    @Test func earlierMonthCrossesYear() {
        #expect(ClimateLog.earlierMonth("202601", by: 1) == "202512")
        #expect(ClimateLog.earlierMonth("202611", by: 2) == "202609")
        #expect(ClimateLog.earlierMonth("2026", by: 1) == nil)
    }
}
