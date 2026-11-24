import Foundation
import Testing
@testable import RlcdProtocol

/// The Android `ConfigBackupTest` renders the same `CONFIG` reply and compares it with the same file.
@Suite struct ConfigBackupSharedFixtureTests {
    private static func fixtureURL(_ name: String) -> URL {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { url.deleteLastPathComponent() }
        return url.appendingPathComponent("tests/fixtures/\(name)")
    }

    static func exportOfSample() throws -> String {
        let text = String(decoding: try Data(contentsOf: fixtureURL("config_reply_sample.txt")), as: UTF8.self)
        let lines = text.split(separator: "\n").map(String.init).filter { !$0.hasPrefix("CRC32") }
        return ConfigBackup.render(values: try ConfigReply.parse(lines: lines).values)
    }

    @Test func exportOfConfigReplySampleEqualsSharedFixture() throws {
        let expected = String(decoding: try Data(contentsOf: Self.fixtureURL("config_backup_sample.ini")), as: UTF8.self)
        #expect(try Self.exportOfSample() == expected)
    }

    /// Regenerates the fixture: `REGEN_CONFIG_BACKUP_FIXTURE=1 swift test --filter regenerate`.
    @Test(.enabled(if: ProcessInfo.processInfo.environment["REGEN_CONFIG_BACKUP_FIXTURE"] == "1"))
    func regenerate() throws {
        try Data(try Self.exportOfSample().utf8).write(to: Self.fixtureURL("config_backup_sample.ini"))
    }
}
