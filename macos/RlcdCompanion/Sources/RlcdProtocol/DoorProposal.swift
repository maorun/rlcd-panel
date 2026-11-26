import Foundation

/// Proposal for `door_ref_slot` from the `WEEK` reply: the slot with the lowest mean daily humidity maximum,
/// at least 3 %RH drier than the next slot and drier than slot 1 (the door room). A pure function of the reply
/// and the active config. The outdoor slot is no candidate.
public struct DoorProposal: Equatable, Sendable {
    /// Slot 1 and each candidate need at least this many days with a humidity maximum.
    public static let minDays = 3
    /// The best mean maximum must be at least this far (%RH) below the mean of the next slot.
    public static let minGapPct = 3.0

    public let slot: Int
    public let name: String
    /// Mean daily humidity maximum in %RH.
    public let meanMaxPct: Double
    /// Mean daily humidity maximum of the next slot in %RH.
    public let nextMaxPct: Double

    public var text: String {
        String(format: "Door reference slot %d (%@): %.1f %%RH, next %.1f %%RH", slot, name, meanMaxPct, nextMaxPct)
    }

    static func meanMax(_ rows: [WeekRow], sensor: Int) -> Double? {
        let values = rows.filter { $0.sensor == sensor }.compactMap(\.humidityMaxPct)
        guard values.count >= minDays else { return nil }
        return values.reduce(0, +) / Double(values.count)
    }

    /// `slotN_name` (also without a fixed MAC, like the phone), else the default sensor name.
    static func name(slot: Int, config: ConfigReply?) -> String {
        if let n = config?.values["slot\(slot)_name"], !n.isEmpty { return n }
        return ClimateCompare.name(sensor: slot, config: config)
    }

    /// `nil` without a clear candidate or when `door_ref_slot` already holds the slot.
    public static func propose(week: [WeekRow], config: ConfigReply?) -> DoorProposal? {
        guard let door = meanMax(week, sensor: 1) else { return nil }
        let outdoor = Int(config?.values["outdoor_slot"]?.trimmingCharacters(in: .whitespaces) ?? "") ?? 0
        let ranked = SlotAssignment.slots.filter { $0 != 1 && $0 != outdoor }
            .compactMap { slot in meanMax(week, sensor: slot).map { (slot, $0) } }
            .sorted { $0.1 < $1.1 }
        guard ranked.count >= 2, let best = ranked.first else { return nil }
        let next = ranked[1].1
        guard next - best.1 >= minGapPct, best.1 < door else { return nil }
        guard config?.values["door_ref_slot"]?.trimmingCharacters(in: .whitespaces) != String(best.0) else { return nil }
        return DoorProposal(slot: best.0, name: name(slot: best.0, config: config),
                            meanMaxPct: best.1, nextMaxPct: next)
    }
}
