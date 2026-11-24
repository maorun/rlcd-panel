import Foundation

/// Proposal for `outdoor_slot` from the `WEEK` reply: the slot with the widest daily temperature range that is
/// colder at night than the onboard sensor. A pure function of the reply and the active config.
public struct OutdoorProposal: Equatable, Sendable {
    /// A slot needs at least this many days with a temperature pair.
    public static let minDays = 3
    /// The best range must be at least this factor above the range of the next slot.
    public static let rangeFactor = 2.0

    public let slot: Int
    public let name: String
    /// Mean daily range (maximum minus minimum) in C.
    public let rangeC: Double
    /// Mean daily range of the next slot in C, 0 without another slot.
    public let nextRangeC: Double
    public let minC: Double
    public let onboardMinC: Double

    public var text: String {
        String(format: "Outdoor slot %d (%@): range %.1f C, next %.1f C, minimum %.1f C, onboard minimum %.1f C",
               slot, name, rangeC, nextRangeC, minC, onboardMinC)
    }

    struct Stats { let range: Double; let minimum: Double }

    static func stats(_ rows: [WeekRow], sensor: Int) -> Stats? {
        let pairs = rows.filter { $0.sensor == sensor }.compactMap { r -> (Double, Double)? in
            guard let lo = r.temperatureMinC, let hi = r.temperatureMaxC else { return nil }
            return (lo, hi)
        }
        guard pairs.count >= minDays else { return nil }
        let n = Double(pairs.count)
        return Stats(range: pairs.reduce(0) { $0 + ($1.1 - $1.0) } / n, minimum: pairs.reduce(0) { $0 + $1.0 } / n)
    }

    /// `nil` without a clear candidate or when `outdoor_slot` already holds the slot.
    public static func propose(week: [WeekRow], config: ConfigReply?) -> OutdoorProposal? {
        guard let onboard = stats(week, sensor: 0) else { return nil }
        let ranked = SlotAssignment.slots.compactMap { slot in stats(week, sensor: slot).map { (slot, $0) } }
            .sorted { $0.1.range > $1.1.range }
        guard let best = ranked.first, best.1.range > 0 else { return nil }
        let next = ranked.count > 1 ? ranked[1].1.range : 0
        guard best.1.range >= rangeFactor * next, best.1.minimum < onboard.minimum else { return nil }
        guard config?.values["outdoor_slot"] != String(best.0) else { return nil }
        return OutdoorProposal(slot: best.0, name: ClimateCompare.name(sensor: best.0, config: config), rangeC: best.1.range,
                               nextRangeC: next, minC: best.1.minimum, onboardMinC: onboard.minimum)
    }
}
