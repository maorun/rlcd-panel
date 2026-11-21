import Foundation

/// Lines of two to six sensors of one `ClimateLog` month for the `Compare rooms` chart. Pure data: no console command.
public enum ClimateCompare {
    public enum Metric: Equatable {
        case temperature, humidity
    }

    public static let minSensors = 2
    public static let maxSensors = 6

    /// One value of a series; `nil` is an empty field (a gap).
    public struct Sample: Equatable {
        public let timestamp: String
        public let value: Double?
    }

    public struct Series: Equatable {
        /// Index in `ClimateLog.sensorNames` (0 onboard, 1 to 10 slots).
        public let sensor: Int
        public let name: String
        /// One sample for each row, in row order.
        public let samples: [Sample]

        /// Runs of consecutive values; each empty field ends a run, so a line breaks there.
        public var segments: [[Sample]] {
            var out: [[Sample]] = []
            var run: [Sample] = []
            for s in samples {
                if s.value == nil {
                    if !run.isEmpty { out.append(run); run = [] }
                } else {
                    run.append(s)
                }
            }
            if !run.isEmpty { out.append(run) }
            return out
        }
    }

    public struct Chart: Equatable {
        /// One series for each selected sensor, in the order of the selection.
        public let series: [Series]
        /// Smallest to largest value over all series; `nil` when every field is empty. A flat range widens by 0.5.
        public let axisRange: ClosedRange<Double>?
    }

    /// Display name of a sensor: the name of the active config (`onboard_name`, `slotN_name` of the fixed MAC), else
    /// the default of `ClimateLog.sensorNames`.
    public static func name(sensor: Int, config: ConfigReply?) -> String {
        guard ClimateLog.sensorNames.indices.contains(sensor) else { return "" }
        return config?.name(forSlot: sensor) ?? ClimateLog.sensorNames[sensor]
    }

    /// `nil` for fewer than 2 or more than 6 sensors, a repeated sensor or an index outside `ClimateLog.sensorNames`.
    /// A row without the sensor (an older format) gives an empty field.
    public static func build(rows: [ClimateRow], sensors: [Int], metric: Metric, config: ConfigReply? = nil) -> Chart? {
        guard (minSensors...maxSensors).contains(sensors.count), Set(sensors).count == sensors.count,
              sensors.allSatisfy({ ClimateLog.sensorNames.indices.contains($0) }) else { return nil }
        let names = sensors.map { name(sensor: $0, config: config) }
        let series = sensors.enumerated().map { index, sensor in
            // A repeated config name would merge two colors in the chart, so the default name tells the lines apart.
            let unique = names.filter { $0 == names[index] }.count == 1
            return Series(sensor: sensor, name: unique ? names[index] : "\(names[index]) (\(ClimateLog.sensorNames[sensor]))", samples: rows.map { row in
                let reading = sensor < row.sensors.count ? row.sensors[sensor] : nil
                return Sample(timestamp: row.timestamp, value: metric == .temperature ? reading?.tempC : reading?.rh)
            })
        }
        let values = series.flatMap { $0.samples.compactMap(\.value) }
        guard let low = values.min(), let high = values.max() else { return Chart(series: series, axisRange: nil) }
        return Chart(series: series, axisRange: low == high ? (low - 0.5)...(high + 0.5) : low...high)
    }
}
