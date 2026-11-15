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
    var week: [WeekRow] = []
    /// Rows for the 7-day charts, downsampled; refreshed when the log changes.
    var chartRows: [TelemetryLogRow] = []
    /// Local telemetry history (7 days), saved at most once a minute.
    private let logStore = TelemetryLogStore()
    var telemetryLog: TelemetryLog { logStore.log }
    var events: [EventRow] = []
    var config: ConfigReply?
    /// The last `CONFIG` reply; a link reset keeps it (a panel change clears it), so the menu bar keeps names and `temp_unit`.
    private(set) var lastConfig: ConfigReply?
    var diagnostics: DiagnosticsReply?
    var version: VersionReply?
    /// `Include names and MACs` of the diagnostics report. Off hides each name and MAC value.
    var reportIncludeNames = false
    private var reportRunning = false
    private var reportLink: DiagnosticsReport.Link = .ble
    private var reportFailed = false
    private var reportIncludeNamesAtStart = false
    private var reportSections: [DiagnosticsReport.Section] = []
    private var reportRemaining: [ConsoleCommand] = []
    var scanResult: ScanReply?
    /// RSSI series of the repeated scan; runs only on a user toggle and on the USB link.
    var scanSeries = ScanSeries()
    private var repeatTask: Task<Void, Never>?
    var panelImage: PanelImage?
    var calibration: CalibrationReply?
    var calibrationReference = 0
    var salt: SaltReply?
    var saltSensor = 0
    var saltSelected = true
    /// Result of `SALT N MGCL2`: the second point of the two-point calibration.
    var saltMagnesium: SaltReply?
    var twoPointMessage: String?
    /// Proposal keys that the user selected.
    var calibrationSelection: Set<String> = []
    var archiveFiles: [LogFile] = []
    var archiveSelection: Set<String> = []
    var archiveFolder: URL?
    /// Rows of the daily summary files in `archiveFolder`, read from local files only.
    var dailyFolder: DailyFolder?
    /// Hourly climate charts: the months with a file in the archive folder, the selected month and its rows.
    var climateMonths: [String] = []
    var climateMonth = ""
    var climateData: ClimateFolder?
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
    /// Login item logic; the state comes from `SMAppService` on each refresh.
    private var loginItem = LoginItem(service: MainAppLoginItemService())
    /// View-only snapshot of the service state, read at each window open and after each toggle.
    var loginStatus: LoginItemStatus = .disabled
    var loginError: String?
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
    /// Parsed backup file that waits for the restore review, with its file name.
    var restore: ConfigBackup.Parsed?
    var restoreFileName: String?
    var showRestore = false
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
    /// A discovered peripheral leaves the list after this time without a packet.
    static let discoveryKeepSeconds: TimeInterval = 120
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
        week = []
        events = []
        config = nil
        lastConfig = nil // names and unit belong to the old panel
        diagnostics = nil
        version = nil
        scanResult = nil; panelImage = nil
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
        stopRepeatScan(.user)
        scanResult = nil; panelImage = nil
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
        stopRepeatScan(.user)
        scanResult = nil; panelImage = nil
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
        failReport(reason: "link lost", pending: pendingCommand)
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
        queue = [.status, .history, .events, .config, .week]
        sendNext()
    }

    /// Reads the login item state from the service. The window calls it when it opens.
    func refreshLoginItem() {
        loginItem.clearError()
        loginStatus = loginItem.status
        loginError = loginItem.errorText
    }

    func setOpenAtLogin(_ on: Bool) {
        loginItem.setEnabled(on)
        loginStatus = loginItem.status
        loginError = loginItem.errorText
    }

    func openLoginItemSettings() { loginItem.openSystemSettings() }

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

    var canCopyReport: Bool { consoleConnected && isIdle && !reportRunning }

    /// Sends `VERSION`, `STATUS`, `CONFIG` and `EVENTS` one after the other and puts the report on the
    /// pasteboard. Runs only on the user's button press.
    func copyReport() {
        guard canCopyReport else { message = "Wait for the running command"; return }
        message = nil
        reportSections = []
        reportRemaining = DiagnosticsReport.commands
        reportRunning = true
        reportFailed = false
        reportIncludeNamesAtStart = reportIncludeNames
        reportLink = transport == .usb ? .usb : .ble
        queue = [reportRemaining.removeFirst()]
        sendNext()
        if pendingCommand == nil { failReport(reason: "send failed", pending: nil) } // a failed send abandoned the command
    }

    private func recordReport(_ cmd: ConsoleCommand, _ reply: ConsoleReply) {
        guard reportRunning, let name = cmd.line, DiagnosticsReport.commands.contains(cmd) else { return }
        if case .frame = reply {} else { reportFailed = true }
        let result: Result<[String], DiagnosticsReport.Failure>
        switch reply {
        case let .frame(lines): result = .success(lines)
        case let .status(text): result = .failure(.init(reason: text))
        case let .corrupt(e):
            // `malformedCrcLine` holds a raw device line, so the report names only the error case.
            if case .badCrc = e { result = .failure(.init(reason: "reply rejected: bad CRC-32")) }
            else { result = .failure(.init(reason: "reply rejected: malformed reply")) }
        case .file: result = .failure(.init(reason: "unexpected file reply"))
        }
        reportSections.append(.init(command: name, result: result))
    }

    /// Queues the next report command, or copies the report after the last one.
    private func advanceReport() {
        guard reportRunning else { return }
        if reportRemaining.isEmpty { finishReport() } else { queue = [reportRemaining.removeFirst()] }
    }

    /// A timeout or a lost link ends the report. The running and the unsent commands get an `ERROR` line.
    private func failReport(reason: String, pending: ConsoleCommand?) {
        guard reportRunning else { return }
        reportFailed = true
        let open = ([pending].compactMap { $0 } + reportRemaining).filter { DiagnosticsReport.commands.contains($0) }
        for cmd in open { if let name = cmd.line { reportSections.append(.init(command: name, result: .failure(.init(reason: reason)))) } }
        reportRemaining = []
        finishReport()
    }

    private func finishReport() {
        reportRunning = false
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "dev"
        let text = DiagnosticsReport.build(
            appVersion: version, link: reportLink,
            includeNames: reportIncludeNamesAtStart, sections: reportSections)
        reportSections = []
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
        message = reportFailed ? "Report copied (incomplete)" : "Report copied to the clipboard"
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

    /// Sets the mute flag of the panel, then reads `STATUS` again so the toggle follows the panel state.
    /// Runs only on the user's toggle. The firmware answers `ERR READ_ONLY` on BLE with `ble_console` 1.
    func setMute(_ on: Bool) {
        guard isIdle else { return }
        message = nil
        queue = [.mute(on), .status]
        sendNext()
    }

    /// Trims the RTC frequency from the drift figure, then reads `STATUS` again. Runs only on the user's button.
    /// The firmware answers `ERR NO_DRIFT` without a figure of 24 h or more and `ERR READ_ONLY` on BLE with `ble_console` 1.
    func trimRtc() {
        guard isIdle, canSet, diagnostics?.rtcTrim?.canTrim == true else { return }
        message = nil
        queue = [.rtcTrim, .status]
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

    /// Starts or stops the repeated scan: `SCAN` every 15 s for at most 10 minutes. USB serial only.
    func setRepeatScan(_ on: Bool) {
        guard on else { stopRepeatScan(.user); return }
        // A SCAN in flight from an older series must not reply into the new one.
        guard pendingCommand == nil, queue.isEmpty, batch == nil else { message = "Wait for the running command"; return }
        guard consoleConnected, scanSeries.start(onUsb: transport == .usb) else {
            message = "SCAN needs the USB serial link"
            return
        }
        repeatTask?.cancel()
        repeatTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self, self.scanSeries.shouldScan() else { return }
                if self.pendingCommand == nil, self.queue.isEmpty, self.batch == nil { self.scan() }
                try? await Task.sleep(for: .seconds(ScanSeries.intervalSeconds))
            }
        }
    }

    func stopRepeatScan(_ reason: ScanSeries.StopReason) {
        scanSeries.stop(reason)
        repeatTask?.cancel()
        repeatTask = nil
    }

    /// Reads the frame of the panel with `SCREEN`. USB serial only: the firmware replies `ERR NOT_OVER_BLE` on BLE.
    func refreshPanelImage() {
        guard SlotAssignment.screenAllowed(onUsb: transport == .usb) else { message = "SCREEN needs the USB serial link"; return }
        guard consoleConnected, pendingCommand == nil, queue.isEmpty, batch == nil else { message = "Wait for the running command"; return }
        message = nil
        queue = [.screen]
        sendNext()
    }

    /// Saves the values of the last `CONFIG` reply as a `config.ini` text file. Runs only on a user action.
    func saveBackup() {
        guard let config else { message = "Read CONFIG first"; return }
        let panel = NSSavePanel()
        panel.nameFieldStringValue = "config.ini"
        panel.canCreateDirectories = true
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do {
            try Data(ConfigBackup.render(values: config.values).utf8).write(to: url, options: .atomic)
            message = "Backup saved: \(url.lastPathComponent)"
        } catch {
            message = "Backup failed: \(error.localizedDescription)"
        }
    }

    /// Reads a backup file and opens the restore review. The file is not sent before the user confirms.
    func loadBackup() {
        guard config != nil else { message = "Read CONFIG first"; return }
        let panel = NSOpenPanel()
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = false
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do {
            let values = try url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
            guard values.isRegularFile == true else { message = "Backup refused: not a regular file"; return }
            let size = values.fileSize ?? 0
            guard size <= ConfigBackup.maxBytes else { throw ConfigBackupError.tooLarge } // no read of a large file
            restore = try ConfigBackup.parse(Data(contentsOf: url))
            restoreFileName = url.lastPathComponent
            showRestore = true
            message = nil
        } catch let error as ConfigBackupError {
            message = "Backup refused: \(error.text)"
        } catch {
            message = "Backup not readable: \(error.localizedDescription)"
        }
    }

    /// Changes of the loaded backup against the last `CONFIG` reply, in schema order.
    var restoreChanges: [ConfigChange] {
        guard let config, let restore else { return [] }
        return restore.changes(current: config.values)
    }

    var restoreProblems: [String: String] {
        ConfigSchema.problems(changes: restoreChanges, allValues: config?.values ?? [:])
    }

    var restoreWarning: String? {
        guard transport == .bluetooth,
              let c = restoreChanges.first(where: { $0.key == "ble_console" }), (Int(c.new) ?? 0) < 2 else { return nil }
        return "ble_console below 2 over BLE stops SET. Apply it alone, or use the USB serial link."
    }

    /// Restore is allowed with changes, no problem, no rejected line, an idle link and a console that takes `SET`.
    var canRestore: Bool {
        canSet && !restoreChanges.isEmpty && restoreProblems.isEmpty && restore?.rejected.isEmpty == true
            && pendingCommand == nil && queue.isEmpty && batch == nil
    }

    func closeRestore() {
        showRestore = false
        restore = nil
        restoreFileName = nil
    }

    /// Sends only the changed keys of the backup through `ConfigBatch` (`SET` commands, then `CONFIG`).
    func applyRestore() {
        guard canRestore else { message = "Restore not allowed"; return }
        let changes = restoreChanges
        closeRestore()
        message = nil
        batchSummary = nil
        if changes.contains(where: { SlotAssignment.isMacKey($0.key) }) { scanResult = nil }
        batch = ConfigBatch(changes: changes)
        sendNextBatchCommand()
    }

    /// Calibration changes of the selected proposals against the last `CONFIG` reply.
    var calibrationChanges: [ConfigChange] {
        calibration?.changes(selecting: calibrationSelection, current: config?.values) ?? []
    }

    private func clearCalibration() {
        calibration = nil
        calibrationSelection = []
        salt = nil
        saltMagnesium = nil
        twoPointMessage = nil
    }

    /// Gain and offset from the two salt results, or the reason why there is none.
    var twoPoint: Result<TwoPointCalibration.Result, TwoPointCalibration.Failure>? {
        guard salt != nil, saltMagnesium != nil else { return nil }
        do {
            return .success(try TwoPointCalibration.compute(naCl: salt, mgCl2: saltMagnesium, current: config?.values))
        } catch let failure as TwoPointCalibration.Failure {
            return .failure(failure)
        } catch {
            return .failure(.notMeasured)
        }
    }

    /// Reads `SALT N MGCL2`. Runs only on the user's button press; the firmware reads the RTC copy.
    func saltMagnesiumCheck(sensor: Int) {
        guard consoleConnected, pendingCommand == nil, queue.isEmpty, batch == nil else { message = "Wait for the running command"; return }
        guard ConsoleCommand.saltMagnesium(sensor).line != nil else { message = "Sensor must be 0 to 5"; return }
        message = nil
        batchSummary = nil
        saltMagnesium = nil
        twoPointMessage = nil
        queue = [.saltMagnesium(sensor)]
        sendNext()
    }

    /// Sends the two-point result through `ConfigBatch`. A gain outside the key range gives an error text and no `SET`.
    func applyTwoPoint() {
        guard case let .success(result)? = twoPoint else {
            if case let .failure(failure)? = twoPoint { twoPointMessage = failure.text }
            return
        }
        twoPointMessage = nil
        let changes = result.changes(current: config?.values ?? [:])
        guard !changes.isEmpty else { message = "Gain and offset are already active"; return }
        guard consoleConnected, pendingCommand == nil, queue.isEmpty, batch == nil else { message = "Wait for the running command"; return }
        message = nil
        batchSummary = nil
        batch = ConfigBatch(changes: changes)
        sendNextBatchCommand()
    }

    /// Calibration changes of the salt proposal against the last `CONFIG` reply.
    var saltChanges: [ConfigChange] {
        salt?.changes(selected: saltSelected, current: config?.values) ?? []
    }

    /// Reads the salt check of `SALT N`. Runs only on the user's button press; the firmware reads the RTC copy.
    func saltCheck(sensor: Int) {
        guard consoleConnected, pendingCommand == nil, queue.isEmpty, batch == nil else { message = "Wait for the running command"; return }
        guard ConsoleCommand.salt(sensor).line != nil else { message = "Sensor must be 0 to 5"; return }
        message = nil
        batchSummary = nil
        salt = nil
        saltSelected = true
        twoPointMessage = nil
        queue = [.salt(sensor)]
        sendNext()
    }

    /// Sends the salt proposal through `ConfigBatch` (`SET` command, then `CONFIG`).
    func applySalt() {
        let changes = saltChanges
        guard !changes.isEmpty else { message = "No proposal selected"; return }
        guard consoleConnected, pendingCommand == nil, queue.isEmpty, batch == nil else { message = "Wait for the running command"; return }
        message = nil
        batchSummary = nil
        batch = ConfigBatch(changes: changes)
        sendNextBatchCommand()
    }

    /// Reads the histories comparison of `CALIB N`. Runs only on the user's button press; the firmware reads the RTC copy.
    func calibrate(reference: Int) {
        guard consoleConnected, pendingCommand == nil, queue.isEmpty, batch == nil else { message = "Wait for the running command"; return }
        guard ConsoleCommand.calib(reference).line != nil else { message = "Reference must be 0 to 5"; return }
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
            if dailyFolder != nil, let folder = downloadFolder { dailyFolder = DailyLog.load(from: folder) } // visible charts follow the new files
            if climateData != nil { showClimateCharts() } // the hourly charts follow the new files too
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
            failReport(reason: "no reply", pending: cmd)
            if case .dump = cmd { archiveSummary = "Download stopped, saved \(downloadStats.saved), failed \(downloadStats.failed)" }
            if cmd == .scan { scanResult = nil; stopRepeatScan(.scanFailed) } // no reply ends the repeat
            if cmd == .screen { panelImage = nil }
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
                stopRepeatScan(.linkLost)
                scanResult = nil // the slot column of a lost link is stale
                panelImage = nil
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
        recordReport(cmd, reply)
        defer {
            advanceReport()
            if batch != nil { advanceBatch() } else { sendNext() }
        }
        do {
            switch (cmd, reply) {
            case (_, let .corrupt(e)):
                message = "Reply rejected: \(e)"
                if cmd == .screen { panelImage = nil } // a rejected frame must not stay next to the error
                if cmd == .scan { stopRepeatScan(.scanFailed) }
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
                if case .salt = cmd { salt = nil }
                if case .saltMagnesium = cmd { saltMagnesium = nil }
                if cmd == .screen { panelImage = nil } // ERR NO_FRAME must not leave an old image
                if cmd == .scan { scanResult = nil } // ERR SCAN_FAILED must not leave the rows of an old scan
                if cmd == .scan, text.hasPrefix("ERR") { stopRepeatScan(.scanFailed) }
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
            case let (.week, .frame(lines)):
                week = [] // a reply that fails to parse must not leave the old rows
                week = try WeekReply.parse(lines: lines)
            case let (.events, .frame(lines)): events = try EventsReply.parse(lines: lines)
            case let (.config, .frame(lines)):
                batch?.receiveFrame()
                config = try ConfigReply.parse(lines: lines)
                lastConfig = config
                if batch != nil { draft = [:] } // the panel state replaces the edits
            case let (.scan, .frame(lines)):
                scanResult = nil // a reply that fails to parse must not leave the old rows
                scanResult = try ScanReply.parse(lines: lines)
                if let scanResult { scanSeries.record(scanResult) }
            case let (.screen, .file(name, bytes)):
                panelImage = nil // a frame that fails to decode must not leave the old image
                guard name == PanelImage.fileName else { message = "Unexpected file \(name)"; break }
                panelImage = try PanelImage.decode(bytes)
            case (.screen, .frame): message = "Unexpected frame reply"
            case let (.calib, .frame(lines)):
                calibration = nil
                let parsed = try CalibrationReply.parse(lines: lines)
                calibration = parsed
                calibrationSelection = Set(parsed.proposals.map(\.key))
            case let (.salt, .frame(lines)):
                salt = nil
                salt = try SaltReply.parse(lines: lines)
            case let (.saltMagnesium, .frame(lines)):
                saltMagnesium = nil
                saltMagnesium = try SaltReply.parse(lines: lines)
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
            case (.timeSet, .frame), (.beep, .frame), (.pattern, .frame), (.mute, .frame), (.rtcTrim, .frame):
                message = "Unexpected frame reply"
            }
        } catch {
            message = "Reply not understood: \(error)"
            if cmd == .scan { stopRepeatScan(.scanFailed) }
        }
    }

    /// Reads the months of the archive folder and shows the newest one (local files only).
    /// `ArchiveView.chooseFolder` hides the charts when the folder changes, so `archiveFolder` is also the download folder.
    func showClimateCharts() {
        guard let folder = archiveFolder else { return }
        climateMonths = ClimateLog.availableMonths(in: folder)
        selectClimateMonth(climateMonths.contains(climateMonth) ? climateMonth : climateMonths.last ?? "")
    }

    func selectClimateMonth(_ month: String) {
        climateMonth = month
        guard let folder = archiveFolder, !month.isEmpty else { climateData = ClimateFolder(rows: [], failedFiles: []); return }
        climateData = ClimateLog.load(from: folder, month: month)
    }

    func hideClimateCharts() {
        climateMonths = []
        climateData = nil
    }
}
