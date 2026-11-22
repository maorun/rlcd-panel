import AppKit
import RlcdProtocol
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

    private var summary: MenuBarSummary {
        MenuBarSummary.make(frame: model.frame, frameDate: model.frameDate, config: model.lastConfig, now: model.now)
    }

    var body: some Scene {
        WindowGroup("RLCD Panel", id: "main") {
            MainView(model: model)
        }
        MenuBarExtra {
            ForEach(Array(summary.lines.enumerated()), id: \.offset) { _, line in
                Text(line)
            }
            if summary.hasAlert {
                Label("Alert: " + summary.alertNames.joined(separator: ", "), systemImage: MenuBarSummary.alertSymbol)
            }
            if summary.isStale {
                Label("Stale", systemImage: "clock.badge.exclamationmark")
            }
            Text("Last frame: \(ageText(model.frameDate, now: model.now))")
            Button("Open window") {
                openWindow(id: "main")
                NSApplication.shared.activate(ignoringOtherApps: true)
            }
            Divider()
            if let text = model.diagnostics?.chime?.snoozeText {
                Text(text)
            }
            Button("Mute for 1 hour") { model.snooze(minutes: 60) }
                .disabled(!model.isIdle)
            Button("Mute for 8 hours") { model.snooze(minutes: 480) }
                .disabled(!model.isIdle)
            Button("End snooze") { model.snooze(minutes: 0) }
                .disabled(!model.isIdle)
            Divider()
            Button("Quit") { NSApplication.shared.terminate(nil) }
        } label: {
            Label(summary.labelWithMark, systemImage: summary.symbolName)
        }
    }
}
