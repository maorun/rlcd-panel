import Testing
import Foundation
@testable import RlcdProtocol

@Suite struct EventFilterTests {
    /// Reads tests/fixtures/events_reply_sample.txt, the file of the Android phone tests.
    private func sampleRows() throws -> [EventRow] { try loadEventsFixture(named: "events_reply_sample.txt") }

    @Test func rowCountOfEachCategory() throws {
        let rows = try sampleRows()
        #expect(rows.count == 24)
        #expect(EventFilter.apply(rows, [.alerts]).count == 3)
        #expect(EventFilter.apply(rows, [.sensors]).count == 10)
        #expect(EventFilter.apply(rows, [.power]).count == 4)
        #expect(EventFilter.apply(rows, [.config]).count == 2)
        #expect(EventFilter.apply(rows, [.other]).count == 4)
        #expect(EventFilter.apply(rows, [.alerts, .power]).count == 7)
    }

    @Test func shtc3FaultIsSensor() throws {
        let rows = try sampleRows()
        #expect(EventFilter.category(of: "SHTC3_FAULT") == .sensors)
        #expect(EventFilter.category(of: "SENSOR_BATTERY") == .sensors)
        #expect(EventFilter.category(of: "JUMP_DROP") == .sensors)
        #expect(EventFilter.apply(rows, [.sensors]).map(\.event).contains("SHTC3_FAULT"))
    }

    @Test func scanFaultIsSensorAndWindowOpenIsClimate() throws {
        let rows = try sampleRows()
        #expect(EventFilter.category(of: "SCAN_FAULT") == .sensors)
        #expect(EventFilter.apply(rows, [.sensors]).map(\.event).contains("SCAN_FAULT"))
        #expect(EventFilter.category(of: "WINDOW_OPEN") == .climate)
        #expect(EventFilter.category(of: "WALL_DAMP") == .climate)
        #expect(EventFilter.category(of: "DRY_AIR") == .climate)
        #expect(EventFilter.category(of: "HEAT_TILE") == .climate)
        #expect(EventFilter.apply(rows, [.climate]).count == 1)
    }

    @Test func futureEventIsOther() throws {
        let rows = try sampleRows()
        #expect(EventFilter.category(of: "FUTURE_EVENT") == .other)
        #expect(EventFilter.apply(rows, [.other]).map(\.event).contains("FUTURE_EVENT"))
        #expect(EventFilter.apply(rows, [.other]).map(\.event).contains("EVENTS_DROPPED"))
        #expect(EventFilter.apply(rows, [.other]).map(\.event).contains("CLOCK_SET"))
    }

    @Test func emptySetKeepsAllRowsInOrder() throws {
        let rows = try sampleRows()
        #expect(EventFilter.apply(rows, []) == rows)
    }

    @Test func filterKeepsOrder() throws {
        let rows = try sampleRows()
        let power = EventFilter.apply(rows, [.power])
        #expect(power.map(\.event) == ["RESET", "SD_FAULT", "POWER_SOURCE", "POWER_SOURCE"])
    }

    @Test func mixedCategoriesKeepFixtureOrder() throws {
        let rows = try sampleRows()
        let kept = EventFilter.apply(rows, [.alerts, .power])
        #expect(kept.map(\.event) == ["RESET", "SD_FAULT", "ALERT_RISE", "ALERT_ACK", "BATTERY_LOW_SET", "POWER_SOURCE", "POWER_SOURCE"])
    }
}
