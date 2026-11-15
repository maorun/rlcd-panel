import Foundation
#if canImport(ServiceManagement)
import ServiceManagement
#endif

/// State of the login item of the app.
public enum LoginItemStatus: Equatable, Sendable {
    case enabled
    case disabled
    /// The user must approve the item in System Settings, Login Items.
    case requiresApproval
    /// No app bundle (`swift run`) or the service does not know the app.
    case notAvailable

    /// The toggle shows on for an enabled item and for an item that waits for approval.
    public var isOn: Bool { self == .enabled || self == .requiresApproval }
}

/// Access to the login item registration. The production type wraps `SMAppService.mainApp`; tests use a fake.
public protocol LoginItemService {
    var status: LoginItemStatus { get }
    func register() throws
    func unregister() throws
    func openSystemSettings()
}

/// State logic of the `Open at login` toggle. The state comes from the service on each read and is never copied.
public struct LoginItem {
    public let service: LoginItemService
    /// Text of the last failed registration, `nil` after a success.
    public private(set) var errorText: String?

    public init(service: LoginItemService) {
        self.service = service
    }

    public var status: LoginItemStatus { service.status }
    public var isOn: Bool { status.isOn }
    public var isAvailable: Bool { status != .notAvailable }
    public var needsApproval: Bool { status == .requiresApproval }

    /// Calls `register()` for on and `unregister()` for off. A failure sets `errorText`; the service keeps the old state.
    public mutating func setEnabled(_ on: Bool) {
        guard isAvailable else { return }
        do {
            if on {
                try service.register()
            } else {
                try service.unregister()
            }
            errorText = nil
        } catch {
            errorText = "Open at login failed: \(error.localizedDescription)"
        }
    }

    /// Drops the text of an old failure, for example when the window opens again.
    public mutating func clearError() {
        errorText = nil
    }

    public func openSystemSettings() {
        service.openSystemSettings()
    }
}

#if canImport(ServiceManagement)
/// Production service on `SMAppService.mainApp`. Without an app bundle it reports `notAvailable`.
public struct MainAppLoginItemService: LoginItemService {
    public init() {}

    public var status: LoginItemStatus {
        Self.map(SMAppService.mainApp.status, hasBundle: Bundle.main.bundleIdentifier != nil)
    }

    /// Pure mapping of the service status; a missing app bundle gives `notAvailable`.
    static func map(_ status: SMAppService.Status, hasBundle: Bool) -> LoginItemStatus {
        guard hasBundle else { return .notAvailable }
        switch status {
        case .enabled: return .enabled
        case .requiresApproval: return .requiresApproval
        case .notRegistered: return .disabled
        case .notFound: return .notAvailable
        @unknown default: return .notAvailable
        }
    }

    public func register() throws { try SMAppService.mainApp.register() }
    public func unregister() throws { try SMAppService.mainApp.unregister() }
    public func openSystemSettings() { SMAppService.openSystemSettingsLoginItems() }
}
#endif
