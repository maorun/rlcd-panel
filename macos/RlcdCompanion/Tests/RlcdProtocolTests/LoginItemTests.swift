import Foundation
import ServiceManagement
import Testing
@testable import RlcdProtocol

private struct FakeFailure: LocalizedError {
    var errorDescription: String? { "denied" }
}

private final class FakeLoginItemService: LoginItemService {
    var status: LoginItemStatus
    var failure: Error?
    var registerCalls = 0
    var unregisterCalls = 0
    var settingsCalls = 0

    init(status: LoginItemStatus) { self.status = status }

    func register() throws {
        registerCalls += 1
        if let failure { throw failure }
        status = .enabled
    }

    func unregister() throws {
        unregisterCalls += 1
        if let failure { throw failure }
        status = .disabled
    }

    func openSystemSettings() { settingsCalls += 1 }
}

struct LoginItemTests {
    @Test func toggleOnRegistersOnce() {
        let service = FakeLoginItemService(status: .disabled)
        var item = LoginItem(service: service)
        #expect(!item.isOn)
        item.setEnabled(true)
        #expect(service.registerCalls == 1)
        #expect(service.unregisterCalls == 0)
        #expect(item.status == .enabled)
        #expect(item.isOn)
        #expect(item.errorText == nil)
    }

    @Test func toggleOffUnregisters() {
        let service = FakeLoginItemService(status: .enabled)
        var item = LoginItem(service: service)
        item.setEnabled(false)
        #expect(service.unregisterCalls == 1)
        #expect(service.registerCalls == 0)
        #expect(item.status == .disabled)
        #expect(!item.isOn)
    }

    @Test func failedRegistrationSetsErrorAndKeepsState() {
        let service = FakeLoginItemService(status: .disabled)
        service.failure = FakeFailure()
        var item = LoginItem(service: service)
        item.setEnabled(true)
        #expect(item.errorText?.contains("denied") == true)
        #expect(item.status == .disabled)
        service.failure = nil
        item.setEnabled(true)
        #expect(item.errorText == nil)
    }

    @Test func failedUnregisterKeepsEnabled() {
        let service = FakeLoginItemService(status: .enabled)
        service.failure = FakeFailure()
        var item = LoginItem(service: service)
        item.setEnabled(false)
        #expect(item.errorText != nil)
        #expect(item.status == .enabled)
    }

    @Test func requiresApprovalGivesApprovalState() {
        let service = FakeLoginItemService(status: .requiresApproval)
        let item = LoginItem(service: service)
        #expect(item.needsApproval)
        #expect(item.isOn)
        item.openSystemSettings()
        #expect(service.settingsCalls == 1)
    }

    @Test func notAvailableIgnoresToggle() {
        let service = FakeLoginItemService(status: .notAvailable)
        var item = LoginItem(service: service)
        #expect(!item.isAvailable)
        item.setEnabled(true)
        #expect(service.registerCalls == 0)
        #expect(item.errorText == nil)
    }

    @Test func statusReadsFromServiceEachTime() {
        let service = FakeLoginItemService(status: .disabled)
        let item = LoginItem(service: service)
        #expect(item.status == .disabled)
        service.status = .enabled
        #expect(item.status == .enabled)
    }
}

struct LoginItemStatusTests {
    @Test func onlyEnabledAndApprovalShowOn() {
        #expect(LoginItemStatus.enabled.isOn)
        #expect(LoginItemStatus.requiresApproval.isOn)
        #expect(!LoginItemStatus.disabled.isOn)
        #expect(!LoginItemStatus.notAvailable.isOn)
    }
}

struct MainAppLoginItemServiceTests {
    @Test func mapsEachServiceStatus() {
        #expect(MainAppLoginItemService.map(.enabled, hasBundle: true) == .enabled)
        #expect(MainAppLoginItemService.map(.requiresApproval, hasBundle: true) == .requiresApproval)
        #expect(MainAppLoginItemService.map(.notRegistered, hasBundle: true) == .disabled)
        #expect(MainAppLoginItemService.map(.notFound, hasBundle: true) == .notAvailable)
    }

    @Test func missingBundleGivesNotAvailable() {
        #expect(MainAppLoginItemService.map(.enabled, hasBundle: false) == .notAvailable)
    }
}

struct LoginItemErrorTests {
    @Test func clearErrorDropsOldText() {
        let service = FakeLoginItemService(status: .disabled)
        service.failure = FakeFailure()
        var item = LoginItem(service: service)
        item.setEnabled(true)
        #expect(item.errorText != nil)
        item.clearError()
        #expect(item.errorText == nil)
    }
}
