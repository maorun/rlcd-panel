import Foundation

/// Reply of `CALIB N`: the reference sensor, the compared sensors, the skipped sensors and the proposed offsets.
public struct CalibrationReply: Equatable, Sendable {
    public struct Sensor: Equatable, Sendable {
        public let name: String
        public let hours: Int
        /// Active offsets of `config.ini`.
        public let tempOffset: Double
        public let rhOffset: Double
        /// Mean difference to the reference (sensor minus reference).
        public let tempDiff: Double
        public let rhDiff: Double
    }

    public struct Skipped: Equatable, Sendable {
        public let name: String
        public let hours: Int
    }

    /// One `key=value` line for `config.ini`.
    public struct Proposal: Equatable, Sendable {
        public let key: String
        public let value: String
    }

    /// The `USB` line after the onboard `SENSOR` line: hours on USB and on battery power.
    public struct UsbSplit: Equatable, Sendable {
        public let usbHours: Int
        public let batHours: Int
        /// Active `usb_temp_offset_c`.
        public let usbTempOffset: Double
        /// USB mean difference minus battery mean difference; `nil` (`--`) below 3 USB or 3 battery hours.
        public let usbTempDiff: Double?
    }

    public let reference: String
    public let sensors: [Sensor]
    public let skipped: [Skipped]
    /// `nil` when the reply has no `USB` line, for example for `CALIB 0` or an onboard sensor with too few common hours.
    public let usb: UsbSplit?
    /// Only keys that `ConfigSchema.validate` accepts with the proposed value.
    public let proposals: [Proposal]

    init(reference: String, sensors: [Sensor], skipped: [Skipped], usb: UsbSplit?, proposals: [Proposal]) {
        self.reference = reference
        self.sensors = sensors
        self.skipped = skipped
        self.usb = usb
        self.proposals = proposals
    }

    /// Parses the lines of a `CALIB` frame (a `BEGIN` line is allowed). Needs the `REFERENCE` line.
    public static func parse(lines: [String]) throws -> CalibrationReply {
        var reference: String?
        var sensors: [Sensor] = []
        var skipped: [Skipped] = []
        var proposals: [Proposal] = []
        var usb: UsbSplit?
        for line in lines {
            if line.hasPrefix("USB ") {
                usb = try usbSplit(line)
            } else if line.hasPrefix("REFERENCE ") {
                let name = String(line.dropFirst(10))
                guard !name.isEmpty, !name.contains(" ") else { throw ParseError.badLine(line) }
                reference = name
            } else if line.hasPrefix("SENSOR ") {
                sensors.append(try sensor(line))
            } else if line.hasPrefix("SKIP ") {
                let words = line.split(separator: " ")
                guard words.count == 3, let hours = hours(fields(line)["hours"]) else { throw ParseError.badLine(line) }
                skipped.append(Skipped(name: String(words[1]), hours: hours))
            } else if let eq = line.firstIndex(of: "="), !line.contains(" ") {
                let key = String(line[..<eq]), value = String(line[line.index(after: eq)...])
                if isOffsetKey(key), ConfigSchema.validate(key: key, value: value) == nil {
                    proposals.removeAll { $0.key == key } // the last line of a key wins
                    proposals.append(Proposal(key: key, value: value))
                }
            }
        }
        guard let reference else { throw ParseError.missingLine("REFERENCE") }
        return CalibrationReply(reference: reference, sensors: sensors, skipped: skipped, usb: usb, proposals: proposals)
    }

    private static func isOffsetKey(_ key: String) -> Bool {
        key.hasSuffix("temp_offset_c") || key.hasSuffix("rh_offset_pct")
    }

    static func fields(_ line: String) -> [String: String] {
        var out: [String: String] = [:]
        for part in line.split(separator: " ").dropFirst() {
            if let eq = part.firstIndex(of: "=") { out[String(part[..<eq])] = String(part[part.index(after: eq)...]) }
        }
        return out
    }

    static func hours(_ s: String?) -> Int? {
        guard let s, !s.isEmpty, s.utf8.allSatisfy({ $0 >= 0x30 && $0 <= 0x39 }) else { return nil }
        return Int(s)
    }

