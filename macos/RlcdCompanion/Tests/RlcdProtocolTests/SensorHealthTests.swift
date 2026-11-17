import Foundation
import Testing
@testable import RlcdProtocol

private func status(_ sensors: [String], reception: String? = nil) throws -> StatusReply {
    var lines = ["SENSOR 0 Onboard temp=21.4 rh=45.0 bat=-- rssi=-- age=120 state=OK"] + sensors
    lines.append("POWER src=USB v=4.05 pct=88 alerts=00 muted=0")
    if let reception { lines.append(reception) }
    return try StatusReply.parse(lines: lines)
}

private func config(_ limit: String) throws -> ConfigReply {
    try ConfigReply.parse(lines: ["sensor_battery_limit_pct=\(limit)"])
}

private func verdict(_ sensor: String, reception: String? = nil, limit: String? = nil) throws -> SensorHealth.Verdict {
    let rows = try SensorHealth.rows(status: status([sensor], reception: reception), config: limit.map(config))
    return rows[0].verdict
}

@Suite struct SensorHealthTests {
    @Test func eachVerdictFromSample() throws {
        let s = try status([
            "SENSOR 1 A temp=22.1 rh=48.5 bat=87 rssi=-61 age=14 state=OK",
            "SENSOR 2 B temp=-- rh=-- bat=-- rssi=-- age=-- state=EMPTY",
            "SENSOR 3 C temp=15.0 rh=70.0 bat=80 rssi=-60 age=2000 state=STALE",
            "SENSOR 4 D temp=15.0 rh=70.0 bat=9 rssi=-60 age=10 state=BATLOW",
            "SENSOR 5 E temp=15.0 rh=70.0 bat=80 rssi=-90 age=10 state=OK",
        ])
        let rows = try SensorHealth.rows(status: s, config: nil)
        #expect(rows.map(\.slot) == [1, 2, 3, 4, 5])
        #expect(rows.map(\.verdict) == [.good, .empty, .stale, .battery, .weakSignal])
        #expect(rows[4].advice == "Move the sensor nearer to the panel.")
        #expect(rows[0].name == "A")
    }

    @Test func verdictOrder() throws {
        #expect(try verdict("SENSOR 1 A temp=1 rh=1 bat=1 rssi=-95 age=9 state=STALE") == .stale)
        #expect(try verdict("SENSOR 1 A temp=-- rh=-- bat=-- rssi=-95 age=-- state=EMPTY") == .empty)
        #expect(try verdict("SENSOR 1 A temp=1 rh=1 bat=3 rssi=-95 age=9 state=BATLOW") == .battery)
    }

    @Test func batteryLimitPlusFive() throws {
        let line = "SENSOR 1 A temp=1 rh=1 bat=15 rssi=-60 age=9 state=OK"
        #expect(try verdict(line) == .good)
        #expect(try verdict(line, limit: "10") == .battery)
        #expect(try verdict("SENSOR 1 A temp=1 rh=1 bat=16 rssi=-60 age=9 state=OK", limit: "10") == .good)
        #expect(try verdict(line, limit: "0") == .good)
    }

    @Test func receptionRuleNeedsSixScans() throws {
        let line = "SENSOR 1 A temp=1 rh=1 bat=80 rssi=-60 age=9 state=OK"
        let rest = " s2=-- s3=-- s4=-- s5=--"
        #expect(try verdict(line, reception: "RECEPTION s1=2/5" + rest) == .good)
        #expect(try verdict(line, reception: "RECEPTION s1=2/6" + rest) == .weakSignal)
        #expect(try verdict(line, reception: "RECEPTION s1=3/6" + rest) == .good)
    }

    @Test func rssiOnlyWithoutReceptionLine() throws {
        #expect(try verdict("SENSOR 1 A temp=1 rh=1 bat=80 rssi=-86 age=9 state=OK") == .weakSignal)
        #expect(try verdict("SENSOR 1 A temp=1 rh=1 bat=80 rssi=-85 age=9 state=OK") == .good)
    }

    @Test func nonNumericFieldThrows() throws {
        let s = try status(["SENSOR 1 A temp=1 rh=1 bat=80 rssi=x age=9 state=OK"])
        #expect(throws: ParseError.self) { try SensorHealth.rows(status: s, config: nil) }
        #expect(throws: ParseError.badLine("SENSOR 1 A temp=1 rh=1 bat=80 rssi=x age=9 state=OK")) {
            try SensorHealth.rows(status: s, config: nil)
        }
    }

    @Test func missingStateThrows() throws {
        let s = try status(["SENSOR 1 A temp=1 rh=1 bat=80 rssi=-60 age=9"])
        #expect(throws: ParseError.self) { try SensorHealth.rows(status: s, config: nil) }
    }

    @Test func skipsSlotsOutsideOneToTen() throws {
        let s = try status(["SENSOR 11 X temp=1 rh=1 bat=80 rssi=x age=9 state=OK",
                            "SENSOR 1 A temp=1 rh=1 bat=80 rssi=-60 age=9 state=OK"])
        #expect(try SensorHealth.rows(status: s, config: nil).map(\.slot) == [1])
    }

    @Test func duplicateSlotThrows() throws {
        let s = try status(["SENSOR 1 A temp=1 rh=1 bat=80 rssi=-60 age=9 state=OK",
                            "SENSOR 1 B temp=1 rh=1 bat=80 rssi=-60 age=9 state=OK"])
        #expect(throws: ParseError.self) { try SensorHealth.rows(status: s, config: nil) }
    }

    @Test func emptySlotHasNoReception() throws {
        let line = "SENSOR 1 A temp=-- rh=-- bat=-- rssi=-- age=-- state=EMPTY"
        let rows = try SensorHealth.rows(status: status([line], reception: "RECEPTION s1=-- s2=-- s3=-- s4=-- s5=--"), config: nil)
        #expect(rows[0].reception == nil)
        #expect(rows[0].verdict == .empty)
    }
}
