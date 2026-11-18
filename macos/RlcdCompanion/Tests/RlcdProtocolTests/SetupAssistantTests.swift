import Foundation
import Testing
@testable import RlcdProtocol

@Suite struct SetupAssistantTests {
    static let newMac = "AA:BB:CC:DD:EE:09"

    static let scan = ConsoleReply.frame(lines: [
        "BEGIN SCAN", ScanReply.header,
        "AA:BB:CC:DD:EE:01,WOIO,-40,1,21.5,45.0,87",
        "AA:BB:CC:DD:EE:08,METER,-70,-,19.0,50.5,",
        "\(Self.newMac),WOIO,-55,-,20.0,48.0,90",
    ])

    static func config(fixedSlot1: Bool = true) -> ConfigReply {
        var values = ["slot2_mac": "", "slot3_mac": "", "slot4_mac": "", "slot5_mac": ""]
        values["slot1_mac"] = fixedSlot1 ? "AA:BB:CC:DD:EE:01" : ""
        return ConfigReply(values: values)
    }

    static func status(slot2: String = "state=EMPTY", slot2Mac: String? = nil) -> StatusReply {
        var lines = [
            "SENSOR 0 Onboard temp=21.4 rh=45.0 bat=-- rssi=-- age=120 state=OK",
            "SENSOR 1 Bad temp=22.1 rh=48.5 bat=87 rssi=-61 age=14 state=OK mac=AA:BB:CC:DD:EE:01",
            "SENSOR 2 Kueche temp=-- rh=-- bat=-- rssi=-- age=-- \(slot2)" + (slot2Mac.map { " mac=\($0)" } ?? ""),
        ]
        lines.append("POWER src=USB v=4.05 pct=88 alerts=00 muted=0")
        return try! StatusReply.parse(lines: lines)
    }

    static let calib = ConsoleReply.frame(lines: [
        "REFERENCE onboard",
        "SENSOR slot2 hours=12 temp_offset=0.0 rh_offset=0.0 temp_diff=0.4 rh_diff=-2.1",
        "slot2_temp_offset_c=-0.4",
        "slot2_rh_offset_pct=2.1",
    ])

    /// Runs the assistant up to step `verify`.
    static func assigned() -> SetupAssistant {
        var a = SetupAssistant()
        _ = a.beginScan(onUsb: true)
        a.receive(scan)
        a.choose(mac: Self.newMac, name: "Kueche")
        _ = a.beginAssign(status: status(), config: config())
        a.receive(.status("OK SET slot2_mac"))
        _ = a.nextCommand()
        a.receive(.status("OK SET slot2_name"))
        _ = a.nextCommand()
        a.receive(.frame(lines: ["slot2_mac=\(Self.newMac)", "slot2_name=KUECHE"]))
        return a
    }

    @Test func runsAllStepsInOrder() {
        var a = SetupAssistant()
        #expect(a.step == .scan)
        #expect(a.beginScan(onUsb: true) == .scan)
        a.receive(Self.scan)
        #expect(a.step == .choose)
        #expect(a.choose(mac: Self.newMac, name: "Kueche"))
        #expect(a.step == .assign)

        #expect(a.beginAssign(status: Self.status(), config: Self.config()) == .set(key: "slot2_mac", value: Self.newMac))
        #expect(a.slot == 2)
        a.receive(.status("OK SET slot2_mac"))
        #expect(a.nextCommand() == .set(key: "slot2_name", value: "KUECHE"))
        a.receive(.status("OK SET slot2_name"))
        #expect(a.nextCommand() == .config)
        a.receive(.frame(lines: ["slot2_mac=\(Self.newMac)", "slot2_name=KUECHE"]))
        #expect(a.step == .verify)
        #expect(a.nextCommand() == nil)

        #expect(a.checkReception() == .status)
        a.receive(.frame(lines: Self.statusLines(slot2: "state=OK", mac: Self.newMac)))
        #expect(a.step == .calibrate)

        #expect(a.beginCalibrate(reference: 0) == .calib(0))
        a.receive(Self.calib)
        #expect(a.selection == ["slot2_temp_offset_c", "slot2_rh_offset_pct"])
        #expect(a.beginApply() == .set(key: "slot2_temp_offset_c", value: "-0.4"))
        a.receive(.status("OK SET slot2_temp_offset_c"))
        #expect(a.nextCommand() == .set(key: "slot2_rh_offset_pct", value: "2.1"))
        a.receive(.status("OK SET slot2_rh_offset_pct"))
        #expect(a.nextCommand() == .config)
        a.receive(.frame(lines: ["slot2_temp_offset_c=-0.4"]))
        #expect(a.step == .done)
        #expect(a.stop == nil)
    }

    static func statusLines(slot2: String, mac: String?) -> [String] {
        [
            "SENSOR 0 Onboard temp=21.4 rh=45.0 bat=-- rssi=-- age=120 state=OK",
            "SENSOR 2 Kueche temp=20.0 rh=48.0 bat=90 rssi=-55 age=3 \(slot2)" + (mac.map { " mac=\($0)" } ?? ""),
            "POWER src=USB v=4.05 pct=88 alerts=00 muted=0",
        ]
    }

