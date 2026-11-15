import Charts
import RlcdProtocol
import SwiftUI

/// One chart pair for each sensor with the hourly temperature and humidity of one month, and one chart with the noise
/// level and the L10 and L90 percentiles. Reads only the rows that `ClimateLog.load` gave; sends no console command.
struct ClimateChartView: View {
    let model: AppModel
    var temperatureUnit = "°C"

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

    private func chart(_ title: String, _ points: [Point]) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            if points.isEmpty {
                Text("No data").font(.caption2).foregroundStyle(.secondary)
            } else {
                Chart(points) {
                    LineMark(x: .value("Time", $0.date), y: .value(title, $0.value))
                        .foregroundStyle(by: .value("Series", $0.series))
                }
                .chartYScale(domain: .automatic(includesZero: false))
                .frame(height: 110)
            }
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
                ForEach(0..<ClimateLog.sensorNames.count, id: \.self) { sensor in
                    let temp = points("Temperature") { sensor < $0.sensors.count ? $0.sensors[sensor].tempC : nil }
                    let rh = points("Humidity") { sensor < $0.sensors.count ? $0.sensors[sensor].rh : nil }
                    if !temp.isEmpty || !rh.isEmpty { // slots 4 and 5 exist only in climate_v5 rows
                        Text(ClimateLog.sensorNames[sensor]).font(.headline)
                        HStack(alignment: .top) {
                            chart("Temperature (\(temperatureUnit))", temp)
                            chart("Humidity (%RH)", rh)
                        }
                    }
                }
                let noise = points("Noise") { $0.noiseDb.map(Double.init) }
                    + points("L10") { $0.noiseL10.map(Double.init) }
                    + points("L90") { $0.noiseL90.map(Double.init) }
                if !noise.isEmpty {
                    Text("Noise").font(.headline)
                    chart("Noise level (dB)", noise)
                }
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

    private func label(_ r: WeekRow) -> String { r.date.isEmpty ? "day \(r.day)" : String(r.date.suffix(5)) }

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
                    }
                }
            }
        }
    }
}
