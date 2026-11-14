import Foundation
import Observation
import RlcdProtocol

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
    var events: [EventRow] = []
    var config: ConfigReply?
    var message: String?
    /// Wall clock for the age of the last frame. A 30 s tick refreshes it, so the menu bar shows `--` for a silent panel.
    var now = Date()
    /// Peripherals that sent a valid frame or the console service, strongest signal first.
    var discovered = DiscoveryList()
    /// The pinned panel, `nil` without a pin.
    var pinnedPanel: UUID?
    var setKey = ""
    var setValue = ""
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
        scanner.onBluetoothState = { [weak self] in self?.bluetoothState = $0 }
        scanner.onConsoleAvailable = { [weak self] in self?.consoleAvailable = $0 }
        scanner.onFrame = { [weak self] f, r in
            self?.frame = f
            self?.frameDate = Date()
            self?.now = Date()
            self?.rssi = r
        }
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
    }

    func disconnectConsole() {
        resetLink()
        consoleConnected = false
        consoleState = "Console not connected"
    }

    func selectTransport(_ t: Transport) {
        transport = t
        resetLink()
        consoleConnected = false
        consoleState = "Console not connected"
    }

    private func resetLink() {
        scanner.setConsoleWanted(false)
        serial.close()
        config = nil // the ble_console level of the old link must not gate SET on the new link
        assembler.reset()
        abandonCommands()
    }

    /// Drops the running command, the queue, the half-received reply and the timeout of the old link.
    private func abandonCommands() {
        if transport == .bluetooth { scanner.commandDidFinish() }
        timeout?.cancel()
        timeout = nil
        pendingCommand = nil
        queue.removeAll()
        assembler.reset()
    }

    /// Sends the four read commands one after the other. Runs only on the user's button press.
    func refresh() {
        guard consoleConnected, pendingCommand == nil, queue.isEmpty else { return }
        message = nil
        queue = [.status, .history, .events, .config]
        sendNext()
    }

    func set(key: String, value: String) {
        guard pendingCommand == nil, queue.isEmpty else { message = "Wait for the running command"; return }
        guard canSet else { message = "SET needs ble_console=2 or the USB serial port"; return }
        let cmd = ConsoleCommand.set(key: key, value: value)
        guard cmd.line != nil else { message = "Key or value not valid"; return }
        message = nil
        queue = [cmd, .config]
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
            try? await Task.sleep(for: .seconds(10))
            guard !Task.isCancelled else { return }
            guard let self else { return }
            message = "No reply to \(cmd.line ?? "command")"
            pendingCommand = nil
            queue.removeAll()
            assembler.reset() // a late reply must not reach the next command
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
                config = nil // the ble_console level of a lost link must not gate SET on the next link
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
        defer { sendNext() }
        do {
            switch (cmd, reply) {
            case (_, let .corrupt(e)):
                message = "Reply rejected: \(e)"
                queue.removeAll()
            case (_, let .status(text)):
                message = text
                if text.hasPrefix("ERR") { queue.removeAll() }
            case let (.status, .frame(lines)): status = try StatusReply.parse(lines: lines)
            case let (.history, .frame(lines)): history = try HistoryReply.parse(lines: lines)
            case let (.events, .frame(lines)): events = try EventsReply.parse(lines: lines)
            case let (.config, .frame(lines)): config = try ConfigReply.parse(lines: lines)
            case (.set, .frame): break
            }
        } catch {
            message = "Reply not understood: \(error)"
        }
    }
}
