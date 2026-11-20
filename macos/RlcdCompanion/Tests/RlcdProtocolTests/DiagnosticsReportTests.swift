import Foundation
import Testing
@testable import RlcdProtocol

private func lines(_ text: String) -> [String] { text.split(separator: "\n").map(String.init) }

private let reportConfigText = """
onboard_name=KUECHE
slot1_name=Wohnzimmer
slot1_mac=AA:BB:CC:DD:EE:01
temp_unit=C
ble_console=2
SOURCE SD
REJECTED line=7

"""

private func rejectsFixture() -> [String] {
    let url = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("tests/fixtures/rejects_reply_sample.txt")
    return ((try? String(contentsOf: url, encoding: .utf8)) ?? "").split(separator: "\n").map(String.init)
}

private func sections(
    events: Result<[String], DiagnosticsReport.Failure>? = nil,
    rejects: Result<[String], DiagnosticsReport.Failure>? = nil
) -> [DiagnosticsReport.Section] {
    [
        .init(command: "VERSION", result: .success(["VERSION fw=1.0.0 git=abc core=3.3.12 state=49 built=today"])),
        .init(command: "STATUS", result: .success(lines(statusText) + ["CRC32 8e7ee74b"])),
        .init(command: "CONFIG", result: .success(lines(reportConfigText))),
        .init(command: "EVENTS", result: events ?? .success(lines(eventsText))),
        .init(command: "REJECTS", result: rejects ?? .success(rejectsFixture())),
    ]
}

@Suite struct DiagnosticsReportTests {
    @Test func sectionsFollowTheCommandOrder() {
        let text = DiagnosticsReport.build(appVersion: "1.2", link: .usb, includeNames: false, sections: sections())
        let headings = lines(text).filter { $0.hasPrefix("## ") }
        #expect(headings == ["## VERSION", "## STATUS", "## CONFIG", "## EVENTS", "## REJECTS"])
        #expect(lines(text).first?.contains("1.2") == true)
        #expect(lines(text).contains("Link: USB"))
    }

    @Test func wakeCycleLineIsInTheReport() {
        let text = DiagnosticsReport.build(
            appVersion: "1.2", link: .usb, includeNames: false,
            sections: [.init(command: "STATUS", result: .success(["WAKE cause=TIMER boots=1234 battery_interval_s=600"]))])
        #expect(lines(text).contains("WAKE cause=TIMER boots=1234 battery_interval_s=600"))
        #expect(lines(text).contains("Wake cycle: TIMER, boots 1234, battery interval 600 s"))
    }

    @Test func noCrcLines() {
        let text = DiagnosticsReport.build(appVersion: "1.2", link: .ble, includeNames: false, sections: sections())
        #expect(!text.contains("CRC32"))
        #expect(lines(text).contains("Link: BLE"))
    }

    @Test func namesAndMacsAreHiddenByDefault() {
        let text = DiagnosticsReport.build(appVersion: "1.2", link: .usb, includeNames: false, sections: sections())
        #expect(text.contains("slot1_mac=<hidden>"))
        #expect(text.contains("onboard_name=<hidden>"))
        #expect(text.contains("slot1_name=<hidden>"))
        #expect(!text.contains("AA:BB:CC:DD:EE:01"))
        #expect(!text.contains("KUECHE"))
        #expect(text.contains("temp_unit=C"))
        #expect(text.contains("ble_console=2"))
    }

    @Test func sensorNamesAndBleMacAreHiddenByDefault() {
        let text = DiagnosticsReport.build(appVersion: "1.2", link: .usb, includeNames: false, sections: sections())
        #expect(!text.contains("Wohnzimmer"))
        #expect(!text.contains("Keller"))
        #expect(!text.contains("AA:BB:CC:DD:EE:FF"))
        #expect(text.contains("SENSOR 1 <hidden> temp=22.1 rh=48.5"))
        #expect(text.contains("last=<hidden>"))
    }

    @Test func sensorNamesAndBleMacStayWithIncludeNames() {
        let text = DiagnosticsReport.build(appVersion: "1.2", link: .usb, includeNames: true, sections: sections())
        #expect(text.contains("SENSOR 1 Wohnzimmer temp=22.1"))
        #expect(text.contains("last=AA:BB:CC:DD:EE:FF"))
    }

