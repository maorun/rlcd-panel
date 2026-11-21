import Testing
@testable import RlcdProtocol

struct AlertNotificationPlanTests {
    @Test func actionBitsLowestFirst() {
        #expect(AlertNotificationPlan.actionBits(rising: AlertMask(rawValue: 0x32)) == [1, 5])
        #expect(AlertNotificationPlan.actionBits(rising: AlertMask(rawValue: 0x81)) == [0, 7])
    }

    @Test func chargeFullHasNoAction() {
        #expect(AlertNotificationPlan.actionBits(rising: AlertMask(rawValue: 0x10)) == [])
        #expect(AlertNotificationPlan.actionBits(rising: AlertMask(rawValue: 0)) == [])
    }

    @Test func identifierRoundTrip() {
        #expect(AlertNotificationPlan.identifier(bit: 5) == "rlcd_panel_alert_5")
        #expect(AlertNotificationPlan.bit(identifier: "rlcd_panel_alert_5") == 5)
        #expect(AlertNotificationPlan.bit(identifier: "rlcd_panel_alert_8") == nil)
        #expect(AlertNotificationPlan.bit(identifier: "rlcd_panel_wall_1") == nil)
        #expect(AlertNotificationPlan.bit(identifier: "rlcd_panel_alert_-1") == nil)
        #expect(AlertNotificationPlan.bit(identifier: "rlcd_panel_alert_x") == nil)
    }

    @Test func fullMaskSkipsChargeFullOnly() {
        #expect(AlertNotificationPlan.actionBits(rising: AlertMask(rawValue: 0xFF)) == [0, 1, 2, 3, 5, 6, 7])
    }
}