    /// Plain decimal text with an optional minus sign; `nan`, `inf`, a plus sign and hex floats are bad.
    static func decimal(_ s: String?) -> Double? {
        guard let s, !s.isEmpty else { return nil }
        let body = s.hasPrefix("-") ? s.dropFirst() : Substring(s)
        let parts = body.split(separator: ".", omittingEmptySubsequences: false)
        guard (1...2).contains(parts.count),
              parts.allSatisfy({ !$0.isEmpty && $0.utf8.allSatisfy { $0 >= 0x30 && $0 <= 0x39 } }),
              let d = Double(s), d.isFinite else { return nil }
        return d
    }

    private static func sensor(_ line: String) throws -> Sensor {
        let words = line.split(separator: " ")
        let f = fields(line)
        guard words.count == 7, let h = hours(f["hours"]), let to = decimal(f["temp_offset"]),
              let ro = decimal(f["rh_offset"]), let td = decimal(f["temp_diff"]), let rd = decimal(f["rh_diff"])
        else { throw ParseError.badLine(line) }
        return Sensor(name: String(words[1]), hours: h, tempOffset: to, rhOffset: ro, tempDiff: td, rhDiff: rd)
    }

    private static func usbSplit(_ line: String) throws -> UsbSplit {
        let f = fields(line)
        guard line.split(separator: " ").count == 5, let uh = hours(f["hours"]), let bh = hours(f["bat_hours"]),
              let offset = decimal(f["usb_temp_offset"]), let diffText = f["usb_temp_diff"] else {
            throw ParseError.badLine(line)
        }
        if diffText == "--" { return UsbSplit(usbHours: uh, batHours: bh, usbTempOffset: offset, usbTempDiff: nil) }
        guard let diff = decimal(diffText) else { throw ParseError.badLine(line) }
        return UsbSplit(usbHours: uh, batHours: bh, usbTempOffset: offset, usbTempDiff: diff)
    }

    /// Config changes for the selected proposal keys against the active values (`current`).
    /// A proposal that equals the active value gives no change. Without a `CONFIG` reply (`current == nil`)
    /// the active values are unknown, so each selected proposal is a change.
    public func changes(selecting keys: Set<String>, current: [String: String]?) -> [ConfigChange] {
        let chosen = proposals.filter { keys.contains($0.key) }
        guard let current else { return chosen.map { ConfigChange(key: $0.key, old: "", new: $0.value) } }
        var merged = current
        for p in chosen { merged[p.key] = p.value }
        return ConfigSchema.diff(old: current, new: merged)
    }
}

/// Reply of `SALT N`: the check of one sensor in a closed box with saturated sodium chloride.
public struct SaltReply: Equatable, Sendable {
    public struct Measurement: Equatable, Sendable {
        public let name: String
        public let hours: Int
        public let rhMean: Double
        public let rhSpan: Double
        public let tempMean: Double
        /// Equilibrium humidity of the salt at the mean temperature.
        public let reference: Double
    }

    public enum Outcome: Equatable, Sendable {
        case measured(Measurement, CalibrationReply.Proposal?)
        case skipped(name: String, hours: Int)
        case unstable(name: String, span: Double)
    }

    public let outcome: Outcome

    /// The proposal of a measured reply, or `nil`.
    public var proposal: CalibrationReply.Proposal? {
        if case let .measured(_, proposal) = outcome { return proposal }
        return nil
    }

