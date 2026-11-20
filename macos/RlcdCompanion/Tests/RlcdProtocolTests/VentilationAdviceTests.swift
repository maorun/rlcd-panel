import Foundation
import Testing
@testable import RlcdProtocol

struct VentilationAdviceTests {
    struct Row {
        let tIn: Double, rhIn: Double, tOut: Double, rhOut: Double
        let ventilating: Bool, minutes: Int
        let action: VentilationAction?
        let diff: Double?
    }

    static func loadRows() throws -> [Row] {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { url.deleteLastPathComponent() }
        let text = try String(contentsOf: url.appendingPathComponent("tests/fixtures/ventilation_vectors.txt"), encoding: .utf8)
        return text.split(separator: "\n").filter { !$0.hasPrefix("#") && !$0.isEmpty }.map { line in
            let f = line.split(separator: "|").map { $0.trimmingCharacters(in: .whitespaces) }
            return Row(tIn: Double(f[0])!, rhIn: Double(f[1])!, tOut: Double(f[2])!, rhOut: Double(f[3])!,
                       ventilating: f[4] == "1", minutes: Int(f[5])!,
                       action: f[6] == "-" ? nil : VentilationAction(rawValue: f[6]),
                       diff: f[7] == "-" ? nil : Double(f[7]))
        }
    }

    @Test func fixtureRows() throws {
        let rows = try Self.loadRows()
        #expect(!rows.isEmpty)
        for r in rows {
            let advice = VentilationAdvice.make(
                indoor: TelemetryTile(temperatureC: r.tIn, humidityPct: r.rhIn),
                outdoor: TelemetryTile(temperatureC: r.tOut, humidityPct: r.rhOut),
                isVentilating: r.ventilating, ventilatingMinutes: r.minutes)
            #expect(advice?.action == r.action, "row \(r)")
            if let diff = r.diff, let advice {
                #expect(abs(advice.difference - diff) < 0.01, "row \(r)")
            } else {
                #expect(advice == nil && r.diff == nil, "row \(r)")
            }
        }
    }

    @Test func nanTileGivesNil() {
        let ok = TelemetryTile(temperatureC: 20, humidityPct: 60)
        #expect(VentilationAdvice.make(indoor: TelemetryTile(temperatureC: .nan, humidityPct: 60), outdoor: ok) == nil)
        #expect(VentilationAdvice.make(indoor: ok, outdoor: TelemetryTile(temperatureC: nil, humidityPct: nil)) == nil)
    }

    // MARK: outdoor tile

    let now = Date(timeIntervalSince1970: 1_800_000_000)

    func frame(outdoor: TelemetryTile) -> TelemetryFrame {
        var tiles = (0..<TelemetryFrame.tileCount).map { _ in TelemetryTile(temperatureC: 21, humidityPct: 60) }
        tiles[2] = outdoor
        return TelemetryFrame(sequence: 1, cellPercent: 80, alerts: [], tiles: tiles)
    }

    func config(_ slot: String?) -> ConfigReply {
        ConfigReply(values: slot.map { ["outdoor_slot": $0] } ?? ["temp_unit": "C"])
    }

    @Test func outdoorTileFromSlot() {
        let out = TelemetryTile(temperatureC: 5, humidityPct: 80)
        let tile = VentilationAdvice.outdoorTile(frame: frame(outdoor: out), frameDate: now, config: config("2"), now: now)
        #expect(tile == out)
    }

    @Test func outdoorSlotZeroGivesNil() {
        let out = TelemetryTile(temperatureC: 5, humidityPct: 80)
        #expect(VentilationAdvice.outdoorTile(frame: frame(outdoor: out), frameDate: now, config: config("0"), now: now) == nil)
    }

    @Test func missingConfigGivesNil() {
        let out = TelemetryTile(temperatureC: 5, humidityPct: 80)
        #expect(VentilationAdvice.outdoorTile(frame: frame(outdoor: out), frameDate: now, config: nil, now: now) == nil)
        #expect(VentilationAdvice.outdoorTile(frame: frame(outdoor: out), frameDate: now, config: config(nil), now: now) == nil)
    }

    @Test func staleFrameGivesNil() {
        let out = TelemetryTile(temperatureC: 5, humidityPct: 80)
        let f = frame(outdoor: out)
        let edge = now.addingTimeInterval(VentilationAdvice.maxFrameAge)
        #expect(VentilationAdvice.outdoorTile(frame: f, frameDate: now, config: config("2"), now: edge) == out)
        #expect(VentilationAdvice.outdoorTile(frame: f, frameDate: now, config: config("2"), now: edge.addingTimeInterval(1)) == nil)
        #expect(VentilationAdvice.outdoorTile(frame: f, frameDate: nil, config: config("2"), now: now) == nil)
    }

    @Test func invalidOutdoorTileGivesNil() {
        let empty = TelemetryTile(temperatureC: nil, humidityPct: nil)
        let outOfRange = TelemetryTile(temperatureC: 5, humidityPct: 120)
        #expect(VentilationAdvice.outdoorTile(frame: frame(outdoor: empty), frameDate: now, config: config("2"), now: now) == nil)
        #expect(VentilationAdvice.outdoorTile(frame: frame(outdoor: outOfRange), frameDate: now, config: config("2"), now: now) == nil)
    }

    @Test func adviceForTileSkipsOutdoorTileAndBadIndex() {
        let out = TelemetryTile(temperatureC: 5, humidityPct: 60)
        let f = frame(outdoor: out)
        let c = config("2")
        #expect(VentilationAdvice.advice(forTile: 0, frame: f, frameDate: now, config: c, now: now)?.action == .luftungStarten)
        #expect(VentilationAdvice.advice(forTile: 2, frame: f, frameDate: now, config: c, now: now) == nil)
        #expect(VentilationAdvice.advice(forTile: 99, frame: f, frameDate: now, config: c, now: now) == nil)
        #expect(VentilationAdvice.advice(forTile: -1, frame: f, frameDate: now, config: c, now: now) == nil)
        #expect(VentilationAdvice.advice(forTile: 0, frame: f, frameDate: now, config: config("0"), now: now) == nil)
    }

    private func wallCfg(_ frsi: String) -> ConfigReply {
        ConfigReply(values: ["wall_frsi_pct": frsi, "outdoor_slot": "2"])
    }

    @Test func wallRhValue() throws {
        let indoor = TelemetryTile(temperatureC: 20, humidityPct: 50)
        let outdoor = TelemetryTile(temperatureC: 0, humidityPct: 80)
        let rh = try #require(VentilationAdvice.wallRh(tile: indoor, index: 1, outdoor: outdoor, config: wallCfg("70")))
        #expect(rh >= 72.5 && rh <= 74.0)
        #expect(VentilationAdvice.wallRh(tile: indoor, index: 1, outdoor: outdoor, config: wallCfg("0")) == nil)
        #expect(VentilationAdvice.wallText(tile: indoor, index: 1, outdoor: outdoor, config: wallCfg("70")) == "Wall 73%")
    }
}
