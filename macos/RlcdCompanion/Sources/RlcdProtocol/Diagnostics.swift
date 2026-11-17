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

/// A signed counter field: an optional `-` and digits; `--` gives `nil`.
private func signedCounter(_ f: [String: String], _ key: String, line: String) throws -> Int? {
    guard let s = f[key] else { throw ParseError.badLine(line) }
    if s == "--" { return nil }
    let digits = s.hasPrefix("-") ? String(s.dropFirst()) : s
    guard !digits.isEmpty, digits.utf8.allSatisfy({ $0 >= 0x30 && $0 <= 0x39 }), let n = Int(s) else { throw ParseError.badLine(line) }
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

/// `SHTC3 nack=<N> short=<N> crc=<N> run=<N> retried=<N>`: failed reads of the onboard sensor by cause.
/// `retried` counts the retries after a soft reset; old firmware has no such field and gives `nil`.
public struct Shtc3Stats: Equatable {
    /// The firmware inverts the diagnostics line from this run length on.
    public static let alertRun = 3
    public let nack: Int?
    public let short: Int?
    public let crc: Int?
    /// Failures in a row.
    public let run: Int?
    /// Retries after a soft reset, also those that succeeded; `nil` for old firmware.
    public var retried: Int? = nil

    public var isAlert: Bool { (run ?? 0) >= Self.alertRun }

    public static func parse(line: String) throws -> Shtc3Stats {
        let f = fieldMap(line)
        return Shtc3Stats(
            nack: try counter(f, "nack", line: line), short: try counter(f, "short", line: line),
            crc: try counter(f, "crc", line: line), run: try counter(f, "run", line: line),
            retried: f["retried"] == nil ? nil : try counter(f, "retried", line: line))
    }
}

/// `BLE rejected=<N> last=<MAC|--> woio=<N> meter=<N> bthome=<N> atc=<N>[ thermopro=<N>]`.
public struct BleRejectMetrics: Equatable {
    public let rejected: Int?
    public let lastMac: String?
    public let woio: Int?
    public let meter: Int?
    public let bthome: Int?
    public let atc: Int?
    public let thermopro: Int?

    public init(
        rejected: Int? = nil, lastMac: String? = nil, woio: Int? = nil, meter: Int? = nil,
        bthome: Int? = nil, atc: Int? = nil, thermopro: Int? = nil
    ) {
        self.rejected = rejected
        self.lastMac = lastMac
        self.woio = woio
        self.meter = meter
        self.bthome = bthome
        self.atc = atc
        self.thermopro = thermopro
    }

    public static func parse(line: String) throws -> BleRejectMetrics {
        let f = fieldMap(line)
        guard let last = f["last"] else { throw ParseError.badLine(line) }
        return BleRejectMetrics(
            rejected: try counter(f, "rejected", line: line), lastMac: last == "--" ? nil : last,
            woio: try counter(f, "woio", line: line), meter: try counter(f, "meter", line: line),
            bthome: try counter(f, "bthome", line: line), atc: try counter(f, "atc", line: line),
            thermopro: f["thermopro"] == nil ? nil : try counter(f, "thermopro", line: line))
    }
}

/// `RECEPTION s1=<N>/<M> ... s5=<N>/<M>`: scans with a packet of the last `M` scans (`M` up to 24) for each
/// registry slot. `--` is an empty slot and gives `nil`.
public struct ReceptionMetrics: Equatable {
    public struct Rate: Equatable {
        /// A rate below this share counts as weak.
        public static let warnBelowPercent = 50
        public let received: Int
        public let scans: Int

        public var percent: Int { scans > 0 ? received * 100 / scans : 0 }
        public var isWeak: Bool { scans > 0 && received * 100 < Self.warnBelowPercent * scans }
    }

    public static let slotCount = 10
    /// One entry for each slot 1 to 10 (or 1 to 5 for older firmware).
    public let slots: [Rate?]

    public static func parse(line: String) throws -> ReceptionMetrics {
        let f = fieldMap(line)
        var slots: [Rate?] = []
        for i in 1...5 {
            guard let s = f["s\(i)"] else { throw ParseError.badLine(line) }
            if s == "--" { slots.append(nil); continue }
            let parts = s.split(separator: "/", omittingEmptySubsequences: false)
            guard parts.count == 2,
                  parts.allSatisfy({ !$0.isEmpty && $0.utf8.allSatisfy { $0 >= 0x30 && $0 <= 0x39 } }),
                  let n = Int(parts[0]), let m = Int(parts[1]), m >= 1, m <= 24, n <= m else { throw ParseError.badLine(line) }
            slots.append(Rate(received: n, scans: m))
        }
        for i in 6...slotCount {
            guard let s = f["s\(i)"] else { break }
            if s == "--" { slots.append(nil); continue }
            let parts = s.split(separator: "/", omittingEmptySubsequences: false)
            guard parts.count == 2,
                  parts.allSatisfy({ !$0.isEmpty && $0.utf8.allSatisfy { $0 >= 0x30 && $0 <= 0x39 } }),
                  let n = Int(parts[0]), let m = Int(parts[1]), m >= 1, m <= 24, n <= m else { throw ParseError.badLine(line) }
            slots.append(Rate(received: n, scans: m))
        }
        return ReceptionMetrics(slots: slots)
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

/// `RTC trim=<N|--> drift_ppm=<N|-->`: the PCF85063A offset register (-64 to 63) and the drift rate of an interval of 24 h or more.
public struct RtcTrimMetrics: Equatable {
    public static let range = -64...63
    public let trim: Int?
    /// Drift rate in ppm; positive means a fast clock. `nil` without a figure of 24 h or more.
    public let driftPpm: Int?

    /// The panel can trim only with a drift figure of 3 ppm or more: a smaller rate rounds to 0 steps.
    public var canTrim: Bool { driftPpm.map { abs($0) >= 3 } ?? false }

    public static func parse(line: String) throws -> RtcTrimMetrics {
        let f = fieldMap(line)
        let trim = try signedCounter(f, "trim", line: line)
        if let trim, !range.contains(trim) { throw ParseError.badLine(line) }
        return RtcTrimMetrics(trim: trim, driftPpm: try signedCounter(f, "drift_ppm", line: line))
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
    public let reception: ReceptionMetrics?
    public let rtcTrim: RtcTrimMetrics?

    public init(shtc3: Shtc3Stats?, ble: BleRejectMetrics?, reset: ResetMetrics?, runtime: RuntimeMetrics?,
                awake: AwakeMetrics?, reception: ReceptionMetrics?, rtcTrim: RtcTrimMetrics? = nil) {
        self.shtc3 = shtc3
        self.ble = ble
        self.reset = reset
        self.runtime = runtime
        self.awake = awake
        self.reception = reception
        self.rtcTrim = rtcTrim
    }

    /// `otherLines` of `StatusReply`. A present line with a bad counter throws `ParseError.badLine`.
    public static func parse(otherLines: [String: String]) throws -> DiagnosticsReply {
        DiagnosticsReply(
            shtc3: try otherLines["SHTC3"].map(Shtc3Stats.parse(line:)),
            ble: try otherLines["BLE"].map(BleRejectMetrics.parse(line:)),
            reset: try otherLines["RESET"].map(ResetMetrics.parse(line:)),
            runtime: try otherLines["RUNTIME"].map(RuntimeMetrics.parse(line:)),
            awake: try otherLines["AWAKE"].map(AwakeMetrics.parse(line:)),
            reception: try otherLines["RECEPTION"].map(ReceptionMetrics.parse(line:)),
            rtcTrim: try otherLines["RTC"].map(RtcTrimMetrics.parse(line:)))
    }

    public static func parse(status: StatusReply) throws -> DiagnosticsReply {
        try parse(otherLines: status.otherLines)
    }
}
