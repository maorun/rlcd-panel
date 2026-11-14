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