    @Test func chooseListHoldsOnlyRowsWithoutSlot() {
        var a = SetupAssistant()
        _ = a.beginScan(onUsb: true)
        a.receive(Self.scan)
        #expect(a.candidates.map(\.mac) == [Self.newMac, "AA:BB:CC:DD:EE:08"]) // sorted by RSSI
        #expect(!a.candidates.contains { $0.slot != nil })
        #expect(!a.choose(mac: "AA:BB:CC:DD:EE:01"))
        #expect(a.step == .choose)
    }

    @Test func scanNeedsUsbLink() {
        var a = SetupAssistant()
        #expect(a.beginScan(onUsb: false) == nil)
        #expect(a.pending == nil)
        #expect(a.notice != nil)
        #expect(!a.isStopped)
    }

    @Test func assignPicksFirstSlotWithoutFixedMacAndReading() {
        var a = SetupAssistant()
        _ = a.beginScan(onUsb: true)
        a.receive(Self.scan)
        a.choose(mac: Self.newMac)
        // Slot 1 has a fixed MAC, slot 2 holds a reading, so slot 3 is the first free slot.
        let busy = Self.status(slot2: "state=OK", slot2Mac: "AA:BB:CC:DD:EE:02")
        #expect(a.beginAssign(status: busy, config: Self.config()) == .set(key: "slot3_mac", value: Self.newMac))
        #expect(a.slot == 3)
    }

    @Test func fixedMacErrorStopsBatchBeforeConfig() {
        var a = SetupAssistant()
        _ = a.beginScan(onUsb: true)
        a.receive(Self.scan)
        a.choose(mac: Self.newMac, name: "Kueche")
        _ = a.beginAssign(status: Self.status(), config: Self.config())
        a.receive(.status("ERR FIXED_MAC"))
        #expect(a.stop == .reply("ERR FIXED_MAC"))
        #expect(a.nextCommand() == nil) // neither the name SET nor CONFIG
        #expect(a.checkReception() == nil)
        #expect(a.step == .assign)
    }

    @Test func otherMacKeepsStepVerify() {
        var a = Self.assigned()
        #expect(a.step == .verify)
        #expect(a.checkReception() == .status)
        a.receive(.frame(lines: Self.statusLines(slot2: "state=OK", mac: "AA:BB:CC:DD:EE:77")))
        #expect(a.step == .verify)
        #expect(a.notice != nil)
        #expect(!a.isStopped)
        #expect(a.checkReception() == .status) // each click sends one STATUS
        a.receive(.frame(lines: Self.statusLines(slot2: "state=STALE", mac: Self.newMac)))
        #expect(a.step == .verify)
    }

    @Test func lostLinkEndsAssistantWithFailureText() {
        var a = Self.assigned()
        _ = a.checkReception()
        a.linkLost(LinkFailure.commandTimeout.reason)
        #expect(a.stop == .linkLost("Console reply timed out (10 s)"))
        #expect(a.stop?.text == "Link lost: Console reply timed out (10 s)")
        #expect(a.pending == nil)
        #expect(a.checkReception() == nil)
        #expect(a.step == .verify)
    }

    @Test func errReplyStopsAnyStep() {
        var a = Self.assigned()
        _ = a.checkReception()
        a.receive(.status("ERR READ_ONLY"))
        #expect(a.stop?.text == "ERR READ_ONLY")
        #expect(a.checkReception() == nil)
    }

    @Test func clockStopsAfterMaxIdleTime() {
        var t = Date(timeIntervalSince1970: 1_000)
        var a = SetupAssistant(now: { t })
        t = t.addingTimeInterval(SetupAssistant.maxIdleSeconds + 1)
        #expect(a.beginScan(onUsb: true) == nil)
        #expect(a.stop == .timeout)
    }

    @Test func noFreeSlotGivesNoCommand() {
        var a = SetupAssistant()
        _ = a.beginScan(onUsb: true)
        a.receive(Self.scan)
        a.choose(mac: Self.newMac)
        var values: [String: String] = [:]
        for n in SlotAssignment.slots { values["slot\(n)_mac"] = "AA:BB:CC:00:00:\(String(format: "%02X", n))" }
        #expect(a.beginAssign(status: Self.status(), config: ConfigReply(values: values)) == nil)
        #expect(a.notice == "No free slot")
    }

    @Test func calibrationSelectsOnlyKeysOfTheNewSlot() {
        var a = Self.assigned()
        _ = a.checkReception()
        a.receive(.frame(lines: Self.statusLines(slot2: "state=OK", mac: Self.newMac)))
        _ = a.beginCalibrate(reference: 0)
        a.receive(.frame(lines: [
            "REFERENCE onboard",
            "slot2_temp_offset_c=-0.4",
            "slot3_temp_offset_c=-0.3",
        ]))
        #expect(a.selection == ["slot2_temp_offset_c"])
        a.toggle(proposal: "slot3_temp_offset_c")
        #expect(a.selection == ["slot2_temp_offset_c", "slot3_temp_offset_c"])
    }

