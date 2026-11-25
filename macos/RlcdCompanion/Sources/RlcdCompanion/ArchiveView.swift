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
                    Button("Show daily charts") { model.dailySource = .card; model.dailyFolder = model.archiveFolder.map(DailyLog.load(from:)) }
                        .disabled(model.archiveFolder == nil)
                    Button("Show climate charts") { model.showClimateCharts() }
                        .disabled(model.archiveFolder == nil)
                    Button("Show alert statistics") { model.alertStats = model.archiveFolder.map { AlertStats.load(from: $0) } }
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
                DailyChartView(model: model, limits: ChartLimits.from(config: model.lastConfig))
                if let stats = model.alertStats { AlertStatsView(result: stats) }
                if let climate = model.climateData {
                    if !model.climateMonth.isEmpty {
                        LogGapSummary(
                            report: LogGaps.report(rows: climate.rows), month: model.climateMonth,
                            failedFiles: climate.failedFiles)
                    }
                    ClimateChartView(model: model)
                }
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
            model.alertStats = nil
            model.hideClimateCharts()
            model.selectNewArchiveFiles() // the plan depends on the local copies in the folder
        }
    }
}

/// Missing hours of the selected climate month. Reads only the rows of the downloaded files.
private struct LogGapSummary: View {
    let report: LogGaps.Report
    let month: String
    let failedFiles: [String]

    private static let formatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = PanelClock.timeZone
        f.dateFormat = "yyyy-MM-dd HH:mm"
        return f
    }()

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("Log coverage \(month): \(String(format: "%.1f", report.coveragePct)) % (\(report.rowCount) rows, \(report.missingHours) missing h)")
                .font(.caption)
            if !failedFiles.isEmpty {
                Text("Unreadable file(s), coverage is too low: \(failedFiles.joined(separator: ", "))")
                    .font(.caption).foregroundStyle(.orange)
            }
            if report.gaps.isEmpty {
                Text(report.rowCount == 0 ? "No rows in this month." : "No gaps above 90 minutes.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 2) {
                    ForEach(Array(report.gaps.enumerated()), id: \.offset) { _, gap in
                        Text("\(Self.formatter.string(from: gap.start)) to \(Self.formatter.string(from: gap.end)): \(gap.missingHours) h missing")
                            .font(.system(.caption, design: .monospaced)).foregroundStyle(.secondary)
                    }
                }.frame(maxWidth: .infinity, alignment: .leading)
            }.frame(maxHeight: 120)
        }
    }
}
