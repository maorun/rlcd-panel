import Foundation
import Testing
@testable import RlcdProtocol

struct ReceptionTests {
    private static let move = "Move the sensor nearer to the panel"
    private static let battery = "Check the sensor battery"

    private func row(scans: Int, received: Int) -> ReceptionRow {
        ReceptionRow(slot: 1, mac: "AA:BB:CC:DD:EE:01", name: "", scans: scans, received: received,
                     cells: (0..<scans).map { $0 < received })
    }

    @Test func weakRateWithFarSignalAdvisesMoving() {
        #expect(ReceptionWatch.advice(rate: 49, rssi: -86) == Self.move)
        #expect(ReceptionWatch.advice(rate: 0, rssi: -127) == Self.move)
    }

    @Test func weakRateAtMinusEightyFiveAdvisesBattery() {
        #expect(ReceptionWatch.advice(rate: 10, rssi: -85) == Self.battery)
        #expect(ReceptionWatch.advice(rate: 49, rssi: -85) == Self.battery)
    }

    @Test func weakRateWithGoodSignalAdvisesBattery() {
        #expect(ReceptionWatch.advice(rate: 0, rssi: -60) == Self.battery)
    }

    @Test func weakRateWithoutRssiAdvisesBattery() {
        #expect(ReceptionWatch.advice(rate: 20, rssi: nil) == Self.battery)
    }

    @Test func rateAtFiftyGivesNoAdvice() {
        #expect(ReceptionWatch.advice(rate: 50, rssi: -90) == nil)
        #expect(ReceptionWatch.advice(rate: 50, rssi: -60) == nil)
        #expect(ReceptionWatch.advice(rate: 50, rssi: nil) == nil)
    }

    @Test func rateAboveFiftyGivesNoAdvice() {
        #expect(ReceptionWatch.advice(rate: 100, rssi: -95) == nil)
    }

    @Test func rowAdviceAgreesWithIsWeakForEveryRate() {
        for scans in 1...ReceptionReply.maxScans {
            for received in 0...scans {
                let r = row(scans: scans, received: received)
                #expect((ReceptionWatch.advice(for: r, rssi: -90) != nil) == r.isWeak,
                        "scans \(scans) received \(received)")
            }
        }
    }

    @Test func rowWithoutScansGivesNoAdvice() {
        #expect(ReceptionWatch.advice(for: row(scans: 0, received: 0), rssi: -90) == nil)
    }

    @Test func rowAtHalfGivesNoAdviceAndBelowHalfGivesAdvice() {
        #expect(ReceptionWatch.advice(for: row(scans: 24, received: 12), rssi: -90) == nil)
        #expect(ReceptionWatch.advice(for: row(scans: 24, received: 11), rssi: -90) == Self.move)
        #expect(ReceptionWatch.advice(for: row(scans: 15, received: 7), rssi: -70) == Self.battery)
    }
}
