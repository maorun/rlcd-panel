import Foundation

/// One line `<key>=<value>` of the `CONFIG CHANGED` reply: a key whose value differs from the default.
public struct ConfigChangedEntry: Equatable {
    public let key: String
    public let value: String

    public init(key: String, value: String) {
        self.key = key
        self.value = value
    }
}

/// Parser of the `CONFIG CHANGED` reply (`SerialConsoleRunner::showConfigChanged` of the firmware): one line
/// `<key>=<value>` for each key that differs from the default, then `END CHANGED <N>` and the `CRC32` line.
public enum ConfigChangedReply {
    /// `lines` hold the entries and the `END CHANGED <N>` line, without the `CRC32` line (`ReplyAssembler` checks it).
    /// A line without `=`, an unknown or repeated key, a missing `END` line and a count that differs from the
    /// line count throw `ParseError`.
    public static func parse(lines: [String]) throws -> [ConfigChangedEntry] {
        guard let end = lines.last else { throw ParseError.missingLine("END CHANGED") }
        let prefix = "END CHANGED "
        guard end.hasPrefix(prefix) else { throw ParseError.missingLine("END CHANGED") }
        let number = end.dropFirst(prefix.count)
        // No sign, no leading zero, digits only.
        guard !number.isEmpty, number.utf8.allSatisfy({ (0x30...0x39).contains($0) }),
              number == "0" || number.first != "0", let count = Int(number) else { throw ParseError.badLine(end) }
        var entries: [ConfigChangedEntry] = []
        var seen = Set<String>()
        for line in lines.dropLast() {
            guard let eq = line.firstIndex(of: "="), eq != line.startIndex else { throw ParseError.badLine(line) }
            let key = String(line[line.startIndex..<eq])
            guard ConfigSchema.entry(for: key) != nil, seen.insert(key).inserted else { throw ParseError.badLine(line) }
            entries.append(ConfigChangedEntry(key: key, value: String(line[line.index(after: eq)...])))
        }
        guard entries.count == count else { throw ParseError.badLine(end) }
        return entries
    }
}

/// Reset plan for the keys of a `CONFIG CHANGED` reply.
public struct ConfigResetPlan: Equatable {
    /// Changes to the schema default, in schema order.
    public let changes: [ConfigChange]
    /// Problem text by key for a default that the console cannot send (empty name, for example).
    public let problems: [String: String]
}

extension ConfigSchema {
    /// Reset plan for `changed`: one `SET key=default` for each key whose value differs from the schema default,
    /// in schema order. `ConfigBatch(changes:)` puts `ble_console` last through the schema order.
    public static func resetPlan(changed: [ConfigChangedEntry]) -> ConfigResetPlan {
        let current = Dictionary(changed.map { ($0.key, $0.value) }) { _, last in last }
        let defaults = current.reduce(into: [String: String]()) { $0[$1.key] = entry(for: $1.key)?.defaultValue }
        let all = diff(old: current, new: defaults)
        let problems = problems(changes: all)
        return ConfigResetPlan(changes: all.filter { problems[$0.key] == nil }, problems: problems)
    }

    /// Reset of one key: a plan with its change, or `nil` when the key is no change from the default or the console
    /// cannot send the default.
    public static func resetChanges(for entry: ConfigChangedEntry) -> [ConfigChange] {
        resetPlan(changed: [entry]).changes
    }
}
