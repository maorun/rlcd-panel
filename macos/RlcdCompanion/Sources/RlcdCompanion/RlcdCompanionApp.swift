import AppKit
import SwiftUI

final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApplication.shared.setActivationPolicy(.regular)
        NSApplication.shared.activate(ignoringOtherApps: true)
    }
}

@main
struct RlcdCompanionApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    private let model = AppModel()
    @Environment(\.openWindow) private var openWindow

    var body: some Scene {
        WindowGroup("RLCD Panel", id: "main") {
            MainView(model: model)
        }
        MenuBarExtra {
            Text("Onboard: \(model.menuBarText)")
            Text("Last frame: \(ageText(model.frameDate))")
            Button("Open window") {
                openWindow(id: "main")
                NSApplication.shared.activate(ignoringOtherApps: true)
            }
            Divider()
            Button("Quit") { NSApplication.shared.terminate(nil) }
        } label: {
            Label(model.menuBarText, systemImage: "thermometer.medium")
        }
    }
}