    @Test func everyNameAndMacKeyIsHidden() {
        let keys = ConfigSchema.entries.filter { $0.kind == .name || $0.kind == .mac }.map(\.key)
        #expect(!keys.isEmpty)
        let text = DiagnosticsReport.build(
            appVersion: "1.2", link: .usb, includeNames: false,
            sections: [.init(command: "CONFIG", result: .success(keys.map { "\($0)=SECRET" }))])
        #expect(!text.contains("SECRET"))
        for k in keys { #expect(text.contains("\(k)=<hidden>")) }
    }

    @Test func sensorNamesWithSpacesAndUnknownShapesAreHidden() {
        let text = DiagnosticsReport.build(
            appVersion: "1.2", link: .usb, includeNames: false,
            sections: [.init(command: "STATUS", result: .success([
                "SENSOR 2 Bad Zimmer temp=20.0 rh=50.0 bat=-- rssi=-- age=1 state=OK",
                "SENSOR 3 Keller future=1",
                "BLE rejected=0 last=-- woio=0 meter=0 bthome=0 atc=0",
            ]))])
        #expect(!text.contains("Zimmer"))
        #expect(!text.contains("Keller"))
        #expect(text.contains("SENSOR 2 <hidden> temp=20.0"))
        #expect(text.contains("last=-- woio=0"))
    }

    @Test func includeNamesKeepsTheValues() {
        let text = DiagnosticsReport.build(appVersion: "1.2", link: .usb, includeNames: true, sections: sections())
        #expect(text.contains("slot1_mac=AA:BB:CC:DD:EE:01"))
        #expect(text.contains("onboard_name=KUECHE"))
    }

    @Test func failedEventsKeepsTheOtherSections() {
        let text = DiagnosticsReport.build(
            appVersion: "1.2", link: .usb, includeNames: false,
            sections: sections(events: .failure(.init(reason: "no reply"))))
        let all = lines(text)
        let i = all.firstIndex(of: "## EVENTS")
        #expect(i != nil)
        #expect(all[(i ?? 0) + 1] == "ERROR no reply")
        #expect(all.contains("## VERSION"))
        #expect(all.contains("## STATUS"))
        #expect(all.contains("## CONFIG"))
        #expect(text.contains("slot1_mac=<hidden>"))
    }

    @Test func commandsEndWithRejects() {
        #expect(DiagnosticsReport.commands.map(\.line) == ["VERSION", "STATUS", "CONFIG", "EVENTS", "REJECTS"])
        #expect(!rejectsFixture().isEmpty)
    }

    @Test func rejectsFollowEventsAndHideMacs() {
        let text = DiagnosticsReport.build(appVersion: "1.2", link: .usb, includeNames: false, sections: sections())
        let all = lines(text)
        let e = all.firstIndex(of: "## EVENTS")
        let r = all.firstIndex(of: "## REJECTS")
        #expect(e != nil && r != nil && (e ?? 0) < (r ?? 0))
        #expect(!text.contains("AA:00:00:00:00"))
        #expect(all.contains("<hidden>,METER,FULL,2"))
        #expect(all.contains("<hidden>,THERMOPRO,DISABLED,255"))
        #expect(all.contains("mac,format,reason,count"))
        #expect(all.contains("DROPPED,1"))
        #expect(!text.contains("33ec9f33"))
    }

    @Test func rejectsKeepMacsWithIncludeNames() {
        let text = DiagnosticsReport.build(appVersion: "1.2", link: .usb, includeNames: true, sections: sections())
        #expect(text.contains("AA:00:00:00:00:13,METER,FULL,2"))
        #expect(text.contains("AA:00:00:00:00:01,WOIO,JUMP,1"))
        #expect(!text.contains("CRC32"))
    }

    @Test func failedRejectsKeepsTheOtherSections() {
        let text = DiagnosticsReport.build(
            appVersion: "1.2", link: .usb, includeNames: false,
            sections: sections(rejects: .failure(.init(reason: "ERR UNKNOWN"))))
        let all = lines(text)
        let i = all.firstIndex(of: "## REJECTS")
        #expect(i != nil)
        #expect(all[(i ?? 0) + 1] == "ERROR ERR UNKNOWN")
        #expect(all.contains("## EVENTS"))
        #expect(all.contains("## CONFIG"))
        #expect(text.contains("slot1_mac=<hidden>"))
    }
}