    /// Parses the lines of a `SALT` frame (a `BEGIN` line is allowed). The first line of the frame decides the outcome.
    /// Only a proposal key that `ConfigSchema.validate` accepts with its value is kept.
    public static func parse(lines: [String]) throws -> SaltReply {
        let body = lines.filter { !$0.hasPrefix("BEGIN ") }
        guard let first = body.first else { throw ParseError.missingLine("SALT") }
        let words = first.split(separator: " ")
        if first.hasPrefix("SKIP ") {
            guard words.count == 3, let h = hours(fields(first)["hours"]) else { throw ParseError.badLine(first) }
            return SaltReply(outcome: .skipped(name: String(words[1]), hours: h))
        }
        if first.hasPrefix("UNSTABLE ") {
            guard words.count == 3, let span = decimal(fields(first)["span"]) else { throw ParseError.badLine(first) }
            return SaltReply(outcome: .unstable(name: String(words[1]), span: span))
        }
        guard first.hasPrefix("SALT ") else { throw ParseError.missingLine("SALT") }
        let f = fields(first)
        guard words.count == 7, let h = hours(f["hours"]), let mean = decimal(f["rh_mean"]),
              let span = decimal(f["rh_span"]), let temp = decimal(f["temp_mean"]), let ref = decimal(f["reference"])
        else { throw ParseError.badLine(first) }
        var proposal: CalibrationReply.Proposal?
        for line in body.dropFirst() {
            guard let eq = line.firstIndex(of: "="), !line.contains(" ") else { continue }
            let key = String(line[..<eq]), value = String(line[line.index(after: eq)...])
            if key == expectedKey(sensor: String(words[1])), ConfigSchema.validate(key: key, value: value) == nil {
                proposal = CalibrationReply.Proposal(key: key, value: value)
            }
        }
        let measurement = Measurement(name: String(words[1]), hours: h, rhMean: mean, rhSpan: span, tempMean: temp,
                                      reference: ref)
        return SaltReply(outcome: .measured(measurement, proposal))
    }

    /// The key of the humidity offset of a sensor name: `rh_offset_pct` for `onboard`, else `<name>_rh_offset_pct`.
    private static func expectedKey(sensor: String) -> String {
        sensor == "onboard" ? "rh_offset_pct" : "\(sensor)_rh_offset_pct"
    }

    /// Config changes for the proposal against the active values (`current`); `nil` without a `CONFIG` reply
    /// makes the proposal a change. A proposal that equals the active value gives no change.
    public func changes(selected: Bool, current: [String: String]?) -> [ConfigChange] {
        guard selected, let proposal else { return [] }
        guard let current else { return [ConfigChange(key: proposal.key, old: "", new: proposal.value)] }
        var merged = current
        merged[proposal.key] = proposal.value
        return ConfigSchema.diff(old: current, new: merged)
    }

    private static func fields(_ line: String) -> [String: String] { CalibrationReply.fields(line) }
    private static func hours(_ s: String?) -> Int? { CalibrationReply.hours(s) }
    private static func decimal(_ s: String?) -> Double? { CalibrationReply.decimal(s) }
}

/// Two-point humidity calibration of one sensor: the `SALT` results of saturated sodium chloride and of saturated
/// magnesium chloride give a new gain and a new offset (`rh = raw * gain + offset`).
public enum TwoPointCalibration {
    public struct Result: Equatable, Sendable {
        public let gainKey: String
        public let offsetKey: String
        /// Gain with 3 decimals (steps of 0.002) and offset with 1 decimal, as `SET` values.
        public let gain: String
        public let offset: String
        /// Raw means (before the active gain and offset) at the NaCl and the MgCl2 point.
        public let rawNaCl: Double
        public let rawMgCl2: Double

        /// Config changes against the active values (`current`); a value that equals the active one gives no change.
        public func changes(current: [String: String]) -> [ConfigChange] {
            var merged = current
            merged[gainKey] = gain
            merged[offsetKey] = offset
            return ConfigSchema.diff(old: current, new: merged)
        }
    }

    public enum Failure: Error, Equatable, Sendable {
        case notMeasured
        case differentSensors
        case configUnknown
        case badActiveValues
        case pointsTooClose
        case clampedSample
        case gainOutOfRange(Double)
        case offsetOutOfRange(Double)

