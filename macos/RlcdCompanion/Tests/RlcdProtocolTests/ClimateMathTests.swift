import Foundation
import Testing
@testable import RlcdProtocol

struct ClimateVector {
    let tempC: Double
    let rhPct: Double
    let dewPointC: Double?
    let absHumidityGm3: Double?
    let heatIndexC: Double?
    let comfortZone: ComfortZone
}

func loadClimateVectors() throws -> [ClimateVector] {
    var url = URL(fileURLWithPath: #filePath)
    for _ in 0..<5 { url.deleteLastPathComponent() }
    let text = try String(contentsOf: url.appendingPathComponent("tests/fixtures/climate_math_vectors.txt"), encoding: .utf8)
    return text.split(separator: "\n").filter { !$0.hasPrefix("#") && !$0.isEmpty }.map { line in
        let f = line.split(separator: "|").map { $0.trimmingCharacters(in: .whitespaces) }
        let t = Double(f[0]) ?? .nan
        let rh = Double(f[1]) ?? .nan
        let dp = f[2] == "-" ? nil : Double(f[2])
        let ah = f[3] == "-" ? nil : Double(f[3])
        let hi = f[4] == "-" ? nil : Double(f[4])
        let cz = ComfortZone(rawValue: f[5]) ?? .invalid
        return ClimateVector(tempC: t, rhPct: rh, dewPointC: dp, absHumidityGm3: ah, heatIndexC: hi, comfortZone: cz)
    }
}

struct ClimateMathTests {
    @Test func curvePointsMatchFixtureRow() throws {
        let v = try #require(loadClimateVectors().first { $0.tempC == 32.0 && $0.rhPct == 70.0 })
        let hi = try #require(ClimateMath.feelsLikePoint(tempC: v.tempC, humidityPct: v.rhPct))
        let ah = try #require(ClimateMath.absoluteHumidityPoint(tempC: v.tempC, humidityPct: v.rhPct))
        #expect(abs(hi - 40.41) < 0.01)
        #expect(abs(ah - 23.57) < 0.01)
        #expect(abs(hi - (v.heatIndexC ?? .nan)) < 0.01)
        #expect(abs(ah - (v.absHumidityGm3 ?? .nan)) < 0.01)
    }

    @Test func curvePointsSkipMissingValues() {
        #expect(ClimateMath.feelsLikePoint(tempC: 32.0, humidityPct: nil) == nil)
        #expect(ClimateMath.absoluteHumidityPoint(tempC: 32.0, humidityPct: nil) == nil)
        #expect(ClimateMath.feelsLikePoint(tempC: nil, humidityPct: 70.0) == nil)
        let row = ClimateSensorReading(tempC: 32.0, rh: nil, batteryPct: nil)
        #expect(ClimateCompare.value(of: .feelsLike, row) == nil)
        #expect(ClimateCompare.value(of: .absoluteHumidity, row) == nil)
        #expect(ClimateCompare.value(of: .temperature, row) == 32.0)
    }

    @Test func allFixtureVectorsMatch() throws {
        let vectors = try loadClimateVectors()
        #expect(vectors.count >= 15)
        for v in vectors {
            let dp = ClimateMath.dewPointC(tempC: v.tempC, humidityPct: v.rhPct)
            let ah = ClimateMath.absoluteHumidityGm3(tempC: v.tempC, humidityPct: v.rhPct)
            let hi = ClimateMath.heatIndexC(tempC: v.tempC, humidityPct: v.rhPct)
            let cz = ClimateMath.comfort(tempC: v.tempC, humidityPct: v.rhPct)

            #expect(cz == v.comfortZone, "comfort for \(v.tempC)/\(v.rhPct)")
            if let expectedDp = v.dewPointC {
                #expect(dp != nil, "dp nil for \(v.tempC)/\(v.rhPct)")
                if let actual = dp {
                    #expect(abs(actual - expectedDp) <= 0.05, "dp for \(v.tempC)/\(v.rhPct): actual \(actual), expected \(expectedDp)")
                }
            } else {
                #expect(dp == nil, "dp expected nil for \(v.tempC)/\(v.rhPct)")
            }

            if let expectedAh = v.absHumidityGm3 {
                #expect(ah != nil, "ah nil for \(v.tempC)/\(v.rhPct)")
                if let actual = ah {
                    #expect(abs(actual - expectedAh) <= 0.05, "ah for \(v.tempC)/\(v.rhPct): actual \(actual), expected \(expectedAh)")
                }
            } else {
                #expect(ah == nil, "ah expected nil for \(v.tempC)/\(v.rhPct)")
            }

            if let expectedHi = v.heatIndexC {
                #expect(hi != nil, "hi nil for \(v.tempC)/\(v.rhPct)")
                if let actual = hi {
                    #expect(abs(actual - expectedHi) <= 0.05, "hi for \(v.tempC)/\(v.rhPct): actual \(actual), expected \(expectedHi)")
                }
            } else {
                #expect(hi == nil, "hi expected nil for \(v.tempC)/\(v.rhPct)")
            }
        }
    }

    @Test func alertTransitions() {
        let t0 = TelemetryTile(temperatureC: 20, humidityPct: 50)
        let f1 = TelemetryFrame(sequence: 1, cellPercent: 80, alerts: AlertMask(rawValue: 0x00), tiles: [t0])
        let f2 = TelemetryFrame(sequence: 2, cellPercent: 80, alerts: AlertMask(rawValue: 0x81), tiles: [t0])
        let f3 = TelemetryFrame(sequence: 2, cellPercent: 80, alerts: AlertMask(rawValue: 0x81), tiles: [t0])
        let f4 = TelemetryFrame(sequence: 3, cellPercent: 80, alerts: AlertMask(rawValue: 0x01), tiles: [t0])

        // Mask 0x00 -> 0x81 gives rising bits 0x81
        let rising1to2 = AlertTransitions.risingAlerts(previous: f1, current: f2)
        #expect(rising1to2.rawValue == 0x81)
        #expect(rising1to2.names == ["FREEZER", "HEAT"])

        // Same sequence number gives empty
        let risingSameSeq = AlertTransitions.risingAlerts(previous: f2, current: f3)
        #expect(risingSameSeq.rawValue == 0)

        // Cleared bit (0x81 -> 0x01) gives no rising bits
        let risingCleared = AlertTransitions.risingAlerts(previous: f2, current: f4)
        #expect(risingCleared.rawValue == 0)

        // Nil previous frame gives empty
        let risingNilPrev = AlertTransitions.risingAlerts(previous: nil, current: f2)
        #expect(risingNilPrev.rawValue == 0)
    }
}

struct WallRhTests {
    private func tile(_ t: Double?, _ h: Double?) -> TelemetryTile { TelemetryTile(temperatureC: t, humidityPct: h) }
    private func cfg(_ frsi: String, outdoor: String = "2") -> ConfigReply {
        ConfigReply(values: ["wall_frsi_pct": frsi, "outdoor_slot": outdoor])
    }

    @Test func fixtureVectorsMatch() throws {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { url.deleteLastPathComponent() }
        let text = try String(contentsOf: url.appendingPathComponent("tests/fixtures/wall_rh_vectors.txt"), encoding: .utf8)
        var count = 0
        for line in text.split(separator: "\n") where !line.hasPrefix("#") && !line.isEmpty {
            let f = line.split(separator: "|").map { $0.trimmingCharacters(in: .whitespaces) }
            #expect(f.count == 5)
            let wall = ClimateMath.wallSurfaceRh(tInC: Double(f[0])!, rhInPct: Double(f[1])!,
                                                 tOutC: Double(f[2])!, frsi: Double(f[3])!)
            if f[4] == "-" {
                #expect(wall == nil, "expected nil for \(line)")
            } else {
                #expect(wall != nil, "nil for \(line)")
                if let wall { #expect(abs(wall - Double(f[4])!) <= 0.1, "wall for \(line): \(wall)") }
            }
            count += 1
        }
        #expect(count >= 10)
    }

    @Test func nanInputGivesNil() {
        #expect(ClimateMath.wallSurfaceRh(tInC: .nan, rhInPct: 50, tOutC: 0, frsi: 0.7) == nil)
    }

    @Test func wallTextValue() {
        #expect(VentilationAdvice.wallText(tile: tile(20, 50), index: 1, outdoor: tile(0, 80), config: cfg("70")) == "Wall 73%")
    }

    @Test func wallTextEdgeCases() {
        let indoor = tile(20, 50)
        let outdoor = tile(0, 80)
        #expect(VentilationAdvice.wallText(tile: indoor, index: 1, outdoor: outdoor, config: cfg("0")) == nil)
        #expect(VentilationAdvice.wallText(tile: indoor, index: 2, outdoor: outdoor, config: cfg("70", outdoor: "2")) == nil)
        #expect(VentilationAdvice.wallText(tile: indoor, index: 1, outdoor: nil, config: cfg("70")) == nil)
        #expect(VentilationAdvice.wallText(tile: indoor, index: 1, outdoor: outdoor, config: nil) == nil)
        #expect(VentilationAdvice.wallText(tile: tile(nil, nil), index: 1, outdoor: outdoor, config: cfg("70")) == nil)
        #expect(VentilationAdvice.wallText(tile: indoor, index: 1, outdoor: tile(25, 50), config: cfg("70")) == nil)
    }
}
