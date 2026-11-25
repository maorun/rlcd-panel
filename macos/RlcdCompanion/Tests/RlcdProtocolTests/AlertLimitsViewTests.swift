import Testing
@testable import RlcdProtocol

private func config(_ pairs: [String: String]) -> ConfigReply { ConfigReply(values: pairs) }

@Suite struct AlertLimitsViewTests {
    @Test func fixedOrderOfTheNineKeys() {
        #expect(AlertLimits.limits.map(\.key) == [
            "mold_alert_limit_rh", "mold_alert_delay_h", "dry_alert_limit_rh", "dry_alert_delay_h",
            "freeze_alert_limit_c", "heat_alert_limit_c", "heat_alert_delay_h",
            "ventilate_limit_rh", "sensor_battery_limit_pct",
        ])
    }

    @Test func defaultsComeFromTheSchemaAndAreValid() {
        for limit in AlertLimits.limits {
            #expect(ConfigSchema.entry(for: limit.key)?.defaultValue == limit.defaultText)
            #expect(AlertLimits.error(limit, limit.defaultText) == nil)
        }
        #expect(AlertLimits.limits.map(\.defaultText) == ["70", "0", "0", "0", "0.0", "0", "0", "60", "10"])
    }

    @Test func hugeAndMalformedNumbersGiveAnErrorWithoutCrash() {
        let mold = AlertLimits.limits[0]
        for text in ["1e30", "1e19", "9999999999", "0x10", "+", "", "nan", "inf", "7 0"] {
            #expect(AlertLimits.error(mold, text) == "Mold alert: whole number needed", "\(text)")
        }
        #expect(AlertLimits.error(mold, "70.5") == "Mold alert: whole number needed")
        #expect(AlertLimits.error(mold, "96") == "Mold alert: allowed 40 to 95 %RH")
        let freeze = AlertLimits.limits[4]
        #expect(AlertLimits.error(freeze, "1.25") == "Freeze alert: number with one decimal at most needed")
    }

    @Test func validRangesAndOffValues() {
        func err(_ key: String, _ v: String) -> String? {
            AlertLimits.error(AlertLimits.limits.first { $0.key == key }!, v)
        }
        #expect(err("mold_alert_limit_rh", "40") == nil)
        #expect(err("mold_alert_limit_rh", "95") == nil)
        #expect(err("mold_alert_limit_rh", "39") != nil)
        #expect(err("mold_alert_limit_rh", "96") != nil)
        #expect(err("mold_alert_delay_h", "0") == nil)
        #expect(err("mold_alert_delay_h", "12") == nil)
        #expect(err("mold_alert_delay_h", "13") != nil)
        #expect(err("dry_alert_limit_rh", "0") == nil)
        #expect(err("dry_alert_limit_rh", "19") != nil)
        #expect(err("dry_alert_limit_rh", "45") == nil)
        #expect(err("dry_alert_limit_rh", "46") != nil)
        #expect(err("freeze_alert_limit_c", "-20.0") == nil)
        #expect(err("freeze_alert_limit_c", "15") == nil)
        #expect(err("freeze_alert_limit_c", "15.1") != nil)
        #expect(err("freeze_alert_limit_c", "-20.1") != nil)
        #expect(err("heat_alert_limit_c", "0") == nil)
        #expect(err("heat_alert_limit_c", "26") != nil)
        #expect(err("heat_alert_limit_c", "27") == nil)
        #expect(err("heat_alert_delay_h", "1") == nil)
        #expect(err("ventilate_limit_rh", "49") != nil)
        #expect(err("ventilate_limit_rh", "80") == nil)
        #expect(err("sensor_battery_limit_pct", "0") == nil)
        #expect(err("sensor_battery_limit_pct", "51") != nil)
        #expect(err("mold_alert_limit_rh", "abc") != nil)
        #expect(err("mold_alert_limit_rh", "70.5") != nil)
    }

    @Test func shownValueFallsBackToDefault() {
        let mold = AlertLimits.limits[0]
        #expect(AlertLimits.shown(mold, config: config(["mold_alert_limit_rh": "65"])) == "65")
        #expect(AlertLimits.shown(mold, config: nil) == "70")
        #expect(AlertLimits.shown(mold, config: config([:])) == "70")
    }

    @Test func planGivesSetForChangedKeysInFixedOrder() {
        let cfg = config(["mold_alert_limit_rh": "70", "heat_alert_limit_c": "0", "freeze_alert_limit_c": "0.0"])
        let plan = AlertLimits.plan(edits: [
            "heat_alert_limit_c": "32", "mold_alert_limit_rh": "65", "freeze_alert_limit_c": "-5",
        ], config: cfg)
        #expect(plan == .commands([
            .set(key: "mold_alert_limit_rh", value: "65"),
            .set(key: "freeze_alert_limit_c", value: "-5.0"),
            .set(key: "heat_alert_limit_c", value: "32"),
        ]))
    }

    @Test func unchangedValueGivesNoCommand() {
        let cfg = config(["mold_alert_limit_rh": "70", "freeze_alert_limit_c": "0.0"])
        let plan = AlertLimits.plan(edits: ["mold_alert_limit_rh": "70", "freeze_alert_limit_c": "0"], config: cfg)
        #expect(plan == .commands([]))
    }

    @Test func invalidValueRefusesTheWholeList() {
        let plan = AlertLimits.plan(edits: ["mold_alert_limit_rh": "65", "heat_alert_limit_c": "10"], config: config([:]))
        guard case .invalid(let reason) = plan else { Issue.record("expected invalid"); return }
        #expect(reason.contains("Heat"))
    }

    @Test func commaCountsAsDecimalPoint() {
        let plan = AlertLimits.plan(edits: ["freeze_alert_limit_c": "-2,5"], config: config(["freeze_alert_limit_c": "0.0"]))
        #expect(plan == .commands([.set(key: "freeze_alert_limit_c", value: "-2.5")]))
    }

    @Test func batchSerializesSetCommandsThenConfig() {
        guard case .commands(let commands) = AlertLimits.plan(
            edits: ["mold_alert_limit_rh": "60", "dry_alert_delay_h": "3"], config: config([:])) else {
            Issue.record("expected commands"); return
        }
        var batch = ConfigBatch(commands: commands)
        var lines: [String] = []
        while let next = batch.next() {
            lines.append(next.line ?? "")
            if next == .config { batch.receive(status: "OK"); break }
            batch.receive(status: "OK SET")
        }
        #expect(lines == ["SET mold_alert_limit_rh=60", "SET dry_alert_delay_h=3", "CONFIG"])
    }

    @Test func firstErrStopsTheRemainingSets() {
        var batch = ConfigBatch(commands: [.set(key: "mold_alert_limit_rh", value: "60"), .set(key: "dry_alert_delay_h", value: "3")])
        _ = batch.next()
        batch.receive(status: "ERR BAD_VALUE")
        #expect(batch.next() == .config)
        batch.receive(status: "OK")
        #expect(batch.next() == nil)
        #expect(batch.applied.isEmpty)
        #expect(batch.failure?.key == "mold_alert_limit_rh")
    }

    @Test func canEditNeedsWritableConsole() {
        #expect(AlertLimits.canEdit(transportIsUsb: true, consoleLevel: 0))
        #expect(AlertLimits.canEdit(transportIsUsb: false, consoleLevel: 2))
        #expect(!AlertLimits.canEdit(transportIsUsb: false, consoleLevel: 1))
    }

    @Test func zeroIsInvalidForMoldAndTheHeatErrorNamesTheRange() {
        let mold = AlertLimits.limits.first { $0.key == "mold_alert_limit_rh" }!
        #expect(AlertLimits.error(mold, "0") != nil)
        let heat = AlertLimits.limits.first { $0.key == "heat_alert_limit_c" }!
        #expect(AlertLimits.error(heat, "26") == "Heat alert: allowed 0 (off) or 27 to 45 °C")
    }

    @Test func typingTheShownValueBackDropsTheEdit() {
        let mold = AlertLimits.limits[0]
        let cfg = config(["mold_alert_limit_rh": "65"])
        var edits = AlertLimits.updated(edits: [:], limit: mold, text: "66", config: cfg)
        #expect(edits == ["mold_alert_limit_rh": "66"])
        edits = AlertLimits.updated(edits: edits, limit: mold, text: "65", config: cfg)
        #expect(edits.isEmpty)
        // An invalid text stays so the form shows the error.
        #expect(AlertLimits.updated(edits: [:], limit: mold, text: "x", config: cfg) == ["mold_alert_limit_rh": "x"])
    }
}
