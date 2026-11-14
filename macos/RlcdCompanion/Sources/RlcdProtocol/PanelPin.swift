import Foundation

/// Small key-value store for the pin. The app uses `UserDefaults`, the tests use `InMemoryPinStore`.
public protocol PinStore: AnyObject {
    func string(forKey key: String) -> String?
    func set(_ value: String?, forKey key: String)
}

/// Store that keeps the values in memory only.
public final class InMemoryPinStore: PinStore {
    private var values: [String: String] = [:]
    public init() {}
    public func string(forKey key: String) -> String? { values[key] }
    public func set(_ value: String?, forKey key: String) { values[key] = value }
}

public enum PanelPinError: Error, Equatable {
    case invalidUUID(String)
}

/// The pinned panel: the `CBPeripheral.identifier` UUID of one panel.
/// Without a pin, `accepts` lets each peripheral pass.
public struct PanelPin {
    public static let storeKey = "pinnedPanelUUID"
    private let store: PinStore

    public init(store: PinStore) { self.store = store }

    /// The pinned UUID. A stored text that is not a UUID counts as no pin.
    public var pinned: UUID? { store.string(forKey: Self.storeKey).flatMap { UUID(uuidString: $0) } }

    public static func parse(_ text: String) throws -> UUID {
        guard let id = UUID(uuidString: text.trimmingCharacters(in: .whitespacesAndNewlines)) else {
            throw PanelPinError.invalidUUID(text)
        }
        return id
    }

    public func pin(_ id: UUID) { store.set(id.uuidString, forKey: Self.storeKey) }

    public func pin(text: String) throws { pin(try Self.parse(text)) }

    public func clear() { store.set(nil, forKey: Self.storeKey) }

    /// True when a packet of this peripheral may pass: no pin, or the pinned UUID.
    public func accepts(_ id: UUID) -> Bool { pinned.map { $0 == id } ?? true }
}

/// One peripheral that sent a valid telemetry frame or the console service.
public struct DiscoveredPanel: Equatable, Identifiable {
    public let id: UUID
    public var name: String?
    public var rssi: Int
    public var lastPacket: Date

    public func age(at now: Date) -> TimeInterval { max(0, now.timeIntervalSince(lastPacket)) }
}

/// The list of discovered peripherals. The strongest signal comes first.
public struct DiscoveryList: Equatable {
    /// A foreign sender can invent many UUIDs. The list keeps the strongest ones.
    public static let maxPanels = 16
    public static let maxNameLength = 32
    public private(set) var panels: [DiscoveredPanel] = []
    public init() {}

    /// Removes control characters and cuts the name. An advertised name is text of a foreign sender.
    public static func sanitizedName(_ name: String?) -> String? {
        guard let name else { return nil }
        let clean = String(String.UnicodeScalarView(name.unicodeScalars.filter { !$0.properties.generalCategory.isControlLike }))
            .trimmingCharacters(in: .whitespaces)
        return clean.isEmpty ? nil : String(clean.prefix(maxNameLength))
    }
    /// Records one packet. An empty name keeps the last known name.
    public mutating func record(id: UUID, name: String?, rssi: Int, at date: Date) {
        let known = Self.sanitizedName(name)
        if let i = panels.firstIndex(where: { $0.id == id }) {
            panels[i].rssi = rssi
            panels[i].lastPacket = date
            if let known { panels[i].name = known }
        } else {
            panels.append(DiscoveredPanel(id: id, name: known, rssi: rssi, lastPacket: date))
        }
        panels.sort { $0.rssi != $1.rssi ? $0.rssi > $1.rssi : $0.id.uuidString < $1.id.uuidString }
        if panels.count > Self.maxPanels { panels.removeLast(panels.count - Self.maxPanels) }
    }

    /// Removes each panel that sent no packet for `seconds`.
    public mutating func prune(olderThan seconds: TimeInterval, at now: Date) {
        panels.removeAll { $0.age(at: now) > seconds }
    }

    /// Removes each panel except the pinned one.
    public mutating func keepOnly(_ id: UUID) { panels.removeAll { $0.id != id } }
}

private extension Unicode.GeneralCategory {
    var isControlLike: Bool { self == .control || self == .format || self == .lineSeparator || self == .paragraphSeparator }
}
