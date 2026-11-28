import Foundation
import Testing
@testable import RlcdProtocol

@Suite struct CalibrationCheckTests {
    static let t0 = Date(timeIntervalSince1970: 1_800_000_000)
    static let hour: TimeInterval = 3600

    final class Clock { var date = CalibrationCheckTests.t0 }

    static func store(_ backing: InMemoryPinStore = InMemoryPinStore(), clock: Clock = Clock()) -> (CalibrationCheckStore, Clock, InMemoryPinStore) {
        (CalibrationCheckStore(store: backing, clock: { clock.date }), clock, backing)
    }

    static func calibReply(tempDiff: String, rhDiff: String) throws -> CalibrationReply {
        try CalibrationReply.parse(lines: [
            "REFERENCE onboard",
            "SENSOR slot1 hours=12 temp_offset=-0.4 rh_offset=2.1 temp_diff=\(tempDiff) rh_diff=\(rhDiff)",
        ])
    }

    static func spreadReply(tempDev: String, rhDev: String) throws -> SpreadReply {
        try SpreadReply.parse(lines: [
            "SPREAD sensors=3 temp_median=21.0 rh_median=45.0",
            "SENSOR onboard temp_dev=0.0 rh_dev=0.0 OK",
            "SENSOR slot1 temp_dev=\(tempDev) rh_dev=\(rhDev) OK",
            "SENSOR slot2 temp_dev=0.1 rh_dev=0.5 OK",
        ])
    }

    static let changes = [
        ConfigChange(key: "slot1_temp_offset_c", old: "0.0", new: "-0.4"),
        ConfigChange(key: "slot1_rh_offset_pct", old: "0.0", new: "2.1"),
    ]

