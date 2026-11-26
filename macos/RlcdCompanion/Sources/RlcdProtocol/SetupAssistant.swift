import Foundation

/// Guided setup of one new sensor: scan, choose, assign, verify, calibrate.
///
/// A pure state machine with an injected clock. It owns no timer and no link. The app calls one action method
/// for each user click and sends the returned command. `receive` takes the reply of that command. After a reply
/// in the `assign` step and in the apply batch of the `calibrate` step, `nextCommand` gives the next command of the
/// same user action. The first `ERR` reply or a lost link stops the assistant. A stopped assistant sends no
/// command and does not undo a `SET` that the panel already applied.
public struct SetupAssistant {
    public enum Step: Int, CaseIterable, Sendable { case scan, choose, assign, verify, calibrate, done }

    public enum Stop: Equatable, Sendable {
        /// The first `ERR` reply, or a reply that the assistant cannot use.
        case reply(String)
        case linkLost(String)
        case timeout

        public var text: String {
            switch self {
            case let .reply(text): return text
            case let .linkLost(reason): return "Link lost: \(reason)"
            case .timeout: return "Setup timed out"
            }
        }
    }

    /// The assistant stops after this idle time between two user actions.
    public static let maxIdleSeconds: TimeInterval = 1800

    public private(set) var step: Step = .scan
    public private(set) var stop: Stop?
    /// Hint for the user, `nil` after an action without a hint. A hint is not a stop.
    public private(set) var notice: String?
    public private(set) var scan: ScanReply?
    public private(set) var chosenMac: String?
    public private(set) var chosenName = ""
    /// Registry slot 1 to 10 that the assign step picked.
    public private(set) var slot: Int?
    public private(set) var config: ConfigReply?
    public private(set) var status: StatusReply?
    public private(set) var calibration: CalibrationReply?
    /// Proposal keys that the user selected. A new `CALIB` reply selects the keys of the new slot only.
    public private(set) var selection: Set<String> = []
    /// The command that waits for a reply.
    public private(set) var pending: ConsoleCommand?

    private var batch: ConfigBatch?
    /// True while the batch of the outdoor or door proposal runs: the assistant stays in step `calibrate` after its `CONFIG`.
    private var keepStepBatch = false
    private let now: () -> Date
    private var lastActionAt: Date

    public init(now: @escaping () -> Date = Date.init) {
        self.now = now
        lastActionAt = now()
    }

    public var isStopped: Bool { stop != nil }

    /// Rows of the last scan that hold no slot, strongest signal first.
    public var candidates: [ScanRow] { (scan?.sortedByRssi ?? []).filter { $0.slot == nil } }

    // MARK: User actions

    /// `SCAN`, USB link only. Allowed in the steps `scan` and `choose` (a new scan).
    public mutating func beginScan(onUsb: Bool) -> ConsoleCommand? {
        guard begin(), step == .scan || step == .choose else { return nil }
        guard SlotAssignment.scanAllowed(onUsb: onUsb) else { notice = "SCAN needs the USB serial link"; return nil }
        return send(.scan)
    }

    /// Takes one sensor of the `choose` list and its optional name. No command.
    @discardableResult
    public mutating func choose(mac: String, name: String = "") -> Bool {
        guard begin(), step == .choose, pending == nil else { return false }
        guard let row = candidates.first(where: { $0.mac.caseInsensitiveCompare(mac) == .orderedSame }) else {
            notice = "Choose a sensor of the list"
            return false
        }
        guard SlotAssignment.batch(slot: 1, mac: row.mac, name: name) != nil else {
            notice = "Name not valid (up to \(SlotAssignment.maxNameChars) characters: A-Z, 0-9, -)"
            return false
        }
        chosenMac = row.mac.uppercased()
        chosenName = name.uppercased()
        step = .assign
        return true
    }

