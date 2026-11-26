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
                // One tap sends ALERTS; the menu never polls the panel.
                Button {
                    model.readAlerts()
                } label: {
                    Label("Alert: " + summary.alertNames.joined(separator: ", "), systemImage: MenuBarSummary.alertSymbol)
                }
                .disabled(!model.isIdle)
                // Causes of an older alert set are not shown: the read must match the alerts of the last frame.
                if let alerts = model.alertsResult, let frame = model.frame, alerts.activeMask == frame.alerts.rawValue {
                    ForEach(alerts.causeLines(tileNames: AlertCauses.tileNames(config: model.lastConfig)), id: \.self) { line in
                        Text(line)
                    }
                } else if let error = model.alertsError {
                    Text(error)
                } else if !model.consoleConnected {
                    Text("Connect the console to read the causes")
                } else {
                    Text("Click to read the causes")
                }
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
            Text("Recent events")
            if model.recentEvents.lines.isEmpty {
                Text("No events read")
            }
            ForEach(Array(model.recentEvents.lines.enumerated()), id: \.offset) { _, line in
                Text(line)
            }
            if model.recentEvents.lost {
                Text("Events lost")
            }
            Button("Read events") { model.readRecentEvents() }
                .disabled(!model.isIdle)
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
