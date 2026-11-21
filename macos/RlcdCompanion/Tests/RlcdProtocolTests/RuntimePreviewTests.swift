import Testing
@testable import RlcdProtocol

struct RuntimePreviewTests {
    private func schedule(_ wake: Int, night: Int = 0) -> RuntimePreview.Schedule {
        .init(wakeS: wake, nightS: night, quietStartHour: 22, quietEndHour: 7)
    }

    @Test func doubledIntervalGivesLongerRuntime() {
        let h = RuntimePreview.hours(runtimeHours: 400, avgMs: 6000, samples: 24, current: schedule(60), edited: schedule(120))
        // 400 * (120 + 6) / (60 + 6) = 763.6
        #expect(h == 764)
    }

    @Test func missingAwakeMeanGivesNil() throws {
        let awake = try AwakeMetrics.parse(line: "AWAKE last_ms=-- avg_ms=-- max_ms=-- samples=0")
        #expect(RuntimePreview.hours(runtimeHours: 400, avgMs: awake.avgMs, samples: awake.samples, current: schedule(60), edited: schedule(120)) == nil)
        #expect(RuntimePreview.text(nil) == "--")
    }

    @Test func missingRuntimeGivesNil() {
        #expect(RuntimePreview.hours(runtimeHours: nil, avgMs: 6000, samples: 24, current: schedule(60), edited: schedule(120)) == nil)
    }

    @Test func cappedRuntimeGivesNil() {
        #expect(RuntimePreview.hours(runtimeHours: 2388, avgMs: 6000, samples: 24, current: schedule(60), edited: schedule(120)) == nil)
    }

    @Test func fewSamplesOrLowMeanUseTheFirmwareConstant() {
        let constant = RuntimePreview.hours(runtimeHours: 400, avgMs: 10000, current: schedule(60), edited: schedule(120))
        #expect(RuntimePreview.hours(runtimeHours: 400, avgMs: 3000, samples: 7, current: schedule(60), edited: schedule(120)) == constant)
        #expect(RuntimePreview.hours(runtimeHours: 400, avgMs: 2000, samples: 24, current: schedule(60), edited: schedule(120)) == constant)
        #expect(RuntimePreview.hours(runtimeHours: 400, avgMs: 3000, samples: 8, current: schedule(60), edited: schedule(120)) != constant)
    }

    @Test func meanOfZeroOrMissingSamplesUseTheConstant() {
        let constant = RuntimePreview.hours(runtimeHours: 400, avgMs: 10000, samples: 24, current: schedule(60), edited: schedule(120))
        #expect(constant != nil)
        #expect(RuntimePreview.hours(runtimeHours: 400, avgMs: 0, samples: 24, current: schedule(60), edited: schedule(120)) == constant)
        #expect(RuntimePreview.hours(runtimeHours: 400, avgMs: 6000, samples: nil, current: schedule(60), edited: schedule(120)) == constant)
    }

    @Test func quietHoursWeighNineOfTwentyFourHours() {
        #expect(RuntimePreview.nightHours(startHour: 22, endHour: 7) == 9)
        #expect(RuntimePreview.nightHours(startHour: 1, endHour: 6) == 5)
        #expect(RuntimePreview.nightHours(startHour: 5, endHour: 5) == 0)
        let awake = 6.0
        let mixed = RuntimePreview.drainRate(schedule(60, night: 600), awakeS: awake)!
        let expected = (15.0 / (60 + awake) + 9.0 / (600 + awake)) / 24.0
        #expect(abs(mixed - expected) < 1e-12)
        // A night interval of 0 keeps the day interval for each hour.
        #expect(abs(RuntimePreview.drainRate(schedule(60), awakeS: awake)! - 1.0 / (60 + awake)) < 1e-12)
    }

    @Test func currentScheduleEqualsRuntimeValueWithinTenPercent() throws {
        let runtime = try RuntimeMetrics.parse(line: "RUNTIME h=412 samples=24 cycles=3.5 scan_saved_ms=0")
        let awake = try AwakeMetrics.parse(line: "AWAKE last_ms=6400 avg_ms=6500 max_ms=9000 samples=24")
        let quiet = try QuietMetrics.parse(line: "QUIET start_h=22 end_h=7 active=0 night_interval_s=300")
        let config = ["wake_interval_s": "60", "night_wake_interval_s": "300"]
        let h = RuntimePreview.hours(config: config, draft: [:], runtime: runtime, awake: awake, quiet: quiet)
        #expect(h != nil)
        #expect(abs(Double(h!) - 412) <= 41.2)
    }

    @Test func nightIntervalExtendsRuntime() {
        let base = RuntimePreview.hours(runtimeHours: 400, avgMs: 6000, samples: 24, current: schedule(60), edited: schedule(60))!
        let night = RuntimePreview.hours(runtimeHours: 400, avgMs: 6000, samples: 24, current: schedule(60), edited: schedule(60, night: 600))!
        #expect(night > base)
    }

    @Test func intervalOutsideKeyRangeGivesNil() {
        #expect(RuntimePreview.hours(runtimeHours: 400, avgMs: 6000, samples: 24, current: schedule(60), edited: schedule(0)) == nil)
        #expect(RuntimePreview.hours(runtimeHours: 400, avgMs: 6000, samples: 24, current: schedule(60), edited: schedule(901)) == nil)
        #expect(RuntimePreview.hours(runtimeHours: 400, avgMs: 6000, samples: 24, current: schedule(60), edited: schedule(60, night: 30)) == nil)
        #expect(RuntimePreview.hours(runtimeHours: 400, avgMs: 6000, samples: 24, current: schedule(60), edited: schedule(Int.max)) == nil)
    }

    @Test func resultStopsAtTheFirmwareCapAndBadQuietHourGivesNil() {
        let capped = RuntimePreview.hours(runtimeHours: 2000, avgMs: 6000, samples: 24, current: schedule(30), edited: schedule(900))
        #expect(capped == 2388)
        let bad = RuntimePreview.Schedule(wakeS: 60, nightS: 0, quietStartHour: 24, quietEndHour: 7)
        #expect(RuntimePreview.hours(runtimeHours: 400, avgMs: 6000, samples: 24, current: schedule(60), edited: bad) == nil)
    }

    @Test func draftValuesDriveTheEditedSchedule() throws {
        let runtime = try RuntimeMetrics.parse(line: "RUNTIME h=400 samples=24 cycles=1.0 scan_saved_ms=0")
        let awake = try AwakeMetrics.parse(line: "AWAKE last_ms=6000 avg_ms=6000 max_ms=9000 samples=24")
        let config = ["wake_interval_s": "60"]
        func run(_ draft: [String: String]) -> Int? {
            RuntimePreview.hours(config: config, draft: draft, runtime: runtime, awake: awake, quiet: nil)
        }
        #expect(run([:]) == 400)
        #expect(run(["wake_interval_s": "120"]) == 764)
        #expect(run(["wake_interval_s": "abc"]) == nil)
        #expect(run(["wake_interval_s": ""]) == nil)
        #expect(run(["wake_interval_s": "9000000000000000000"]) == nil)
        // A shorter draft quiet window weighs the night interval less, so the runtime falls.
        let night = ["night_wake_interval_s": "600"]
        #expect(run(night)! > run(night.merging(["quiet_hours_start_hour": "1", "quiet_hours_end_hour": "6"]) { $1 })!)
    }

    @Test func textFormat() {
        #expect(RuntimePreview.text(412) == "About 412 h on battery")
    }
}