    /// Picks the first free slot and starts `SET slotN_mac`, `SET slotN_name`, then `CONFIG`.
    /// `status` and `config` are the last replies of the panel; both must be known.
    public mutating func beginAssign(status: StatusReply?, config: ConfigReply?) -> ConsoleCommand? {
        guard begin(), step == .assign, pending == nil, batch == nil, let mac = chosenMac else { return nil }
        guard let status, let config else { notice = "Read STATUS and CONFIG first"; return nil }
        guard let slot = Self.freeSlot(status: status, config: config) else { notice = "No free slot"; return nil }
        if let warning = SlotAssignment.warning(slot: slot, mac: mac, config: config) { notice = warning; return nil }
        guard let commands = SlotAssignment.batch(slot: slot, mac: mac, name: chosenName) else {
            notice = "Slot, MAC or name not valid"
            return nil
        }
        self.status = status
        self.config = config
        self.slot = slot
        batch = ConfigBatch(commands: commands)
        return nextCommand()
    }

    /// `STATUS`, on each click of `Check reception`.
    public mutating func checkReception() -> ConsoleCommand? {
        guard begin(), step == .verify else { return nil }
        return send(.status)
    }

    /// `CALIB <reference>`: 0 is the onboard sensor, 1 to 10 a slot other than the new one.
    public mutating func beginCalibrate(reference: Int) -> ConsoleCommand? {
        guard begin(), step == .calibrate, batch == nil else { return nil }
        guard ConsoleCommand.calib(reference).line != nil, reference != slot else {
            notice = "Choose another reference"
            return nil
        }
        calibration = nil
        selection = []
        return send(.calib(reference))
    }

    public mutating func toggle(proposal key: String) {
        guard begin(), step == .calibrate, pending == nil, calibration?.proposals.contains(where: { $0.key == key }) == true else { return }
        if selection.contains(key) { selection.remove(key) } else { selection.insert(key) }
    }

    /// Changes of the selected proposals against the last `CONFIG` reply.
    public var calibrationChanges: [ConfigChange] {
        calibration?.changes(selecting: selection, current: config?.values) ?? []
    }

    /// Sends the selected proposals through `ConfigBatch` (`SET` commands, then `CONFIG`).
    /// `config` is the newest `CONFIG` reply of the app; the assistant keeps it for the diff.
    public mutating func beginApply(config current: ConfigReply? = nil) -> ConsoleCommand? {
        guard begin(), step == .calibrate, pending == nil, batch == nil else { return nil }
        if let current { config = current }
        let changes = calibrationChanges
        guard !changes.isEmpty else { notice = "No proposal selected"; return nil }
        batch = ConfigBatch(changes: changes)
        return nextCommand()
    }

    /// Proposal for `outdoor_slot` from the `WEEK` reply, `nil` without a clear candidate.
    public func outdoorProposal(week: [WeekRow], config current: ConfigReply?) -> OutdoorProposal? {
        guard step == .calibrate else { return nil }
        return OutdoorProposal.propose(week: week, config: current ?? config)
    }

    /// Sends `SET outdoor_slot=<N>` through `ConfigBatch`, then `CONFIG`.
    public mutating func beginApplyOutdoor(week: [WeekRow], config current: ConfigReply? = nil) -> ConsoleCommand? {
        guard begin(), step == .calibrate, pending == nil, batch == nil else { return nil }
        if let current { config = current }
        guard let proposal = outdoorProposal(week: week, config: config) else { notice = "No outdoor proposal"; return nil }
        let change = ConfigChange(key: "outdoor_slot", old: config?.values["outdoor_slot"] ?? "", new: String(proposal.slot))
        batch = ConfigBatch(changes: [change])
        keepStepBatch = true
        return nextCommand()
    }

    /// Proposal for `door_ref_slot` from the `WEEK` reply, `nil` without a clear candidate.
    public func doorProposal(week: [WeekRow], config current: ConfigReply?) -> DoorProposal? {
        guard step == .calibrate else { return nil }
        return DoorProposal.propose(week: week, config: current ?? config)
    }

    /// Sends `SET door_ref_slot=<N>` through `ConfigBatch`, then `CONFIG`.
    public mutating func beginApplyDoor(week: [WeekRow], config current: ConfigReply? = nil) -> ConsoleCommand? {
        guard begin(), step == .calibrate, pending == nil, batch == nil else { return nil }
        if let current { config = current }
        guard let proposal = doorProposal(week: week, config: config) else { notice = "No door proposal"; return nil }
        let change = ConfigChange(key: "door_ref_slot", old: config?.values["door_ref_slot"] ?? "", new: String(proposal.slot))
        batch = ConfigBatch(changes: [change])
        keepStepBatch = true
        return nextCommand()
    }

