import CruxCoachCore
import Foundation
import UserNotifications

/// Rest-timer notifications. Android fires them from an exact AlarmManager alarm;
/// on iOS a time-interval request is the only thing that still fires once the app
/// is suspended or the phone is locked. One stable id per timer, so a re-scheduled
/// rest replaces the pending one instead of stacking.
final class LocalNotificationScheduler: NotificationScheduler {
    private let center = UNUserNotificationCenter.current()

    func schedule(id: String, afterSeconds: Int32, titleKey: String, bodyKey: String) {
        guard afterSeconds >= 1 else { return }
        let content = UNMutableNotificationContent()
        content.title = L(titleKey)
        content.body = L(bodyKey)
        content.sound = .default
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: TimeInterval(afterSeconds), repeats: false)
        center.removePendingNotificationRequests(withIdentifiers: [id])
        center.add(UNNotificationRequest(identifier: id, content: content, trigger: trigger))
    }

    func cancel(id: String) {
        center.removePendingNotificationRequests(withIdentifiers: [id])
        center.removeDeliveredNotifications(withIdentifiers: [id])
    }

    func requestPermission(onResult: @escaping (KotlinBoolean) -> Void) {
        center.requestAuthorization(options: [.alert, .sound]) { granted, _ in
            DispatchQueue.main.async { onResult(KotlinBoolean(bool: granted)) }
        }
    }
}

/// Shows a rest that ends while the app is open as a banner with sound, like
/// Android's notification; without a delegate iOS would swallow it in the foreground.
final class ForegroundNotificationPresenter: NSObject, UNUserNotificationCenterDelegate {
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }
}
