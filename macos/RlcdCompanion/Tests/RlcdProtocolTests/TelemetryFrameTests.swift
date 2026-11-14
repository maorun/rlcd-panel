import Foundation
import Testing
@testable import RlcdProtocol

struct Vector {
    let name: String
    let sequence: UInt8
    let cell: Int?
    let alerts: UInt8
    let tiles: [(Double, Double)?]
    let hex: String
}

func hexData(_ hex: String) -> Data {
    var data = Data()
    var index = hex.startIndex
    while index < hex.endIndex {
        let next = hex.index(index, offsetBy: 2)
        data.append(UInt8(hex[index..<next], radix: 16)!)
        index = next
    }
    return data
}

func fixtureText() throws -> String {
    var url = URL(fileURLWithPath: #filePath)
    for _ in 0..<5 { url.deleteLastPathComponent() } // Tests/RlcdProtocolTests/ -> repository root
    return try String(contentsOf: url.appendingPathComponent("tests/fixtures/ble_telemetry_vectors.txt"), encoding: .utf8)
}

/// Reads tests/fixtures/ble_telemetry_vectors.txt, the file of the C++ host tests.
func loadVectors() throws -> [Vector] {
    let text = try fixtureText()
    return text.split(separator: "\n").filter { !$0.hasPrefix("#") && !$0.isEmpty }.map { line in
        let f = line.split(separator: "|").map { $0.trimmingCharacters(in: .whitespaces) }
        let tiles: [(Double, Double)?] = f[4...9].map { t in
            if t == "-" { return nil }
            let p = t.split(separator: "/")
            return (Double(p[0])!, Double(p[1])!)
        }
        let cell = Int(f[2])!
        return Vector(name: f[0], sequence: UInt8(f[1])!, cell: cell == 255 ? nil : cell,
                      alerts: UInt8(f[3])!, tiles: tiles, hex: f[10])
    }
}

struct TelemetryFrameTests {
    @Test func allFixtureVectorsDecode() throws {
        let vectors = try loadVectors()
        let fixtureLines = try fixtureText().split(separator: "\n").filter { !$0.hasPrefix("#") && !$0.isEmpty }.count
        #expect(vectors.count == fixtureLines)
        #expect(vectors.count >= 9)
        for v in vectors {
            let frame = try TelemetryFrame.decode(hexData(v.hex))
            #expect(frame.sequence == v.sequence, Comment(rawValue: v.name))
            #expect(frame.cellPercent == v.cell, Comment(rawValue: v.name))
            #expect(frame.alerts.rawValue == v.alerts, Comment(rawValue: v.name))
            #expect(frame.tiles.count == 6, Comment(rawValue: v.name))
            for (i, expected) in v.tiles.enumerated() {
                if let expected {
                    // The fixture lists the encoder input; the frame holds it rounded like roundf():
                    // 0.01 C steps for the temperature, 0.5 %RH steps for the humidity.
                    let t = (expected.0 * 100).rounded(.toNearestOrAwayFromZero) / 100
                    let h = (expected.1 * 2).rounded(.toNearestOrAwayFromZero) / 2
                    #expect(abs((frame.tiles[i].temperatureC ?? .nan) - t) < 0.001, "\(v.name) temp \(i)")
                    #expect(abs((frame.tiles[i].humidityPct ?? .nan) - h) < 0.001, "\(v.name) rh \(i)")
                } else {
                    #expect(!frame.tiles[i].isValid, "\(v.name) tile \(i)")
                }
            }
        }
    }

    @Test func badCrcIsRejectedForEveryVector() throws {
        for v in try loadVectors() {
            var d = hexData(v.hex)
            d[25] ^= 0x01
            let expected = Checksums.crc8(d[2..<25])
            #expect(throws: TelemetryError.badCrc(expected: expected, actual: d[25])) { try TelemetryFrame.decode(d) }
        }
    }

    @Test func changedPayloadByteIsRejected() throws {
        var d = hexData(try loadVectors()[0].hex)
        d[7] ^= 0x10
        #expect(throws: TelemetryError.self) { try TelemetryFrame.decode(d) }
    }

    @Test func wrongCompanyId() throws {
        var d = hexData(try loadVectors()[0].hex)
        d[0] = 0x69
        d[1] = 0x09
        #expect(throws: TelemetryError.wrongCompanyId(0x0969)) { try TelemetryFrame.decode(d) }
    }

    @Test func unknownVersionWithValidCrc() throws {
        var d = hexData(try loadVectors()[0].hex)
        d[2] = 1
        d[25] = Checksums.crc8(d[2..<25])
        #expect(throws: TelemetryError.unknownVersion(1)) { try TelemetryFrame.decode(d) }
    }

    @Test func shortAndLongFrames() throws {
        let d = hexData(try loadVectors()[0].hex)
        #expect(throws: TelemetryError.badLength(25)) { try TelemetryFrame.decode(d.prefix(25)) }
        #expect(throws: TelemetryError.badLength(0)) { try TelemetryFrame.decode(Data()) }
        #expect(throws: TelemetryError.badLength(27)) { try TelemetryFrame.decode(d + [0]) }
    }

    @Test func tileOutsidePhysicalRangeIsEmpty() throws {
        var d = hexData(try loadVectors()[0].hex)
        d[7] = 0x10; d[8] = 0x27 // 100.00 C stays valid
        d[9] = 201 // 100.5 %RH is above the range
        d[25] = Checksums.crc8(d[2..<25])
        let frame = try TelemetryFrame.decode(d)
        #expect(!frame.tiles[0].isValid)
        d[9] = 200
        d[8] = 0x28 // 102.4 C is above the range
        d[25] = Checksums.crc8(d[2..<25])
        #expect(!(try TelemetryFrame.decode(d)).tiles[0].isValid)
    }

    @Test func alertNames() {
        let all = ["FREEZER", "HUMIDITY", "NOISE HIGH", "SENSOR LOST", "CHARGE FULL", "VENTILATE", "SENSOR BATTERY", "HEAT"]
        #expect(AlertMask(rawValue: 0xFF).names == all)
        for (i, name) in all.enumerated() { #expect(AlertMask(rawValue: UInt8(1 << i)).names == [name]) }
        #expect(AlertMask(rawValue: 0x81).names == ["FREEZER", "HEAT"])
        #expect(AlertMask(rawValue: 0).names.isEmpty)
    }

    @Test func crcReferenceValues() {
        #expect(Checksums.crc32(Array("123456789".utf8)) == 0xCBF4_3926)
        #expect(Checksums.crc8([0xBE, 0xEF]) == 0x92) // Sensirion datasheet: init 0xFF, poly 0x31
    }
}
