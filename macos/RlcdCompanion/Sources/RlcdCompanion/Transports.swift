import CoreBluetooth
import Foundation
import RlcdProtocol

/// Receives bytes and state changes of a console link. The model owns the reply assembly.
protocol ConsoleLinkDelegate: AnyObject {
    /// Both callbacks arrive on the main queue. `link` names the sender, so the model ignores a late callback of an inactive link.
    func linkDidChange(_ link: ConsoleLink, state: String, connected: Bool)
    func linkDidReceive(_ link: ConsoleLink, _ data: Data)
}

protocol ConsoleLink: AnyObject {
    var delegate: ConsoleLinkDelegate? { get set }
    func send(_ data: Data)
    func close()
}

/// Passive scanner for the telemetry frame and the console bridge. It writes nothing to the panel.
final class BluetoothScanner: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate, ConsoleLink {
    var onFrame: ((TelemetryFrame, Int) -> Void)?
    var onBluetoothState: ((String) -> Void)?
    /// Called when a peripheral advertises the console service.
    var onConsoleAvailable: ((Bool) -> Void)?
    weak var delegate: ConsoleLinkDelegate?

    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var rx: CBCharacteristic?
    private var wantsConsole = false
    private let service = CBUUID(string: ConsoleBridge.serviceUUID)

    override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: .main)
    }

    /// Allows the connection to a console bridge that the scan finds. The user switches it on with a button. No command goes out by itself.
    func setConsoleWanted(_ wanted: Bool) {
        wantsConsole = wanted
        if !wanted { close() }
    }

    func centralManagerDidUpdateState(_ c: CBCentralManager) {
        switch c.state {
        case .poweredOn:
            onBluetoothState?("Bluetooth on")
            c.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
        case .unauthorized: onBluetoothState?("Bluetooth access denied")
        case .poweredOff: onBluetoothState?("Bluetooth off")
        default: onBluetoothState?("Bluetooth unavailable")
        }
        if c.state != .poweredOn { close() }
    }

    func centralManager(_ c: CBCentralManager, didDiscover p: CBPeripheral,
                        advertisementData ad: [String: Any], rssi: NSNumber) {
        if let data = ad[CBAdvertisementDataManufacturerDataKey] as? Data,
           data.count == TelemetryFrame.size, let frame = try? TelemetryFrame.decode(data) {
            onFrame?(frame, rssi.intValue)
        }
        if let uuids = ad[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID], uuids.contains(service) {
            onConsoleAvailable?(true)
            if wantsConsole, peripheral == nil {
                peripheral = p
                p.delegate = self
                c.connect(p)
                delegate?.linkDidChange(self, state: "Connecting to console", connected: false)
            }
        }
    }

    func centralManager(_ c: CBCentralManager, didConnect p: CBPeripheral) {
        guard p === peripheral else { c.cancelPeripheralConnection(p); return }
        p.discoverServices([service])
    }

    func centralManager(_ c: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) {
        guard p === peripheral else { return }
        peripheral = nil
        wantsConsole = false // a new connection needs a new button press
        delegate?.linkDidChange(self, state: "Console connection failed", connected: false)
    }

    func centralManager(_ c: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) {
        guard p === peripheral else { return }
        peripheral = nil
        wantsConsole = false // a new connection needs a new button press
        rx = nil
        delegate?.linkDidChange(self, state: "Console disconnected", connected: false)
        onConsoleAvailable?(false)
    }

    /// Ends a half-open connection and tells the model why. The user then sees "Connect console" again.
    private func fail(_ text: String) {
        close()
        delegate?.linkDidChange(self, state: text, connected: false)
    }

    func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        guard p === peripheral else { return }
        guard error == nil, let s = p.services?.first(where: { $0.uuid == service }) else {
            return fail("Console service not found")
        }
        p.discoverCharacteristics([CBUUID(string: ConsoleBridge.rxUUID), CBUUID(string: ConsoleBridge.txUUID)], for: s)
    }

    func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor s: CBService, error: Error?) {
        guard p === peripheral else { return }
        var tx: CBCharacteristic?
        for ch in s.characteristics ?? [] {
            if ch.uuid == CBUUID(string: ConsoleBridge.txUUID) { tx = ch }
            if ch.uuid == CBUUID(string: ConsoleBridge.rxUUID) { rx = ch }
        }
        guard error == nil, let tx, rx != nil else { return fail("Console characteristics not found") }
        p.setNotifyValue(true, for: tx)
    }

    func peripheral(_ p: CBPeripheral, didUpdateNotificationStateFor ch: CBCharacteristic, error: Error?) {
        guard p === peripheral else { return }
        guard error == nil, ch.isNotifying, rx != nil else { return fail("Console notification failed") }
        delegate?.linkDidChange(self, state: "Console connected (BLE)", connected: true)
    }

    func peripheral(_ p: CBPeripheral, didUpdateValueFor ch: CBCharacteristic, error: Error?) {
        guard p === peripheral else { return }
        if ch.uuid == CBUUID(string: ConsoleBridge.txUUID), let v = ch.value { delegate?.linkDidReceive(self, v) }
    }

    func send(_ data: Data) {
        guard let p = peripheral, let rx else { return }
        let size = max(20, p.maximumWriteValueLength(for: .withResponse))
        var offset = 0
        while offset < data.count {
            let end = min(offset + size, data.count)
            p.writeValue(data.subdata(in: offset..<end), for: rx, type: .withResponse)
            offset = end
        }
    }

    /// Ends the console connection. The scanner reports the change itself, because the disconnect
    /// callback of the dropped peripheral no longer matches `peripheral`.
    func close() {
        wantsConsole = false // a new connection needs a new button press
        guard let p = peripheral else { return }
        central.cancelPeripheralConnection(p)
        peripheral = nil
        rx = nil
        delegate?.linkDidChange(self, state: "Console disconnected", connected: false)
    }
}

