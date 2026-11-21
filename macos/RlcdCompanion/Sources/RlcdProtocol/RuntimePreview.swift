import Foundation

/// Battery runtime for an edited `wake_interval_s` and `night_wake_interval_s`.
///
/// Ratio model on top of `BatteryHistory::estimate`: the cell drains by the awake time of each wake cycle, so the
/// drain per hour is proportional to the wake cycles per hour, weighted by `interval + awake`. The `RUNTIME h` value
/// of the last `STATUS` was measured with the current schedule; the preview scales it with the ratio of the drain
/// rates. The sleep current and `scan_saved_ms` are not modelled, so the value is an estimate.
public enum RuntimePreview {
    /// Range of the `config.ini` keys (`ConfigSchema`).
    public static let wakeRange = 30...900
    public static let nightRange = 60...1800
    /// `BatteryHistory::AWAKE_MS`, `MIN_AWAKE_MS`, `AwakeTimeStats::MIN_SAMPLES` of the firmware.
    static let defaultAwakeMs = 10000
    static let minAwakeMs = 2000
    static let minSamples = 8
    /// `BatteryHistory::estimate` caps the hours at 99 days and 12 hours; a capped value is no measurement.
    static let capHours = 99.0 * 24.0 + 12.0

    /// One wake schedule. A `nightS` of 0 uses `wakeS` also in the quiet hours.
    public struct Schedule: Equatable {
        public let wakeS: Int
        public let nightS: Int
        public let quietStartHour: Int
        public let quietEndHour: Int

        public init(wakeS: Int, nightS: Int, quietStartHour: Int, quietEndHour: Int) {
            self.wakeS = wakeS
            self.nightS = nightS
            self.quietStartHour = quietStartHour
            self.quietEndHour = quietEndHour
        }

        var valid: Bool {
            RuntimePreview.wakeRange.contains(wakeS) && (nightS == 0 || RuntimePreview.nightRange.contains(nightS))
                && (0...23).contains(quietStartHour) && (0...23).contains(quietEndHour)
        }
    }

    /// Hours of the quiet window in 24 hours (22 to 7 gives 9). An equal start and end give 0.
    public static func nightHours(startHour: Int, endHour: Int) -> Int {
        guard (0...23).contains(startHour), (0...23).contains(endHour), startHour != endHour else { return 0 }
        return (endHour - startHour + 24) % 24
    }

    /// Wake cycles per hour of the whole day, weighted by the cost of one cycle.
    static func drainRate(_ s: Schedule, awakeS: Double) -> Double? {
        guard s.valid else { return nil }
        let night = Double(nightHours(startHour: s.quietStartHour, endHour: s.quietEndHour))
        let nightInterval = s.nightS > 0 ? s.nightS : s.wakeS
        let day = 24.0 - night
        return (day / (Double(s.wakeS) + awakeS) + night / (Double(nightInterval) + awakeS)) / 24.0
    }

    /// Expected runtime in whole hours for `edited`. `nil` without an `AWAKE` mean or a `RUNTIME h` value, for
    /// a capped `RUNTIME h` and for an interval outside the key range. Like the firmware, a mean with fewer than 8
    /// samples (or no sample count) or at most 2000 ms is no measurement: the constant awake time of 10 s applies.
    public static func hours(runtimeHours: Double?, avgMs: Int?, samples: Int? = nil,
                             current: Schedule, edited: Schedule) -> Int? {
        guard let runtimeHours, runtimeHours >= 0, runtimeHours < capHours, let avgMs else { return nil }
        let measured = (samples ?? 0) >= minSamples && avgMs > minAwakeMs
        let awakeS = Double(measured ? avgMs : defaultAwakeMs) / 1000.0
        guard let now = drainRate(current, awakeS: awakeS), let next = drainRate(edited, awakeS: awakeS) else { return nil }
        let scaled = runtimeHours * now / next
        // Like the firmware, the value stops at 99 days and 12 hours; the guard keeps `Int` safe for a huge ratio.
        guard scaled.isFinite else { return nil }
        return Int(min(scaled, capHours).rounded())
    }

    /// Preview for the config form: the current schedule comes from the `CONFIG` values, the edited one from the
    /// draft over the values. A draft value that is no integer gives `nil`. The quiet window of the draft wins over
    /// the `QUIET` line, which holds the window of the panel.
    public static func hours(config: [String: String], draft: [String: String], runtime: RuntimeMetrics?,
                             awake: AwakeMetrics?, quiet: QuietMetrics?) -> Int? {
        func int(_ key: String, _ fallback: Int, edited: Bool) -> Int? {
            guard let text = (edited ? draft[key] : nil) ?? config[key] else { return fallback }
            return Int(text.trimmingCharacters(in: .whitespaces))
        }
        func schedule(edited: Bool) -> Schedule? {
            guard let wake = int("wake_interval_s", 60, edited: edited),
                  let night = int("night_wake_interval_s", 0, edited: edited),
                  let start = int("quiet_hours_start_hour", quiet?.startHour ?? 22, edited: edited),
                  let end = int("quiet_hours_end_hour", quiet?.endHour ?? 7, edited: edited) else { return nil }
            return Schedule(wakeS: wake, nightS: night, quietStartHour: start, quietEndHour: end)
        }
        guard let current = schedule(edited: false), let edited = schedule(edited: true) else { return nil }
        return hours(runtimeHours: runtime?.hours, avgMs: awake?.avgMs, samples: awake?.samples,
                     current: current, edited: edited)
    }

    /// `About <N> h on battery` or `--`.
    public static func text(_ hours: Int?) -> String {
        hours.map { "About \($0) h on battery" } ?? "--"
    }
}
