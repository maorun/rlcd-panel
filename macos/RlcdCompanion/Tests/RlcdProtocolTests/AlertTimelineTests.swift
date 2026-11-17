import Foundation
import Testing
@testable import RlcdProtocol

private func row(_ time: String, _ event: String, _ detail: Int) -> EventRow {
    EventRow(time: time, event: event, detail: detail)
}

private func date(_ time: String) -> Date { AlertTimeline.parse(time: time)! }

@Suite struct AlertTimelineTests {
    let now = AlertTimeline.parse(time: "2026-01-01 12:00:00")!

    @Test func riseAndFallGiveOneInterval() {
        let intervals = AlertTimeline.build(rows: [
            row("2026-01-01 08:00:00", "ALERT_RISE", 1),
            row("2026-01-01 09:30:00", "ALERT_FALL", 1),
        ], now: now)
        #expect(intervals == [AlertInterval(
            alert: 1, start: date("2026-01-01 08:00:00"), end: date("2026-01-01 09:30:00"),
            isOpen: false, startUnknown: false)])
        #expect(intervals.first?.name == "FREEZER")
    }

    @Test func riseWithoutFallStaysOpenUntilNow() {
        let intervals = AlertTimeline.build(rows: [row("2026-01-01 10:00:00", "ALERT_RISE", 2)], now: now)
        #expect(intervals == [AlertInterval(
            alert: 2, start: date("2026-01-01 10:00:00"), end: now, isOpen: true, startUnknown: false)])
    }

    @Test func fallWithoutRiseStartsAtFirstRowAndIsUnknown() {
        let intervals = AlertTimeline.build(rows: [
            row("2026-01-01 07:00:00", "SD_FAULT", 2),
            row("2026-01-01 09:00:00", "ALERT_FALL", 4),
        ], now: now)
        #expect(intervals == [AlertInterval(
            alert: 4, start: date("2026-01-01 07:00:00"), end: date("2026-01-01 09:00:00"),
            isOpen: false, startUnknown: true)])
    }

    @Test func intervalSpanningDroppedRowIsUnknown() {
        let intervals = AlertTimeline.build(rows: [
            row("2026-01-01 06:00:00", "ALERT_RISE", 8),
            row("2026-01-01 07:00:00", "EVENTS_DROPPED", 3),
            row("2026-01-01 08:00:00", "ALERT_FALL", 8),
            row("2026-01-01 09:00:00", "ALERT_RISE", 8),
            row("2026-01-01 10:00:00", "ALERT_FALL", 8),
        ], now: now)
        #expect(intervals.map(\.startUnknown) == [true, false])
    }

    @Test func fallAfterDroppedRowIsUnknown() {
        let intervals = AlertTimeline.build(rows: [
            row("2026-01-01 07:00:00", "EVENTS_DROPPED", 5),
            row("2026-01-01 08:00:00", "ALERT_FALL", 1),
        ], now: now)
        #expect(intervals.count == 1)
        #expect(intervals.first?.startUnknown == true)
    }

    @Test func twoAlertBitsGiveSeparateIntervals() {
        let intervals = AlertTimeline.build(rows: [
            row("2026-01-01 08:00:00", "ALERT_RISE", 1),
            row("2026-01-01 08:10:00", "ALERT_RISE", 128),
            row("2026-01-01 09:00:00", "ALERT_FALL", 1),
        ], now: now)
        #expect(intervals.count == 2)
        let freezer = intervals.first { $0.alert == 1 }
        let heat = intervals.first { $0.alert == 128 }
        #expect(freezer?.isOpen == false)
        #expect(freezer?.end == date("2026-01-01 09:00:00"))
        #expect(heat?.isOpen == true)
        #expect(heat?.end == now)
    }

    @Test func otherEventCodesGiveNoInterval() {
        let intervals = AlertTimeline.build(rows: [
            row("2026-01-01 08:00:00", "CONFIG_SET", 1),
            row("2026-01-01 08:05:00", "CONFIG_SET", 2),
            row("2026-01-01 08:10:00", "RESET", 1),
        ], now: now)
        #expect(intervals.isEmpty)
    }

    @Test func duplicateRiseKeepsFirstStart() {
        let intervals = AlertTimeline.build(rows: [
            row("2026-01-01 08:00:00", "ALERT_RISE", 1),
            row("2026-01-01 08:30:00", "ALERT_RISE", 1),
            row("2026-01-01 09:00:00", "ALERT_FALL", 1),
        ], now: now)
        #expect(intervals.count == 1)
        #expect(intervals.first?.start == date("2026-01-01 08:00:00"))
    }

    @Test func fallWithBadTimeClosesWithoutInterval() {
        let intervals = AlertTimeline.build(rows: [
            row("2026-01-01 08:00:00", "ALERT_RISE", 1),
            row("", "ALERT_FALL", 1),
        ], now: now)
        #expect(intervals.isEmpty)
    }

    @Test func openIntervalAfterDroppedRowIsKnown() {
        let intervals = AlertTimeline.build(rows: [
            row("2026-01-01 11:00:00", "EVENTS_DROPPED", 2),
            row("2026-01-01 10:00:00", "ALERT_RISE", 1),
        ], now: now)
        #expect(intervals.count == 1)
        #expect(intervals.first?.isOpen == true)
        #expect(intervals.first?.startUnknown == false)
    }

    @Test func droppedRowTimeDoesNotStartFallInterval() {
        let intervals = AlertTimeline.build(rows: [
            row("2026-01-01 11:30:00", "EVENTS_DROPPED", 2),
            row("2026-01-01 08:00:00", "ALERT_FALL", 1),
        ], now: now)
        #expect(intervals.first?.start == date("2026-01-01 08:00:00"))
        #expect(intervals.first?.startUnknown == true)
    }

    @Test func parsesPanelTimeAsLocalCalendarDate() {
        let expected = Calendar.current.date(from: DateComponents(year: 2026, month: 1, day: 1, hour: 8, minute: 30, second: 15))
        #expect(AlertTimeline.parse(time: "2026-01-01 08:30:15") == expected)
        #expect(AlertTimeline.parse(time: "") == nil)
    }

    @Test func droppedRowFirstMarksSpanningRiseAndKeepsLaterPair() {
        let intervals = AlertTimeline.build(rows: [
            row("2026-01-01 11:00:00", "EVENTS_DROPPED", 4),
            row("2026-01-01 08:00:00", "ALERT_FALL", 1),
            row("2026-01-01 09:00:00", "ALERT_RISE", 1),
            row("2026-01-01 09:30:00", "ALERT_FALL", 1),
        ], now: now)
        #expect(intervals.map(\.startUnknown) == [true, false])
    }
}
