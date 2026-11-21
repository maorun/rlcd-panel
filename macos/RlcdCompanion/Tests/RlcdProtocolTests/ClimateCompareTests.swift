import Foundation
import Testing
@testable import RlcdProtocol

struct ClimateCompareTests {
    private static let header = "timestamp,battery_pct,battery_v,usb,onboard_temp_c,onboard_rh,"
        + "s1_temp_c,s1_rh,s1_battery_pct,s2_temp_c,s2_rh,s2_battery_pct,s3_temp_c,s3_rh,s3_battery_pct,"
        + "noise_rms,noise_db,noise_band,noise_events,noise_l10,noise_l90"
    /// Fixture month: slot 1 has an empty field at 11:00, slot 2 has values only at 10:00 and 12:00.
    private static let rows: [ClimateRow] = {
        let tail = ",1000,45,QUIET,2,50,38"
        let lines = [
            "2026-11-14 10:00:00,85,4.05,1,21.0,45.0,19.0,50,87,23.5,60,80,,,\(tail)",
            "2026-11-14 11:00:00,85,4.05,1,22.0,46.0,,,87,,,80,,,\(tail)",
            "2026-11-14 12:00:00,85,4.05,1,20.0,44.0,18.0,52,87,17.5,41,80,,,\(tail)",
        ]
        return (try? ClimateLog.parse(text: ([header] + lines).joined(separator: "\n")).rows) ?? []
    }()

    @Test func buildsOneSeriesForEachSensorInSelectionOrder() throws {
        let chart = try #require(ClimateCompare.build(rows: Self.rows, sensors: [2, 0, 1], metric: .temperature))
        #expect(chart.series.map(\.sensor) == [2, 0, 1])
        #expect(chart.series.map(\.name) == ["s2", "onboard", "s1"])
        #expect(chart.series.allSatisfy { $0.samples.count == 3 })
        #expect(chart.series[1].samples.map(\.value) == [21.0, 22.0, 20.0])
    }

    @Test func emptyFieldGivesGapAndBreaksLine() throws {
        let chart = try #require(ClimateCompare.build(rows: Self.rows, sensors: [0, 1], metric: .temperature))
        let s1 = chart.series[1]
        #expect(s1.samples.map(\.value) == [19.0, nil, 18.0])
        #expect(s1.segments.map { $0.map(\.timestamp) } == [["2026-11-14 10:00:00"], ["2026-11-14 12:00:00"]])
        #expect(chart.series[0].segments.count == 1)
    }

    @Test func axisRangeCoversAllSeries() throws {
        let temp = try #require(ClimateCompare.build(rows: Self.rows, sensors: [0, 1, 2], metric: .temperature))
        #expect(temp.axisRange == 17.5...23.5)
        let rh = try #require(ClimateCompare.build(rows: Self.rows, sensors: [0, 2], metric: .humidity))
        #expect(rh.axisRange == 41...60)
    }

    @Test func missingSensorInRowAndAllEmptyGiveNoRange() throws {
        let chart = try #require(ClimateCompare.build(rows: Self.rows, sensors: [3, 4], metric: .temperature))
        #expect(chart.series.allSatisfy { $0.segments.isEmpty })
        #expect(chart.axisRange == nil)
    }

    @Test func flatRangeWidens() throws {
        let flat = try #require(ClimateCompare.build(rows: Array(Self.rows.prefix(1)), sensors: [0, 5], metric: .humidity))
        #expect(flat.axisRange == 44.5...45.5)
    }

    @Test func rejectsBadSelection() {
        #expect(ClimateCompare.build(rows: Self.rows, sensors: [0], metric: .humidity) == nil)
        #expect(ClimateCompare.build(rows: Self.rows, sensors: [0, 1, 2, 3, 4, 5, 6], metric: .humidity) == nil)
        #expect(ClimateCompare.build(rows: Self.rows, sensors: [1, 1], metric: .humidity) == nil)
        #expect(ClimateCompare.build(rows: Self.rows, sensors: [0, 11], metric: .humidity) == nil)
    }

    @Test func usesConfigNames() throws {
        let config = try ConfigReply.parse(lines: ["onboard_name=Office", "slot1_mac=AA:BB:CC:DD:EE:01", "slot1_name=Bath"])
        let chart = try #require(ClimateCompare.build(rows: Self.rows, sensors: [0, 1, 2], metric: .humidity, config: config))
        #expect(chart.series.map(\.name) == ["Office", "Bath", "s2"])
    }

    @Test func duplicateConfigNamesStayDistinct() throws {
        let config = try ConfigReply.parse(lines: ["onboard_name=Room", "slot1_mac=AA:BB:CC:DD:EE:01", "slot1_name=Room"])
        let chart = try #require(ClimateCompare.build(rows: Self.rows, sensors: [0, 1], metric: .humidity, config: config))
        #expect(chart.series.map(\.name) == ["Room (onboard)", "Room (s1)"])
    }
}
