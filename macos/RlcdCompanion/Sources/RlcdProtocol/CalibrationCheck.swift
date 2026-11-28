import Foundation

/// One applied offset proposal that the user can check later: the sensor, the command that made the proposal,
/// the changed keys with the old and the new value, and the time of the apply.
public struct CalibrationCheckRecord: Codable, Equatable, Sendable {
    public enum Source: String, Codable, Sendable { case calib, spread, salt }

    public struct Change: Codable, Equatable, Sendable {
        public let key: String
        public let old: String
        public let new: String
        public init(key: String, old: String, new: String) {
            self.key = key
            self.old = old
            self.new = new
        }
    }

    /// `onboard` or `slot<N>` (N 1 to 10).
    public let sensor: String
    public let source: Source
    /// `CALIB` reference sensor 0 to 10, `SPREAD` window 6 to 24 hours, `SALT` sensor 0 to 10.
    public let argument: Int
    public let changes: [Change]
    /// Seconds since 1970 of the apply.
    public let appliedAt: Double

    public init(sensor: String, source: Source, argument: Int, changes: [Change], appliedAt: Date) {
        self.sensor = sensor
        self.source = source
        self.argument = argument
        self.changes = changes
        self.appliedAt = appliedAt.timeIntervalSince1970
    }

    /// The command that the check sends again; `nil` for an argument outside the command range.
    public var command: ConsoleCommand? {
        let cmd: ConsoleCommand
        switch source {
        case .calib: cmd = .calib(argument)
        case .spread: cmd = .spread(hours: argument)
        case .salt: cmd = .salt(argument)
        }
        return cmd.line == nil ? nil : cmd
    }

    /// The sensor number of a name: 0 for `onboard`, N for `slot<N>`; `nil` for any other text.
    public static func sensorNumber(_ name: String) -> Int? {
        if name == "onboard" { return 0 }
        guard name.hasPrefix("slot"), let n = CalibrationReply.hours(String(name.dropFirst(4))), (1...10).contains(n)
        else { return nil }
        return n
    }

    /// The sensor name of an offset key: `temp_offset_c` and `rh_offset_pct` belong to `onboard`,
    /// `slotN_temp_offset_c` and `slotN_rh_offset_pct` to `slotN`; `nil` for another key.
    public static func sensorName(ofKey key: String) -> String? {
        for suffix in ["temp_offset_c", "rh_offset_pct"] where key.hasSuffix(suffix) {
            let prefix = String(key.dropLast(suffix.count))
            if prefix.isEmpty { return "onboard" }
            guard prefix.hasSuffix("_") else { return nil }
            let name = String(prefix.dropLast())
            return sensorNumber(name) == nil || name == "onboard" ? nil : name
        }
        return nil
    }

    /// Hours to wait before the check. The check reads a mean over hourly samples, so every sample of the mean must
    /// come from after the apply, plus one hour because the first new sample can land up to one hour later:
    /// `SALT` reads the newest 6 samples (7 h), `SPREAD` the newest `argument` samples (`argument` + 1 h), and
    /// `CALIB` the whole common history of up to 24 samples (25 h).
    public var waitHours: Int {
        switch source {
        case .salt: return 7
        case .spread: return max(6, argument) + 1
        case .calib: return 25
        }
    }

    /// True when a changed key is a temperature offset; the check judges only the values that the apply changed.
    var changesTemperature: Bool { changes.contains { $0.key.hasSuffix("temp_offset_c") } }
    /// True when a changed key is a humidity offset.
    var changesHumidity: Bool { changes.contains { $0.key.hasSuffix("rh_offset_pct") } }

    /// A record that is usable: a known sensor, an argument that the command accepts, at least one change and a finite time.
    var isValid: Bool {
        Self.sensorNumber(sensor) != nil && command != nil && !changes.isEmpty && appliedAt.isFinite
            && changes.allSatisfy { !$0.key.isEmpty && !$0.new.isEmpty }
    }
}

/// The verdict of a check.
public enum CalibrationCheckResult: Equatable, Sendable {
    case good
    /// The deviations in C and %RH; `nil` where the command does not measure the value (`SALT` has humidity only).
    case notGood(temp: Double?, rh: Double?)
    /// Whole hours still to wait.
    case tooEarly(hours: Int)
    /// The reply has no usable value for the sensor.
    case unavailable(String)

    public var text: String {
        switch self {
        case .good: return "Good"
        case let .notGood(temp, rh):
            let parts = [temp.map { String(format: "%+.1f °C", $0) }, rh.map { String(format: "%+.1f %%RH", $0) }]
            return "Not good: " + parts.compactMap { $0 }.joined(separator: " / ")
        case let .tooEarly(hours): return "Too early: \(hours) h"
        case let .unavailable(reason): return reason
        }
    }
}

public enum CalibrationCheck {
    /// Limits of a good result, in tenths: 0.2 C and 1.0 %RH. A deviation exactly at the limit is good.
    public static let tempLimitTenths = 2
    public static let rhLimitTenths = 10

    /// Whole hours still to wait (rounded up, 1 to `record.waitHours`), or `nil` when the check may run. A clock that
    /// ran back gives the full wait.
    public static func hoursToWait(_ record: CalibrationCheckRecord, now: Date) -> Int? {
        let elapsed = now.timeIntervalSince1970 - record.appliedAt
        let waitHours = record.waitHours
        guard elapsed.isFinite else { return waitHours }
        let wait = Double(waitHours) * 3600
        guard elapsed < wait else { return nil }
        return min(waitHours, max(1, Int(((wait - max(elapsed, 0)) / 3600).rounded(.up))))
    }

