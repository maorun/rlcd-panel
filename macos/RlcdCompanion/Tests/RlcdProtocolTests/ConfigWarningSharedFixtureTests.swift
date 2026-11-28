import Foundation
import Testing
@testable import RlcdProtocol

/// The Android `PanelConfigTest` and `ConfigSchemaTest` read the same two `CONFIG` replies.
@Suite struct ConfigWarningSharedFixtureTests {
    private static func lines(_ name: String) throws -> [String] {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { url.deleteLastPathComponent() }
        let text = String(decoding: try Data(contentsOf: url.appendingPathComponent("tests/fixtures/\(name)")), as: UTF8.self)
        return text.split(separator: "\n").map(String.init).filter { !$0.hasPrefix("CRC32") }
    }

    @Test func sampleWithoutWarnLinesHasNoWarnings() throws {
        let reply = try ConfigReply.parse(lines: try Self.lines("config_reply_sample.txt"))
        #expect(reply.warnings.isEmpty)
        // outdoor_slot=3 with the default tile mask: the sample has no climate_alert_tiles key, so the form uses the default mask and warns. The reply holds no WARN line.
        #expect(ConfigSchema.warnings(values: reply.values).map(\.reason) == ["OUTDOOR_ALERTS"])
    }

    @Test func warnSampleParsesAndMatchesTheRules() throws {
        let reply = try ConfigReply.parse(lines: try Self.lines("config_reply_warn_sample.txt"))
        #expect(reply.warnings.map(\.reason) == ["DOOR_IS_OUTDOOR", "NIGHT_SHORTER", "OUTDOOR_ALERTS"])
        #expect(reply.rejectedLines == [12, 31])
        // The four rules on the reply values give the lines of the firmware.
        #expect(ConfigSchema.warnings(values: reply.values) == reply.warnings)
    }
}
