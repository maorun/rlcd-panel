import Foundation
import Testing
@testable import RlcdProtocol

private func row(_ time: String, _ event: String, _ detail: Int) -> EventRow {
    EventRow(time: time, event: event, detail: detail)
}

private func date(_ time: String) -> Date { AlertTimeline.parse(time: time)! }

@Suite struct BadgeIntervalTests {
    let now = AlertTimeline.parse(time: "2026-01-01 12:00:00")!

    @Test func setAndClearRowGiveOneInterval() {
        // DRY_AIR detail 0x0102: set (high byte 1) for tile 2, then 0x0002: clear.
        let intervals = AlertTimeline.badges(rows: [
            row("2026-01-01 08:00:00", "DRY_AIR", 258),
            row("2026-01-01 09:30:00", "DRY_AIR", 2),
        ], now: now)
        #expect(intervals == [BadgeInterval(
            event: "DRY_AIR", tile: 2, start: date("2026-01-01 08:00:00"), end: date("2026-01-01 09:30:00"),
            isOpen: false, startUnknown: false)])
        #expect(intervals.first?.badge == "D")
    }

    @Test func heatTileSetAndClearGiveOneInterval() {
        // HEAT_TILE detail 0x0103: hot for tile 3, then 0x0003: good again; 0x020B is a bad detail.
        let intervals = AlertTimeline.badges(rows: [
            row("2026-01-01 08:00:00", "HEAT_TILE", 259),
            row("2026-01-01 09:00:00", "HEAT_TILE", 3),
            row("2026-01-01 09:30:00", "HEAT_TILE", 523),
        ], now: now)
        #expect(intervals == [BadgeInterval(
            event: "HEAT_TILE", tile: 3, start: date("2026-01-01 08:00:00"), end: date("2026-01-01 09:00:00"),
            isOpen: false, startUnknown: false)])
        #expect(intervals.first?.badge == "H")
    }

    @Test func windowOpenWithoutClearEndsAtNow() {
        let intervals = AlertTimeline.badges(rows: [row("2026-01-01 10:00:00", "WINDOW_OPEN", 257)], now: now)
        #expect(intervals == [BadgeInterval(
            event: "WINDOW_OPEN", tile: 1, start: date("2026-01-01 10:00:00"), end: now,
            isOpen: true, startUnknown: false)])
        #expect(intervals.first?.badge == "W")
    }

    @Test func clearWithoutSetGivesStartUnknown() {
        let intervals = AlertTimeline.badges(rows: [
            row("2026-01-01 07:00:00", "SD_FAULT", 2),
            row("2026-01-01 09:00:00", "WALL_DAMP", 3),
        ], now: now)
        #expect(intervals == [BadgeInterval(
            event: "WALL_DAMP", tile: 3, start: date("2026-01-01 07:00:00"), end: date("2026-01-01 09:00:00"),
            isOpen: false, startUnknown: true)])
        #expect(intervals.first?.badge == "C")
    }

    @Test func twoTilesOfOneBadgeGiveTwoIntervals() {
        let intervals = AlertTimeline.badges(rows: [
            row("2026-01-01 08:00:00", "SENSOR_DRIFT", 256),
            row("2026-01-01 08:10:00", "SENSOR_DRIFT", 261),
            row("2026-01-01 09:00:00", "SENSOR_DRIFT", 0),
        ], now: now)
        #expect(intervals.count == 2)
        #expect(intervals.map(\.tile) == [0, 5])
        #expect(intervals[0].isOpen == false)
        #expect(intervals[0].end == date("2026-01-01 09:00:00"))
        #expect(intervals[1].isOpen == true)
        #expect(intervals[1].end == now)
        #expect(intervals[1].badge == "S")
    }

    @Test func sameTileOfTwoBadgesStaysSeparate() {
        let intervals = AlertTimeline.badges(rows: [
            row("2026-01-01 08:00:00", "DRY_AIR", 257),
            row("2026-01-01 08:30:00", "WINDOW_OPEN", 257),
            row("2026-01-01 09:00:00", "DRY_AIR", 1),
        ], now: now)
        #expect(intervals.count == 2)
        #expect(intervals.first { $0.event == "DRY_AIR" }?.isOpen == false)
        #expect(intervals.first { $0.event == "WINDOW_OPEN" }?.isOpen == true)
    }

    @Test func invalidDetailAndOtherCodesAreIgnored() {
        let intervals = AlertTimeline.badges(rows: [
            row("2026-01-01 08:00:00", "DRY_AIR", 11),      // tile 11
            row("2026-01-01 08:01:00", "DRY_AIR", 513),     // state 2
            row("2026-01-01 08:02:00", "DRY_AIR", 70000),   // above 16 bits
            row("2026-01-01 08:03:00", "ALERT_RISE", 1),
        ], now: now)
        #expect(intervals.isEmpty)
    }

    @Test func droppedRowMarksOpenIntervalUnknown() {
        let intervals = AlertTimeline.badges(rows: [
            row("2026-01-01 06:00:00", "WINDOW_OPEN", 256),
            row("2026-01-01 07:00:00", "EVENTS_DROPPED", 3),
            row("2026-01-01 08:00:00", "WINDOW_OPEN", 0),
        ], now: now)
        #expect(intervals.map(\.startUnknown) == [true])
    }

    @Test func duplicateSetKeepsFirstStart() {
        let intervals = AlertTimeline.badges(rows: [
            row("2026-01-01 08:00:00", "DRY_AIR", 257),
            row("2026-01-01 08:30:00", "DRY_AIR", 257),
            row("2026-01-01 09:00:00", "DRY_AIR", 1),
        ], now: now)
        #expect(intervals.count == 1)
        #expect(intervals.first?.start == date("2026-01-01 08:00:00"))
    }

    @Test func roomNameUsesTile() {
        let interval = BadgeInterval(event: "DRY_AIR", tile: 2, start: now, end: now, isOpen: false, startUnknown: false)
        #expect(interval.room(config: nil) == "Slot 2")
    }

    @Test func parsesEventsReplySample() throws {
        let rows = try loadEventsFixture(named: "events_reply_sample.txt")
        let intervals = AlertTimeline.badges(rows: rows, now: now)
        // The sample holds one WINDOW_OPEN set row (0x0101, tile 1) and no clear row. The dropped row precedes
        // the set row, so the start is known.
        #expect(intervals.count == 1)
        #expect(intervals.first?.event == "WINDOW_OPEN")
        #expect(intervals.first?.tile == 1)
        #expect(intervals.first?.isOpen == true)
        #expect(intervals.first?.startUnknown == false)
        #expect(intervals.first?.start == date("2026-11-18 09:22:00"))
    }

    /// Reads tests/fixtures/badge_events_sample.txt through the `ReplyAssembler`.
    private func badgeFixtureRows() throws -> [EventRow] { try loadEventsFixture(named: "badge_events_sample.txt") }

    @Test func badgeFixtureIsAcceptedWithItsCrcLine() throws {
        #expect(try badgeFixtureRows().count == 13)
    }

    @Test func badgeFixtureWithChangedByteFailsTheCrcCheck() throws {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { url.deleteLastPathComponent() }
        var data = try Data(contentsOf: url.appendingPathComponent("tests/fixtures/badge_events_sample.txt"))
        let index = try #require(data.firstIndex(of: UInt8(ascii: "9")))
        data[index] = UInt8(ascii: "8")
        var assembler = ReplyAssembler()
        let replies = assembler.append(data)
        #expect(replies.count == 1)
        if case .frame? = replies.first { Issue.record("a changed byte passed the CRC check") }
    }

    @Test func badgeFixtureGivesSevenIntervalsWithTilesAndStartFlags() throws {
        let fixtureNow = date("2026-11-18 12:00:00")
        let intervals = AlertTimeline.badges(rows: try badgeFixtureRows(), now: fixtureNow)
        #expect(intervals.map { "\($0.event)\($0.tile)" } == [
            "SENSOR_DRIFT0", "WINDOW_OPEN1", "WALL_DAMP2", "DRY_AIR3", "DRY_AIR4", "SENSOR_DRIFT6", "SENSOR_DRIFT5"])
        #expect(intervals.map(\.startUnknown) == [true, false, false, false, false, false, false])
        #expect(intervals.map(\.isOpen) == [false, false, false, false, false, false, true])
        // The clear row without a set row starts at the first row with a valid time.
        #expect(intervals[0].start == date("2026-11-18 09:00:00"))
        #expect(intervals[0].end == date("2026-11-18 09:45:00"))
        #expect(intervals.map(\.badge) == ["S", "W", "C", "D", "D", "S", "S"])
        // Two tiles of the badge D.
        #expect(intervals.filter { $0.event == "DRY_AIR" }.map(\.tile) == [3, 4])
    }

    @Test func badgeFixtureOpenIntervalEndsAtInjectedNow() throws {
        let rows = try badgeFixtureRows()
        let early = date("2026-11-18 10:30:00")
        let late = date("2026-11-18 15:00:00")
        let open1 = AlertTimeline.badges(rows: rows, now: early).filter(\.isOpen)
        let open2 = AlertTimeline.badges(rows: rows, now: late).filter(\.isOpen)
        #expect(open1.count == 1)
        #expect(open1.first?.event == "SENSOR_DRIFT")
        #expect(open1.first?.tile == 5)
        #expect(open1.first?.start == date("2026-11-18 10:00:00"))
        #expect(open1.first?.end == early)
        #expect(open2.first?.end == late)
    }

    @Test func lineShowsBadgeRoomStartEndAndDuration() {
        let closed = BadgeInterval(event: "DRY_AIR", tile: 2, start: date("2026-01-01 08:00:00"),
                                   end: date("2026-01-01 09:05:00"), isOpen: false, startUnknown: false)
        #expect(BadgeIntervalText.line(closed, config: nil) == "D  Slot 2  2026-01-01 08:00 - 2026-01-01 09:05  1 h 05 min")
        let open = BadgeInterval(event: "WINDOW_OPEN", tile: 0, start: date("2026-01-01 11:20:00"),
                                 end: now, isOpen: true, startUnknown: true)
        #expect(BadgeIntervalText.line(open, config: nil) == "W  Onboard  ~2026-01-01 11:20 - now  40 min")
    }

    @Test func durationTexts() {
        #expect(BadgeIntervalText.duration(30) == "< 1 min")
        #expect(BadgeIntervalText.duration(-5) == "< 1 min")
        #expect(BadgeIntervalText.duration(3600) == "1 h 00 min")
    }

    @Test func twoCyclesOfOneTileGiveTwoIntervalsInOrder() {
        let intervals = AlertTimeline.badges(rows: [
            row("2026-01-01 06:00:00", "DRY_AIR", 257),
            row("2026-01-01 07:00:00", "DRY_AIR", 1),
            row("2026-01-01 08:00:00", "DRY_AIR", 257),
            row("2026-01-01 09:00:00", "DRY_AIR", 1),
        ], now: now)
        #expect(intervals.map(\.start) == [date("2026-01-01 06:00:00"), date("2026-01-01 08:00:00")])
        #expect(intervals.map(\.end) == [date("2026-01-01 07:00:00"), date("2026-01-01 09:00:00")])
        #expect(intervals.map(\.startUnknown) == [false, false])
        #expect(intervals.map(\.isOpen) == [false, false])
    }

    @Test func clearWithBadTimeClosesWithoutInterval() {
        let intervals = AlertTimeline.badges(rows: [
            row("2026-01-01 08:00:00", "DRY_AIR", 257),
            row("", "DRY_AIR", 1),
        ], now: now)
        #expect(intervals.isEmpty)
    }

    @Test func setWithBadTimeGivesNoInterval() {
        let intervals = AlertTimeline.badges(rows: [
            row("2026-01-01 08:00:00", "SD_FAULT", 2),
            row("", "DRY_AIR", 257),
        ], now: now)
        #expect(intervals.isEmpty)
    }

    @Test func tileTenIsValidAndRoomNameUsesSlotTen() {
        let intervals = AlertTimeline.badges(rows: [row("2026-01-01 08:00:00", "SENSOR_DRIFT", 266)], now: now)
        #expect(intervals.count == 1)
        #expect(intervals.first?.tile == 10)
        #expect(intervals.first?.room(config: nil) == "Slot 10")
    }

    @Test func sortsEqualStartsByEventAndTile() {
        let intervals = AlertTimeline.badges(rows: [
            row("2026-01-01 08:00:00", "WINDOW_OPEN", 258),
            row("2026-01-01 08:00:00", "DRY_AIR", 257),
            row("2026-01-01 08:00:00", "DRY_AIR", 256),
        ], now: now)
        #expect(intervals.map { "\($0.event)\($0.tile)" } == ["DRY_AIR0", "DRY_AIR1", "WINDOW_OPEN2"])
    }

    @Test func describeKeepsTextsAndRawFallbacksForBadgeCodes() {
        #expect(EventText.describe(row("t", "WINDOW_OPEN", 257)) == "Window open in Slot 1")
        #expect(EventText.describe(row("t", "WINDOW_OPEN", 0)) == "Window closed in Onboard")
        #expect(EventText.describe(row("t", "WALL_DAMP", 258)) == "Wall damp (Slot 2)")
        #expect(EventText.describe(row("t", "DRY_AIR", 2)) == "Air humidity good (Slot 2)")
        #expect(EventText.describe(row("t", "SENSOR_DRIFT", 266)) == "Sensor drift (Slot 10)")
        for event in ["WINDOW_OPEN", "WALL_DAMP", "DRY_AIR", "SENSOR_DRIFT"] {
            for detail in [11, 513, 70000] {
                #expect(EventText.describe(row("t", event, detail)) == "\(event),\(detail)")
            }
        }
    }
}
