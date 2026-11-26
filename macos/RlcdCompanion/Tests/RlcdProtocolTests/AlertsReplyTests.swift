import Foundation
import Testing
@testable import RlcdProtocol

@Suite struct AlertsReplyTests {
    static let head = ["BEGIN ALERTS", AlertsReply.header]
    static let names = ["Wohnzimmer", "Bad", "Küche", "Flur", "Keller", "Garage", "S6", "S7", "S8", "S9", "S10"]

    static func fixtureLines() throws -> [String] {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("tests/fixtures/alerts_reply_sample.txt")
        return try String(contentsOf: url, encoding: .utf8).split(separator: "\n").map(String.init)
            .filter { !$0.hasPrefix("CRC32") }
    }

    @Test func parsesFixture() throws {
        let r = try AlertsReply.parse(lines: Self.fixtureLines())
        #expect(r.rows.count == 8)
        #expect(r.rows.filter(\.active).map(\.name) == ["FREEZER", "HUMIDITY", "NOISE", "SENSOR_BATTERY", "HEAT"])
        let humidity = r.rows[1]
        #expect(humidity.tiles == 0x012)
        #expect(humidity.tileIndices == [1, 4])
        #expect(humidity.limit == "70")
        #expect(r.rows[2].acked)
        #expect(r.rows[2].tiles == nil) // NOISE has no tiles
    }

    @Test func causeTextNamesTheTiles() throws {
        let r = try AlertsReply.parse(lines: Self.fixtureLines())
        #expect(r.causeText(for: "HUMIDITY", tileNames: Self.names) == "HUMIDITY: Bad, Keller")
        #expect(r.causeText(for: "FREEZER", tileNames: Self.names) == "FREEZER: Küche")
        #expect(r.causeText(for: "HEAT", tileNames: Self.names) == "HEAT: Flur")
        #expect(r.causeText(for: "NOISE", tileNames: Self.names) == "NOISE")
        #expect(r.causeText(for: "VENTILATE", tileNames: Self.names) == nil) // not active
    }

    @Test func heldAlertWithoutTileSaysSo() throws {
        let r = try AlertsReply.parse(lines: Self.head + ["HUMIDITY,1,0,000,70"])
        #expect(r.causeText(for: "HUMIDITY", tileNames: Self.names) == "HUMIDITY: no tile above the limit")
    }

    @Test func causeLinesFollowTheAlertMask() throws {
        let r = try AlertsReply.parse(lines: Self.fixtureLines())
        #expect(r.causeLines(tileNames: Self.names) == [
            "FREEZER: Küche", "HUMIDITY: Bad, Keller", "NOISE", "SENSOR_BATTERY: Küche", "HEAT: Flur",
        ])
    }

    @Test func missingTileNameFallsBackToTileNumber() throws {
        let r = try AlertsReply.parse(lines: Self.head + ["HUMIDITY,1,0,400,70"])
        #expect(r.causeText(for: "HUMIDITY", tileNames: ["A"]) == "HUMIDITY: Tile 10")
    }

    @Test func badMaskThrows() {
        let line = "HUMIDITY,1,0,0G1,70"
        #expect(throws: ParseError.badLine(line)) { try AlertsReply.parse(lines: Self.head + [line]) }
    }

    @Test func otherBadLinesThrow() {
        let bad = [
            "BOGUS,1,0,000,70",
            "HUMIDITY,2,0,000,70",
            "HUMIDITY,1,0,0012,70",
            "HUMIDITY,1,0,800,70",
            "HUMIDITY,1,0,000",
            "HUMIDITY,1,0,000,7x",
        ]
        for line in bad {
            #expect(throws: ParseError.badLine(line)) { try AlertsReply.parse(lines: Self.head + [line]) }
        }
    }

    @Test func repeatedAlertThrows() {
        let line = "HUMIDITY,0,0,000,70"
        #expect(throws: ParseError.badLine(line)) {
            try AlertsReply.parse(lines: Self.head + ["HUMIDITY,1,0,002,70", line])
        }
    }

    @Test func missingHeaderThrows() {
        #expect(throws: ParseError.missingLine("BEGIN ALERTS")) { try AlertsReply.parse(lines: []) }
        #expect(throws: ParseError.missingLine(AlertsReply.header)) { try AlertsReply.parse(lines: ["BEGIN ALERTS"]) }
    }

    @Test func activeMaskFollowsTheAlertBits() throws {
        let r = try AlertsReply.parse(lines: Self.fixtureLines())
        // FREEZER 1, HUMIDITY 2, NOISE 4, SENSOR_BATTERY 64, HEAT 128
        #expect(r.activeMask == 1 | 2 | 4 | 64 | 128)
        #expect(try AlertsReply.parse(lines: Self.head).activeMask == 0)
    }

    @Test func defaultTileNamesCoverAllTiles() {
        #expect(AlertCauses.tileNames(config: nil) == AlertCauses.defaultNames)
        #expect(AlertCauses.defaultNames.count == 11)
    }

    @Test func oldFirmwareGivesUpdateHint() {
        #expect(AlertsReply.errorText("ERR UNKNOWN") == "Update the panel firmware")
        #expect(AlertsReply.errorText("ERR BAD_ARG") == "The panel rejected the command")
        #expect(AlertsReply.errorText("ERR X") == "ERR X")
        #expect(ConsoleCommand.alerts.line == "ALERTS")
    }
}