    @Test func errOnNameSetStopsBeforeConfig() {
        var a = SetupAssistant()
        _ = a.beginScan(onUsb: true)
        a.receive(Self.scan)
        a.choose(mac: Self.newMac, name: "Kueche")
        _ = a.beginAssign(status: Self.status(), config: Self.config())
        a.receive(.status("OK SET slot2_mac"))
        #expect(a.nextCommand() == .set(key: "slot2_name", value: "KUECHE"))
        a.receive(.status("ERR BAD_VALUE"))
        #expect(a.stop == .reply("ERR BAD_VALUE"))
        #expect(a.nextCommand() == nil)
    }

    @Test func errOnConfigStopsAssistant() {
        var a = SetupAssistant()
        _ = a.beginScan(onUsb: true)
        a.receive(Self.scan)
        a.choose(mac: Self.newMac)
        _ = a.beginAssign(status: Self.status(), config: Self.config())
        a.receive(.status("OK SET slot2_mac"))
        #expect(a.nextCommand() == .config)
        a.receive(.status("ERR BUSY"))
        #expect(a.stop == .reply("ERR BUSY"))
        #expect(a.step == .assign)
    }

    @Test func lostLinkInMiddleOfBatchEndsAssistant() {
        var a = SetupAssistant()
        _ = a.beginScan(onUsb: true)
        a.receive(Self.scan)
        a.choose(mac: Self.newMac, name: "Kueche")
        _ = a.beginAssign(status: Self.status(), config: Self.config())
        a.linkLost("USB serial closed")
        #expect(a.stop?.text == "Link lost: USB serial closed")
        #expect(a.nextCommand() == nil)
    }

    @Test func duplicateMacAndBadNameGiveNoCommand() {
        var a = SetupAssistant()
        _ = a.beginScan(onUsb: true)
        a.receive(Self.scan)
        #expect(!a.choose(mac: Self.newMac, name: "Name with space"))
        #expect(a.step == .choose)
        a.choose(mac: Self.newMac)
        var values = Self.config().values
        values["slot3_mac"] = Self.newMac
        #expect(a.beginAssign(status: Self.status(), config: ConfigReply(values: values)) == nil)
        #expect(a.notice?.contains("already holds") == true)
        #expect(!a.isStopped)
    }

    @Test func disabledSlotIsNotFree() {
        var values = Self.config().values
        values["slot2_enabled"] = "0"
        #expect(SetupAssistant.freeSlot(status: Self.status(), config: ConfigReply(values: values)) == 3)
    }

    @Test func calibrateRejectsOwnSlotAndEmptyApply() {
        var a = Self.assigned()
        _ = a.checkReception()
        a.receive(.frame(lines: Self.statusLines(slot2: "state=OK", mac: Self.newMac)))
        #expect(a.beginCalibrate(reference: 2) == nil) // the new sensor is no reference for itself
        #expect(a.notice != nil)
        #expect(a.beginApply() == nil) // no proposal selected
        #expect(a.notice == "No proposal selected")
    }

    @Test func errOnApplyStopsBeforeConfig() {
        var a = Self.assigned()
        _ = a.checkReception()
        a.receive(.frame(lines: Self.statusLines(slot2: "state=OK", mac: Self.newMac)))
        _ = a.beginCalibrate(reference: 0)
        a.receive(Self.calib)
        #expect(a.beginApply() != nil)
        a.receive(.status("ERR READ_ONLY"))
        #expect(a.stop == .reply("ERR READ_ONLY"))
        #expect(a.nextCommand() == nil)
    }

    @Test func finishEndsAfterCalibrateStepOnly() {
        var a = Self.assigned()
        a.finish() // still in verify
        #expect(a.step == .verify)
        _ = a.checkReception()
        a.receive(.frame(lines: Self.statusLines(slot2: "state=OK", mac: Self.newMac)))
        a.finish()
        #expect(a.step == .done)
    }

    @Test func commandNotSentEndsAssistant() {
        var a = SetupAssistant()
        _ = a.beginScan(onUsb: true)
        a.commandNotSent()
        #expect(a.stop?.text == "Link lost: command not sent")
        #expect(a.pending == nil)
    }

    @Test func idleClockRestartsAtEachAction() {
        var t = Date(timeIntervalSince1970: 1_000)
        var a = SetupAssistant(now: { t })
        t = t.addingTimeInterval(SetupAssistant.maxIdleSeconds - 1)
        #expect(a.beginScan(onUsb: true) == .scan)
        a.receive(Self.scan)
        t = t.addingTimeInterval(SetupAssistant.maxIdleSeconds - 1) // total time is above the limit
        #expect(a.choose(mac: Self.newMac))
        #expect(!a.isStopped)
    }
}
