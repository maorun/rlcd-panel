import Foundation

/// One text report of `VERSION`, `STATUS`, `CONFIG` and `EVENTS` for a fault report.
public enum DiagnosticsReport {
    public enum Link: String, Equatable, Sendable { case usb = "USB", ble = "BLE" }

    public struct Failure: Error, Equatable, Sendable {
        public let reason: String
        public init(reason: String) { self.reason = reason }
    }

    /// The reply of one command: its verified lines, or the reason of a failed reply.
    public struct Section: Equatable, Sendable {
        public let command: String
        public let result: Result<[String], Failure>
        public init(command: String, result: Result<[String], Failure>) {
            self.command = command
            self.result = result
        }
    }

    public static let commands: [ConsoleCommand] = [.version, .status, .config, .events]
    public static let hiddenValue = "<hidden>"

    /// Joins the sections in the given order. Removes each `CRC32` line. Replaces the value of each name
    /// and MAC key of `ConfigSchema` with `<hidden>`, unless `includeNames` is true.
    public static func build(appVersion: String, link: Link, includeNames: Bool, sections: [Section]) -> String {
        var out = ["RLCD Companion \(appVersion)", "Link: \(link.rawValue)"]
        for s in sections {
            out.append("")
            out.append("## \(s.command)")
            switch s.result {
            case let .failure(f):
                out.append("ERROR \(f.reason)")
            case let .success(lines):
                for line in lines where !line.hasPrefix("CRC32") {
                    out.append(includeNames ? line : redact(line))
                }
            }
        }
        return out.joined(separator: "\n") + "\n"
    }

    private static func redact(_ line: String) -> String {
        if line.hasPrefix("SENSOR ") { return redactSensor(line) }
        if line.hasPrefix("BLE ") { return redactBle(line) }
        guard let eq = line.firstIndex(of: "=") else { return line }
        let key = String(line[..<eq])
        guard let kind = ConfigSchema.entry(for: key)?.kind, kind == .name || kind == .mac else { return line }
        return key + "=" + hiddenValue
    }

    /// `SENSOR <i> <name> temp=...`: the name has 1 to 12 characters and may hold spaces.
    private static func redactSensor(_ line: String) -> String {
        let parts = line.split(separator: " ", maxSplits: 2, omittingEmptySubsequences: false)
        guard parts.count == 3, let end = parts[2].range(of: " temp=") else { return line }
        return "\(parts[0]) \(parts[1]) \(hiddenValue)" + parts[2][end.lowerBound...]
    }

    /// `BLE rejected=.. last=<MAC|--> ...`: the MAC belongs to a foreign device.
    private static func redactBle(_ line: String) -> String {
        line.split(separator: " ", omittingEmptySubsequences: false).map { part -> String in
            part.hasPrefix("last=") && part != "last=--" ? "last=" + hiddenValue : String(part)
        }.joined(separator: " ")
    }
}
