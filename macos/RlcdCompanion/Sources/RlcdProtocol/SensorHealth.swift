import Foundation

/// One verdict for each registry slot, from the `SENSOR` and `RECEPTION` lines of one `STATUS` reply.
public struct SensorHealth: Equatable {
    /// The verdicts in evaluation order: the first match wins.
    public enum Verdict: String, Equatable {
        case empty = "EMPTY"
        case stale = "STALE"
        case battery = "BATTERY"
        case weakSignal = "WEAK_SIGNAL"
        case good = "GOOD"

        public var advice: String {
            switch self {
            case .empty: return "No sensor in this slot. Assign a sensor or ignore the slot."
            case .stale: return "No recent packet. Check the sensor battery and the distance to the panel."
            case .battery: return "Replace the sensor battery."
            case .weakSignal: return "Move the sensor nearer to the panel."
            case .good: return "No action needed."
            }
        }
    }

    /// An RSSI below this value (dBm) counts as weak.
    public static let weakRssi = -85.0
    /// A reception rate counts as weak (`Rate.isWeak`) from this many counted scans.
    public static let minScans = 6
    /// The battery verdict starts this many percent above `sensor_battery_limit_pct`.
    public static let batteryMargin = 5.0

    public let slot: Int
    public let name: String
    public let rssi: Double?
    public let battery: Double?
    public let ageSeconds: Double?
    public let state: String
    /// `nil` without a `RECEPTION` line or for an empty slot.
    public let reception: ReceptionMetrics.Rate?
    public let verdict: Verdict

    public var advice: String { verdict.advice }

    /// One row for each slot 1 to 5 that the reply lists; other slots are skipped unparsed. `config` supplies
    /// `sensor_battery_limit_pct` when known (0 is off). A non-numeric field or a duplicate slot throws `ParseError.badLine`.
    public static func rows(status: StatusReply, config: ConfigReply?) throws -> [SensorHealth] {
        let limit = config?.values["sensor_battery_limit_pct"].flatMap { Double($0) }
        let reception = try status.otherLines["RECEPTION"].map(ReceptionMetrics.parse(line:))
        var out: [SensorHealth] = []
        var seen = Set<Int>()
        for line in status.sensorLines {
            let words = line.split(separator: " ", omittingEmptySubsequences: true)
            guard words.count >= 3, let slot = Int(words[1]) else { throw ParseError.badLine(line) }
            guard (1...ReceptionMetrics.slotCount).contains(slot) else { continue }
            guard seen.insert(slot).inserted else { throw ParseError.badLine(line) }
            var f: [String: String] = [:]
            for part in words.dropFirst(3) {
                if let eq = part.firstIndex(of: "=") { f[String(part[..<eq])] = String(part[part.index(after: eq)...]) }
            }
            let rssi = try value(f, "rssi", line: line)
            let battery = try value(f, "bat", line: line)
            let age = try value(f, "age", line: line)
            guard let state = f["state"], !state.isEmpty else { throw ParseError.badLine(line) }
            let rate = reception.flatMap { $0.slots[slot - 1] }
            out.append(SensorHealth(
                slot: slot, name: String(words[2]), rssi: rssi, battery: battery, ageSeconds: age, state: state,
                reception: rate,
                verdict: verdict(state: state, rssi: rssi, battery: battery, rate: rate, limit: limit)))
        }
        return out
    }

    public static func verdict(state: String, rssi: Double?, battery: Double?, rate: ReceptionMetrics.Rate?,
                               limit: Double?) -> Verdict {
        if state == "EMPTY" { return .empty }
        if state == "STALE" { return .stale }
        if state == "BATLOW" { return .battery }
        if let limit, limit > 0, let battery, battery <= limit + batteryMargin { return .battery }
        if let rssi, rssi < weakRssi { return .weakSignal }
        if let rate, rate.scans >= minScans, rate.isWeak { return .weakSignal }
        return .good
    }

    /// `--` gives `nil`; plain signed decimal text only, else `ParseError.badLine`.
    private static func value(_ f: [String: String], _ key: String, line: String) throws -> Double? {
        guard let s = f[key] else { throw ParseError.badLine(line) }
        if s == "--" { return nil }
        let body = s.hasPrefix("-") ? String(s.dropFirst()) : s
        let parts = body.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count <= 2, parts.allSatisfy({ !$0.isEmpty && $0.utf8.allSatisfy { $0 >= 0x30 && $0 <= 0x39 } }),
              let d = Double(s) else { throw ParseError.badLine(line) }
        return d
    }
}
