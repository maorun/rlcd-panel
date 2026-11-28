import Foundation

/// Markdown record of the calibration state: the `VERSION` reply, the offset and gain keys of `CONFIG CHANGED`,
/// the newest `SPREAD` reply and the stored salt results. Each part shows the time of its read; a missing part
/// shows `--`.
public enum CalibrationReport {
    /// One reply with the time of its read. A `nil` value is a missing part.
    public struct Part<Value> {
        public let value: Value?
        public let date: Date?

        public init(_ value: Value?, date: Date?) {
            self.value = value
            self.date = date
        }
    }

    /// Keys of `CONFIG CHANGED` that the report lists: the temperature and humidity offsets and the humidity gains.
    public static func isCalibrationKey(_ key: String) -> Bool {
        key.hasSuffix("temp_offset_c") || key.hasSuffix("rh_offset_pct") || key.hasSuffix("rh_gain")
    }

    /// UTC time text of a part, `--` without a time. The format is fixed so that the text does not depend on the locale.
    public static func dateText(_ date: Date?) -> String {
        guard let date else { return "--" }
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.string(from: date)
    }

    /// The Markdown text. `generated` is the time of the export.
    public static func render(generated: Date, version: Part<VersionReply>, changed: Part<[ConfigChangedEntry]>,
                              spread: Part<SpreadReply>, salt: Part<SaltReply>, saltMagnesium: Part<SaltReply>) -> String {
        var out = ["# Calibration report", "", "Generated: \(dateText(generated))", ""]
        out += versionSection(version)
        out += keySection(changed)
        out += spreadSection(spread)
        out += ["## Salt check", ""]
        out += saltLines(title: "NaCl (75 %RH)", salt)
        out += saltLines(title: "MgCl2 (33 %RH)", saltMagnesium)
        return out.joined(separator: "\n") + "\n"
    }

    /// Table cell text: a bar or a backslash of a sensor name or a value must not break the row.
    static func cell(_ text: String) -> String {
        text.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "|", with: "\\|")
    }

    private static func heading(_ title: String, _ date: Date?, present: Bool) -> [String] {
        ["## \(title)", "", "Read: \(present ? dateText(date) : "--")", ""]
    }

    private static func versionSection(_ part: Part<VersionReply>) -> [String] {
        var out = heading("Firmware", part.date, present: part.value != nil)
        guard let v = part.value else { return out + ["--", ""] }
        out += ["- Firmware: \(v.firmware)", "- Git: \(v.git)", "- Core: \(v.core)", "- State: \(v.state)",
                "- Built: \(v.built)", ""]
        return out
    }

    private static func keySection(_ part: Part<[ConfigChangedEntry]>) -> [String] {
        var out = heading("Offsets and gains", part.date, present: part.value != nil)
        guard let entries = part.value else { return out + ["--", ""] }
        let keys = entries.filter { isCalibrationKey($0.key) }
        if keys.isEmpty { return out + ["No offset or gain differs from the default.", ""] }
        out += ["| Key | Value |", "| --- | --- |"]
        out += keys.map { "| \(cell($0.key)) | \(cell($0.value)) |" }
        return out + [""]
    }

    private static func spreadSection(_ part: Part<SpreadReply>) -> [String] {
        var out = heading("Co-location spread", part.date, present: part.value != nil)
        guard let reply = part.value else { return out + ["--", ""] }
        switch reply.outcome {
        case let .skipped(sensors):
            return out + ["Skipped: \(sensors) sensor(s) with enough samples.", ""]
        case let .measured(sensors, temp, rh, rows):
            out += ["Sensors: \(sensors), median \(temp) C and \(rh) %RH", "",
                    "| Sensor | Temp dev (C) | RH dev (%RH) | Result |", "| --- | --- | --- | --- |"]
            out += rows.map { "| \(cell($0.name)) | \($0.tempDev) | \($0.rhDev) | \($0.isOutlier ? "OUTLIER" : "OK") |" }
            return out + [""]
        }
    }

    private static func saltLines(title: String, _ part: Part<SaltReply>) -> [String] {
        var out = ["### \(title)", "", "Read: \(part.value != nil ? dateText(part.date) : "--")", ""]
        guard let reply = part.value else { return out + ["--", ""] }
        switch reply.outcome {
        case let .measured(m, proposal):
            out += ["- Sensor: \(m.name)", "- Hours: \(m.hours)", "- RH mean: \(m.rhMean) %RH",
                    "- RH span: \(m.rhSpan) %RH", "- Temperature mean: \(m.tempMean) C",
                    "- Reference: \(m.reference) %RH"]
            if let proposal { out.append("- Proposal: \(proposal.key)=\(proposal.value)") }
        case let .skipped(name, hours):
            out.append("Skipped: \(name) has \(hours) hour(s).")
        case let .unstable(name, span):
            out.append("Unstable: \(name) has a span of \(span) %RH.")
        }
        return out + [""]
    }
}

/// Time of the last good read of each part of the report. A part that the app clears keeps its time here; the
/// report ignores a time without a value.
public struct CalibrationReadLog: Equatable {
    public enum Part: Hashable { case version, configChanged, spread, salt, saltMagnesium }

    private var dates: [Part: Date] = [:]

    public init() {}

    public mutating func record(_ part: Part, at date: Date) { dates[part] = date }

    public func date(_ part: Part) -> Date? { dates[part] }
}