        public var text: String {
            switch self {
            case .notMeasured: return "Check both salts first"
            case .differentSensors: return "The two checks belong to different sensors"
            case .configUnknown: return "Read the config first: the active gain and offset are unknown"
            case .badActiveValues: return "The active gain or offset is not a valid number"
            case .pointsTooClose: return "The two raw means are too close or in the wrong order"
            case .clampedSample: return "A mean humidity at 0 or 100 %RH is clamped, so no raw value follows"
            case let .gainOutOfRange(g): return "Gain \(String(format: "%.3f", g)) is outside 0.90 to 1.10, no SET sent"
            case let .offsetOutOfRange(o): return "Offset \(String(format: "%.1f", o)) is outside -10.0 to 10.0, no SET sent"
            }
        }
    }

    /// Step of the stored gain.
    static let gainStep = 0.002

    /// The keys of a sensor name: `onboard` has `rh_gain` and `rh_offset_pct`, `slotN` has `slotN_rh_gain` and
    /// `slotN_rh_offset_pct`.
    public static func keys(sensor: String) -> (gain: String, offset: String) {
        sensor == "onboard" ? ("rh_gain", "rh_offset_pct") : ("\(sensor)_rh_gain", "\(sensor)_rh_offset_pct")
    }

    /// Calculates gain and offset from the two measured replies. The history samples already contain the active
    /// gain and offset, so the raw means are `(mean - offset) / gain`. The gain is the slope through the two
    /// references, rounded to 0.002; the offset is the mean residual at the two points.
    public static func compute(naCl: SaltReply?, mgCl2: SaltReply?, current: [String: String]?) throws -> Result {
        guard let naCl, let mgCl2, case let .measured(a, _) = naCl.outcome, case let .measured(b, _) = mgCl2.outcome
        else { throw Failure.notMeasured }
        guard a.name == b.name else { throw Failure.differentSensors }
        guard let current else { throw Failure.configUnknown }
        let k = keys(sensor: a.name)
        guard let activeGain = ConfigSchema.parseNumber(current[k.gain] ?? ConfigSchema.entry(for: k.gain)?.defaultValue ?? ""),
              let activeOffset = ConfigSchema.parseNumber(current[k.offset] ?? ConfigSchema.entry(for: k.offset)?.defaultValue ?? ""),
              activeGain > 0
        else { throw Failure.badActiveValues }
        guard (0.5...99.5).contains(a.rhMean), (0.5...99.5).contains(b.rhMean) else { throw Failure.clampedSample }
        let rawA = (a.rhMean - activeOffset) / activeGain
        let rawB = (b.rhMean - activeOffset) / activeGain
        guard rawA - rawB > 1.0 else { throw Failure.pointsTooClose } // NaCl is the wetter point
        let slope = (a.reference - b.reference) / (rawA - rawB)
        guard slope >= 0.9, slope <= 1.1 else { throw Failure.gainOutOfRange(slope) }
        let gain = (slope / gainStep).rounded() * gainStep
        let offset = (((a.reference - gain * rawA) + (b.reference - gain * rawB)) / 2 * 10).rounded() / 10
        let gainText = String(format: "%.3f", gain), offsetText = String(format: "%.1f", offset)
        if ConfigSchema.validate(key: k.gain, value: gainText) != nil { throw Failure.gainOutOfRange(gain) }
        if ConfigSchema.validate(key: k.offset, value: offsetText) != nil { throw Failure.offsetOutOfRange(offset) }
        return Result(gainKey: k.gain, offsetKey: k.offset, gain: gainText, offset: offsetText, rawNaCl: rawA, rawMgCl2: rawB)
    }
}

/// The sensors that the calibration pickers offer: the onboard sensor (0), the slots 1 to 5 and each slot 6 to 10
/// with a reading (`STATUS` state `OK`, `STALE` or `BATLOW`; `EMPTY` and `DISABLED` have none). Without a `STATUS` reply only 0 to 5 show.
public enum CalibrationSensors {
    public static let alwaysShown = 0...5
    public static let extraSlots = 6...10
    private static let readingStates: Set<String> = ["OK", "STALE", "BATLOW"]

    public static func selectable(status: [StatusSensor]?) -> [Int] {
        let extra = (status ?? []).filter { extraSlots.contains($0.index) && readingStates.contains($0.state) }.map(\.index)
        return Array(alwaysShown) + Array(Set(extra)).sorted()
    }
}
