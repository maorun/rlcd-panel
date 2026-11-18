import Charts
import RlcdProtocol
import SwiftUI

/// Bar chart of the active hours of each alert and month, from the `events_v1` files of the archive folder.
struct AlertStatsView: View {
    let result: AlertStatsResult

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            let bars = result.stats.filter { $0.rises > 0 || $0.hours > 0 }
            if bars.isEmpty {
                Text("No alert intervals with a known start in the archive folder.").font(.caption).foregroundStyle(.secondary)
            } else {
                Chart(bars, id: \.id) { s in
                    BarMark(x: .value("Month", s.month), y: .value("Hours", s.hours))
                        .foregroundStyle(by: .value("Alert", s.name))
                        .position(by: .value("Alert", s.name))
                        .annotation(position: .top) {
                            Text("\(s.rises)×").font(.caption2).foregroundStyle(.secondary)
                        }
                }
                .chartYAxisLabel("Active hours")
                .frame(height: 200)
                Text("Label: rises of the month. Intervals with an unknown start count apart: \(unknownText)")
                    .font(.caption).foregroundStyle(.secondary)
            }
            if !result.skippedFiles.isEmpty {
                Text("Skipped: \(result.skippedFiles.joined(separator: ", "))").font(.caption).foregroundStyle(.red)
            }
        }
    }

    private var unknownText: String {
        let total = result.stats.reduce(0) { $0 + $1.unknownStarts }
        return total == 0 ? "none" : "\(total)"
    }
}

private extension AlertMonthStat {
    var id: String { "\(month)/\(alert)" }
}
