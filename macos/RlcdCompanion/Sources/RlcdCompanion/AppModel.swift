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
    /// Selected category of the event list; `nil` shows all events.
    var eventCategory: EventFilter.Category?
    var filteredEvents: [EventRow] {
        EventFilter.apply(events, eventCategory.map { [$0] } ?? [])
    }
    var config: ConfigReply?
    /// The last `CONFIG` reply; a link reset keeps it (a panel change clears it), so the menu bar keeps names and `temp_unit`.
    private(set) var lastConfig: ConfigReply?
    var diagnostics: DiagnosticsReply?
    /// `CONFIG` values that were active when `diagnostics` was read. `RUNTIME h` belongs to this schedule, not to a later `SET`.
    private(set) var diagnosticsConfig: [String: String]?
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
    /// Reply of the last `REJECTS` command, `nil` before a user read.
    var rejectsResult: RejectsReply?
    /// Last `SELFTEST` result and its verified lines (for the diagnostics report). Cleared on a new run and on a link change.
    var selfTestResult: SelfTestReply?
    private var selfTestLines: [String] = []
    /// The guided setup of one new sensor, `nil` while the assistant is closed.
    var setup: SetupAssistant?
    /// Entries of the assistant view: the MAC under choice, its name and the calibration reference.
    var setupChosen: String?
    var setupName = ""
    var setupReference = 0
    /// RSSI series of the repeated scan; runs only on a user toggle and on the USB link.
    var scanSeries = ScanSeries()
    private var repeatTask: Task<Void, Never>?
    private var saltWatchTask: Task<Void, Never>?
    var panelImage: PanelImage?
    var calibration: CalibrationReply?
    var calibrationReference = 0
    var salt: SaltReply?
    var saltSensor = 0
    /// Watch of the salt test: `HISTORY N` every 10 minutes for at most 8 hours.
    var saltProgress = SaltProgress()
    private(set) var saltWatchSensor = 0
    var saltSelected = true
    /// Result of `SALT N MGCL2`: the second point of the two-point calibration.
    var saltMagnesium: SaltReply?
    /// Sensors of the wall factor check and its last reply (`FRSI R W`).
    var frsiRoom = 0
    var frsiWall = 1
    var frsi: FrsiReply?
    var twoPointMessage: String?
    /// Proposal keys that the user selected.
    var calibrationSelection: Set<String> = []
    var archiveFiles: [LogFile] = []
    var archiveSelection: Set<String> = []
    var archiveFolder: URL?
    /// Rows of the daily summary files in `archiveFolder`, read from local files only.
    var dailyFolder: DailyFolder?
    /// Monthly alert statistics from the `events_v1` files in `archiveFolder`, read from local files only.
    var alertStats: AlertStatsResult?
    /// Hourly climate charts: the months with a file in the archive folder, the selected month and its rows.
    var climateMonths: [String] = []
    var climateMonth = ""
    var climateData: ClimateFolder?
    /// Rows of the selected month and the two months before it, for the battery forecast (60 days).
    var climateForecastRows: [ClimateRow] = []
    var archiveSummary: String?
    /// Folder of the running download. A later folder choice does not split the files of one run.
    private var downloadFolder: URL?
    var panelTime: TimeReply?
    var panelOffsetSeconds: Int?
    var lastChimeResult: String?
    var lastPatternResult: String?
    /// Text after `IDENTIFY` (`Panel shows RLCD <XXXX>` or the error text).
    var identifyResult: String?
    var message: String?
    /// User notifications for active alerts.
    var notificationsEnabled = false
    /// Detects a panel that stopped to send frames; checked at the 30 s tick.
    private var silenceWatch = SilenceWatch()
    private var wallWatch = WallWatch()
    private var staleWatch = StaleWatch()
    private var windowWatch = WindowWatch()
    private static let silenceNotificationId = "rlcd_panel_silence"
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
    /// Name typed in the scanner for a scanned MAC; sent with the MAC by `assign(slot:mac:name:)`.
    var scanNames: [String: String] = [:]
    /// MAC of a running scanner assignment: its name entry clears and a new `SCAN` runs after a clean batch.
    private var assigningMac: String?
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
    private let notificationDelegate = NotificationActionDelegate()

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
        registerNotificationActions()
        Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(30))
                self?.now = Date()
                self?.checkSilence()
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
        if let initial = Self.loadInitialConfig() {
            config = initial
            lastConfig = initial
        }
        scanner.onFrame = { [weak self] f, r in
            guard let self else { return }
            let prev = self.frame
            let merged = TelemetryFrame.merge(current: prev, incoming: f)
            self.frame = merged
            self.frameDate = Date()
            self.now = Date()
            self.rssi = r
            if f.page == 0 && self.logStore.record(f) { self.chartRows = self.logStore.log.downsampled(maxRows: 400) }
            self.checkAlertNotifications(previous: prev, current: f)
            self.checkWallNotifications()
            self.checkStaleNotifications(incoming: f)
            if self.silenceWatch.onFrame() == .back, Bundle.main.bundleIdentifier != nil {
                UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [Self.silenceNotificationId])
            }
        }
    }

    private func checkSilence() {
        guard silenceWatch.check() == .silent, notificationsEnabled, Bundle.main.bundleIdentifier != nil else { return }
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm"
        let since = silenceWatch.lastFrameAt.map { formatter.string(from: $0) } ?? "--:--"
        let content = UNMutableNotificationContent()
        content.title = "RLCD Panel Alert"
        content.body = "Panel silent since \(since)"
        content.sound = .default
        let req = UNNotificationRequest(identifier: Self.silenceNotificationId, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(req)
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

    /// Saves the last panel image as a PNG file. Sends no console command.
    func exportPanelPng() {
        guard let image = panelImage else { return }
        guard let data = image.pngData() else { message = "PNG export failed: encoder error"; return }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMdd_HHmmss"
        let panel = NSSavePanel()
        panel.nameFieldStringValue = "panel_\(formatter.string(from: Date())).png"
        panel.allowedContentTypes = [.png]
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do {
            try data.write(to: url, options: .atomic)
            message = "PNG saved: \(url.lastPathComponent)"
        } catch {
            message = "PNG export failed: \(error.localizedDescription)"
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
            // A loss that began while notifications were off must not stay latched.
            staleWatch = StaleWatch()
        }
    }

    /// Feeds `WallWatch` with the wall humidity of each tile and posts one notification for each rise.
    /// Without enabled notifications `WallWatch` stays untouched, so a rise does not use the 6 hour window.
    private func checkWallNotifications() {
        guard notificationsEnabled, Bundle.main.bundleIdentifier != nil else { return }
        for tile in 0..<WallWatch.tileCount {
            guard case .notify(let t, let value)? = wallWatch.update(tile: tile, rh: wallRh(forTile: tile)) else { continue }
            let name = config?.name(forSlot: t) ?? (t == 0 ? "Onboard" : "Slot \(t)")
            let content = UNMutableNotificationContent()
            content.title = "RLCD Panel Alert"
            content.body = "\(name): wall \(Int(value.rounded()))%"
            content.sound = .default
            let req = UNNotificationRequest(identifier: "rlcd_panel_wall_\(t)", content: content, trigger: nil)
            UNUserNotificationCenter.current().add(req)
        }
    }

    /// Feeds `StaleWatch` with the valid bit of each tile of the incoming frame and posts one notification for each loss.
    /// Only the tiles of the incoming page count: the merged frame keeps the tiles of the other page.
    /// Without enabled notifications `StaleWatch` stays untouched.
    private func checkStaleNotifications(incoming: TelemetryFrame) {
        guard notificationsEnabled, Bundle.main.bundleIdentifier != nil else { return }
        for (i, incomingTile) in incoming.tiles.enumerated() {
            let tile = incoming.page * TelemetryFrame.tileCount + i
            guard tile < StaleWatch.tileCount else { continue }
            let valid = incomingTile.temperatureC != nil
            let identifier: String
            switch staleWatch.update(tile: tile, valid: valid) {
            case .lost(let t)?:
                identifier = "rlcd_panel_stale_\(t)"
                let name = config?.name(forSlot: t) ?? (t == 0 ? "Onboard" : "Slot \(t)")
                let content = UNMutableNotificationContent()
                content.title = "RLCD Panel Alert"
                content.body = "\(name): no data for \(Int(StaleWatch.lostAfter / 60)) min"
                content.sound = .default
                UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: identifier, content: content, trigger: nil))
            case .back(let t)?:
                UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ["rlcd_panel_stale_\(t)"])
            case nil:
                continue
            }
        }
    }

    /// Posts one notification for each new open window event of the event list. `WindowWatch` runs on each list,
    /// so a list read while notifications are off does not repeat later.
    private func checkWindowNotifications(_ rows: [EventRow]) {
        let tiles = windowWatch.newOpenTiles(rows)
        guard notificationsEnabled, Bundle.main.bundleIdentifier != nil else { return }
        for tile in tiles {
            let content = UNMutableNotificationContent()
            content.title = "RLCD Panel Alert"
            content.body = "Window open in \(EventText.roomName(tile: tile, config: lastConfig))"
            content.sound = .default
            UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "rlcd_panel_window_\(tile)", content: content, trigger: nil))
        }
    }

    private func checkAlertNotifications(previous: TelemetryFrame?, current: TelemetryFrame) {
        guard notificationsEnabled, Bundle.main.bundleIdentifier != nil else { return }
        let rising = AlertTransitions.risingAlerts(previous: previous, current: current)
        guard rising.rawValue != 0 else { return }
        let actionBits = AlertNotificationPlan.actionBits(rising: rising)
        for bit in 0..<8 {
            let mask = AlertMask(rawValue: UInt8(1 << bit))
            guard rising.contains(mask), let name = mask.names.first else { continue }
            let content = UNMutableNotificationContent()
            content.title = "RLCD Panel Alert"
            content.body = "Alert raised: \(name)"
            content.sound = .default
            let hasAction = actionBits.contains(bit)
            if hasAction { content.categoryIdentifier = AlertNotificationPlan.categoryIdentifier }
            // A fixed identifier lets a new rising edge of the same bit replace the older banner.
            let identifier = hasAction ? AlertNotificationPlan.identifier(bit: bit) : UUID().uuidString
            UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: identifier, content: content, trigger: nil))
        }
    }

    /// Registers the category `rlcd_alert` with the action `Acknowledge` and takes the notification responses.
    func registerNotificationActions() {
        guard Bundle.main.bundleIdentifier != nil else { return }
        let action = UNNotificationAction(identifier: AlertNotificationPlan.acknowledgeActionIdentifier, title: "Acknowledge", options: [])
        let category = UNNotificationCategory(identifier: AlertNotificationPlan.categoryIdentifier, actions: [action], intentIdentifiers: [], options: [])
        let center = UNUserNotificationCenter.current()
        center.setNotificationCategories([category])
        center.delegate = notificationDelegate
        notificationDelegate.onAcknowledge = { [weak self] bit in self?.acknowledgeFromNotification(bit: bit) }
    }

    /// Sends `ACK <bit>` when the link is up and the model is idle; else posts `Panel not connected`.
    private func acknowledgeFromNotification(bit: Int) {
        if isIdle {
            acknowledge(bit: bit)
            return
        }
        let content = UNMutableNotificationContent()
        content.title = "RLCD Panel Alert"
        content.body = "Panel not connected"
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil))
    }

    /// Outdoor tile of the ventilation advice from `outdoor_slot` of the cached `CONFIG` reply; `nil` for a stale frame,
    /// a missing config, `outdoor_slot` 0 and an invalid tile. Reads only the frame; sends no console command.
    var ventilationOutdoorTile: TelemetryTile? {
        VentilationAdvice.outdoorTile(frame: frame, frameDate: frameDate, config: lastConfig, now: now)
    }

    /// Advice for the indoor tile `index`; `nil` for the outdoor tile itself and for an invalid indoor tile.
    func ventilationAdvice(forTile index: Int) -> VentilationAdvice? {
        VentilationAdvice.advice(forTile: index, frame: frame, frameDate: frameDate, config: lastConfig, now: now)
    }

    /// `Wall <N>%` for the indoor tile `index`; `nil` without `wall_frsi_pct`, without a fresh outdoor tile or for a stale frame.
    func wallText(forTile index: Int) -> String? {
        guard let frame, frame.tiles.indices.contains(index),
              let outdoor = VentilationAdvice.outdoorTile(frame: frame, frameDate: frameDate, config: lastConfig, now: now) else { return nil }
        return VentilationAdvice.wallText(tile: frame.tiles[index], index: index, outdoor: outdoor, config: lastConfig)
    }

    /// Wall humidity of the indoor tile `index` with the freshness rules of `wallText`.
    private func wallRh(forTile index: Int) -> Double? {
        guard let frame, frame.tiles.indices.contains(index),
              let outdoor = VentilationAdvice.outdoorTile(frame: frame, frameDate: frameDate, config: lastConfig, now: Date()) else { return nil }
        return VentilationAdvice.wallRh(tile: frame.tiles[index], index: index, outdoor: outdoor, config: lastConfig)
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
        windowWatch = WindowWatch() // the next list of a panel is a new baseline
        config = nil
        lastConfig = nil // names and unit belong to the old panel
        diagnostics = nil
        diagnosticsConfig = nil
        version = nil
        scanResult = nil; panelImage = nil; rejectsResult = nil; selfTestResult = nil; selfTestLines = []
        calibration = nil
        clearArchive()
        panelTime = nil
        panelOffsetSeconds = nil
        lastChimeResult = nil
        lastPatternResult = nil
        identifyResult = nil
    }

    private func clearArchive() {
        archiveFiles = []
        archiveSelection = []
        archiveSummary = nil
    }

    func disconnectConsole() {
        stopRepeatScan(.user)
        stopSaltWatch(.user)
        scanResult = nil; panelImage = nil; rejectsResult = nil; selfTestResult = nil; selfTestLines = []
        calibration = nil
        clearArchive()
        panelTime = nil
        panelOffsetSeconds = nil
        lastChimeResult = nil
        lastPatternResult = nil
        identifyResult = nil
        resetLink()
        consoleConnected = false
        consoleState = "Console not connected"
    }

    func selectTransport(_ t: Transport) {
        stopRepeatScan(.user)
        stopSaltWatch(.user)
        scanResult = nil; panelImage = nil; rejectsResult = nil; selfTestResult = nil; selfTestLines = []
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
        status = nil // the sensor list of the old panel must not feed the slot choice
        draft = [:] // edits belong to the panel that was read
        setup?.linkLost("link changed")
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
        if setup?.pending != nil { setup?.linkLost("no reply") } // an idle assistant survives an unrelated timeout
        guard let b = batch else { return }
        batchSummary = b.summary + " (no reply)"
        batch = nil
        assigningMac = nil
        clearCalibration()
    }

    /// Sends the four read commands one after the other. Runs only on the user's button press.
    func refresh() {
        guard consoleConnected, pendingCommand == nil, queue.isEmpty else { return }
        message = nil
        queue = [.config, .status, .history, .events, .week] // CONFIG first: `diagnosticsConfig` takes the values that the STATUS read ran with
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
        diagnosticsConfig = nil
        version = nil
        panelTime = nil
        panelOffsetSeconds = nil
        queue = [.config] + diagnosticsRefreshCommands + [.time] // CONFIG first, see `diagnosticsConfig`
        sendNext()
    }

    var canCopyReport: Bool { consoleConnected && isIdle && !reportRunning }

    /// Sends `VERSION`, `STATUS`, `CONFIG`, `EVENTS` and `REJECTS` one after the other and puts the report on the
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
            includeNames: reportIncludeNamesAtStart,
            sections: reportSections + (selfTestResult == nil ? [] : [.init(command: ConsoleCommand.selfTest.line ?? "SELFTEST", result: .success(selfTestLines))]))
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
        identifyResult = nil
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

    /// Mutes the chime for `minutes` (`SNOOZE M`, 0 ends it), then reads `STATUS` again so the text follows the panel.
    /// Runs only on the user's menu item.
    func snooze(minutes: Int) {
        guard isIdle, ConsoleCommand.snooze(minutes).line != nil else { return }
        message = nil
        queue = [.snooze(minutes), .status]
        sendNext()
    }

    /// Stops the chime reminder of one active alert (`ACK N`), then reads `STATUS` again so the row follows the panel.
    /// Runs only on the user's button. The firmware answers `ERR NOT_ACTIVE` for an alert that is off and `ERR READ_ONLY` on BLE with `ble_console` 1.
    func acknowledge(bit: Int) {
        guard isIdle, ConsoleCommand.ack(bit).line != nil else { return }
        message = nil
        queue = [.ack(bit), .status]
        sendNext()
    }

    /// Stops the chime reminder of each active alert (`ACK ALL`), then reads `STATUS` again so the rows follow the panel.
    /// Runs only on the user's button.
    func acknowledgeAll() {
        guard isIdle else { return }
        message = nil
        queue = [.ackAll, .status]
        sendNext()
    }

    /// Shows the identify screen on the connected panel (`IDENTIFY`). Runs only on the user's button.
    func identifyPanel() {
        guard isIdle else { return }
        message = nil
        identifyResult = nil
        queue = [.identify]
        sendNext()
    }

    /// Sensors of the calibration pickers: 0 to 5 and each slot 6 to 10 with a reading.
    var calibrationSensors: [Int] { CalibrationSensors.selectable(status: status?.sensors) }

    /// Slots with a reading (`STATUS` state other than `EMPTY`) and without a fixed MAC (`CONFIG`): `SLOT CLEAR` accepts them.
    var freeableSlots: [Int] {
        guard let sensors = status?.sensors, let config else { return [] } // without CONFIG a fixed MAC is unknown
        return sensors.compactMap { sensor in
            guard SlotAssignment.slots.contains(sensor.index), sensor.state != "EMPTY",
                  (config.values["slot\(sensor.index)_mac"] ?? "").isEmpty else { return nil }
            return sensor.index
        }
    }

    /// Frees one registry slot (`SLOT CLEAR N`), then reads `STATUS` again so the tile list follows the panel.
    /// Runs only on the user's button. The firmware answers `ERR FIXED_MAC` for a slot with a fixed MAC and `ERR READ_ONLY` on BLE with `ble_console` 1.
    func clearSlot(_ slot: Int) {
        guard isIdle, canSet else { return }
        guard ConsoleCommand.slotClear(slot).line != nil else { message = "Slot not valid"; return }
        message = nil
        scanResult = nil // the slot column of the old scan is stale now
        rejectsResult = nil // the rows describe the state before the change
        queue = [.slotClear(slot), .status]
        sendNext()
    }

    /// Swaps the data of two registry slots (`SLOT SWAP A B`), then reads `STATUS` again. Runs only on the user's menu.
    /// The firmware answers `ERR FIXED_MAC`, `ERR SLOT_OFF` and `ERR READ_ONLY` (BLE with `ble_console` 1); the texts come from `slotSwapMessage`.
    func swapSlots(_ a: Int, _ b: Int) {
        guard isIdle, canSet else { return }
        guard a != b, ConsoleCommand.slotSwap(a, b).line != nil else { message = "Slot not valid"; return }
        message = nil
        scanResult = nil
        rejectsResult = nil
        queue = [.slotSwap(a, b), .status]
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

    /// Syncs the clock over Wi-Fi (`NTP SYNC`), then reads `STATUS` again. Runs only on the user's button and on USB.
    /// The firmware answers `ERR NO_WIFI`, `ERR NTP_FAILED <reason>` and `ERR NOT_OVER_BLE`; the texts come from `ntpSyncMessage`.
    func syncClockOverWifi() {
        guard isIdle, canSet else { return }
        guard SlotAssignment.ntpSyncAllowed(onUsb: transport == .usb) else { message = ConsoleCommand.ntpSyncMessage("ERR NOT_OVER_BLE"); return }
        message = nil
        queue = [.ntpSync, .status]
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
        if changes.contains(where: { SlotAssignment.isMacKey($0.key) }) { scanResult = nil; rejectsResult = nil } // slot column goes stale
        batch = ConfigBatch(changes: changes)
        sendNextBatchCommand()
    }

    // MARK: Setup assistant. Each method runs on one user click and sends the commands of that click.

    func openSetup() { setup = SetupAssistant(); setupChosen = nil; setupName = ""; setupReference = 0 }
    /// A running batch must finish first: a close in the middle would leave a half assignment.
    func closeSetup() {
        if let a = setup, a.pending != nil, !a.isStopped { message = "Wait for the running command"; return }
        setup = nil
    }

    func setupScan() { setupChosen = nil; runSetup { $0.beginScan(onUsb: transport == .usb) } }
    func setupChoose(mac: String, name: String) { setup?.choose(mac: mac, name: name) }
    func setupAssign() {
        guard canSet else { message = "SET needs ble_console=2 or the USB serial link"; return }
        scanResult = nil // the slot column is stale once the SET runs
        rejectsResult = nil // the rows describe the state before the change
        runSetup { [status, config] in $0.beginAssign(status: status, config: config) }
    }
    func setupCheckReception() { runSetup { $0.checkReception() } }
    func setupCalibrate(reference: Int) { runSetup { $0.beginCalibrate(reference: reference) } }
    func setupApply() {
        guard canSet else { message = "SET needs ble_console=2 or the USB serial link"; return }
        runSetup { [config] in $0.beginApply(config: config) }
    }

    func setupApplyOutdoor() {
        guard canSet else { message = "SET needs ble_console=2 or the USB serial link"; return }
        runSetup { [week, config] in $0.beginApplyOutdoor(week: week, config: config) }
    }

    private func runSetup(_ action: (inout SetupAssistant) -> ConsoleCommand?) {
        guard var assistant = setup else { return }
        guard consoleConnected, pendingCommand == nil, queue.isEmpty, batch == nil else { message = "Wait for the running command"; return }
        message = nil
        let command = action(&assistant)
        setup = assistant
        guard let command else { return }
        queue = [command]
        sendNext()
        // A failed send ended the assistant through the link-lost path. A command without a console line never left.
        if pendingCommand == nil, var a = setup, a.pending != nil, !a.isStopped {
            a.commandNotSent()
            setup = a
        }
    }

    /// Hands the reply of an assistant command to the assistant, then sends the next command of its batch.
    private func advanceSetup(_ cmd: ConsoleCommand, _ reply: ConsoleReply) {
        guard var assistant = setup, assistant.pending == cmd else { return }
        assistant.receive(reply)
        if cmd == .config, !assistant.isStopped, assistant.step == .verify || assistant.step == .done {
            // The batch ended: the stored hours hold the old offsets, so a new CALIB must follow.
            clearCalibration()
            if let mac = assistant.chosenMac, !assistant.isStopped { scanNames[mac] = nil }
        }
        let next = assistant.nextCommand()
        if next?.data == nil, next != nil { assistant.commandNotSent() }
        setup = assistant
        guard let next, next.data != nil else { return }
        queue = [next]
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

    /// Reads the rejected packets of the last BLE scan with `REJECTS`. Runs only on the user's button, on USB and on BLE.
    func readRejects() {
        guard consoleConnected else { message = "Console not connected"; return }
        guard pendingCommand == nil, queue.isEmpty, batch == nil else { message = "Wait for the running command"; return }
        message = nil
        queue = [.rejects]
        sendNext()
    }

    /// Runs the onboard hardware checks with `SELFTEST`. Runs only on the user's button, on USB and on BLE.
    func runSelfTest() {
        guard consoleConnected else { message = "Console not connected"; return }
        guard pendingCommand == nil, queue.isEmpty, batch == nil, !reportRunning else { message = "Wait for the running command"; return }
        message = nil
        selfTestResult = nil
        selfTestLines = []
        queue = [.selfTest]
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

    /// Starts or stops the salt test watch: `HISTORY N` every 10 minutes for at most 8 hours.
    func setSaltWatch(_ on: Bool, sensor: Int) {
        guard on else { stopSaltWatch(.user); return }
        guard consoleConnected else { message = "Connect the console first"; return }
        guard ConsoleCommand.historySensor(sensor).line != nil else { message = "Sensor must be 0 to 10"; return }
        guard pendingCommand == nil, queue.isEmpty, batch == nil else { message = "Wait for the running command"; return }
        message = nil
        saltWatchSensor = sensor
        saltProgress.start()
        saltWatchTask?.cancel()
        saltWatchTask = Task { [weak self] in
            // `self` is not held across the sleep, so a closed window can free the model.
            while !Task.isCancelled {
                guard let go = self?.pollSaltWatch(sensor: sensor), go else { return }
                try? await Task.sleep(for: .seconds(SaltProgress.intervalSeconds))
            }
        }
    }

    /// One tick of the salt watch. A tick while another command runs is skipped without a catch-up; the next tick reads the same 24 hours.
    /// Returns false when the watch is over. The toggle is the user action;
    /// the watch also runs on BLE, where the firmware allows `HISTORY` at each `ble_console` level.
    private func pollSaltWatch(sensor: Int) -> Bool {
        guard saltProgress.shouldPoll() else { return false }
        if pendingCommand == nil, queue.isEmpty, batch == nil {
            queue = [.historySensor(sensor)]
            sendNext()
        }
        return true
    }

    func stopSaltWatch(_ reason: SaltProgress.StopReason) {
        saltProgress.stop(reason)
        saltWatchTask?.cancel()
        saltWatchTask = nil
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
        if changes.contains(where: { SlotAssignment.isMacKey($0.key) }) { scanResult = nil; rejectsResult = nil }
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
        guard ConsoleCommand.saltMagnesium(sensor).line != nil else { message = "Sensor must be 0 to 10"; return }
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
        guard ConsoleCommand.salt(sensor).line != nil else { message = "Sensor must be 0 to 10"; return }
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

    /// Wall factor changes of the last `FRSI` proposal against the last `CONFIG` reply.
    var frsiChanges: [ConfigChange] {
        frsi?.changes(current: config?.values) ?? []
    }

    /// Reads the wall factor of `FRSI R W`. Runs only on the user's button press; the firmware reads the RTC copy.
    func frsiCheck(room: Int, wall: Int) {
        guard consoleConnected, pendingCommand == nil, queue.isEmpty, batch == nil else { message = "Wait for the running command"; return }
        guard room != wall, ConsoleCommand.frsi(room: room, wall: wall).line != nil else {
            message = "Pick two different sensors, the wall must be a slot"; return
        }
        message = nil
        batchSummary = nil
        frsi = nil
        queue = [.frsi(room: room, wall: wall)]
        sendNext()
    }

    /// Sends `SET wall_frsi_pct=<N>` through `ConfigBatch` (`SET` command, then `CONFIG`).
    func applyFrsi() {
        let changes = frsiChanges
        guard !changes.isEmpty else { message = "No proposal to apply"; return }
        guard consoleConnected, pendingCommand == nil, queue.isEmpty, batch == nil else { message = "Wait for the running command"; return }
        message = nil
        batchSummary = nil
        batch = ConfigBatch(changes: changes)
        sendNextBatchCommand()
    }

    /// Reads the histories comparison of `CALIB N`. Runs only on the user's button press; the firmware reads the RTC copy.
    func calibrate(reference: Int) {
        guard consoleConnected, pendingCommand == nil, queue.isEmpty, batch == nil else { message = "Wait for the running command"; return }
        guard ConsoleCommand.calib(reference).line != nil else { message = "Reference must be 0 to 10"; return }
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
            if alertStats != nil, let folder = downloadFolder { alertStats = AlertStats.load(from: folder) } // the alert statistics follow the new files
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
        rejectsResult = nil // the rows describe the state before the change
        queue = [cmd, .config]
        if transport == .usb { queue.append(.scan) } // the slot column of the old scan is stale now
        sendNext()
    }

    /// Binds a name to a sensor: `SET slotN_mac`, then `SET slotN_name` for a name that is not empty, through
    /// `ConfigBatch` (the first `ERR` stops it, then `CONFIG`).
    func assign(slot: Int, mac: String, name: String) {
        guard canSet else { message = "SET needs ble_console=2 or the USB serial link"; return }
        guard pendingCommand == nil, queue.isEmpty, batch == nil else { message = "Wait for the running command"; return }
        guard let commands = SlotAssignment.batch(slot: slot, mac: mac, name: name) else {
            message = "Slot, MAC or name not valid (up to \(SlotAssignment.maxNameChars) characters: A-Z, 0-9, -)"
            return
        }
        if let warning = SlotAssignment.warning(slot: slot, mac: mac, config: config) {
            message = warning
            return
        }
        message = nil
        batchSummary = nil
        scanResult = nil // the slot column is stale once the SET runs
        rejectsResult = nil // the rows describe the state before the change
        assigningMac = mac
        batch = ConfigBatch(commands: commands)
        sendNextBatchCommand()
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
            if case .historySensor = cmd { stopSaltWatch(.replyFailed) }
            if cmd == .screen { panelImage = nil }
            if cmd == .rejects { rejectsResult = nil }
            if cmd == .selfTest { selfTestResult = nil; selfTestLines = [] }
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
            if let mac = assigningMac {
                assigningMac = nil
                if b.failure == nil { scanNames[mac] = nil } // the SET commands applied, even if CONFIG failed
                if transport == .usb { scan() } // the slot column of the old scan is stale now
            }
            return
        }
        batch = b
        sendNextBatchCommand()
        // A send that failed abandoned the command; end the batch, so the form unlocks.
        if pendingCommand == nil, queue.isEmpty {
            b = batch ?? b
            batchSummary = b.summary + " (link lost)"
            batch = nil
            assigningMac = nil
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
                status = nil // the sensor list of a lost link must not feed the slot choice of the next one
                setup?.linkLost(state)
                stopRepeatScan(.linkLost)
                stopSaltWatch(.linkLost)
                scanResult = nil // the slot column of a lost link is stale
                panelImage = nil
                rejectsResult = nil
                config = nil // the ble_console level of a lost link must not gate SET on the next link
                diagnosticsConfig = nil // the runtime preview needs the schedule of the same link
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
            advanceSetup(cmd, reply)
            advanceReport()
            if batch != nil { advanceBatch() } else { sendNext() }
        }
        do {
            switch (cmd, reply) {
            case (_, let .corrupt(e)):
                message = "Reply rejected: \(e)"
                if cmd == .screen { panelImage = nil } // a rejected frame must not stay next to the error
                if cmd == .rejects { rejectsResult = nil }
                if cmd == .selfTest { selfTestResult = nil; selfTestLines = [] }
                if cmd == .scan { stopRepeatScan(.scanFailed) }
                if case .historySensor = cmd { stopSaltWatch(.replyFailed) }
                // A wrong CRC-32 follows a complete frame, so the stream stays in step and the next file may follow.
                // Any other error leaves the stream out of step.
                if case .badCrc = e, case .dump = cmd {} else { queue.removeAll() }
                finishDownload(cmd, text: "rejected: \(e)")
                batch?.receive(status: "ERR CORRUPT")
            case (_, let .status(text)):
                message = text
                if case .ack = cmd { message = ConsoleCommand.ackMessage(text) }
                if cmd == .ackAll { message = ConsoleCommand.ackMessage(text) }
                if cmd == .identify {
                    message = ConsoleCommand.identifyMessage(text)
                    identifyResult = message
                }
                if case .slotSwap = cmd { message = ConsoleCommand.slotSwapMessage(text) }
                if cmd == .ntpSync { message = ConsoleCommand.ntpSyncMessage(text) }
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
                if case .frsi = cmd {
                    let result = FrsiReply.statusResult(text) // ERR LOW_DELTA and ERR NO_OUTDOOR are results
                    frsi = result.reply
                    message = result.message
                }
                if cmd == .screen { panelImage = nil } // ERR NO_FRAME must not leave an old image
                if cmd == .rejects { rejectsResult = nil }
                if cmd == .selfTest { selfTestResult = nil; selfTestLines = [] }
                if cmd == .scan { scanResult = nil } // ERR SCAN_FAILED must not leave the rows of an old scan
                if cmd == .scan, text.hasPrefix("ERR") { stopRepeatScan(.scanFailed) }
                if case .historySensor = cmd, text.hasPrefix("ERR") { stopSaltWatch(.replyFailed) }
                batch?.receive(status: text)
                if text.hasPrefix("ERR"), !keepQueue { queue.removeAll() }
                // A failed ACK still reads STATUS, so the row follows the panel (the alert may have cleared).
                if case .ack = cmd, text.hasPrefix("ERR") { queue = [.status] }
                if cmd == .ackAll, text.hasPrefix("ERR") { queue = [.status] }
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
                diagnosticsConfig = nil
                let parsed = try StatusReply.parse(lines: lines)
                status = parsed
                diagnostics = try DiagnosticsReply.parse(status: parsed)
                diagnosticsConfig = config?.values
            case let (.version, .frame(lines)):
                version = nil
                version = try VersionReply.parse(lines: lines)
            case let (.history, .frame(lines)): history = try HistoryReply.parse(lines: lines)
            case let (.historySensor(n), .frame(lines)):
                // The watch reads its own reply; the shared history rows stay as they are.
                saltProgress.record(rows: try HistoryReply.parse(lines: lines), sensor: n)
                if !saltProgress.isRunning { stopSaltWatch(saltProgress.stopReason ?? .timeout) }
            case let (.week, .frame(lines)):
                week = [] // a reply that fails to parse must not leave the old rows
                week = try WeekReply.parse(lines: lines)
            case let (.events, .frame(lines)):
                events = try EventsReply.parse(lines: lines)
                checkWindowNotifications(events)
            case let (.config, .frame(lines)):
                batch?.receiveFrame()
                config = try ConfigReply.parse(lines: lines)
                lastConfig = config
                if batch != nil { draft = [:] } // the panel state replaces the edits
            case let (.rejects, .frame(lines)):
                rejectsResult = nil // a reply that fails to parse must not leave the old rows
                rejectsResult = try RejectsReply.parse(lines: lines)
            case (.rejects, .file): message = "Unexpected file reply"
            case let (.selfTest, .frame(lines)):
                selfTestResult = nil // a reply that fails to parse must not leave an old result
                selfTestLines = []
                selfTestResult = try SelfTestReply.parse(lines: lines)
                selfTestLines = lines
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
                // The assistant reads its own CALIB reply; the shared calibration view must not show it.
                if setup?.pending == cmd { break }
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
            case let (.frsi, .frame(lines)):
                frsi = nil
                frsi = try FrsiReply.parse(lines: lines)
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
            case (.timeSet, .frame), (.beep, .frame), (.pattern, .frame), (.mute, .frame), (.snooze, .frame), (.ack, .frame), (.ackAll, .frame), (.rtcTrim, .frame), (.ntpSync, .frame), (.identify, .frame), (.slotClear, .frame), (.slotSwap, .frame):
                message = "Unexpected frame reply"
            }
        } catch {
            message = "Reply not understood: \(error)"
            if cmd == .scan { stopRepeatScan(.scanFailed) }
            if case .historySensor = cmd { stopSaltWatch(.replyFailed) }
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
        guard let folder = archiveFolder, !month.isEmpty else {
            climateData = ClimateFolder(rows: [], failedFiles: [])
            climateForecastRows = []
            return
        }
        let current = ClimateLog.load(from: folder, month: month)
        climateData = current
        let earlier = [2, 1].compactMap { ClimateLog.earlierMonth(month, by: $0) }.filter(climateMonths.contains)
        climateForecastRows = earlier.flatMap { ClimateLog.load(from: folder, month: $0).rows } + current.rows
    }

    func hideClimateCharts() {
        climateMonths = []
        climateData = nil
        climateForecastRows = []
    }

    private static func loadInitialConfig() -> ConfigReply? {
        let candidates = [
            URL(fileURLWithPath: "docs/config.ini"),
            URL(fileURLWithPath: "../docs/config.ini"),
            URL(fileURLWithPath: "../../docs/config.ini"),
            Bundle.main.url(forResource: "config", withExtension: "ini")
        ].compactMap { $0 }
        for url in candidates {
            if let data = try? Data(contentsOf: url),
               let parsed = try? ConfigBackup.parse(data) {
                return ConfigReply(
                    values: parsed.values,
                    keys: ConfigSchema.entries.map(\.key),
                    source: "FILE",
                    rejectedLines: parsed.rejected.map(\.line)
                )
            }
        }
        return nil
    }
}
