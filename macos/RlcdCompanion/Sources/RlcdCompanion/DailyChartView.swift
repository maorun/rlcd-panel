import Charts
import RlcdProtocol
import SwiftUI

/// One chart pair for each sensor: daily minimum, mean and maximum of temperature and humidity, and the daily noise
/// peak for rows of `daily_v2` and `daily_v3`. Reads only the rows that `DailyLog.load` gave; sends no console command.
struct DailyChartView: View {
    let folder: DailyFolder
    var temperatureUnit = "°C"

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

    private func chart(_ title: String, _ data: [Point]) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            if data.isEmpty {
                Text("No data").font(.caption2).foregroundStyle(.secondary)
            } else {
                Chart(data) {
                    LineMark(x: .value("Day", $0.date, unit: .day), y: .value(title, $0.value))
                        .foregroundStyle(by: .value("Series", $0.series))
                    PointMark(x: .value("Day", $0.date, unit: .day), y: .value(title, $0.value))
                        .foregroundStyle(by: .value("Series", $0.series))
                }
                .chartYScale(domain: .automatic(includesZero: false))
                .frame(height: 110)
            }
        }
    }

    var body: some View {
        if folder.rows.isEmpty {
            Text("No daily summary rows in the folder").font(.caption).foregroundStyle(.secondary)
        } else {
            VStack(alignment: .leading, spacing: 8) {
                ForEach(0..<DailyLog.sensorNames.count, id: \.self) { sensor in
                    let temp = points(sensor) { [("Min", $0.tempMinC), ("Mean", $0.tempMeanC), ("Max", $0.tempMaxC)] }
                    let rh = points(sensor) { [("Min", $0.rhMin), ("Mean", $0.rhMean), ("Max", $0.rhMax)] }
                    if !temp.isEmpty || !rh.isEmpty { // slots 4 and 5 exist only in daily_v3 rows
                        Text(DailyLog.sensorNames[sensor]).font(.headline)
                        HStack(alignment: .top) {
                            chart("Temperature (\(temperatureUnit))", temp)
                            chart("Humidity (%RH)", rh)
                        }
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