    /// Ends the assistant without a calibration.
    public mutating func finish() {
        guard begin(), step == .calibrate, pending == nil, batch == nil else { return }
        step = .done
    }

    // MARK: Replies and link

    /// The next command of a running batch, `nil` when the batch is done or the assistant waits for the user.
    public mutating func nextCommand() -> ConsoleCommand? {
        guard stop == nil, pending == nil, var b = batch else { return nil }
        let command = b.next()
        batch = b
        pending = command
        return command
    }

    /// Takes the reply of the pending command. The first `ERR` stops the assistant before the next command.
    public mutating func receive(_ reply: ConsoleReply) {
        guard stop == nil, let command = pending else { return }
        pending = nil
        switch reply {
        case let .corrupt(error): fail("Reply rejected: \(error)")
        case .file: fail("Unexpected file reply")
        case let .status(text):
            if text.hasPrefix("ERR") { fail(text); return }
            guard case .set = command else { fail("Unexpected reply: \(text)"); return }
            batch?.receive(status: text)
        case let .frame(lines):
            do { try receive(frame: lines, for: command) } catch { fail("Reply not understood: \(error)") }
        }
    }

    /// A lost link, a timeout or a reset ends the assistant. The panel state stays as it is.
    public mutating func linkLost(_ reason: String) {
        guard stop == nil, step != .done else { return }
        stop = .linkLost(reason)
        pending = nil
        batch = nil
        keepStepBatch = false
    }

    /// The command could not go out (no console line). Ends the assistant, so it does not wait for a reply.
    public mutating func commandNotSent() { linkLost("command not sent") }

    // MARK: Slot choice

    /// The first slot without a fixed MAC, without a reading and not disabled (`slotN_enabled=0`).
    public static func freeSlot(status: StatusReply, config: ConfigReply) -> Int? {
        SlotAssignment.slots.first { n in
            guard config.mac(forSlot: n) == nil, SlotAssignment.isEnabled(slot: n, config: config) else { return false }
            guard let sensor = status.sensors.first(where: { $0.index == n }) else { return true }
            return sensor.state == "EMPTY"
        }
    }

    // MARK: Private

    private mutating func begin() -> Bool {
        notice = nil
        guard stop == nil else { return false }
        let current = now()
        if current.timeIntervalSince(lastActionAt) > Self.maxIdleSeconds {
            stop = .timeout
            pending = nil
            batch = nil
            keepStepBatch = false
            return false
        }
        lastActionAt = current
        return pending == nil
    }

    private mutating func send(_ command: ConsoleCommand) -> ConsoleCommand {
        pending = command
        return command
    }

    private mutating func fail(_ text: String) {
        stop = .reply(text)
        batch = nil
        keepStepBatch = false
    }

    private mutating func receive(frame lines: [String], for command: ConsoleCommand) throws {
        switch command {
        case .scan:
            scan = nil
            scan = try ScanReply.parse(lines: lines)
            step = .choose
            if candidates.isEmpty { notice = "No new sensor found. Scan again." }
        case .config:
            config = try ConfigReply.parse(lines: lines)
            batch?.receiveFrame()
            guard batch?.isFinished == true else { return }
            batch = nil
            if keepStepBatch { keepStepBatch = false; return }
            step = step == .assign ? .verify : .done
        case .status:
            let parsed = try StatusReply.parse(lines: lines)
            status = parsed
            verify(parsed)
        case .calib:
            let parsed = try CalibrationReply.parse(lines: lines)
            calibration = parsed
            // Select only the offsets of the new sensor. The user may add the other proposals.
            let prefix = "slot\(slot ?? 0)_"
            selection = Set(parsed.proposals.map(\.key).filter { $0.hasPrefix(prefix) })
        default:
            fail("Unexpected frame reply")
        }
    }

    /// Passes when the `SENSOR` line of the slot has the chosen `mac=` and `state=OK`.
    private mutating func verify(_ status: StatusReply) {
        guard step == .verify, let slot, let mac = chosenMac else { return }
        let sensor = status.sensors.first { $0.index == slot }
        if let sensor, sensor.state == "OK", sensor.mac?.uppercased() == mac {
            step = .calibrate
        } else {
            notice = "Slot \(slot) waits: state=\(sensor?.state ?? "--") mac=\(sensor?.mac ?? "--")"
        }
    }
}
