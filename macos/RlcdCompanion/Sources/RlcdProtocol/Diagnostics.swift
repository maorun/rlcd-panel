import Foundation

private func fieldMap(_ line: String) -> [String: String] {
    var out: [String: String] = [:]
    for part in line.split(separator: " ").dropFirst() {
        if let eq = part.firstIndex(of: "=") {
            out[String(part[..<eq])] = String(part[part.index(after: eq)...])
        }
    }
    return out
}

/// A counter field: `--` gives `nil`; a missing key or a non-numeric text is a parse error.
private func counter(_ f: [String: String], _ key: String, line: String) throws -> Int? {
    guard let s = f[key] else { throw ParseError.badLine(line) }
    if s == "--" { return nil }
    guard !s.isEmpty, s.utf8.allSatisfy({ $0 >= 0x30 && $0 <= 0x39 }), let n = Int(s) else { throw ParseError.badLine(line) }
    return n
}

private func decimal(_ f: [String: String], _ key: String, line: String) throws -> Double? {
    guard let s = f[key] else { throw ParseError.badLine(line) }
    if s == "--" { return nil }
    // Plain decimal text only: digits with at most one point, as the firmware prints it.
    let parts = s.split(separator: ".", omittingEmptySubsequences: false)
    guard parts.count <= 2, parts.allSatisfy({ !$0.isEmpty && $0.utf8.allSatisfy { $0 >= 0x30 && $0 <= 0x39 } }),
          let d = Double(s) else { throw ParseError.badLine(line) }
    return d
}

/// `SHTC3 nack=<N> short=<N> crc=<N> run=<N>`: failed reads of the onboard sensor by cause.
public struct Shtc3Stats: Equatable {
    /// The firmware inverts the diagnostics line from this run length on.
    public static let alertRun = 3
    public let nack: Int?
    public let short: Int?
    public let crc: Int?
    /// Failures in a row.
    public let run: Int?

    public var isAlert: Bool { (run ?? 0) >= Self.alertRun }

    public static func parse(line: String) throws -> Shtc3Stats {
        let f = fieldMap(line)
        return Shtc3Stats(
            nack: try counter(f, "nack", line: line), short: try counter(f, "short", line: line),
            crc: try counter(f, "crc", line: line), run: try counter(f, "run", line: line))
    }
}

/// `BLE rejected=<N> last=<MAC|--> woio=<N> meter=<N> bthome=<N> atc=<N>`.
public struct BleRejectMetrics: Equatable {
    public let rejected: Int?
    public let lastMac: String?
    public let woio: Int?
    public let meter: Int?
    public let bthome: Int?
    public let atc: Int?

    public static func parse(line: String) throws -> BleRejectMetrics {
        let f = fieldMap(line)
        guard let last = f["last"] else { throw ParseError.badLine(line) }
        return BleRejectMetrics(
            rejected: try counter(f, "rejected", line: line), lastMac: last == "--" ? nil : last,
            woio: try counter(f, "woio", line: line), meter: try counter(f, "meter", line: line),
            bthome: try counter(f, "bthome", line: line), atc: try counter(f, "atc", line: line))
    }
}

/// `RESET reason=<TEXT> abnormal=<N|-->`.
public struct ResetMetrics: Equatable {
    /// Reset reasons that point to a fault (`ResetReasonCodec` of the firmware).
    public static let abnormalReasons: Set<String> = ["PANIC", "INT_WDT", "TASK_WDT", "WDT", "BROWNOUT"]
    public let reason: String
    /// Count of abnormal resets since the first boot, `nil` when the panel does not know it.
    public let abnormalCount: Int?

    public var isAbnormal: Bool { Self.isAbnormal(reason: reason) }

    public static func isAbnormal(reason: String) -> Bool { abnormalReasons.contains(reason) }

    public static func parse(line: String) throws -> ResetMetrics {
        let f = fieldMap(line)
        guard let reason = f["reason"], !reason.isEmpty else { throw ParseError.badLine(line) }
        return ResetMetrics(reason: reason, abnormalCount: try counter(f, "abnormal", line: line))
    }
}

/// `RUNTIME h=<N|--> samples=<N> cycles=<N.N|--> scan_saved_ms=<N>`.
public struct RuntimeMetrics: Equatable {
    /// Estimated remaining battery hours.
    public let hours: Double?
    public let samples: Int?
    public let cycles: Double?
    public let scanSavedMs: Int?

    public static func parse(line: String) throws -> RuntimeMetrics {
        let f = fieldMap(line)
        return RuntimeMetrics(
            hours: try decimal(f, "h", line: line), samples: try counter(f, "samples", line: line),
            cycles: try decimal(f, "cycles", line: line), scanSavedMs: try counter(f, "scan_saved_ms", line: line))
    }
}

/// `AWAKE last_ms=<N|--> avg_ms=<N|--> max_ms=<N|--> samples=<N>`.
public struct AwakeMetrics: Equatable {
    public let lastMs: Int?
    public let avgMs: Int?
    public let maxMs: Int?
    public let samples: Int?

    public static func parse(line: String) throws -> AwakeMetrics {
        let f = fieldMap(line)
        return AwakeMetrics(
            lastMs: try counter(f, "last_ms", line: line), avgMs: try counter(f, "avg_ms", line: line),
            maxMs: try counter(f, "max_ms", line: line), samples: try counter(f, "samples", line: line))
    }
}

/// The commands that the refresh button of the diagnostics view sends, in this order.
public let diagnosticsRefreshCommands: [ConsoleCommand] = [.status, .version]

/// The hardware metrics of one `STATUS` reply. A line that the firmware does not send gives `nil`, no error.
public struct DiagnosticsReply: Equatable {
    public let shtc3: Shtc3Stats?
    public let ble: BleRejectMetrics?
    public let reset: ResetMetrics?
    public let runtime: RuntimeMetrics?
    public let awake: AwakeMetrics?

    /// `otherLines` of `StatusReply`. A present line with a bad counter throws `ParseError.badLine`.
    public static func parse(otherLines: [String: String]) throws -> DiagnosticsReply {
        DiagnosticsReply(
            shtc3: try otherLines["SHTC3"].map(Shtc3Stats.parse(line:)),
            ble: try otherLines["BLE"].map(BleRejectMetrics.parse(line:)),
            reset: try otherLines["RESET"].map(ResetMetrics.parse(line:)),
            runtime: try otherLines["RUNTIME"].map(RuntimeMetrics.parse(line:)),
            awake: try otherLines["AWAKE"].map(AwakeMetrics.parse(line:)))
    }

    public static func parse(status: StatusReply) throws -> DiagnosticsReply {
        try parse(otherLines: status.otherLines)
    }
}
