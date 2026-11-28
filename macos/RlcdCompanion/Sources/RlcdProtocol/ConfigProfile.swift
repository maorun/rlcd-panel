import Foundation

/// A named set of `config.ini` keys, for example `Winter` and `Summer`.
/// A profile holds alert limits and similar settings only. It never holds MACs, names, offsets or gains,
/// because those describe one sensor or one unit and not a season.
public struct ConfigProfile: Equatable, Sendable {
    public static let maxNameLength = 24

    public let name: String
    /// Values by key. A stored profile can hold a key that this app version does not know; `plan` reports it.
    public let values: [String: String]

    public init(name: String, values: [String: String]) {
        self.name = name
        self.values = values
    }

    public enum Failure: Error, Equatable, Sendable {
        case badName
        case nothingToSave
        /// A value that `ConfigSchema` rejects (key, reason).
        case invalidValue(key: String, reason: String)

        public var text: String {
            switch self {
            case .badName: return "Name: 1 to \(ConfigProfile.maxNameLength) characters, no tab and no line break"
            case .nothingToSave: return "No key to save. Offsets, gains, names and MACs are not part of a profile"
            case let .invalidValue(key, reason): return "\(key): \(reason). Fix the value before you save"
            }
        }
    }

    /// True for a key that a profile must not hold: names, MACs, offsets and gains.
    public static func isExcluded(_ key: String) -> Bool {
        key == "onboard_name" || key.hasSuffix("_name") || key.hasSuffix("_mac")
            || key.contains("_offset_") || key.hasSuffix("_gain")
    }

    static func isValidName(_ name: String) -> Bool {
        // Same rule as the Android `isValidName`: no blank (U+0020) at an edge, 1 to 24 code points, no control character.
        let scalars = name.unicodeScalars
        return !scalars.isEmpty && scalars.first != " " && scalars.last != " " && scalars.count <= maxNameLength
            && !scalars.contains { $0.properties.generalCategory == .control }
    }

    /// Profile from the changed keys of the editor (`changes` holds the new values). Keys that the schema does
    /// not know and the excluded keys drop out. Fails for a bad name or when no key is left.
    public static func make(name: String, changes: [ConfigChange]) throws -> ConfigProfile {
        guard isValidName(name) else { throw Failure.badName }
        var values: [String: String] = [:]
        for c in changes where ConfigSchema.entry(for: c.key) != nil && !isExcluded(c.key) {
            if let err = ConfigSchema.validate(key: c.key, value: c.new) { throw Failure.invalidValue(key: c.key, reason: err.text) }
            // `ConfigSchema.validate` accepts no tab and no line break in a value, so the text format holds.
            values[c.key] = c.new
        }
        guard !values.isEmpty else { throw Failure.nothingToSave }
        return ConfigProfile(name: name, values: values)
    }

    /// What applying a profile does to a panel.
    public struct Plan: Equatable, Sendable {
        /// Differences against the current values, in schema order. The keys that already match are not in it.
        public let changes: [ConfigChange]
        /// Keys of the profile that are not sent: a key that this app does not know, or an excluded key.
        public let skipped: [String]
        /// Problem texts by key for values that the schema rejects. A problem blocks the whole apply.
        public let problems: [String: String]

        public var canApply: Bool { problems.isEmpty && !changes.isEmpty }
    }

    /// Plan against the values of the last `CONFIG` reply. The caller runs `ConfigBatch(changes:)` for it.
    public func plan(current: [String: String]) -> Plan {
        var usable: [String: String] = [:]
        var skipped: [String] = []
        for key in values.keys.sorted() {
            if ConfigSchema.entry(for: key) == nil || ConfigProfile.isExcluded(key) { skipped.append(key) } else { usable[key] = values[key] }
        }
        let changes = ConfigSchema.diff(old: current, new: current.merging(usable) { _, new in new })
        return Plan(changes: changes, skipped: skipped, problems: ConfigSchema.problems(changes: changes, allValues: current))
    }
}

/// Stored profiles. Text format, shared with the Android app (`tests/fixtures/config_profiles_sample.txt`):
/// `P<TAB>name` starts a profile, `K<TAB>key<TAB>value` adds one key. A line of another form is skipped.
public final class ConfigProfileStore {
    public static let storeKey = "configProfiles"
    public static let maxProfiles = 20
    private let store: PinStore

    public init(store: PinStore) { self.store = store }

    /// Same name by Unicode scalars, like the Android store. `String ==` would join canonically equal names.
    private static func same(_ a: String, _ b: String) -> Bool { a.unicodeScalars.elementsEqual(b.unicodeScalars) }

    public static func encode(_ profiles: [ConfigProfile]) -> String {
        profiles.map { p in
            "P\t\(p.name)\n" + p.values.keys.sorted().map { "K\t\($0)\t\(p.values[$0] ?? "")\n" }.joined()
        }.joined()
    }

    /// A line that breaks the format drops itself only. A later profile of the same name replaces the earlier one.
    public static func decode(_ text: String) -> [ConfigProfile] {
        // The key is the scalar list, so canonically equal names stay apart like on Android.
        func id(_ name: String) -> [UInt32] { name.unicodeScalars.map(\.value) }
        var order: [String] = []
        var byName: [[UInt32]: [String: String]] = [:]
        var current: String?
        for line in text.split(separator: "\n", omittingEmptySubsequences: true) {
            let parts = line.split(separator: "\t", maxSplits: 2, omittingEmptySubsequences: false).map(String.init)
            if parts.count == 2, parts[0] == "P" {
                if ConfigProfile.isValidName(parts[1]) {
                    current = parts[1]
                    if byName[id(parts[1])] == nil { order.append(parts[1]) }
                    byName[id(parts[1])] = [:]
                } else { current = nil }
            } else if parts.count == 3, parts[0] == "K", let name = current, !parts[1].isEmpty {
                byName[id(name)]?[parts[1]] = parts[2]
            }
        }
        return order.map { ConfigProfile(name: $0, values: byName[id($0)] ?? [:]) }
    }

    /// Profiles sorted by name.
    public func profiles() -> [ConfigProfile] {
        Self.decode(store.string(forKey: Self.storeKey) ?? "").sorted { $0.name < $1.name }
    }

    public func profile(named name: String) -> ConfigProfile? { profiles().first { Self.same($0.name, name) } }

    /// Saves the profile and replaces one of the same name. Returns false at the limit of `maxProfiles`.
    @discardableResult
    public func save(_ profile: ConfigProfile) -> Bool {
        var all = profiles().filter { !Self.same($0.name, profile.name) }
        guard all.count < Self.maxProfiles else { return false }
        all.append(profile)
        store.set(Self.encode(all), forKey: Self.storeKey)
        return true
    }

    public func remove(name: String) {
        store.set(Self.encode(profiles().filter { !Self.same($0.name, name) }), forKey: Self.storeKey)
    }
}
