import SwiftUI

@main
struct RlcdCompanionApp: App {
    private let model = AppModel()
    @Environment(\.openWindow) private var openWindow

    var body: some Scene {
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
        Window("RLCD Panel", id: "main") {
            MainView(model: model)
        }
    }
}
