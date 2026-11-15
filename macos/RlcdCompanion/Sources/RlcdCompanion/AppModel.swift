import Foundation
import Observation
import RlcdProtocol
import UserNotifications
import AppKit
import UniformTypeIdentifiers

enum Transport: String, CaseIterable { case bluetooth = "Bluetooth", usb = "USB serial" }

/// State of the app. The model sends a console command only for a user action.
@Observable
@MainActor
final class AppModel: ConsoleLinkDelegate {
    var frame: TelemetryFrame?
    var frameDate: Date?
    var rssi: Int?
    var bluetoothState = "Starting"
    var consoleAvailable = false
    var consoleState = "Console not connected"
    var consoleConnected = false
    var transport: Transport = .bluetooth
    var status: StatusReply?
    var history: [HistoryRow] = []
    /// Rows for the 7-day charts, downsampled; refreshed when the log changes.
    var chartRows: [TelemetryLogRow] = []
    /// Local telemetry history (7 days), saved at most once a minute.
    private let logStore = TelemetryLogStore()
    var telemetryLog: TelemetryLog { logStore.log }
    var events: [EventRow] = []
    var config: ConfigReply?
    var diagnostics: DiagnosticsReply?
    var version: VersionReply?
    var scanResult: ScanReply?
    var calibration: CalibrationReply?
    var calibrationReference = 0
    /// Proposal keys that the user selected.
    var calibrationSelection: Set<String> = []
    var archiveFiles: [LogFile] = []
    var archiveSelection: Set<String> = []
    var archiveFolder: URL?
    var archiveSummary: String?
    /// Folder of the running download. A later folder choice does not split the files of one run.
    private var downloadFolder: URL?
    var panelTime: TimeReply?
    var panelOffsetSeconds: Int?
    var lastChimeResult: String?
    var lastPatternResult: String?
    var message: String?
    /// User notifications for active alerts.
    var notificationsEnabled = false
    /// Wall clock for the age of the last frame. A 30 s tick refreshes it, so the menu bar shows `--` for a silent panel.
    var now = Date()
    /// Peripherals that sent a valid frame or the console service, strongest signal first.
    var discovered = DiscoveryList()
    /// The pinned panel, `nil` without a pin.
    var pinnedPanel: UUID?
    /// Values that the user edited in the config form (key to new text). Keys without an edit are absent.
    var draft: [String: String] = [:]
    /// The running or last batch of the config form.
    var batch: ConfigBatch?
    var batchSummary: String?
    var showDiff = false
    var pendingPattern: Int?
    var showingPatternAlert = false
    private var downloadStats = (saved: 0, failed: 0)
    private var pendingCommand: ConsoleCommand?
    private var queue: [ConsoleCommand] = []
    private var assembler = ReplyAssembler()
    private var timeout: Task<Void, Never>?

    private let pin = PanelPin(store: UserDefaults.standard)
    let scanner = BluetoothScanner()
    private let serial = SerialLink()

    /// Level of `ble_console` from the last `CONFIG` reply (0 until read).
    var consoleLevel: Int { config?.bleConsoleLevel ?? 0 }
    var canSet: Bool { consoleConnected && (transport == .usb || consoleLevel == 2) }
    var canTimeSet: Bool { consoleConnected && PanelClock.isTimeSetAllowed(onUsb: transport == .usb, bleConsoleLevel: consoleLevel) }
    var canBeep: Bool { consoleConnected }
    var canPattern: Bool { consoleConnected }
    var isIdle: Bool { consoleConnected && pendingCommand == nil && queue.isEmpty && batch == nil }
    /// A frame older than this shows `--`. It exceeds the longest firmware wake interval (night, 1800 s), so a silent panel never looks current.
    static let staleSeconds: TimeInterval = 1900
    /// A discovered peripheral leaves the list after this time without a packet.
    static let discoveryKeepSeconds: TimeInterval = 120
    /// The menu bar text.
    var menuBarText: String {
        guard let date = frameDate, now.timeIntervalSince(date) < Self.staleSeconds else { return "-- °C" }
        return onboardTemperatureText
    }
    var onboardTemperatureText: String {
        if let t = frame?.tiles.first?.temperatureC { return String(format: "%.1f °C", t) }
        return "-- °C"
    }

