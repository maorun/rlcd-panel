import Foundation
import Testing
@testable import RlcdProtocol

@Suite struct ConfigChangedReplyTests {
    /// Lines of tests/fixtures/config_changed_reply_sample.txt, the file of the Android phone tests, without `CRC32`.
    static func fixtureLines() throws -> [String] {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("tests/fixtures/config_changed_reply_sample.txt")
        return try String(contentsOf: url, encoding: .utf8).split(separator: "\n").map(String.init)
            .filter { !$0.hasPrefix("CRC32") }
    }

    @Test func parsesFixture() throws {
        let entries = try ConfigChangedReply.parse(lines: Self.fixtureLines())
        #expect(entries == [
            ConfigChangedEntry(key: "temp_offset_c", value: "2.25"),
            ConfigChangedEntry(key: "mold_alert_limit_rh", value: "65"),
            ConfigChangedEntry(key: "slot1_name", value: "Bad"),
        ])
    }

    @Test func emptyReplyHasNoEntries() throws {
        #expect(try ConfigChangedReply.parse(lines: ["END CHANGED 0"]).isEmpty)
    }

    @Test func countThatDiffersThrows() {
        #expect(throws: ParseError.badLine("END CHANGED 2")) {
            try ConfigChangedReply.parse(lines: ["temp_offset_c=2.25", "END CHANGED 2"])
        }
        #expect(throws: ParseError.badLine("END CHANGED 0")) {
            try ConfigChangedReply.parse(lines: ["temp_offset_c=2.25", "END CHANGED 0"])
        }
        #expect(throws: ParseError.badLine("END CHANGED 01")) {
            try ConfigChangedReply.parse(lines: ["END CHANGED 01"])
        }
    }

    @Test func unknownKeyThrows() {
        #expect(throws: ParseError.badLine("no_such_key=1")) {
            try ConfigChangedReply.parse(lines: ["no_such_key=1", "END CHANGED 1"])
        }
    }

    @Test func repeatedKeyAndBadLineThrow() {
        #expect(throws: ParseError.badLine("temp_offset_c=3")) {
            try ConfigChangedReply.parse(lines: ["temp_offset_c=2", "temp_offset_c=3", "END CHANGED 2"])
        }
        #expect(throws: ParseError.badLine("temp_offset_c")) {
            try ConfigChangedReply.parse(lines: ["temp_offset_c", "END CHANGED 1"])
        }
        #expect(throws: ParseError.badLine("=5")) {
            try ConfigChangedReply.parse(lines: ["=5", "END CHANGED 1"])
        }
    }

    @Test func missingEndLineThrows() {
        #expect(throws: ParseError.missingLine("END CHANGED")) { try ConfigChangedReply.parse(lines: []) }
        #expect(throws: ParseError.missingLine("END CHANGED")) {
            try ConfigChangedReply.parse(lines: ["temp_offset_c=2.25"])
        }
    }

    @Test func commandLine() {
        #expect(ConsoleCommand.configChanged.line == "CONFIG CHANGED")
    }

    /// The reset batch of a changed key holds the schema default, then `CONFIG`.
    @Test func resetBatchHoldsSchemaDefault() throws {
        let entries = try ConfigChangedReply.parse(lines: Self.fixtureLines())
        let entry = try #require(entries.first { $0.key == "mold_alert_limit_rh" })
        let changes = ConfigSchema.resetChanges(for: entry)
        let defaultValue = try #require(ConfigSchema.entry(for: "mold_alert_limit_rh")).defaultValue
        #expect(changes == [ConfigChange(key: "mold_alert_limit_rh", old: "65", new: defaultValue)])
        var batch = ConfigBatch(changes: changes)
        #expect(batch.next()?.line == "SET mold_alert_limit_rh=\(defaultValue)")
        batch.receive(status: "OK SET mold_alert_limit_rh")
        #expect(batch.next() == .config)
    }

    @Test func resetPlanKeepsSchemaOrderAndDropsUnsendableDefault() throws {
        let plan = ConfigSchema.resetPlan(changed: try ConfigChangedReply.parse(lines: Self.fixtureLines()))
        // slot1_name has an empty default: the console cannot send it.
        #expect(plan.changes.map(\.key) == ["temp_offset_c", "mold_alert_limit_rh"])
        #expect(Set(plan.problems.keys) == ["slot1_name"])
        #expect(plan.changes.allSatisfy { $0.new == ConfigSchema.entry(for: $0.key)?.defaultValue })
    }

    @Test func resetOfDefaultValueIsEmpty() throws {
        let def = try #require(ConfigSchema.entry(for: "temp_offset_c")).defaultValue
        #expect(ConfigSchema.resetChanges(for: ConfigChangedEntry(key: "temp_offset_c", value: def)).isEmpty)
    }
}
