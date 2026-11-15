import Foundation

public enum ConfigBackupError: Error, Equatable, Sendable {
    case tooLarge
    case notText

    public var text: String {
        switch self {
        case .tooLarge: return "File above \(ConfigBackup.maxBytes) bytes"
        case .notText: return "File is not UTF-8 text"
        }
    }
}

/// One line of a backup file that the parser rejects.
public struct ConfigBackupRejectedLine: Equatable, Sendable {
    public let line: Int
    public let key: String?
    public let reason: String
    /// `line N: reason`.
    public var text: String { "line \(line): \(reason)" }
}

/// Backup and restore of `config.ini`: same line rules as `DeviceConfigParser` and `docs/config.example.ini`.
/// The backup holds the schema keys only, so it never holds Wi-Fi credentials.
/// Limit: an empty name is a comment line, so a restore keeps the name that the panel has now (the console cannot clear a name).
public enum ConfigBackup {
    /// `DeviceConfigParser::MAX_FILE_SIZE` of the firmware.
    public static let maxBytes = 8192

    public struct Parsed: Equatable, Sendable {
        /// Valid values of known keys. A later line of the same key wins.
        public let values: [String: String]
        public let rejected: [ConfigBackupRejectedLine]

        /// Changes against the current values, in schema order. A key absent from the file stays as it is.
        public func changes(current: [String: String]) -> [ConfigChange] {
            ConfigSchema.diff(old: current, new: current.merging(values) { _, new in new })
        }
    }

    /// Text of a backup: each schema key once, in schema order, `key=value`, LF line ends.
    /// A key that `values` lacks gets the schema default. Keys outside the schema are dropped.
    /// A value that the schema rejects (an empty name) stays a comment line `# key=`, as in the template.
    public static func render(values: [String: String]) -> String {
        ConfigSchema.entries.map { entry in
            let value = values[entry.key] ?? entry.defaultValue
            let valid = ConfigSchema.validate(key: entry.key, value: value) == nil
            return "\(valid ? "" : "# ")\(entry.key)=\(value)\n"
        }.joined()
    }

    /// `DeviceConfigParser::trim`: cut blanks, tabs, CR and LF from both ends of a line, key or value.
    private static func trimmed(_ s: Substring) -> String {
        let blanks: Set<UInt8> = [0x20, 0x09, 0x0D, 0x0A]
        var b = s.utf8[...]
        while let f = b.first, blanks.contains(f) { b = b.dropFirst() }
        while let l = b.last, blanks.contains(l) { b = b.dropLast() }
        return String(Substring(b))
    }

    public static func parse(_ data: Data) throws -> Parsed {
        guard data.count <= maxBytes else { throw ConfigBackupError.tooLarge }
        // The firmware skips a UTF-8 byte order mark (`EF BB BF`), so the line numbers stay equal.
        let body = data.starts(with: [0xEF, 0xBB, 0xBF]) ? data.dropFirst(3) : data[...]
        guard let text = String(data: body, encoding: .utf8) else { throw ConfigBackupError.notText }
        var values: [String: String] = [:]
        var rejected: [ConfigBackupRejectedLine] = []
        // Split on the byte `\n` so that `\r\n` (a Swift grapheme) is cut the same way.
        let lines = text.utf8.split(separator: UInt8(ascii: "\n"), omittingEmptySubsequences: false)
        for (i, raw) in lines.enumerated() {
            let line = trimmed(Substring(raw))
            let number = i + 1
            if line.isEmpty || line.hasPrefix("#") || line.hasPrefix(";") { continue }
            // Search the bytes like `memchr`: a combining mark after `=` must not hide it.
            guard let eqByte = line.utf8.firstIndex(of: UInt8(ascii: "=")) else {
                rejected.append(.init(line: number, key: nil, reason: "No key=value"))
                continue
            }
            let key = trimmed(Substring(line.utf8[..<eqByte]))
            let value = trimmed(Substring(line.utf8[line.utf8.index(after: eqByte)...]))
            guard ConfigSchema.entry(for: key) != nil else { continue } // unknown keys are no errors
            if let err = ConfigSchema.validate(key: key, value: value) {
                rejected.append(.init(line: number, key: key, reason: "\(key): \(err.text)"))
            } else {
                values[key] = value
            }
        }
        return Parsed(values: values, rejected: rejected)
    }
}
