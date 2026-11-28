import Charts
import RlcdProtocol
import SwiftUI

/// Source of the daily chart rows.
enum DailySource: String, CaseIterable, Identifiable {
    case card = "Card files"
    case panel = "Panel (BLE)"
    var id: String { rawValue }
}

/// Time scale of the daily charts.
enum DailyScale: String, CaseIterable, Identifiable {
    case days = "Days"
    case months = "Months"
    var id: String { rawValue }
}

/// One chart pair for each sensor: daily minimum, mean and maximum of temperature and humidity, and the daily noise
/// peak for rows of `daily_v2` to `daily_v6`, and the mold hours (`daily_v4` to `daily_v6`) and dry hours (`daily_v6`)
/// of each sensor as two bar series. The source `Card files` reads only the rows that `DailyLog.load`
/// gave; the source `Panel (BLE)` sends `DAYS <N>` only on the read button.
struct DailyChartView: View {
    @Bindable var model: AppModel
    var temperatureUnit = "°C"
    var limits: [ChartLimit] = []

    /// View state as a class: `@State` needs the SwiftUI macro plugin, which the command line tools lack.
    private final class ViewState: ObservableObject {
        @Published var scale = DailyScale.days
    }

    @StateObject private var state = ViewState()
    private var scale: DailyScale { state.scale }

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
            return Point(id: "\(row.date)-Mold", date: date, series: "Mold", value: Double(hours))
        }
    }

    private func dryPoints(_ sensor: Int) -> [Point] {
        folder.rows.compactMap { row in
            guard let date = Self.parser.date(from: row.date), sensor < row.dryHours.count,
                  let hours = row.dryHours[sensor] else { return nil }
            return Point(id: "\(row.date)-Dry", date: date, series: "Dry", value: Double(hours))
        }
    }

    private func heatPoints(_ sensor: Int) -> [Point] {
        folder.rows.compactMap { row in
            guard let date = Self.parser.date(from: row.date), sensor < row.heatHours.count,
                  let hours = row.heatHours[sensor] else { return nil }
            return Point(id: "\(row.date)-Heat", date: date, series: "Heat", value: Double(hours))
        }
    }

    /// Heat hours (`daily_v7`) as one bar series.
    private func heatChart(_ heat: [Point]) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("Hours at or above heat limit").font(.caption).foregroundStyle(.secondary)
            Chart(heat) {
                BarMark(x: .value("Day", $0.date, unit: .day), y: .value("Hours", $0.value))
                    .foregroundStyle(.red)
            }
            .chartYScale(domain: 0...24)
            .frame(height: 110)
        }
    }

    /// Mold hours and, for `daily_v6` rows, dry hours as two bar series beside each other.
    private func hoursChart(mold: [Point], dry: [Point]) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("Hours above mold limit / at or below dry limit").font(.caption).foregroundStyle(.secondary)
            Chart(mold + dry) {
                BarMark(x: .value("Day", $0.date, unit: .day), y: .value("Hours", $0.value))
                    .foregroundStyle(by: .value("Series", $0.series))
                    .position(by: .value("Series", $0.series))
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

    /// One line for each room that holds a mold or heat value; none for a room without either (older files, empty sensor).
    @ViewBuilder private func moldSummary(_ rows: [DailyRow]) -> some View {
        let lines = MoldSummary.summarize(rows: rows).compactMap { room in
            MoldSummary.chartLine(room).map { (DailyLog.sensorNames[room.sensor], $0) }
        }
        if !lines.isEmpty {
            VStack(alignment: .leading, spacing: 2) {
                ForEach(lines, id: \.0) { name, text in
                    Text("\(name): \(text)").font(.caption)
                }
            }
        }
    }

    /// Months shown in the year overview (the newest twelve).
    private static let overviewMonths = 12

    private struct MonthPoint: Identifiable {
        let id: String
        let month: String
        let series: String
        let value: Double
    }

    private func monthRange(_ months: [MonthSummary], _ low: KeyPath<MonthSummary, Double?>,
                            _ mean: KeyPath<MonthSummary, Double?>, _ high: KeyPath<MonthSummary, Double?>) -> [MonthPoint] {
        months.flatMap { m -> [MonthPoint] in
            [("Min", low), ("Mean", mean), ("Max", high)].compactMap { name, path in
                m[keyPath: path].map { MonthPoint(id: "\(m.month)-\(name)", month: m.month, series: name, value: $0) }
            }
        }
    }

    private func monthHours(_ months: [MonthSummary]) -> [MonthPoint] {
        months.flatMap { m -> [MonthPoint] in
            [("Mold", m.moldHours), ("Dry", m.dryHours), ("Heat", m.heatHours)].compactMap { name, hours in
                hours.map { MonthPoint(id: "\(m.month)-\(name)", month: m.month, series: name, value: Double($0)) }
            }
        }
    }

    /// Minimum, mean and maximum of each month; a month without a row has no mark.
    private func monthChart(_ title: String, _ data: [MonthPoint], months: [String]) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            Chart(data) {
                PointMark(x: .value("Month", $0.month), y: .value(title, $0.value))
                    .foregroundStyle(by: .value("Series", $0.series))
            }
            .chartXScale(domain: months)
            .chartYScale(domain: .automatic(includesZero: false))
            .frame(height: 110)
        }
    }

    private func monthHoursChart(_ data: [MonthPoint], months: [String]) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("Hours per month: mold / dry / heat (each day counts with the limit of its day; older files add no dry or heat hours)").font(.caption).foregroundStyle(.secondary)
            Chart(data) {
                BarMark(x: .value("Month", $0.month), y: .value("Hours", $0.value))
                    .foregroundStyle(by: .value("Series", $0.series))
                    .position(by: .value("Series", $0.series))
            }
            .chartXScale(domain: months)
            .frame(height: 110)
        }
    }

    @ViewBuilder private func yearOverview(_ rows: [DailyRow]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            if model.dailySource == .panel {
                Text("The panel source holds at most \(DaysReply.rowChoices.max() ?? 31) days; use the card files for a full year.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            let all = (0..<DailyLog.sensorNames.count).map { DailyLog.monthSummary(rows: rows, sensor: $0) }
            if all.allSatisfy({ $0.allSatisfy { $0.isGap } }) {
                Text("No monthly data").font(.caption).foregroundStyle(.secondary)
            }
            ForEach(0..<all.count, id: \.self) { sensor in
                let months = Array(all[sensor].suffix(Self.overviewMonths))
                if months.contains(where: { !$0.isGap }) {
                    let names = months.map(\.month)
                    Text(DailyLog.sensorNames[sensor]).font(.headline)
                    HStack(alignment: .top) {
                        monthChart("Temperature (\(temperatureUnit))",
                                   monthRange(months, \.tempMinC, \.tempMeanC, \.tempMaxC), months: names)
                        monthChart("Humidity (%RH)", monthRange(months, \.rhMin, \.rhMean, \.rhMax), months: names)
                    }
                    let hours = monthHours(months)
                    if !hours.isEmpty { monthHoursChart(hours, months: names) }
                }
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
            Picker("Scale", selection: $state.scale) {
                ForEach(DailyScale.allCases) { Text($0.rawValue).tag($0) }
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
                moldSummary(folder.rows)
                if scale == .months { yearOverview(folder.rows) }
                if scale == .days {
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
                            let dry = dryPoints(sensor)
                            if !mold.isEmpty || !dry.isEmpty { hoursChart(mold: mold, dry: dry) } // mold: daily_v4 and newer, dry: daily_v6
                            let heat = heatPoints(sensor)
                            if !heat.isEmpty { heatChart(heat) } // heat: daily_v7
                        }
                    }
                }
                if scale == .days {
                    Text("Noise").font(.headline)
                    chart("Daily noise peak (dB)", noisePeaks)
                }
                if !folder.failedFiles.isEmpty {
                    Text("Unreadable: \(folder.failedFiles.joined(separator: ", "))")
                        .font(.caption).foregroundStyle(.red)
                }
            }
        }
    }
}
