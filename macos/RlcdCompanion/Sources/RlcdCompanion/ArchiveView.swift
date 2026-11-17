import AppKit
import RlcdProtocol
import SwiftUI
import UniformTypeIdentifiers

/// Lists the log files of the MicroSD card and downloads the selected ones into a folder. Needs the USB serial link.
struct ArchiveView: View {
    @Bindable var model: AppModel
    /// An events file holds a few rows for each day, so 4 MiB is far above any real file.
    private static let maxEventsFileBytes = 4_194_304

    var body: some View {
        GroupBox("SD card log archive") {
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Button("List card files") { model.listArchive() }
                        .disabled(!model.canArchive)
                    Button("Choose folder…") { chooseFolder() }
                    Button("Show daily charts") { model.dailyFolder = model.archiveFolder.map(DailyLog.load(from:)) }
                        .disabled(model.archiveFolder == nil)
                    Button("Show climate charts") { model.showClimateCharts() }
                        .disabled(model.archiveFolder == nil)
                    Button("Open events file") { openEventsFile() }
                    Button("Select new files") { model.selectNewArchiveFiles() }
                        .disabled(model.archiveFolder == nil || model.archiveFiles.isEmpty)
                    Button("Download selected") { model.downloadSelected() }
                        .disabled(!model.canArchive || model.archiveFolder == nil || model.archiveSelection.isEmpty)
                    if model.transport != .usb {
                        Text("LIST and DUMP need the USB serial link.").font(.caption).foregroundStyle(.secondary)
                    }
                }
                Text(model.archiveFolder?.path ?? "No folder chosen")
                    .font(.caption).foregroundStyle(.secondary).lineLimit(1).truncationMode(.middle)
                ForEach(model.archiveFiles) { file in
                    Toggle(isOn: Binding(
                        get: { model.archiveSelection.contains(file.name) },
                        set: { on in
                            if on { model.archiveSelection.insert(file.name) } else { model.archiveSelection.remove(file.name) }
                        })) {
                        HStack {
                            Text(file.name).font(.system(.caption, design: .monospaced))
                            Spacer()
                            Text("\(file.bytes) B").foregroundStyle(.secondary)
                        }
                    }
                }
                if let summary = model.archiveSummary { Text(summary).font(.caption).foregroundStyle(.secondary) }
                if let daily = model.dailyFolder { DailyChartView(folder: daily, limits: ChartLimits.from(config: model.lastConfig)) }
                if model.climateData != nil { ClimateChartView(model: model) }
            }.frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func openEventsFile() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.directoryURL = model.archiveFolder
        panel.allowedContentTypes = [.commaSeparatedText]
        panel.prompt = "Open"
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do {
            let size = (try FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.intValue
            guard let size, size <= Self.maxEventsFileBytes else {
                model.archiveSummary = "\(url.lastPathComponent) is too large or has no known size"
                return
            }
            model.events = try EventsReply.parse(file: String(contentsOf: url, encoding: .utf8))
            model.archiveSummary = "Opened \(url.lastPathComponent): \(model.events.count) event(s)"
        } catch {
            model.archiveSummary = "Cannot open \(url.lastPathComponent): \(error)"
        }
    }

    private func chooseFolder() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = false
        panel.canChooseDirectories = true
        panel.canCreateDirectories = true
        panel.prompt = "Choose"
        if panel.runModal() == .OK {
            model.archiveFolder = panel.url
            model.dailyFolder = nil
            model.hideClimateCharts()
            model.selectNewArchiveFiles() // the plan depends on the local copies in the folder
        }
    }
}
