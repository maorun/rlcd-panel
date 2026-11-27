import Charts
import RlcdProtocol
import SwiftUI

/// One chart pair for each sensor with the hourly temperature and humidity of one month, and one chart with the noise
/// level and the L10 and L90 percentiles. Reads only the rows that `ClimateLog.load` gave; sends no console command.
struct ClimateChartView: View {
    let model: AppModel
    var temperatureUnit = "°C"

    private enum Mode: String, CaseIterable, Identifiable {
        case sensors = "Sensors"
        case compare = "Compare rooms"
        var id: String { rawValue }
    }

    /// View state as a class: `@State` needs the SwiftUI macro plugin, which the command line tools lack.
    private final class ViewState: ObservableObject {
        @Published var mode = Mode.sensors
        @Published var compareMetric = ClimateCompare.Metric.temperature
        @Published var compareSelection: [Int] = [0, 1]
    }

    @StateObject private var state = ViewState()
    private var mode: Mode { state.mode }
    private var compareMetric: ClimateCompare.Metric { state.compareMetric }
    private var compareSelection: [Int] { state.compareSelection }

    private var data: ClimateFolder { model.climateData ?? ClimateFolder(rows: [], failedFiles: []) }
    private var month: String { model.climateMonth }

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
        f.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return f
    }()

    private func points(_ series: String, _ value: (ClimateRow) -> Double?) -> [Point] {
        data.rows.compactMap { row in
            guard let date = Self.parser.date(from: row.timestamp), let v = value(row) else { return nil }
            return Point(id: "\(row.timestamp)-\(series)", date: date, series: series, value: v)
        }
    }

    private static let day: DateFormatter = {
        let f = DateFormatter()
        f.dateStyle = .medium
        f.timeStyle = .none
        return f
    }()

    /// Forecast from the rows of the selected month and the two months before it; the app sends no command for it.
    private func forecastText(_ sensor: Int) -> String {
        let limit = BatteryForecast.limitPct(config: model.lastConfig)
        guard let date = BatteryForecast.estimate(rows: model.climateForecastRows, sensor: sensor, limitPct: limit) else {
            return "Battery forecast: not enough data"
        }
        return "Battery change near \(Self.day.string(from: date))"
    }

    private func chart(_ title: String, _ points: [Point], limits: [ChartLimit] = []) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            if points.isEmpty {
                Text("No data").font(.caption2).foregroundStyle(.secondary)
            } else {
                Chart {
                    ForEach(points) {
                        LineMark(x: .value("Time", $0.date), y: .value(title, $0.value))
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

    /// One chart of the panel cell voltage; a shaded band marks each run of hours on USB power.
    @ViewBuilder private var panelBatteryView: some View {
        let series = PanelBatterySeries(rows: data.rows)
        Text("Panel cell").font(.headline)
        if series.isEmpty {
            Text("No battery data").font(.caption2).foregroundStyle(.secondary)
        } else {
            VStack(alignment: .leading, spacing: 2) {
                Text("Cell voltage (V), shaded: USB power").font(.caption).foregroundStyle(.secondary)
                Chart {
                    ForEach(Array(series.usbRanges.enumerated()), id: \.offset) { _, range in
                        if let start = range.startDate, let end = range.endDate {
                            RectangleMark(xStart: .value("USB from", start), xEnd: .value("USB to", end))
                                .foregroundStyle(.gray.opacity(0.25))
                        }
                    }
                    ForEach(Array(series.points.enumerated()), id: \.offset) { _, point in
                        if let date = PanelBatterySeries.date(point.timestamp) {
                            LineMark(x: .value("Time", date), y: .value("Cell voltage", point.volts))
                        }
                    }
                }
                .chartYScale(domain: .automatic(includesZero: false))
                .frame(height: 110)
            }
        }
    }

    private func compareTitle(_ metric: ClimateCompare.Metric) -> String {
        switch metric {
        case .temperature: return "Temperature (\(temperatureUnit))"
        case .humidity: return "Humidity (%RH)"
        case .feelsLike: return "Feels like (\(temperatureUnit))"
        case .absoluteHumidity: return "Absolute humidity (g/m³)"
        }
    }

    private func toggleCompare(_ sensor: Int) {
        if let i = state.compareSelection.firstIndex(of: sensor) {
            if state.compareSelection.count > ClimateCompare.minSensors { state.compareSelection.remove(at: i) }
        } else if state.compareSelection.count < ClimateCompare.maxSensors {
            state.compareSelection.append(sensor)
        }
    }

    /// One chart with one colored line for each selected sensor; an empty field ends a line (each run is its own series).
    @ViewBuilder private var compareView: some View {
        let available = (0..<ClimateLog.sensorNames.count).filter { sensor in
            data.rows.contains { sensor < $0.sensors.count && ($0.sensors[sensor].tempC != nil || $0.sensors[sensor].rh != nil) }
        }
        HStack {
            ForEach(available, id: \.self) { sensor in
                Toggle(ClimateCompare.name(sensor: sensor, config: model.lastConfig), isOn: Binding(
                    get: { compareSelection.contains(sensor) }, set: { _ in toggleCompare(sensor) }))
                    .toggleStyle(.button)
            }
        }
        Picker("Value", selection: $state.compareMetric) {
            Text("Temperature").tag(ClimateCompare.Metric.temperature)
            Text("Humidity").tag(ClimateCompare.Metric.humidity)
            Text("Feels like").tag(ClimateCompare.Metric.feelsLike)
            Text("Absolute humidity").tag(ClimateCompare.Metric.absoluteHumidity)
        }
        .pickerStyle(.segmented)
        .frame(maxWidth: 480)
        // Without two chosen sensors that have data, the first two sensors with data are the default.
        let chosen = compareSelection.filter(available.contains)
        let shown = chosen.count >= ClimateCompare.minSensors ? chosen : Array(available.prefix(ClimateCompare.minSensors))
        if let chart = ClimateCompare.build(rows: data.rows, sensors: shown,
                                            metric: compareMetric, config: model.lastConfig),
           let range = chart.axisRange {
            let title = compareTitle(compareMetric)
            Chart {
                ForEach(chart.series, id: \.sensor) { series in
                    ForEach(Array(series.segments.enumerated()), id: \.offset) { index, run in
                        ForEach(run, id: \.timestamp) { sample in
                            if let date = Self.parser.date(from: sample.timestamp), let value = sample.value {
                                LineMark(x: .value("Time", date), y: .value(title, value),
                                         series: .value("Run", "\(series.sensor)-\(index)"))
                                    .foregroundStyle(by: .value("Sensor", series.name))
                            }
                        }
                    }
                }
            }
            .chartForegroundStyleScale(domain: chart.series.map(\.name))
            .chartYScale(domain: range)
            .frame(height: 220)
        } else {
            Text("Select two to six sensors with data").font(.caption).foregroundStyle(.secondary)
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if model.climateMonths.isEmpty {
                Text("No hourly climate files in the folder").font(.caption).foregroundStyle(.secondary)
            } else {
                Picker("Month", selection: Binding(get: { model.climateMonth }, set: { model.selectClimateMonth($0) })) {
                    ForEach(model.climateMonths.reversed(), id: \.self) { Text($0) }
                }
                .frame(maxWidth: 220)
                if data.rows.isEmpty {
                    Text("No hourly rows in \(month)").font(.caption).foregroundStyle(.secondary)
                }
                Picker("Mode", selection: $state.mode) {
                    ForEach(Mode.allCases) { (m: Mode) in Text(m.rawValue).tag(m) }
                }
                .pickerStyle(.segmented)
                .frame(maxWidth: 320)
                if mode == .compare { compareView }
                let limits = ChartLimits.from(config: model.lastConfig)
                let sensorIndices: [Int] = mode == .compare ? [] : Array(0..<ClimateLog.sensorNames.count)
                ForEach(sensorIndices, id: \.self) { sensor in
                    let temp = points("Temperature") { sensor < $0.sensors.count ? $0.sensors[sensor].tempC : nil }
                    let rh = points("Humidity") { sensor < $0.sensors.count ? $0.sensors[sensor].rh : nil }
                    if !temp.isEmpty || !rh.isEmpty { // slots 4 and 5 exist only in climate_v5 rows
                        Text(ClimateLog.sensorNames[sensor]).font(.headline)
                        HStack(alignment: .top) {
                            chart("Temperature (\(temperatureUnit))", temp, limits: ChartLimits.temperature(limits))
                            chart("Humidity (%RH)", rh, limits: ChartLimits.humidity(limits))
                        }
                        if sensor > 0 { Text(forecastText(sensor)).font(.caption).foregroundStyle(.secondary) }
                    } else if sensor > 0, model.climateForecastRows.contains(where: { sensor < $0.sensors.count && $0.sensors[sensor].batteryPct != nil }) {
                        // The selected month has no climate values for this slot, but an earlier month has battery data.
                        Text(ClimateLog.sensorNames[sensor]).font(.headline)
                        Text(forecastText(sensor)).font(.caption).foregroundStyle(.secondary)
                    }
                }
                let noise = points("Noise") { $0.noiseDb.map(Double.init) }
                    + points("L10") { $0.noiseL10.map(Double.init) }
                    + points("L90") { $0.noiseL90.map(Double.init) }
                if !noise.isEmpty && mode == .sensors {
                    Text("Noise").font(.headline)
                    chart("Noise level (dB)", noise)
                }
                if mode == .sensors && !data.rows.isEmpty { panelBatteryView }
                if !data.failedFiles.isEmpty {
                    Text("Unreadable: \(data.failedFiles.joined(separator: ", "))")
                        .font(.caption).foregroundStyle(.red)
                }
            }
        }
    }
}

/// Seven bars (daily minimum to maximum) for each sensor from the `WEEK` reply. Sends no console command.
struct WeekChartView: View {
    let rows: [WeekRow]

    private func label(_ r: WeekRow) -> String { WeekMold.label(day: r.day, date: r.date) }

    private func moldChart(_ sensor: Int) -> some View {
        let points = WeekMold.points(rows: rows, sensor: sensor)
        let dry = points.contains { $0.dryHours != nil }
        return VStack(alignment: .leading, spacing: 2) {
            Text(dry ? "Mold / dry hours" : "Mold hours").font(.caption).foregroundStyle(.secondary)
            Chart {
                ForEach(points.filter { $0.hours != nil }, id: \.self) { p in
                    BarMark(x: .value("Day", p.label), y: .value("Hours", p.hours ?? 0))
                        .foregroundStyle(by: .value("Series", "Mold"))
                        .position(by: .value("Series", "Mold"))
                }
                ForEach(points.filter { $0.dryHours != nil }, id: \.self) { p in
                    BarMark(x: .value("Day", p.label), y: .value("Hours", p.dryHours ?? 0))
                        .foregroundStyle(by: .value("Series", "Dry"))
                        .position(by: .value("Series", "Dry"))
                }
            }
            .chartForegroundStyleScale(["Mold": Color.blue, "Dry": Color.orange])
            .chartLegend(.visible)
            .chartXScale(domain: points.map(\.label))
            .chartYScale(domain: 0...24)
            .frame(height: 110)
        }
    }

    private func chart(_ title: String, _ sensor: Int, _ low: KeyPath<WeekRow, Double?>, _ high: KeyPath<WeekRow, Double?>) -> some View {
        let days = rows.filter { $0.sensor == sensor && $0[keyPath: low] != nil && $0[keyPath: high] != nil }
        return VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            if days.isEmpty {
                Text("No data").font(.caption2).foregroundStyle(.secondary)
            } else {
                Chart(days, id: \.self) { r in
                    BarMark(x: .value("Day", label(r)), yStart: .value("Min", r[keyPath: low] ?? 0), yEnd: .value("Max", r[keyPath: high] ?? 0))
                }
                .chartXScale(domain: days.sorted { $0.day > $1.day }.map(label))
                .chartYScale(domain: .automatic(includesZero: false))
                .frame(height: 110)
            }
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Last 7 days").font(.subheadline)
            ForEach(0..<ClimateLog.sensorNames.count, id: \.self) { sensor in
                if rows.contains(where: { $0.sensor == sensor && ($0.temperatureMinC != nil || $0.humidityMinPct != nil) }) {
                    Text(ClimateLog.sensorNames[sensor]).font(.headline)
                    HStack(alignment: .top) {
                        chart("Temperature (°C)", sensor, \.temperatureMinC, \.temperatureMaxC)
                        chart("Humidity (%RH)", sensor, \.humidityMinPct, \.humidityMaxPct)
                        if WeekMold.points(rows: rows, sensor: sensor).contains(where: { $0.hours != nil || $0.dryHours != nil }) {
                            moldChart(sensor)
                        }
                    }
                    if let dry = WeekReply.drySum(rows: rows, sensor: sensor) {
                        Text("Dry \(dry) h").font(.caption).foregroundStyle(.secondary)
                    }
                    if let heat = WeekReply.heatLabel(rows: rows, sensor: sensor) {
                        Text(heat).font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
        }
    }
}