/// USB serial fallback on `/dev/cu.usbmodem*`, 115200 baud.
final class SerialLink: ConsoleLink {
    weak var delegate: ConsoleLinkDelegate?
    private var fd: Int32 = -1
    private var source: DispatchSourceRead?

    static func findPort() -> String? {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: "/dev")) ?? []
        return names.filter { $0.hasPrefix("cu.usbmodem") }.sorted().first.map { "/dev/" + $0 }
    }

    /// Opens the port. Returns an error text on failure.
    func open() -> String? {
        close()
        guard let path = Self.findPort() else { return "No /dev/cu.usbmodem* port found" }
        let handle = Darwin.open(path, O_RDWR | O_NOCTTY | O_NONBLOCK)
        guard handle >= 0 else { return "Cannot open \(path)" }
        var t = termios()
        guard tcgetattr(handle, &t) == 0 else { Darwin.close(handle); return "Cannot configure \(path)" }
        cfmakeraw(&t)
        cfsetspeed(&t, speed_t(B115200))
        t.c_cflag |= tcflag_t(CLOCAL | CREAD)
        guard tcsetattr(handle, TCSANOW, &t) == 0 else { Darwin.close(handle); return "Cannot configure \(path)" }
        fd = handle
        let src = DispatchSource.makeReadSource(fileDescriptor: handle, queue: .main)
        src.setEventHandler { [weak self] in
            var buffer = [UInt8](repeating: 0, count: 1024)
            let n = Darwin.read(handle, &buffer, buffer.count)
            if n > 0 {
                self?.delegate?.linkDidReceive(self!, Data(buffer[0..<n]))
            } else if n == 0 || (errno != EAGAIN && errno != EINTR) {
                self?.close()
            }
        }
        src.setCancelHandler { Darwin.close(handle) }
        src.resume()
        source = src
        delegate?.linkDidChange(self, state: "Console connected (USB \(path))", connected: true)
        return nil
    }

    func send(_ data: Data) {
        guard fd >= 0 else { return }
        var offset = 0
        var failed = false
        let deadline = Date().addingTimeInterval(1) // a stalled port must not freeze the main queue
        data.withUnsafeBytes { raw in
            while offset < data.count {
                let n = Darwin.write(fd, raw.baseAddress! + offset, data.count - offset)
                if n > 0 { offset += n } else if n < 0 && (errno == EAGAIN || errno == EINTR) && Date() < deadline { usleep(1000) } else { failed = true; return }
            }
        }
        if failed {
            close()
            delegate?.linkDidChange(self, state: "USB write failed", connected: false)
        }
    }

    func close() {
        guard fd >= 0 else { return }
        source?.cancel() // the cancel handler of the source closes the descriptor
        source = nil
        fd = -1
        delegate?.linkDidChange(self, state: "Console closed", connected: false)
    }
}
