import Foundation
import Testing
@testable import RlcdProtocol

private func templateData() throws -> Data {
    var url = URL(fileURLWithPath: #filePath)
    for _ in 0..<5 { url.deleteLastPathComponent() }
    return try Data(contentsOf: url.appendingPathComponent("docs/config.example.ini"))
}

@Suite struct ConfigBackupTests {
    static var sample: [String: String] {
        var v = Dictionary(uniqueKeysWithValues: ConfigSchema.entries.map { ($0.key, $0.defaultValue) })
        v["wake_interval_s"] = "120"
        v["slot1_name"] = "KITCHEN"
        v["slot2_mac"] = "AA:BB:CC:DD:EE:01"
        return v
    }

    @Test func backupParsesBackToSameValues() throws {
        let text = ConfigBackup.render(values: Self.sample)
        let parsed = try ConfigBackup.parse(Data(text.utf8))
        #expect(parsed.rejected.isEmpty)
        // An empty name is a comment line, so it is no value of the file.
        let expected = Self.sample.filter { ConfigSchema.validate(key: $0.key, value: $0.value) == nil }
        #expect(parsed.values == expected)
        #expect(parsed.changes(current: Self.sample).isEmpty)
    }

    @Test func backupHasSchemaOrderLfAndNoCr() {
        let text = ConfigBackup.render(values: Self.sample)
        #expect(!text.contains("\r"))
        #expect(text.hasSuffix("\n"))
        let keys = text.split(separator: "\n").map { $0.prefix { $0 != "=" } }.map { String($0).replacingOccurrences(of: "# ", with: "") }
        #expect(keys == ConfigSchema.keys)
        #expect(text.utf8.count <= ConfigBackup.maxBytes)
    }

    @Test func backupHasNoWifiCredentials() {
        var v = Self.sample
        v["wifi_ssid"] = "HOME"
        v["wifi_password"] = "secret"
        let text = ConfigBackup.render(values: v).lowercased()
        #expect(!text.contains("wifi"))
        #expect(!text.contains("secret"))
    }

    @Test func fileAbove4096BytesIsRefused() {
        let data = Data(repeating: UInt8(ascii: "#"), count: ConfigBackup.maxBytes + 1)
        #expect(throws: ConfigBackupError.tooLarge) { try ConfigBackup.parse(data) }
        #expect(throws: Never.self) { try ConfigBackup.parse(Data(repeating: UInt8(ascii: "#"), count: ConfigBackup.maxBytes)) }
    }

    @Test func invalidUtf8IsRefused() {
        #expect(throws: ConfigBackupError.notText) { try ConfigBackup.parse(Data([0xFF, 0xFE, 0x0A])) }
    }

    @Test func rejectedLineHasItsNumber() throws {
        let text = "# backup\n\nwake_interval_s=10\nmold_alert_limit_rh=70\n"
        let parsed = try ConfigBackup.parse(Data(text.utf8))
        #expect(parsed.rejected.map(\.line) == [3])
        #expect(parsed.rejected.first?.key == "wake_interval_s")
        #expect(parsed.values["wake_interval_s"] == nil)
        #expect(parsed.values["mold_alert_limit_rh"] == "70")
    }

    @Test func lineWithoutEqualsSignIsRejected() throws {
        let parsed = try ConfigBackup.parse(Data("; note\nwake_interval_s 60\n".utf8))
        #expect(parsed.rejected.map(\.line) == [2])
    }

    @Test func commentsBlankLinesUnknownKeysAndCrAreHandled() throws {
        let text = "# c\r\n; c\r\n\r\nwifi_ssid=HOME\r\nunknown_key=1\r\nwake_interval_s=90\r\n"
        let parsed = try ConfigBackup.parse(Data(text.utf8))
        #expect(parsed.rejected.isEmpty)
        #expect(parsed.values == ["wake_interval_s": "90"])
    }

    @Test func equalBackupGivesEmptyBatch() throws {
        let parsed = try ConfigBackup.parse(Data(ConfigBackup.render(values: Self.sample).utf8))
        let changes = parsed.changes(current: Self.sample)
        #expect(changes.isEmpty)
        #expect(ConfigSchema.batch(for: changes).isEmpty)
    }

    @Test func onlyChangedKeysAreSent() throws {
        var other = Self.sample
        other["wake_interval_s"] = "300"
        let parsed = try ConfigBackup.parse(Data(ConfigBackup.render(values: other).utf8))
        let changes = parsed.changes(current: Self.sample)
        #expect(changes.map(\.key) == ["wake_interval_s"])
        #expect(changes.first?.old == "120")
        #expect(changes.first?.new == "300")
    }

    @Test func keysAbsentInFileKeepCurrentValue() throws {
        let parsed = try ConfigBackup.parse(Data("wake_interval_s=300\n".utf8))
        let changes = parsed.changes(current: Self.sample)
        #expect(changes.map(\.key) == ["wake_interval_s"])
    }

    @Test func templateParsesWithoutRejectedLine() throws {
        let parsed = try ConfigBackup.parse(try templateData())
        #expect(parsed.rejected.isEmpty)
        #expect(!parsed.values.isEmpty)
    }

    @Test func blanksAroundLineKeyAndValueAreTrimmedLikeTheFirmware() throws {
        let text = "  wake_interval_s = 90 \n\tmold_alert_limit_rh=75\t\n   # note\n  wake_interval_s = 10\n"
        let parsed = try ConfigBackup.parse(Data(text.utf8))
        #expect(parsed.values["mold_alert_limit_rh"] == "75")
        #expect(parsed.values["wake_interval_s"] == "90")
        #expect(parsed.rejected.map(\.line) == [4])
    }

    @Test func renderedFileFitsTheLimitAtLongestValues() {
        var v = Self.sample
        for n in 1...5 { v["slot\(n)_name"] = "ABCDEFGHIJKL"; v["slot\(n)_mac"] = "AA:BB:CC:DD:EE:0\(n)" }
        v["onboard_name"] = "ABCDEFGHIJKL"
        #expect(ConfigBackup.render(values: v).utf8.count <= ConfigBackup.maxBytes)
    }

    @Test func byteOrderMarkIsSkippedLikeTheFirmware() throws {
        let bom: [UInt8] = [0xEF, 0xBB, 0xBF]
        let key = try ConfigBackup.parse(Data(bom + Array("wake_interval_s=90\n".utf8)))
        #expect(key.values["wake_interval_s"] == "90")
        let comment = try ConfigBackup.parse(Data(bom + Array("# note\nwake_interval_s=10\n".utf8)))
        #expect(comment.rejected.map(\.line) == [2])
    }

    @Test func duplicateKeyLastValidLineWinsAndEmptyValueIsRejected() throws {
        let parsed = try ConfigBackup.parse(Data("wake_interval_s=90\nwake_interval_s=120\n=\nwake_interval_s=\n".utf8))
        #expect(parsed.values["wake_interval_s"] == "120")
        #expect(parsed.rejected.map(\.line) == [4]) // line 3 has an unknown (empty) key
    }

    @Test func equalsSignBeforeCombiningMarkStillSplitsTheLine() throws {
        let parsed = try ConfigBackup.parse(Data("wake_interval_s=\u{301}90\n".utf8))
        #expect(parsed.rejected.map(\.line) == [1])
        #expect(parsed.rejected.first?.key == "wake_interval_s")
    }
}
