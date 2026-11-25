import Charts
import RlcdProtocol
import SwiftUI

/// Source of the daily chart rows.
enum DailySource: String, CaseIterable, Identifiable {
    case card = "Card files"
    case panel = "Panel (BLE)"
    var id: String { rawValue }
}

/// One chart pair for each sensor: daily minimum, mean and maximum of temperature and humidity, and the daily noise
/// peak for rows of `daily_v2` to `daily_v4`, and the mold hours
/// of each sensor as a bar chart for rows of `daily_v4`. The source `Card files` reads only the rows that `DailyLog.load`
/// gave; the source `Panel (BLE)` sends `DAYS <N>` only on the read button.
struct DailyChartView: View {
    @Bindable var model: AppModel
    var temperatureUnit = "°C"
    var limits: [ChartLimit] = []

    private struct Point: Identifiable {
        let id: String
        let date: Date
        let series: String
        let value: Double
    }

    private static let parser: DateFormatter = {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()

    private func points(_ sensor: Int, _ values: (DailySensorStats) -> [(String, Double?)]) -> [Point] {
        folder.rows.flatMap { row -> [Point] in
            guard let date = Self.parser.date(from: row.date) else { return [] }
            guard sensor < row.sensors.count else { return [] } // older files hold fewer sensors
            return values(row.sensors[sensor]).compactMap { name, value in
                value.map { Point(id: "\(row.date)-\(name)", date: date, series: name, value: $0) }
            }
        }
    }

    private var noisePeaks: [Point] {
        folder.rows.compactMap { row in
            guard let date = Self.parser.date(from: row.date), let peak = row.noisePeakDb else { return nil }
            return Point(id: row.date, date: date, series: "Peak", value: Double(peak))
        }
    }

    private func moldPoints(_ sensor: Int) -> [Point] {
        folder.rows.compactMap { row in
            guard let date = Self.parser.date(from: row.date), sensor < row.moldHours.count,
                  let hours = row.moldHours[sensor] else { return nil }
            return Point(id: row.date, date: date, series: "Mold", value: Double(hours))
        }
    }

    private func moldChart(_ data: [Point]) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("Hours above mold limit").font(.caption).foregroundStyle(.secondary)
            Chart(data) {
                BarMark(x: .value("Day", $0.date, unit: .day), y: .value("Hours", $0.value))
            }
            .chartYScale(domain: 0...24)
            .frame(height: 110)
        }
    }

    private func chart(_ title: String, _ data: [Point], limits: [ChartLimit] = []) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            if data.isEmpty {
                Text("No data").font(.caption2).foregroundStyle(.secondary)
            } else {
                Chart {
                    ForEach(data) {
                        LineMark(x: .value("Day", $0.date, unit: .day), y: .value(title, $0.value))
                            .foregroundStyle(by: .value("Series", $0.series))
                        PointMark(x: .value("Day", $0.date, unit: .day), y: .value(title, $0.value))
                            .foregroundStyle(by: .value("Series", $0.series))
                    }
                    ForEach(limits, id: \.key) { limit in
                        RuleMark(y: .value(limit.label, limit.value))
                            .lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 3]))
                            .foregroundStyle(.red)
                            .annotation(position: .top, alignment: .leading) {
                                Text(limit.label).font(.caption2).foregroundStyle(.red)
                            }
                    }
                }
                .chartYScale(domain: .automatic(includesZero: false))
                .frame(height: 110)
            }
        }
    }

    private var folder: DailyFolder {
        switch model.dailySource {
        case .card: model.dailyFolder ?? DailyFolder(rows: [], failedFiles: [])
        case .panel: DailyFolder(rows: model.daysResult?.rows ?? [], failedFiles: [])
        }
    }

    private var sourceBar: some View {
        HStack {
            Picker("Source", selection: $model.dailySource) {
                ForEach(DailySource.allCases) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented).fixedSize()
            if model.dailySource == .panel {
                Picker("Days", selection: $model.daysCount) {
                    ForEach(DaysReply.rowChoices, id: \.self) { Text("\($0)").tag($0) }
                }
                .pickerStyle(.segmented).fixedSize()
                Button("Read daily rows") { model.readDays(rows: model.daysCount) }
                    .disabled(!model.consoleConnected)
            }
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            sourceBar
            charts
        }
    }

    @ViewBuilder private var charts: some View {
        let folder = self.folder
        if folder.rows.isEmpty {
            switch model.dailySource {
            case .card:
                if model.dailyFolder != nil {
                    Text("No daily summary rows in the folder").font(.caption).foregroundStyle(.secondary)
                }
            case .panel:
                Text(model.daysResult != nil ? "The panel returned no daily rows"
                    : (model.consoleConnected ? "No daily rows read yet" : "Console not connected"))
                    .font(.caption).foregroundStyle(.secondary)
            }
        } else {
            VStack(alignment: .leading, spacing: 8) {
                ForEach(0..<DailyLog.sensorNames.count, id: \.self) { sensor in
                    let temp = points(sensor) { [("Min", $0.tempMinC), ("Mean", $0.tempMeanC), ("Max", $0.tempMaxC)] }
                    let rh = points(sensor) { [("Min", $0.rhMin), ("Mean", $0.rhMean), ("Max", $0.rhMax)] }
                    if !temp.isEmpty || !rh.isEmpty { // slots 4 and 5 exist only in daily_v3 rows
                        Text(DailyLog.sensorNames[sensor]).font(.headline)
                        HStack(alignment: .top) {
                            chart("Temperature (\(temperatureUnit))", temp, limits: ChartLimits.temperature(limits))
                            chart("Humidity (%RH)", rh, limits: ChartLimits.humidity(limits))
                        }
                        let mold = moldPoints(sensor)
                        if !mold.isEmpty { moldChart(mold) } // only daily_v4 rows hold the mold hours
                    }
                }
                Text("Noise").font(.headline)
                chart("Daily noise peak (dB)", noisePeaks)
                if !folder.failedFiles.isEmpty {
                    Text("Unreadable: \(folder.failedFiles.joined(separator: ", "))")
                        .font(.caption).foregroundStyle(.red)
                }
            }
        }
    }
}