    init() {
        Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(30))
                self?.now = Date()
                self?.discovered.prune(olderThan: Self.discoveryKeepSeconds, at: Date())
            }
        }
        pinnedPanel = pin.pinned
        scanner.pin = pin
        scanner.delegate = self
        scanner.onPeripheral = { [weak self] id, name, r in
            self?.discovered.record(id: id, name: name, rssi: r, at: Date())
        }
        serial.delegate = self
        chartRows = logStore.log.downsampled(maxRows: 400)
        NotificationCenter.default.addObserver(forName: NSApplication.willTerminateNotification, object: nil, queue: .main) { [logStore] _ in
            logStore.flush()
        }
        scanner.onBluetoothState = { [weak self] in self?.bluetoothState = $0 }
        scanner.onConsoleAvailable = { [weak self] in self?.consoleAvailable = $0 }
        scanner.onFrame = { [weak self] f, r in
            guard let self else { return }
            let prev = self.frame
            self.frame = f
            self.frameDate = Date()
            self.now = Date()
            self.rssi = r
            if self.logStore.record(f) { self.chartRows = self.logStore.log.downsampled(maxRows: 400) }
            self.checkAlertNotifications(previous: prev, current: f)
        }
    }

    /// Asks for a target file in a save panel and writes the CSV text there.
    func exportCsv(name: String, text: String) {
        let panel = NSSavePanel()
        panel.nameFieldStringValue = name
        panel.allowedContentTypes = [.commaSeparatedText]
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do {
            try text.write(to: url, atomically: true, encoding: .utf8)
            message = "Exported \(url.lastPathComponent)"
        } catch {
            message = "Export failed: \(error.localizedDescription)"
        }
    }

    /// Enables or disables macOS alert notifications.
    func toggleNotifications(_ enabled: Bool) {
        if enabled {
            guard Bundle.main.bundleIdentifier != nil else {
                notificationsEnabled = false
                message = "Notifications require running from RLCD Companion.app"
                return
            }
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { [weak self] granted, _ in
                Task { @MainActor in
                    self?.notificationsEnabled = granted
                }
            }
        } else {
            notificationsEnabled = false
        }
    }

    private func checkAlertNotifications(previous: TelemetryFrame?, current: TelemetryFrame) {
        guard notificationsEnabled, Bundle.main.bundleIdentifier != nil else { return }
        let rising = AlertTransitions.risingAlerts(previous: previous, current: current)
        guard rising.rawValue != 0 else { return }
        for name in rising.names {
            let content = UNMutableNotificationContent()
            content.title = "RLCD Panel Alert"
            content.body = "Alert raised: \(name)"
            content.sound = .default
            let req = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
            UNUserNotificationCenter.current().add(req)
        }
    }

    /// Count of hours above mold_alert_limit_rh in the last 24 hours for a given sensor (0..5).
    func moldHours(forSensor sensor: Int) -> Int? {
        guard let limitStr = config?.values["mold_alert_limit_rh"], let limit = Double(limitStr) else { return nil }
        let sensorRows = history.filter { $0.sensor == sensor }
        guard !sensorRows.isEmpty else { return nil }
        return sensorRows.filter { ($0.humidityPct ?? 0) > limit }.count
    }

    /// Starts the BLE console connection. The user presses the button; the scan alone never connects.
    func connectConsole() {
        if transport == .bluetooth { scanner.setConsoleWanted(true) } else if let e = serial.open() { consoleState = e }
    }

    /// Pins one panel. The app then shows only its frames and connects the console only to it.
    func pinPanel(_ id: UUID) {
        pin.pin(id)
        pinnedPanel = id
        if transport == .bluetooth { disconnectConsole() }
        discovered.keepOnly(id)
        clearPanelData() // the data may come from another panel
    }

    /// "Forget panel": clears the pin. The app then behaves as without a pin.
    func forgetPanel() {
        pin.clear()
        pinnedPanel = nil
        if transport == .bluetooth { disconnectConsole() }
        clearPanelData()
    }

    private func clearPanelData() {
        consoleAvailable = false
        frame = nil
        frameDate = nil
        rssi = nil
        status = nil
        history = []
        events = []
        config = nil
        diagnostics = nil
        version = nil
        scanResult = nil
        calibration = nil
        clearArchive()
        panelTime = nil
        panelOffsetSeconds = nil
        lastChimeResult = nil
        lastPatternResult = nil
    }

    private func clearArchive() {
        archiveFiles = []
        archiveSelection = []
        archiveSummary = nil
    }

    func disconnectConsole() {
        scanResult = nil
        calibration = nil
        clearArchive()
        panelTime = nil
        panelOffsetSeconds = nil
        lastChimeResult = nil
        lastPatternResult = nil
        resetLink()
        consoleConnected = false
        consoleState = "Console not connected"
    }

    func selectTransport(_ t: Transport) {
        scanResult = nil
        calibration = nil
        clearArchive()
        transport = t
        resetLink()
        consoleConnected = false
        consoleState = "Console not connected"
    }

    private func resetLink() {
        scanner.setConsoleWanted(false)
        serial.close()
        config = nil // the ble_console level of the old link must not gate SET on the new link
        draft = [:] // edits belong to the panel that was read
        assembler.reset()
        abandonCommands()
    }

    /// Drops the running command, the queue, the half-received reply and the timeout of the old link.
    private func abandonCommands() {
        if transport == .bluetooth { scanner.commandDidFinish() }
        timeout?.cancel()
        timeout = nil
        if case .dump = pendingCommand { downloadFolder = nil; archiveSummary = "Download stopped, saved \(downloadStats.saved), failed \(downloadStats.failed)" }
        pendingCommand = nil
        queue.removeAll()
        assembler.reset()
        endBatchAfterLoss()
    }

    /// A lost link, a timeout or a reset ends a running batch, so the form unlocks.
    private func endBatchAfterLoss() {
        guard let b = batch else { return }
        batchSummary = b.summary + " (no reply)"
        batch = nil
        clearCalibration()
    }

    /// Sends the four read commands one after the other. Runs only on the user's button press.
    func refresh() {
        guard consoleConnected, pendingCommand == nil, queue.isEmpty else { return }
        message = nil
        queue = [.status, .history, .events, .config]
        sendNext()
    }

    /// Sends `STATUS`, `VERSION` and `TIME` for the diagnostics panel. Runs only on the user's button press.
    func refreshDiagnostics() {
        guard consoleConnected, pendingCommand == nil, queue.isEmpty, batch == nil else { return }
        message = nil
        diagnostics = nil // a failed reply must not leave values of an older reply
        version = nil
        panelTime = nil
        panelOffsetSeconds = nil
        queue = diagnosticsRefreshCommands + [.time]
        sendNext()
    }

    /// Synchronizes the panel clock with Mac time by sending `TIME SET <date>` followed by `TIME`.
    func syncClock() {
        guard canTimeSet else { message = "TIME SET needs ble_console=2 or the USB serial link"; return }
        guard isIdle else { message = "Wait for the running command"; return }
        message = nil
        queue = [.timeSet(Date()), .time]
        sendNext()
    }

    /// Reads the panel clock.
    func readTime() {
        guard isIdle else { return }
        message = nil
        queue = [.time]
        sendNext()
    }

    /// Plays a test chime (alert bit 0..7).
    func beep(_ bit: Int) {
        guard isIdle else { return }
        message = nil
        lastChimeResult = nil
        queue = [.beep(bit)]
        sendNext()
    }

    /// Displays a test pattern (0..2) on the panel.
    func pattern(_ n: Int) {
        guard isIdle else { return }
        message = nil
        lastPatternResult = nil
        queue = [.pattern(n)]
        sendNext()
    }

    /// Chime alert name.
    static func chimeName(_ bit: Int) -> String {
        switch bit {
        case 0: return "Freezer / frost"
        case 1: return "Humidity / mold"
        case 2: return "Noise level"
        case 3: return "Sensor lost"
        case 4: return "Charge complete"
        case 5: return "Ventilation"
        case 6: return "Sensor battery"
        case 7: return "Heat index"
        default: return "Alert \(bit)"
        }
    }

    /// Changes of the form against the last `CONFIG` reply, in schema order.
    var changes: [ConfigChange] {
        guard let config else { return [] }
        let merged = config.values.merging(draft) { _, new in new }
        return ConfigSchema.diff(old: config.values, new: merged)
    }

    /// Problem text by key for the changed values.
    var problems: [String: String] { ConfigSchema.problems(changes: changes, allValues: config?.values ?? [:]) }

    /// Warning when the batch lowers `ble_console` on the BLE link: later commands may get no reply.
    var bleConsoleWarning: String? {
        guard transport == .bluetooth,
              let c = changes.first(where: { $0.key == "ble_console" }), (Int(c.new) ?? 0) < 2 else { return nil }
        return "ble_console below 2 over BLE stops SET. Apply it alone, or use the USB serial link."
    }

    /// Apply is allowed with changes, no problem, an idle link and a console that takes `SET`.
    var canApply: Bool {
        canSet && !changes.isEmpty && problems.isEmpty
            && pendingCommand == nil && queue.isEmpty && batch == nil
    }

    /// Sends one `SET` for each changed key, stops at the first `ERR` reply and reads `CONFIG` at the end.
    func applyChanges() {
        guard canApply else { message = "Apply not allowed"; return }
        message = nil
        batchSummary = nil
        if changes.contains(where: { SlotAssignment.isMacKey($0.key) }) { scanResult = nil } // slot column goes stale
        batch = ConfigBatch(changes: changes)
        sendNextBatchCommand()
    }

    private func sendNextBatchCommand() {
        guard let cmd = batch?.next() else { return }
        queue = [cmd]
        sendNext()
    }

    /// Starts one 4 s BLE scan of the panel. USB serial only: the firmware replies `ERR NOT_OVER_BLE` on BLE.
    func scan() {
        guard SlotAssignment.scanAllowed(onUsb: transport == .usb) else { message = "SCAN needs the USB serial link"; return }
        guard consoleConnected, pendingCommand == nil, queue.isEmpty, batch == nil else { message = "Wait for the running command"; return }
        message = nil
        queue = [.scan]
        sendNext()
    }

    /// Calibration changes of the selected proposals against the last `CONFIG` reply.
    var calibrationChanges: [ConfigChange] {
        calibration?.changes(selecting: calibrationSelection, current: config?.values) ?? []
    }

    private func clearCalibration() {
        calibration = nil
        calibrationSelection = []
    }

    /// Reads the histories comparison of `CALIB N`. Runs only on the user's button press; the firmware reads the RTC copy.
    func calibrate(reference: Int) {
        guard consoleConnected, pendingCommand == nil, queue.isEmpty, batch == nil else { message = "Wait for the running command"; return }
        guard ConsoleCommand.calib(reference).line != nil else { message = "Reference must be 0 to 3"; return }
        message = nil
        batchSummary = nil // an old batch result must not look like the result of this run
        calibration = nil
        calibrationSelection = []
        queue = [.calib(reference)]
        sendNext()
    }

    /// Sends the selected proposals through `ConfigBatch` (`SET` commands, then `CONFIG`).
    func applyCalibration() {
        let changes = calibrationChanges
        guard !changes.isEmpty else { message = "No proposal selected"; return }
        guard consoleConnected, pendingCommand == nil, queue.isEmpty, batch == nil else { message = "Wait for the running command"; return }
        // No canSet gate: the firmware answers ERR READ_ONLY on BLE with ble_console 1, and the batch summary shows it.
        message = nil
        batchSummary = nil
        batch = ConfigBatch(changes: changes)
        sendNextBatchCommand()
    }

    /// `LIST` and `DUMP` run only on the USB serial link; the firmware replies `ERR NOT_OVER_BLE` on BLE.
    var canArchive: Bool { consoleConnected && transport == .usb }

    /// Reads the file list of the MicroSD card.
    func listArchive() {
        guard canArchive else { message = "LIST needs the USB serial link"; return }
        guard pendingCommand == nil, queue.isEmpty, batch == nil else { message = "Wait for the running command"; return }
        message = nil
        archiveSummary = nil
        archiveFiles = []
        archiveSelection = []
        queue = [.list]
        sendNext()
    }

    /// Selects the files that a folder lacks or holds with another size, and the file of the current month.
    func selectNewArchiveFiles(now: Date = Date()) {
        guard let folder = archiveFolder else { return }
        let c = Calendar.current.dateComponents([.year, .month], from: now)
        let plan = LogArchive.plan(remote: archiveFiles, localSizes: LogArchive.localSizes(in: folder),
                                   year: c.year ?? 0, month: c.month ?? 0)
        archiveSelection = Set(plan.map(\.name))
    }

    /// Downloads the selected files one after the other into the folder. Each file is stored only after a correct CRC-32.
    func downloadSelected() {
        guard canArchive else { message = "DUMP needs the USB serial link"; return }
        guard let folder = archiveFolder else { message = "Choose a folder first"; return }
        guard pendingCommand == nil, queue.isEmpty, batch == nil else { message = "Wait for the running command"; return }
        let selected = archiveFiles.filter { archiveSelection.contains($0.name) }
        let names = selected.filter(LogArchive.isDownloadable).map(\.name)
        guard !names.isEmpty else { message = selected.isEmpty ? "No file selected" : "Selected files are too large"; return }
        message = selected.count > names.count ? "Skipped \(selected.count - names.count) file(s) above 16 MiB" : nil
        downloadStats = (0, 0)
        downloadFolder = folder
        archiveSummary = "Downloading \(names.count) file(s)"
        queue = names.map { .dump($0) }
        sendNext()
    }

    private func finishDownload(_ cmd: ConsoleCommand, text: String?) {
        guard case let .dump(name) = cmd else { return }
        if let text { downloadStats.failed += 1; message = "\(name): \(text)" }
        if queue.isEmpty {
            downloadFolder = nil
            archiveSummary = "Saved \(downloadStats.saved), failed \(downloadStats.failed)"
        }
    }

    /// Sets (`mac`) or clears (`nil`) the fixed MAC of one slot, then reads `CONFIG` and scans again.
    func assign(slot: Int, mac: String?) {
        guard canSet else { message = "SET needs ble_console=2 or the USB serial link"; return }
        guard pendingCommand == nil, queue.isEmpty, batch == nil else { message = "Wait for the running command"; return }
        guard let cmd = SlotAssignment.command(slot: slot, mac: mac), cmd.line != nil else {
            message = "Slot or MAC not valid"
            return
        }
        if let mac, let warning = SlotAssignment.warning(slot: slot, mac: mac, config: config) {
            message = warning // the firmware rejects a MAC that another slot holds as fixed MAC
            return
        }
        message = nil
        scanResult = nil // the slot column is stale once the SET runs; the scan below reloads it
        queue = [cmd, .config]
        if transport == .usb { queue.append(.scan) } // the slot column of the old scan is stale now
        sendNext()
    }

    private func sendNext() {
        guard pendingCommand == nil, !queue.isEmpty else { return }
        let cmd = queue.removeFirst()
        guard let data = cmd.data else { return }
        pendingCommand = cmd
        assembler.reset()
        activeLink.send(data)
        guard pendingCommand != nil else { return } // a failed send already abandoned the command
        armTimeout(for: cmd)
    }

    /// Fails the running command after 10 s without a received chunk. Each chunk restarts the timer.
    private func armTimeout(for cmd: ConsoleCommand) {
        guard transport == .usb else { return } // the BLE watchdog of the scanner owns the BLE timeouts
        timeout?.cancel()
        timeout = Task { [weak self] in
            try? await Task.sleep(for: .seconds(SlotAssignment.timeoutSeconds(for: cmd)))
            guard !Task.isCancelled else { return }
            guard let self else { return }
            message = "No reply to \(cmd.line ?? "command")"
            if case .dump = cmd { archiveSummary = "Download stopped, saved \(downloadStats.saved), failed \(downloadStats.failed)" }
            if cmd == .scan { scanResult = nil }
            pendingCommand = nil
            queue.removeAll()
            endBatchAfterLoss()
            assembler.reset() // a late reply must not reach the next command
        }
    }

    /// Next step of the batch: the next `SET`, or at the end the `CONFIG` refresh and the summary.
    private func advanceBatch() {
        guard var b = batch else { return }
        if b.isFinished {
            batchSummary = b.summary
            batch = nil
            clearCalibration() // the stored hours hold the old offsets, so a new CALIB must follow
            return
        }
        batch = b
        sendNextBatchCommand()
        // A send that failed abandoned the command; end the batch, so the form unlocks.
        if pendingCommand == nil, queue.isEmpty {
            b = batch ?? b
            batchSummary = b.summary + " (link lost)"
            batch = nil
            clearCalibration()
        }
    }

    private var activeLink: ConsoleLink { transport == .usb ? serial : scanner }

    // The transports call these on the main queue, in order. Run them synchronously, so chunks keep their order.
    nonisolated func linkDidChange(_ link: ConsoleLink, state: String, connected: Bool) {
        MainActor.assumeIsolated {
            guard link === activeLink else { return }
            consoleState = state
            consoleConnected = connected
            if !connected {
                scanResult = nil // the slot column of a lost link is stale
                config = nil // the ble_console level of a lost link must not gate SET on the next link
                draft = [:]
                abandonCommands()
            }
        }
    }

    nonisolated func linkDidReceive(_ link: ConsoleLink, _ data: Data) {
        MainActor.assumeIsolated {
            guard link === activeLink else { return }
            if let cmd = pendingCommand { armTimeout(for: cmd) }
            for reply in assembler.append(data) { handle(reply) }
        }
    }

    private func handle(_ reply: ConsoleReply) {
        guard let cmd = pendingCommand else { return } // an unrequested reply is ignored
        timeout?.cancel()
        pendingCommand = nil
        activeLink.commandDidFinish()
        defer {
            if batch != nil { advanceBatch() } else { sendNext() }
        }
        do {
            switch (cmd, reply) {
            case (_, let .corrupt(e)):
                message = "Reply rejected: \(e)"
                // A wrong CRC-32 follows a complete frame, so the stream stays in step and the next file may follow.
                // Any other error leaves the stream out of step.
                if case .badCrc = e, case .dump = cmd {} else { queue.removeAll() }
                finishDownload(cmd, text: "rejected: \(e)")
                batch?.receive(status: "ERR CORRUPT")
            case (_, let .status(text)):
                message = text
                // A file that the card no longer holds (log retention) does not stop the other files.
                var keepQueue = false
                if case .dump = cmd {
                    // ERR READ_FAILED follows a complete frame of padded bytes, so the stream stays in step too.
                    keepQueue = text == "ERR NOT_FOUND" || text == "ERR READ_FAILED"
                    if !keepQueue { queue.removeAll() }
                    finishDownload(cmd, text: text)
                }
                if cmd == .list { archiveFiles = [] }
                if case .calib = cmd { calibration = nil }
                if cmd == .scan { scanResult = nil } // ERR SCAN_FAILED must not leave the rows of an old scan
                batch?.receive(status: text)
                if text.hasPrefix("ERR"), !keepQueue { queue.removeAll() }
                if cmd == .time || text.hasPrefix("TIME ") || text == "TIME" {
                    if let t = try? TimeReply.parse(text) {
                        panelTime = t
                        panelOffsetSeconds = PanelClock.offsetSeconds(panelTime: t, macDate: Date())
                    }
                }
                if case .beep = cmd { lastChimeResult = text }
                if case .pattern = cmd { lastPatternResult = text }
            case let (.time, .frame(lines)):
                let t = try TimeReply.parse(lines: lines)
                panelTime = t
                panelOffsetSeconds = PanelClock.offsetSeconds(panelTime: t, macDate: Date())
            case let (.status, .frame(lines)):
                diagnostics = nil // a bad reply must not leave the values of an older reply
                let parsed = try StatusReply.parse(lines: lines)
                status = parsed
                diagnostics = try DiagnosticsReply.parse(status: parsed)
            case let (.version, .frame(lines)):
                version = nil
                version = try VersionReply.parse(lines: lines)
            case let (.history, .frame(lines)): history = try HistoryReply.parse(lines: lines)
            case let (.events, .frame(lines)): events = try EventsReply.parse(lines: lines)
            case let (.config, .frame(lines)):
                batch?.receiveFrame()
                config = try ConfigReply.parse(lines: lines)
                if batch != nil { draft = [:] } // the panel state replaces the edits
            case let (.scan, .frame(lines)):
                scanResult = nil // a reply that fails to parse must not leave the old rows
                scanResult = try ScanReply.parse(lines: lines)
            case let (.calib, .frame(lines)):
                calibration = nil
                let parsed = try CalibrationReply.parse(lines: lines)
                calibration = parsed
                calibrationSelection = Set(parsed.proposals.map(\.key))
            case let (.list, .frame(lines)):
                archiveFiles = []
                archiveFiles = try ListReply.parse(lines: lines)
                archiveSummary = "\(archiveFiles.count) file(s) on the card"
                selectNewArchiveFiles()
            case let (.dump(name), .file(gotName, bytes)):
                guard gotName == name, let folder = downloadFolder else {
                    queue.removeAll()
                    finishDownload(cmd, text: "unexpected file \(gotName)")
                    break
                }
                do {
                    try LogArchive.store(bytes, name: name, in: folder)
                    downloadStats.saved += 1
                    finishDownload(cmd, text: nil)
                } catch {
                    queue.removeAll()
                    finishDownload(cmd, text: "not saved: \(error.localizedDescription)")
                }
            case (_, .file): message = "Unexpected file reply"
            case (.list, _): message = "Unexpected reply"
            case (.dump, _):
                queue.removeAll()
                finishDownload(cmd, text: "unexpected reply")
            case (.set, .frame): batch?.receive(status: "ERR UNEXPECTED_REPLY")
            case (.timeSet, .frame), (.beep, .frame), (.pattern, .frame):
                message = "Unexpected frame reply"
            }
        } catch {
            message = "Reply not understood: \(error)"
        }
    }
}
