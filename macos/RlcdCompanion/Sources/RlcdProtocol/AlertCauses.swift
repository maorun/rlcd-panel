import Foundation

/// Tile names for the alert causes: the config name of each tile, else the default title.
public enum AlertCauses {
    public static let defaultNames = ["Onboard", "Slot 1", "Slot 2", "Slot 3", "Slot 4", "Slot 5", "Slot 6", "Slot 7", "Slot 8", "Slot 9", "Slot 10"]

    /// Names of tiles 0 to 10 for `AlertsReply.causeText`.
    public static func tileNames(config: ConfigReply?) -> [String] {
        defaultNames.enumerated().map { i, fallback in config?.name(forSlot: i) ?? fallback }
    }
}
