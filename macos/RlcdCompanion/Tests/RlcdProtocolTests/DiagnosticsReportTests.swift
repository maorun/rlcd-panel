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

private func sections(events: Result<[String], DiagnosticsReport.Failure>? = nil) -> [DiagnosticsReport.Section] {
    [
        .init(command: "VERSION", result: .success(["VERSION fw=1.0.0 git=abc core=3.3.12 state=49 built=today"])),
        .init(command: "STATUS", result: .success(lines(statusText) + ["CRC32 8e7ee74b"])),
        .init(command: "CONFIG", result: .success(lines(reportConfigText))),
        .init(command: "EVENTS", result: events ?? .success(lines(eventsText))),
    ]
}

@Suite struct DiagnosticsReportTests {
    @Test func sectionsFollowTheCommandOrder() {
        let text = DiagnosticsReport.build(appVersion: "1.2", link: .usb, includeNames: false, sections: sections())
        let headings = lines(text).filter { $0.hasPrefix("## ") }
        #expect(headings == ["## VERSION", "## STATUS", "## CONFIG", "## EVENTS"])
        #expect(lines(text).first?.contains("1.2") == true)
        #expect(lines(text).contains("Link: USB"))
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
}
