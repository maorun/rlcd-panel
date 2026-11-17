import Testing
@testable import RlcdProtocol

private func config(_ pairs: [String: String]) -> ConfigReply { ConfigReply(values: pairs) }

@Suite struct ChartLimitsTests {
    @Test func knownSampleGivesFourLines() {
        let limits = ChartLimits.from(config: config([
            "mold_alert_limit_rh": "70", "dry_alert_limit_rh": "30",
            "freeze_alert_limit_c": "-18.0", "heat_alert_limit_c": "32",
        ]))
        #expect(limits.map(\.key) == ["mold_alert_limit_rh", "dry_alert_limit_rh", "freeze_alert_limit_c", "heat_alert_limit_c"])
        #expect(limits.map(\.value) == [70, 30, -18, 32])
        #expect(ChartLimits.humidity(limits).map(\.label) == ["Mold", "Dry"])
        #expect(ChartLimits.temperature(limits).map(\.label) == ["Freeze", "Heat (feels-like)"])
    }

    @Test func zeroGivesNoDryAndNoHeatLine() {
        let limits = ChartLimits.from(config: config([
            "mold_alert_limit_rh": "70", "dry_alert_limit_rh": "0",
            "freeze_alert_limit_c": "5", "heat_alert_limit_c": "0",
        ]))
        #expect(limits.map(\.key) == ["mold_alert_limit_rh", "freeze_alert_limit_c"])
    }

    @Test func freezeLimitZeroStaysAValidLine() {
        let limits = ChartLimits.from(config: config(["freeze_alert_limit_c": "0.0"]))
        #expect(limits.map(\.key) == ["freeze_alert_limit_c"])
        #expect(limits.first?.value == 0)
    }

    @Test func rangeBoundaries() {
        func keys(_ k: String, _ v: String) -> [String] { ChartLimits.from(config: config([k: v])).map(\.key) }
        #expect(keys("mold_alert_limit_rh", "40") == ["mold_alert_limit_rh"])
        #expect(keys("mold_alert_limit_rh", "95") == ["mold_alert_limit_rh"])
        #expect(keys("mold_alert_limit_rh", "39").isEmpty)
        #expect(keys("mold_alert_limit_rh", "96").isEmpty)
        #expect(keys("dry_alert_limit_rh", "20") == ["dry_alert_limit_rh"])
        #expect(keys("dry_alert_limit_rh", "46").isEmpty)
        #expect(keys("heat_alert_limit_c", "27") == ["heat_alert_limit_c"])
        #expect(keys("heat_alert_limit_c", "46").isEmpty)
    }

    @Test func valueOutsideSchemaRangeGivesNoLine() {
        let limits = ChartLimits.from(config: config([
            "mold_alert_limit_rh": "99", "dry_alert_limit_rh": "50",
            "freeze_alert_limit_c": "-30", "heat_alert_limit_c": "50",
        ]))
        #expect(limits.isEmpty)
    }

    @Test func withoutReplyOrKeysTheListIsEmpty() {
        #expect(ChartLimits.from(config: nil).isEmpty)
        #expect(ChartLimits.from(config: config(["temp_unit": "C"])).isEmpty)
    }

    @Test func labelTexts() {
        let limits = ChartLimits.from(config: config([
            "mold_alert_limit_rh": "70", "dry_alert_limit_rh": "30",
            "freeze_alert_limit_c": "-5", "heat_alert_limit_c": "30",
        ]))
        #expect(limits.map(\.label) == ["Mold", "Dry", "Freeze", "Heat (feels-like)"])
    }

    @Test func invalidTextGivesNoLine() {
        #expect(ChartLimits.from(config: config(["mold_alert_limit_rh": "abc"])).isEmpty)
        #expect(ChartLimits.from(config: config(["dry_alert_limit_rh": ""])).isEmpty)
        #expect(ChartLimits.from(config: config(["heat_alert_limit_c": "30x"])).isEmpty)
    }
}
