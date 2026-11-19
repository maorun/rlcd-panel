import Foundation

/// CSV text with a header line, Celsius values and empty fields for missing values.
public enum CsvExport {
    public static func telemetry(_ log: TelemetryLog) -> String {
        let tileColumns = (0..<TelemetryFrame.tileCount).flatMap { ["t\($0)_c", "rh\($0)_pct"] }
        var out = (["time", "sequence", "cell_pct", "alerts"] + tileColumns).joined(separator: ",") + "\n"
        let formatter = ISO8601DateFormatter()
        for r in log.rows {
            var f = [formatter.string(from: r.time), String(r.sequence), r.cellPercent.map(String.init) ?? "", String(r.alerts)]
            for i in 0..<TelemetryFrame.tileCount {
                let tile = i < r.tiles.count ? r.tiles[i] : nil
                f.append(number(tile?.temperatureC, 2))
                f.append(number(tile?.humidityPct, 1))
            }
            out += f.joined(separator: ",") + "\n"
        }
        return out
    }

    public static func history(_ rows: [HistoryRow]) -> String {
        HistoryReply.header + "\n" + rows.map {
            [String($0.sensor), String($0.ageHours), number($0.temperatureC, 2), number($0.humidityPct, 1), number($0.noiseDb, 1)]
                .joined(separator: ",") + "\n"
        }.joined()
    }

    public static func events(_ rows: [EventRow]) -> String {
        EventsReply.header + ",text\n" + rows.map {
            "\($0.time),\($0.event),\($0.detail),\(field(EventText.describe($0)))\n"
        }.joined()
    }

    /// Quotes a field with a comma, a quote or a line break and doubles each inner quote.
    private static func field(_ s: String) -> String {
        guard s.contains(where: { $0 == "," || $0 == "\"" || $0 == "\n" || $0 == "\r" }) else { return s }
        return "\"" + s.replacingOccurrences(of: "\"", with: "\"\"") + "\""
    }

    private static func number(_ v: Double?, _ digits: Int) -> String {
        v.map { String(format: "%.\(digits)f", $0) } ?? ""
    }
}
