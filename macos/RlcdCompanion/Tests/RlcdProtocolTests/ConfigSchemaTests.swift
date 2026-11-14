import Foundation
import Testing
@testable import RlcdProtocol

private func templateURL() -> URL {
    var url = URL(fileURLWithPath: #filePath)
    for _ in 0..<5 { url.deleteLastPathComponent() }
    return url.appendingPathComponent("docs/config.example.ini")
}

/// Keys of docs/config.example.ini: active lines give a default, `# key=value` lines give none.
private func templateKeys() throws -> (active: [String: String], commented: Set<String>) {
    let text = try String(contentsOf: templateURL(), encoding: .utf8)
    var active: [String: String] = [:]
    var commented = Set<String>()
    for line in text.split(separator: "\n") {
        if line.hasPrefix("# "), let eq = line.firstIndex(of: "=") {
            let key = String(line[line.index(line.startIndex, offsetBy: 2)..<eq])
            if key.range(of: "^[a-z0-9_]+$", options: .regularExpression) != nil { commented.insert(key) }
        } else if !line.hasPrefix("#"), let eq = line.firstIndex(of: "=") {
            active[String(line[..<eq])] = String(line[line.index(after: eq)...])
        }
    }
    return (active, commented)
}

struct ConfigSchemaTests {
    @Test func schemaMatchesTemplate() throws {
        let (active, commented) = try templateKeys()
        let template = Set(active.keys).union(commented)
        #expect(Set(ConfigSchema.keys) == template)
        #expect(ConfigSchema.keys.count == 40)
        #expect(Set(ConfigSchema.keys).count == ConfigSchema.keys.count)
        for e in ConfigSchema.entries {
            if let v = active[e.key] {
                #expect(e.defaultValue == v, "default of \(e.key)")
            } else {
                #expect(e.defaultValue.isEmpty, "\(e.key) has no template default")
            }
            #expect(!e.description.isEmpty)
        }
    }

    @Test func defaultsValidate() {
        for e in ConfigSchema.entries where !e.defaultValue.isEmpty {
            #expect(ConfigSchema.validate(key: e.key, value: e.defaultValue) == nil, "\(e.key)")
        }
    }

    @Test func everyCategoryHasKeys() {
        for c in ConfigCategory.allCases { #expect(!ConfigSchema.entries(in: c).isEmpty) }
    }

    @Test func numericBoundaries() {
        for e in ConfigSchema.entries {
            switch e.kind {
            case let .integer(lo, hi, off):
                #expect(ConfigSchema.validate(key: e.key, value: String(lo)) == nil, "\(e.key) min")
                #expect(ConfigSchema.validate(key: e.key, value: String(hi)) == nil, "\(e.key) max")
                #expect(ConfigSchema.validate(key: e.key, value: String(hi + 1)) != nil, "\(e.key) max+1")
                if let off { #expect(ConfigSchema.validate(key: e.key, value: String(off)) == nil, "\(e.key) off") }
                // Without an off value below the range, min-1 fails. With off == lo-1 (none here) it would pass.
                if off != lo - 1 { #expect(ConfigSchema.validate(key: e.key, value: String(lo - 1)) != nil, "\(e.key) min-1") }
                #expect(ConfigSchema.validate(key: e.key, value: "1.5") != nil)
            case let .decimal(lo, hi):
                #expect(ConfigSchema.validate(key: e.key, value: String(lo)) == nil, "\(e.key) min")
                #expect(ConfigSchema.validate(key: e.key, value: String(hi)) == nil, "\(e.key) max")
                #expect(ConfigSchema.validate(key: e.key, value: String(hi + 0.1)) != nil, "\(e.key) max+")
                #expect(ConfigSchema.validate(key: e.key, value: String(lo - 0.1)) != nil, "\(e.key) min-")
            case let .bitMask(bits):
                let max = (1 << bits) - 1
                #expect(ConfigSchema.validate(key: e.key, value: "0") == nil)
                #expect(ConfigSchema.validate(key: e.key, value: String(max)) == nil)
                #expect(ConfigSchema.validate(key: e.key, value: String(max + 1)) != nil)
                #expect(ConfigSchema.validate(key: e.key, value: "-1") != nil)
            default: break
            }
        }
    }

    @Test func offValuesAndGaps() {
        #expect(ConfigSchema.validate(key: "heat_alert_limit_c", value: "0") == nil)
        #expect(ConfigSchema.validate(key: "heat_alert_limit_c", value: "26") != nil)
        #expect(ConfigSchema.validate(key: "heat_alert_limit_c", value: "27") == nil)
        #expect(ConfigSchema.validate(key: "heat_alert_limit_c", value: "45") == nil)
        #expect(ConfigSchema.validate(key: "heat_alert_limit_c", value: "46") != nil)
        #expect(ConfigSchema.validate(key: "noise_alert_limit_db", value: "39") != nil)
        #expect(ConfigSchema.validate(key: "noise_alert_limit_db", value: "40") == nil)
        #expect(ConfigSchema.validate(key: "night_wake_interval_s", value: "59") != nil)
        #expect(ConfigSchema.validate(key: "night_wake_interval_s", value: "60") == nil)
        #expect(ConfigSchema.validate(key: "night_wake_interval_s", value: "1801") != nil)
        #expect(ConfigSchema.validate(key: "battery_offset_mv", value: "-300") == nil)
        #expect(ConfigSchema.validate(key: "battery_offset_mv", value: "-301") != nil)
        #expect(ConfigSchema.validate(key: "temp_offset_c", value: "-10.0") == nil)
        #expect(ConfigSchema.validate(key: "temp_offset_c", value: "10.1") != nil)
    }

    @Test func numberGrammar() {
        for bad in ["", "abc", "1e2", "1.", ".5", "+", "1 ", " 1", "0x10", "1,5", "NaN", "1234567890123456"] {
            #expect(ConfigSchema.validate(key: "wake_interval_s", value: bad) != nil, "'\(bad)'")
        }
        #expect(ConfigSchema.validate(key: "wake_interval_s", value: "+60") == nil)
        #expect(ConfigSchema.validate(key: "wake_interval_s", value: "60.0") == nil)
        #expect(ConfigSchema.validate(key: "temp_offset_c", value: "+1.25") == nil)
    }

    @Test func enumValues() {
        #expect(ConfigSchema.validate(key: "temp_unit", value: "C") == nil)
        #expect(ConfigSchema.validate(key: "temp_unit", value: "F") == nil)
        for bad in ["", "c", "f", "K", "CF", " C"] { #expect(ConfigSchema.validate(key: "temp_unit", value: bad) != nil) }
    }

    @Test func namesAndMacs() {
        #expect(ConfigSchema.validate(key: "slot1_name", value: "LIVING-ROOM1") == nil)
        #expect(ConfigSchema.validate(key: "slot1_name", value: "BEDROOM") == nil)
        #expect(ConfigSchema.validate(key: "slot1_name", value: "ABCDEFGHIJKL") == nil)
        #expect(ConfigSchema.validate(key: "slot1_name", value: "ABCDEFGHIJKLM") != nil)
        #expect(ConfigSchema.validate(key: "slot1_name", value: "") != nil)
        #expect(ConfigSchema.validate(key: "slot1_name", value: "bedroom") != nil)
        #expect(ConfigSchema.validate(key: "slot1_name", value: "A_B") != nil)
        #expect(ConfigSchema.validate(key: "slot1_name", value: " A") != nil)
        #expect(ConfigSchema.validate(key: "slot1_mac", value: "") == nil)
        #expect(ConfigSchema.validate(key: "slot1_mac", value: "AA:BB:CC:DD:EE:FF") == nil)
        #expect(ConfigSchema.validate(key: "slot1_mac", value: "aa:bb:cc:dd:ee:ff") == nil)
        #expect(ConfigSchema.validate(key: "slot1_mac", value: "00:00:00:00:00:00") == .zeroMac)
        for bad in ["AA:BB:CC:DD:EE", "AA-BB-CC-DD-EE-FF", "AA:BB:CC:DD:EE:GG", "AABBCCDDEEFF", "AA:BB:CC:DD:EE:FF:00"] {
            #expect(ConfigSchema.validate(key: "slot2_mac", value: bad) == .badMac, "\(bad)")
        }
    }

    @Test func unknownKey() {
        #expect(ConfigSchema.validate(key: "wifi_ssid", value: "x") == .unknownKey)
    }

    @Test func duplicateMacIsAProblem() {
        let changes = [ConfigChange(key: "slot2_mac", old: "", new: "AA:BB:CC:DD:EE:01")]
        let p = ConfigSchema.problems(changes: changes, allValues: ["slot1_mac": "AA:BB:CC:DD:EE:01"])
        #expect(p["slot2_mac"]?.hasPrefix(ConfigValidationError.duplicateMac.text) == true)
        #expect(ConfigSchema.problems(changes: changes, allValues: ["slot1_mac": "AA:BB:CC:DD:EE:02"]).isEmpty)
    }

    @Test func problemsCoverInvalidAndLongLines() {
        let bad = [ConfigChange(key: "temp_unit", old: "C", new: "K")]
        #expect(ConfigSchema.problems(changes: bad)["temp_unit"] != nil)
        // "SET slot1_temp_offset_c=" has 24 bytes; 17 more bytes break the 40 byte limit.
        #expect(ConsoleCommand.set(key: "slot1_name", value: "ABCDEFGHIJKL").line != nil)
        #expect(ConsoleCommand.set(key: "slot1_temp_offset_c", value: "12345678901234567").line == nil)
    }

    @Test func longestValidLinesFitTheLimit() {
        for e in ConfigSchema.entries {
            let sample: String
            switch e.kind {
            case .name: sample = "ABCDEFGHIJKL"
            case .mac: sample = "AA:BB:CC:DD:EE:FF"
            default: sample = e.defaultValue.isEmpty ? "0" : e.defaultValue
            }
            let line = "SET \(e.key)=\(sample)"
            #expect(line.utf8.count <= ConfigSchema.maxLineBytes, "\(line)")
        }
    }

    // MARK: diff and batch

    private func reply(_ pairs: [String]) throws -> ConfigReply { try ConfigReply.parse(lines: pairs) }

    @Test func diffListsChangesInSchemaOrder() throws {
        let old = try reply(["wake_interval_s=60", "temp_unit=C", "ble_console=2", "temp_offset_c=-1.9"])
        let new = try reply(["wake_interval_s=120", "temp_unit=F", "ble_console=2", "temp_offset_c=-1.90"])
        let d = ConfigSchema.diff(old: old, new: new)
        #expect(d.map(\.key) == ["temp_unit", "wake_interval_s"]) // temp_offset_c -1.9 == -1.90
        #expect(d[0].text == "temp_unit: C -> F")
        #expect(d[1].text == "wake_interval_s: 60 -> 120")
        #expect(ConfigSchema.diff(old: old, new: old).isEmpty)
    }

    @Test func diffUsesDefaultForMissingKeys() throws {
        let old = try reply(["temp_unit=C"])
        let new = try reply(["temp_unit=C", "slot1_mac=AA:BB:CC:DD:EE:FF"])
        let d = ConfigSchema.diff(old: old, new: new)
        #expect(d == [ConfigChange(key: "slot1_mac", old: "", new: "AA:BB:CC:DD:EE:FF")])
        #expect(d[0].text == "slot1_mac: (empty) -> AA:BB:CC:DD:EE:FF")
        #expect(ConfigSchema.diff(old: ["slot1_mac": "aa:bb:cc:dd:ee:ff"], new: ["slot1_mac": "AA:BB:CC:DD:EE:FF"]).isEmpty)
    }

    @Test func batchOrderIsFixed() {
        let shuffled = [
            ConfigChange(key: "ble_console", old: "2", new: "1"),
            ConfigChange(key: "wake_interval_s", old: "60", new: "90"),
            ConfigChange(key: "temp_unit", old: "C", new: "F"),
            ConfigChange(key: "unknown_key", old: "", new: "1"),
        ]
        let lines = ConfigSchema.batch(for: shuffled).compactMap(\.line)
        #expect(lines == ["SET temp_unit=F", "SET wake_interval_s=90", "SET ble_console=1"])
        #expect(ConfigSchema.batch(for: shuffled.reversed()).compactMap(\.line) == lines)
        #expect(ConfigSchema.keys.last == "ble_console")
    }

    @Test func batchRunsSetsThenConfig() {
        var b = ConfigBatch(changes: [
            ConfigChange(key: "wake_interval_s", old: "60", new: "90"),
            ConfigChange(key: "temp_unit", old: "C", new: "F"),
        ])
        #expect(b.next()?.line == "SET temp_unit=F")
        #expect(b.next() == nil) // a reply is open
        b.receive(status: "OK SET temp_unit")
        #expect(b.next()?.line == "SET wake_interval_s=90")
        b.receive(status: "OK SET wake_interval_s")
        #expect(b.next() == .config)
        #expect(!b.isFinished)
        b.receiveFrame()
        #expect(b.next() == nil)
        #expect(b.isFinished)
        #expect(b.applied == ["temp_unit", "wake_interval_s"])
        #expect(b.failure == nil)
        #expect(b.summary == "Applied 2 settings")
    }

    @Test func batchStopsAtFirstErrButRefreshesConfig() {
        var b = ConfigBatch(changes: [
            ConfigChange(key: "temp_unit", old: "C", new: "F"),
            ConfigChange(key: "wake_interval_s", old: "60", new: "90"),
            ConfigChange(key: "ble_console", old: "2", new: "1"),
        ])
        #expect(b.next()?.line == "SET temp_unit=F")
        b.receive(status: "OK SET temp_unit")
        #expect(b.next()?.line == "SET wake_interval_s=90")
        b.receive(status: "ERR BAD_VALUE")
        #expect(b.next() == .config) // no further SET
        b.receiveFrame()
        #expect(b.next() == nil)
        #expect(b.isFinished)
        #expect(b.applied == ["temp_unit"])
        #expect(b.failure?.key == "wake_interval_s")
        #expect(b.failure?.reply == "ERR BAD_VALUE")
        #expect(b.summary == "Stopped at wake_interval_s: ERR BAD_VALUE. Applied: 1")
    }

    @Test func batchWithoutChangesOnlyReadsConfig() {
        var b = ConfigBatch(changes: [])
        #expect(b.next() == .config)
    }

    @Test func macSwapNeedsAClearFirst() {
        let old = ["slot1_mac": "AA:BB:CC:DD:EE:01", "slot2_mac": "AA:BB:CC:DD:EE:02"]
        let swap = [
            ConfigChange(key: "slot1_mac", old: "AA:BB:CC:DD:EE:01", new: "AA:BB:CC:DD:EE:02"),
            ConfigChange(key: "slot2_mac", old: "AA:BB:CC:DD:EE:02", new: "AA:BB:CC:DD:EE:01"),
        ]
        #expect(ConfigSchema.problems(changes: swap, allValues: old)["slot1_mac"] != nil)
        // Clearing slot 1 and moving its MAC to slot 2 works in the schema order only when slot 1 goes first.
        let move = [
            ConfigChange(key: "slot1_mac", old: "AA:BB:CC:DD:EE:01", new: ""),
            ConfigChange(key: "slot2_mac", old: "AA:BB:CC:DD:EE:02", new: "AA:BB:CC:DD:EE:01"),
        ]
        #expect(ConfigSchema.problems(changes: move, allValues: old).isEmpty)
    }

    @Test func emptyValueOnlyForSlotMacs() {
        #expect(ConsoleCommand.set(key: "slot1_mac", value: "").line == "SET slot1_mac=")
        #expect(ConsoleCommand.set(key: "slot3_mac", value: "").line == "SET slot3_mac=")
        #expect(ConsoleCommand.set(key: "slot1_name", value: "").line == nil)
        let clear = [ConfigChange(key: "slot1_mac", old: "AA:BB:CC:DD:EE:01", new: "")]
        #expect(ConfigSchema.problems(changes: clear, allValues: [:]).isEmpty)
        #expect(ConfigSchema.batch(for: clear).compactMap(\.line) == ["SET slot1_mac="])
    }

    @Test func nameWithSpaceGivesSpecificProblem() {
        let c = [ConfigChange(key: "slot1_name", old: "", new: "LIVING ROOM")]
        #expect(ConfigSchema.validate(key: "slot1_name", value: "LIVING ROOM") == nil)
        #expect(ConfigSchema.problems(changes: c)["slot1_name"]?.contains("space") == true)
    }

    @Test func batchReportsConfigError() {
        var b = ConfigBatch(changes: [ConfigChange(key: "temp_unit", old: "C", new: "F")])
        _ = b.next()
        b.receive(status: "OK SET temp_unit")
        #expect(b.next() == .config)
        b.receive(status: "ERR NO_CARD")
        #expect(b.isFinished)
        #expect(b.configFailed)
        #expect(b.summary == "Applied 1. CONFIG refresh failed")
    }

    /// Limits copied from rlcd_panel/device_config.h. A wrong schema range fails here.
    @Test func rangesMatchFirmwareConstants() {
        let expected: [String: (Double, Double)] = [
            "temp_offset_c": (-10, 10), "rh_offset_pct": (-10, 10), "freeze_alert_limit_c": (-20, 15),
            "mold_alert_limit_rh": (40, 95), "chime_volume_pct": (0, 100), "quiet_hours_start_hour": (0, 23),
            "quiet_hours_end_hour": (0, 23), "mic_spl_offset_db": (-20, 20), "log_retention_months": (0, 120),
            "noise_alert_limit_db": (40, 100), "chime_alert_mask": (0, 255), "ntp_sync_interval_h": (6, 168),
            "wake_interval_s": (30, 900), "sensor_stale_min": (5, 240), "display_invert": (0, 1),
            "climate_alert_tiles": (0, 15), "battery_offset_mv": (-300, 300), "outdoor_slot": (0, 3),
            "ventilate_limit_rh": (50, 80), "sensor_battery_limit_pct": (0, 50),
            "slot1_temp_offset_c": (-5, 5), "slot2_temp_offset_c": (-5, 5), "slot3_temp_offset_c": (-5, 5),
            "slot1_rh_offset_pct": (-10, 10), "slot2_rh_offset_pct": (-10, 10), "slot3_rh_offset_pct": (-10, 10),
            "slot_release_h": (0, 168), "night_wake_interval_s": (60, 1800), "chime_repeat_h": (0, 24),
            "heat_alert_limit_c": (27, 45), "ble_broadcast_enabled": (0, 1), "ble_console": (0, 2),
        ]
        for (key, (lo, hi)) in expected {
            switch ConfigSchema.entry(for: key)?.kind {
            case let .integer(l, h, _): #expect(Double(l) == lo && Double(h) == hi, "\(key)")
            case let .decimal(l, h): #expect(l == lo && h == hi, "\(key)")
            case let .bitMask(bits): #expect(Double((1 << bits) - 1) == hi && lo == 0, "\(key)")
            default: Issue.record("\(key) has no numeric kind")
            }
        }
        // The other 8 keys are the enum, 4 names and 3 MACs.
        #expect(expected.count + 8 == ConfigSchema.keys.count)
    }
}
