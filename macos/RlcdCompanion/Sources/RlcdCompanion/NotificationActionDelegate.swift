import RlcdProtocol
import UserNotifications

/// Takes the `Acknowledge` action of an alert notification and hands the alert bit to the model.
final class NotificationActionDelegate: NSObject, UNUserNotificationCenterDelegate, @unchecked Sendable {
    /// Runs on the main actor with the alert bit of the notification.
    @MainActor var onAcknowledge: ((Int) -> Void)?

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        guard response.actionIdentifier == AlertNotificationPlan.acknowledgeActionIdentifier,
              let bit = AlertNotificationPlan.bit(identifier: response.notification.request.identifier) else { return }
        await MainActor.run { onAcknowledge?(bit) }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .sound]
    }
}