    @Test func storesSensorKeysOldAndNewValuesAndTime() throws {
        let (store, _, _) = Self.store()
        store.record(applied: Self.changes, source: .calib, argument: 0)
        let r = try #require(store.record(sensor: "slot1"))
        #expect(r.source == .calib)
        #expect(r.argument == 0)
        #expect(r.changes == [.init(key: "slot1_temp_offset_c", old: "0.0", new: "-0.4"),
                              .init(key: "slot1_rh_offset_pct", old: "0.0", new: "2.1")])
        #expect(r.appliedAt == Self.t0.timeIntervalSince1970)
    }

    @Test func goodResultAfterTheCalibWait() throws {
        let (store, clock, _) = Self.store()
        store.record(applied: Self.changes, source: .calib, argument: 0)
        clock.date = Self.t0.addingTimeInterval(25 * Self.hour)
        let r = try #require(store.record(sensor: "slot1"))
        // exactly at the limits 0.2 C and 1.0 %RH is good
        let reply = try Self.calibReply(tempDiff: "-0.2", rhDiff: "1.0")
        let result = CalibrationCheck.evaluate(r, now: store.now, calib: reply)
        #expect(result == .good)
        #expect(result.text == "Good")
    }

    @Test func badResultNamesTheDeviation() throws {
        let (store, clock, _) = Self.store()
        store.record(applied: Self.changes, source: .spread, argument: 6)
        clock.date = Self.t0.addingTimeInterval(7 * Self.hour)
        let r = try #require(store.record(sensor: "slot1"))
        let result = CalibrationCheck.evaluate(r, now: store.now, spread: try Self.spreadReply(tempDev: "0.3", rhDev: "-0.4"))
        #expect(result == .notGood(temp: 0.3, rh: -0.4))
        #expect(result.text == "Not good: +0.3 °C / -0.4 %RH")
        // humidity alone above 1.0 %RH is not good either
        let rhBad = CalibrationCheck.evaluate(r, now: store.now, spread: try Self.spreadReply(tempDev: "0.1", rhDev: "1.1"))
        #expect(rhBad == .notGood(temp: 0.1, rh: 1.1))
    }

    @Test func checkBeforeTheWaitIsTooEarly() throws {
        let (store, clock, _) = Self.store()
        store.record(applied: Self.changes, source: .calib, argument: 0)
        let r = try #require(store.record(sensor: "slot1"))
        let reply = try Self.calibReply(tempDiff: "0.0", rhDiff: "0.0")
        #expect(CalibrationCheck.evaluate(r, now: store.now, calib: reply) == .tooEarly(hours: 25))
        clock.date = Self.t0.addingTimeInterval(2.5 * Self.hour)
        let result = CalibrationCheck.evaluate(r, now: store.now, calib: reply)
        #expect(result == .tooEarly(hours: 23))
        #expect(result.text == "Too early: 23 h")
        clock.date = Self.t0.addingTimeInterval(25 * Self.hour - 1)
        #expect(CalibrationCheck.evaluate(r, now: store.now, calib: reply) == .tooEarly(hours: 1))
        // a clock that ran back gives the full wait
        clock.date = Self.t0.addingTimeInterval(-10 * Self.hour)
        #expect(CalibrationCheck.evaluate(r, now: store.now, calib: reply) == .tooEarly(hours: 25))
    }

    @Test func newBatchForTheSameSensorReplacesTheRecord() throws {
        let (store, clock, _) = Self.store()
        store.record(applied: Self.changes, source: .calib, argument: 0)
        store.record(applied: [ConfigChange(key: "slot2_temp_offset_c", old: "0.0", new: "0.5")], source: .calib, argument: 0)
        #expect(store.records().map(\.sensor) == ["slot1", "slot2"])
        clock.date = Self.t0.addingTimeInterval(3 * Self.hour)
        store.record(applied: [ConfigChange(key: "slot1_rh_offset_pct", old: "2.1", new: "1.5")], source: .spread, argument: 12)
        let r = try #require(store.record(sensor: "slot1"))
        #expect(store.records().count == 2)
        #expect(r.source == .spread)
        #expect(r.argument == 12)
        #expect(r.changes == [.init(key: "slot1_rh_offset_pct", old: "2.1", new: "1.5")])
        #expect(r.appliedAt == Self.t0.addingTimeInterval(3 * Self.hour).timeIntervalSince1970)
    }

    @Test func corruptStoredRecordGivesNoRecord() throws {
        let item = "{\"sensor\":\"slot1\",\"source\":\"calib\",\"argument\":0,\"changes\":[{\"key\":\"a\",\"old\":\"b\",\"new\":\"c\"}],\"appliedAt\":1}"
        for text in ["", "not json", "[]", "{}", "[1]", "[{\"sensor\":\"slot1\"}]",
                     "[{\"sensor\":\"slot1\",\"source\":\"calib\",\"argument\":0,\"changes\":[],\"appliedAt\":1}]",
                     "[{\"sensor\":\"slot1\",\"source\":\"calib\",\"argument\":99,\"changes\":[{\"key\":\"a\",\"old\":\"b\",\"new\":\"c\"}],\"appliedAt\":1}]",
                     "[{\"sensor\":\"slot1\",\"source\":\"spread\",\"argument\":5,\"changes\":[{\"key\":\"a\",\"old\":\"b\",\"new\":\"c\"}],\"appliedAt\":1}]",
                     "[{\"sensor\":\"slot4294967297\",\"source\":\"calib\",\"argument\":0,\"changes\":[{\"key\":\"a\",\"old\":\"b\",\"new\":\"c\"}],\"appliedAt\":1}]",
                     "[{\"sensor\":\"slot1\",\"source\":\"calib\",\"argument\":0,\"changes\":[{\"key\":\"\",\"old\":\"b\",\"new\":\"c\"}],\"appliedAt\":1}]",
                     "[{\"sensor\":\"slot1\",\"source\":\"calib\",\"argument\":0,\"changes\":[{\"key\":\"a\",\"old\":\"b\",\"new\":\"\"}],\"appliedAt\":1}]",
                     "[{\"sensor\":\"slot12\",\"source\":\"calib\",\"argument\":0,\"changes\":[{\"key\":\"a\",\"old\":\"b\",\"new\":\"c\"}],\"appliedAt\":1}]"] {
            let backing = InMemoryPinStore()
            backing.set(text, forKey: CalibrationCheckStore.storeKey)
            let (store, _, _) = Self.store(backing)
            #expect(store.records().isEmpty, "\(text)")
            #expect(store.record(sensor: "slot1") == nil)
        }
        // two records of one sensor: the last one wins
        let dup = InMemoryPinStore()
        dup.set("[\(item),\(item.replacingOccurrences(of: "\"argument\":0", with: "\"argument\":3"))]", forKey: CalibrationCheckStore.storeKey)
        #expect(Self.store(dup).0.records().map(\.argument) == [3])
        // a valid record beside a corrupt record survives
        let mixed = InMemoryPinStore()
        mixed.set("[1,\(item),{\"sensor\":\"slot2\"}]", forKey: CalibrationCheckStore.storeKey)
        #expect(Self.store(mixed).0.records().map(\.sensor) == ["slot1"])
        // a new record replaces the corrupt text
        let backing = InMemoryPinStore()
        backing.set("not json", forKey: CalibrationCheckStore.storeKey)
        let (store, _, _) = Self.store(backing)
        store.record(applied: Self.changes, source: .calib, argument: 0)
        #expect(store.records().map(\.sensor) == ["slot1"])
    }

    @Test func saltChecksHumidityAgainstReference() throws {
        let (store, clock, _) = Self.store()
        store.record(applied: [ConfigChange(key: "slot1_rh_offset_pct", old: "0.0", new: "2.0")], source: .salt, argument: 1)
        clock.date = Self.t0.addingTimeInterval(8 * Self.hour)
        let r = try #require(store.record(sensor: "slot1"))
        let good = try SaltReply.parse(lines: ["SALT slot1 hours=6 rh_mean=75.4 rh_span=0.3 temp_mean=20.0 reference=75.5"])
        #expect(CalibrationCheck.evaluate(r, now: store.now, salt: good) == .good)
        let bad = try SaltReply.parse(lines: ["SALT slot1 hours=6 rh_mean=77.0 rh_span=0.3 temp_mean=20.0 reference=75.5"])
        #expect(CalibrationCheck.evaluate(r, now: store.now, salt: bad) == .notGood(temp: nil, rh: 1.5))
        #expect(CalibrationCheck.evaluate(r, now: store.now, salt: bad).text == "Not good: +1.5 %RH")
        let other = try SaltReply.parse(lines: ["SALT slot2 hours=6 rh_mean=75.4 rh_span=0.3 temp_mean=20.0 reference=75.5"])
        if case .unavailable = CalibrationCheck.evaluate(r, now: store.now, salt: other) {} else { Issue.record("other sensor") }
    }

    @Test func missingSensorInReplyIsUnavailable() throws {
        let (store, clock, _) = Self.store()
        store.record(applied: Self.changes, source: .calib, argument: 0)
        clock.date = Self.t0.addingTimeInterval(25 * Self.hour)
        let r = try #require(store.record(sensor: "slot1"))
        let reply = try CalibrationReply.parse(lines: ["REFERENCE onboard", "SKIP slot1 stale"])
        if case .unavailable = CalibrationCheck.evaluate(r, now: store.now, calib: reply) {} else { Issue.record("stale") }
        if case .unavailable = CalibrationCheck.evaluate(r, now: store.now) {} else { Issue.record("no reply") }
    }

    @Test func secondApplyKeepsTheNewValueOfTheFirstAsOldValue() throws {
        let (store, _, _) = Self.store()
        store.record(applied: Self.changes, source: .calib, argument: 0)
        // the cached config still shows 0.0
        store.record(applied: [ConfigChange(key: "slot1_temp_offset_c", old: "0.0", new: "-0.2")], source: .calib, argument: 0)
        #expect(try #require(store.record(sensor: "slot1")).changes == [.init(key: "slot1_temp_offset_c", old: "-0.4", new: "-0.2")])
    }

    @Test func calibBadResultAndMissingSensor() throws {
        let (store, clock, _) = Self.store()
        store.record(applied: Self.changes, source: .calib, argument: 0)
        clock.date = Self.t0.addingTimeInterval(25 * Self.hour)
        let r = try #require(store.record(sensor: "slot1"))
        #expect(CalibrationCheck.evaluate(r, now: store.now, calib: try Self.calibReply(tempDiff: "0.3", rhDiff: "0.0")) == .notGood(temp: 0.3, rh: 0.0))
        let other = try CalibrationReply.parse(lines: ["REFERENCE onboard", "SENSOR slot2 hours=12 temp_offset=0.0 rh_offset=0.0 temp_diff=0.0 rh_diff=0.0"])
        if case .unavailable = CalibrationCheck.evaluate(r, now: store.now, calib: other) {} else { Issue.record("missing sensor") }
    }

    @Test func skippedAndUnstableRepliesAreUnavailable() throws {
        let (store, clock, _) = Self.store()
        store.record(applied: Self.changes, source: .spread, argument: 6)
        clock.date = Self.t0.addingTimeInterval(7 * Self.hour)
        let r = try #require(store.record(sensor: "slot1"))
        let skipped = try SpreadReply.parse(lines: ["SKIP sensors=2"])
        if case .unavailable = CalibrationCheck.evaluate(r, now: store.now, spread: skipped) {} else { Issue.record("spread skip") }
        store.record(applied: [ConfigChange(key: "slot1_rh_offset_pct", old: "0.0", new: "2.0")], source: .salt, argument: 1)
        clock.date = Self.t0.addingTimeInterval(20 * Self.hour)
        let s = try #require(store.record(sensor: "slot1"))
        for line in ["SKIP slot1 hours=3", "UNSTABLE slot1 span=2.5"] {
            let reply = try SaltReply.parse(lines: [line])
            if case .unavailable = CalibrationCheck.evaluate(s, now: store.now, salt: reply) {} else { Issue.record("\(line)") }
        }
    }

    @Test func keysMapToSensorNames() {
        #expect(CalibrationCheckRecord.sensorName(ofKey: "temp_offset_c") == "onboard")
        #expect(CalibrationCheckRecord.sensorName(ofKey: "rh_offset_pct") == "onboard")
        #expect(CalibrationCheckRecord.sensorName(ofKey: "slot10_rh_offset_pct") == "slot10")
        #expect(CalibrationCheckRecord.sensorName(ofKey: "slot11_rh_offset_pct") == nil)
        #expect(CalibrationCheckRecord.sensorName(ofKey: "usb_temp_offset_c") == nil)
        #expect(CalibrationCheckRecord.sensorName(ofKey: "slot1_rh_gain") == nil)
        #expect(CalibrationCheckRecord.sensorName(ofKey: "slot0_rh_offset_pct") == nil)
    }

    @Test func waitDependsOnTheCommand() throws {
        func record(_ source: CalibrationCheckRecord.Source, _ argument: Int) -> CalibrationCheckRecord {
            CalibrationCheckRecord(sensor: "slot1", source: source, argument: argument,
                                   changes: [.init(key: "slot1_temp_offset_c", old: "0.0", new: "-0.4")], appliedAt: Self.t0)
        }
        #expect(record(.salt, 1).waitHours == 7)
        #expect(record(.spread, 6).waitHours == 7)
        #expect(record(.spread, 24).waitHours == 25)
        #expect(record(.calib, 0).waitHours == 25)
        let end = Self.t0.addingTimeInterval(24 * Self.hour)
        #expect(CalibrationCheck.hoursToWait(record(.spread, 24), now: end) == 1)
        #expect(CalibrationCheck.hoursToWait(record(.spread, 24), now: end.addingTimeInterval(Self.hour)) == nil)
        // 6 h after a SALT apply is still too early: the first new sample can land an hour later
        #expect(CalibrationCheck.hoursToWait(record(.salt, 1), now: Self.t0.addingTimeInterval(6 * Self.hour)) == 1)
    }

    @Test func onlyTheChangedValuesAreJudged() throws {
        let (store, clock, _) = Self.store()
        store.record(applied: [ConfigChange(key: "slot1_temp_offset_c", old: "0.0", new: "-0.4")], source: .spread, argument: 6)
        clock.date = Self.t0.addingTimeInterval(7 * Self.hour)
        let r = try #require(store.record(sensor: "slot1"))
        // humidity deviates by 2.0 %RH, but the apply changed temperature only
        #expect(CalibrationCheck.evaluate(r, now: store.now, spread: try Self.spreadReply(tempDev: "0.1", rhDev: "2.0")) == .good)
        #expect(CalibrationCheck.evaluate(r, now: store.now, spread: try Self.spreadReply(tempDev: "0.3", rhDev: "2.0")) == .notGood(temp: 0.3, rh: nil))
    }
}
