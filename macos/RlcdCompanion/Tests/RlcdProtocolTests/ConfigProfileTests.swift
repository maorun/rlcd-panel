import Foundation
import Testing
@testable import RlcdProtocol

@Suite struct ConfigProfileTests {
    static var defaults: [String: String] { Dictionary(uniqueKeysWithValues: ConfigSchema.entries.map { ($0.key, $0.defaultValue) }) }

    @Test func saveKeepsChangedAlertKeys() throws {
        let changes = ConfigSchema.diff(old: Self.defaults, new: Self.defaults.merging(["freeze_alert_limit_c": "2.0", "mold_alert_limit_rh": "65"]) { $1 })
        let p = try ConfigProfile.make(name: "Winter", changes: changes)
        #expect(p.values == ["freeze_alert_limit_c": "2.0", "mold_alert_limit_rh": "65"])
        let store = ConfigProfileStore(store: InMemoryPinStore())
        #expect(store.save(p))
        #expect(store.profiles() == [p])
        store.remove(name: "Winter")
        #expect(store.profiles().isEmpty)
    }

    @Test func saveReplacesSameNameAndLimitsCount() {
        let store = ConfigProfileStore(store: InMemoryPinStore())
        #expect(store.save(ConfigProfile(name: "A", values: ["mold_alert_limit_rh": "60"])))
        #expect(store.save(ConfigProfile(name: "A", values: ["mold_alert_limit_rh": "61"])))
        #expect(store.profiles().count == 1 && store.profile(named: "A")?.values["mold_alert_limit_rh"] == "61")
        for i in 1..<ConfigProfileStore.maxProfiles { #expect(store.save(ConfigProfile(name: "P\(i)", values: ["mold_alert_limit_rh": "60"]))) }
        #expect(!store.save(ConfigProfile(name: "Extra", values: ["mold_alert_limit_rh": "60"])))
    }

    @Test func excludedKeysAreNotSaved() throws {
        let new = Self.defaults.merging([
            "slot1_mac": "AA:BB:CC:DD:EE:01", "slot1_name": "KITCHEN", "onboard_name": "ROOM", "temp_offset_c": "-1.0",
            "usb_temp_offset_c": "-1.0", "slot2_rh_offset_pct": "2.0", "battery_offset_mv": "10", "rh_gain": "1.02",
            "slot3_rh_gain": "1.02", "heat_alert_limit_c": "30"]) { $1 }
        let p = try ConfigProfile.make(name: "Summer", changes: ConfigSchema.diff(old: Self.defaults, new: new))
        #expect(p.values == ["heat_alert_limit_c": "30"])
        #expect(throws: ConfigProfile.Failure.nothingToSave) {
            try ConfigProfile.make(name: "X", changes: ConfigSchema.diff(old: Self.defaults, new: Self.defaults.merging(["slot1_name": "A"]) { $1 }))
        }
    }

    @Test func badNamesAreRefused() {
        let c = [ConfigChange(key: "mold_alert_limit_rh", old: "70", new: "65")]
        for n in ["", " A", "A ", "A\tB", "A\nB", String(repeating: "x", count: 25)] {
            #expect(throws: ConfigProfile.Failure.badName) { try ConfigProfile.make(name: n, changes: c) }
        }
    }

    @Test func invalidValueIsNotSaved() {
        let c = [ConfigChange(key: "mold_alert_limit_rh", old: "70", new: "99")]
        #expect(throws: ConfigProfile.Failure.self) { try ConfigProfile.make(name: "Bad", changes: c) }
    }

    @Test func tabOrLineBreakInValueIsRefused() {
        // The schema grammar accepts no tab and no line break, so `make` refuses the value.
        for v in ["6\t0", "6\n0"] {
            let c = [ConfigChange(key: "temp_unit", old: "C", new: v)]
            #expect(throws: ConfigProfile.Failure.self) { try ConfigProfile.make(name: "Bad", changes: c) }
        }
    }

    @Test func profilesComeSortedByNameAndNameRulesMatchAndroid() {
        let store = ConfigProfileStore(store: InMemoryPinStore())
        for n in ["Winter", "Autumn", "Summer"] { store.save(ConfigProfile(name: n, values: ["mold_alert_limit_rh": "60"])) }
        #expect(store.profiles().map(\.name) == ["Autumn", "Summer", "Winter"])
        #expect(ConfigProfile.isValidName(String(repeating: "😀", count: 24))) // 24 code points
        #expect(!ConfigProfile.isValidName(String(repeating: "😀", count: 25)))
        #expect(ConfigProfile.isValidName("A\u{00A0}")) // only U+0020 is an edge blank
    }

    @Test func planIsTheDiffAgainstCurrentConfig() {
        let p = ConfigProfile(name: "Winter", values: ["freeze_alert_limit_c": "2.0", "mold_alert_limit_rh": "70"])
        let plan = p.plan(current: Self.defaults)
        #expect(plan.changes == [ConfigChange(key: "freeze_alert_limit_c", old: "0.0", new: "2.0")]) // mold already 70
        #expect(plan.skipped.isEmpty && plan.problems.isEmpty && plan.canApply)
        #expect(ConfigBatch(changes: plan.changes).pending == [.set(key: "freeze_alert_limit_c", value: "2.0"), .config])
        #expect(!p.plan(current: Self.defaults.merging(["freeze_alert_limit_c": "2", "mold_alert_limit_rh": "70"]) { $1 }).canApply) // nothing to do
    }

    @Test func unknownAndExcludedKeysInStoredProfileAreSkipped() {
        let p = ConfigProfile(name: "Old", values: ["future_key": "1", "slot1_mac": "AA:BB:CC:DD:EE:01", "temp_offset_c": "3", "mold_alert_limit_rh": "65"])
        let plan = p.plan(current: Self.defaults)
        #expect(plan.skipped == ["future_key", "slot1_mac", "temp_offset_c"])
        #expect(plan.changes.map(\.key) == ["mold_alert_limit_rh"])
        #expect(plan.canApply)
    }

    @Test func valueOutsideSchemaRangeBlocksApply() {
        let p = ConfigProfile(name: "Bad", values: ["mold_alert_limit_rh": "99", "freeze_alert_limit_c": "2.0"])
        let plan = p.plan(current: Self.defaults)
        #expect(plan.problems["mold_alert_limit_rh"] != nil && plan.problems["freeze_alert_limit_c"] == nil)
        #expect(!plan.canApply)
    }

    @Test func sharedFixtureDecodesLikeAndroid() throws {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { url.deleteLastPathComponent() }
        let text = try String(contentsOf: url.appendingPathComponent("tests/fixtures/config_profiles_sample.txt"), encoding: .utf8)
        let profiles = ConfigProfileStore.decode(text)
        #expect(profiles.map(\.name) == ["Winter", "Summer"])
        #expect(profiles[1].values == ["heat_alert_limit_c": "30", "future_key": "1", "mold_alert_limit_rh": "99"])
        let plan = profiles[1].plan(current: Self.defaults)
        #expect(plan.skipped == ["future_key"] && plan.problems.keys.contains("mold_alert_limit_rh"))
        #expect(ConfigProfileStore.decode(ConfigProfileStore.encode(profiles)) == profiles)
    }

    @Test func corruptStoredLinesDropOnlyThemselves() {
        let text = "junk\nP\t\nK\tx\t1\nP\tOk\nK\t\t1\nK\tmold_alert_limit_rh\t65\nK\tbroken\n"
        #expect(ConfigProfileStore.decode(text) == [ConfigProfile(name: "Ok", values: ["mold_alert_limit_rh": "65"])])
    }
}