    static func tenths(_ value: Double) -> Int { Int((abs(value) * 10).rounded()) }

    /// Verdict of `temp` and `rh` deviations; a `nil` value is not measured and does not count.
    public static func verdict(temp: Double?, rh: Double?) -> CalibrationCheckResult {
        let tempOk = temp.map { tenths($0) <= tempLimitTenths } ?? true
        let rhOk = rh.map { tenths($0) <= rhLimitTenths } ?? true
        return tempOk && rhOk ? .good : .notGood(temp: temp, rh: rh)
    }

    /// Compares the new reply with the limits. Exactly one reply of the record's command is read.
    public static func evaluate(_ record: CalibrationCheckRecord, now: Date, calib: CalibrationReply? = nil,
                                spread: SpreadReply? = nil, salt: SaltReply? = nil) -> CalibrationCheckResult {
        if let hours = hoursToWait(record, now: now) { return .tooEarly(hours: hours) }
        switch record.source {
        case .calib:
            guard let calib else { return .unavailable("No CALIB reply") }
            if let row = calib.sensors.first(where: { $0.name == record.sensor }) {
                return verdict(temp: record.changesTemperature ? row.tempDiff : nil, rh: record.changesHumidity ? row.rhDiff : nil)
            }
            return .unavailable("\(record.sensor) is not in the CALIB reply")
        case .spread:
            guard let spread else { return .unavailable("No SPREAD reply") }
            if case let .skipped(sensors) = spread.outcome { return .unavailable("Only \(sensors) sensors have data (3 needed)") }
            if let row = spread.rows.first(where: { $0.name == record.sensor }) {
                return verdict(temp: record.changesTemperature ? row.tempDev : nil, rh: record.changesHumidity ? row.rhDev : nil)
            }
            return .unavailable("\(record.sensor) is not in the SPREAD reply")
        case .salt:
            guard let salt else { return .unavailable("No SALT reply") }
            switch salt.outcome {
            case let .measured(m, _):
                guard m.name == record.sensor else { return .unavailable("The SALT reply is for \(m.name)") }
                return verdict(temp: nil, rh: m.rhMean - m.reference)
            case let .skipped(name, hours): return .unavailable("\(name): \(hours) h, too few samples (6 needed)")
            case let .unstable(name, span):
                return .unavailable("\(name): humidity span \(String(format: "%.1f", span)) %RH is above 1.0 %RH")
            }
        }
    }
}

/// Stores one record for each sensor in a key-value store (`UserDefaults` in the app). A corrupt stored text
/// gives no records; a record that fails the checks is dropped.
public final class CalibrationCheckStore {
    public static let storeKey = "calibrationCheckRecords"
    private let store: PinStore
    private let clock: () -> Date

    public init(store: PinStore, clock: @escaping () -> Date = Date.init) {
        self.store = store
        self.clock = clock
    }

    public var now: Date { clock() }

    /// All stored records, sorted by sensor name.
    public func records() -> [CalibrationCheckRecord] {
        guard let text = store.string(forKey: Self.storeKey), let data = text.data(using: .utf8),
              let items = (try? JSONSerialization.jsonObject(with: data)) as? [Any] else { return [] }
        var bySensor: [String: CalibrationCheckRecord] = [:]
        for item in items { // a corrupt element drops only itself
            guard JSONSerialization.isValidJSONObject(item), let one = try? JSONSerialization.data(withJSONObject: item),
                  let record = try? JSONDecoder().decode(CalibrationCheckRecord.self, from: one), record.isValid else { continue }
            bySensor[record.sensor] = record
        }
        return bySensor.values.sorted { $0.sensor < $1.sensor }
    }

    public func record(sensor: String) -> CalibrationCheckRecord? { records().first { $0.sensor == sensor } }

    /// Stores the record and replaces the record of the same sensor. A corrupt stored text is dropped.
    public func save(_ record: CalibrationCheckRecord) {
        guard record.isValid else { return }
        var all = Dictionary(uniqueKeysWithValues: records().map { ($0.sensor, $0) })
        all[record.sensor] = record
        write(all)
    }

    public func remove(sensor: String) {
        var all = Dictionary(uniqueKeysWithValues: records().map { ($0.sensor, $0) })
        all[sensor] = nil
        write(all)
    }

    /// Builds one record for each sensor of the applied changes (`oldValues` are the active values before the apply).
    public func record(applied changes: [ConfigChange], source: CalibrationCheckRecord.Source, argument: Int) {
        var bySensor: [String: [CalibrationCheckRecord.Change]] = [:]
        for c in changes {
            guard let sensor = CalibrationCheckRecord.sensorName(ofKey: c.key) else { continue }
            // The cached config can be behind the panel after an earlier apply; the stored new value is newer.
            let before = record(sensor: sensor)?.changes.first { $0.key == c.key }?.new ?? c.old
            bySensor[sensor, default: []].append(.init(key: c.key, old: before, new: c.new))
        }
        for (sensor, list) in bySensor {
            save(CalibrationCheckRecord(sensor: sensor, source: source, argument: argument, changes: list, appliedAt: clock()))
        }
    }

    public func status(of record: CalibrationCheckRecord) -> Int? { CalibrationCheck.hoursToWait(record, now: clock()) }

    private func write(_ all: [String: CalibrationCheckRecord]) {
        guard !all.isEmpty else { store.set(nil, forKey: Self.storeKey); return }
        guard let data = try? JSONEncoder().encode(all.values.sorted { $0.sensor < $1.sensor }), let text = String(data: data, encoding: .utf8) else { return }
        store.set(text, forKey: Self.storeKey)
    }
}
