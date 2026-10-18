import CruxCoachCore
import Foundation
import UserNotifications

/// Local notifications: the rest timer and the weigh-in, measuring and training-day reminders.
/// Android fires them from exact alarms and workers; on iOS a time-interval request is the only
/// thing that still fires once the app is suspended or the phone is locked. One stable id per
/// slot, so a re-scheduled one replaces the pending one instead of stacking.
final class LocalNotificationScheduler: NotificationScheduler {
    /// The `userInfo` key of the place a tap opens, named after Android's `navigate_to` extra.
    static let routeKey = "navigate_to"

    private let center = UNUserNotificationCenter.current()

    func schedule(id: String, afterSeconds: Int32, titleKey: String, bodyKey: String) {
        post(id: id, afterSeconds: afterSeconds, title: L(titleKey), body: L(bodyKey), route: "")
    }

    /// Texts with arguments ("Today: <routine>") and the route a tap opens ("today", "body").
    func scheduleText(id: String, afterSeconds: Int32, title: TrText, body: TrText, route: String) {
        post(id: id, afterSeconds: afterSeconds, title: TrRender.text(title), body: TrRender.text(body), route: route)
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

    private func post(id: String, afterSeconds: Int32, title: String, body: String, route: String) {
        guard afterSeconds >= 1 else { return }
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        if !route.isEmpty {
            content.userInfo = [Self.routeKey: route]
        }
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: TimeInterval(afterSeconds), repeats: false)
        center.removePendingNotificationRequests(withIdentifiers: [id])
        center.add(UNNotificationRequest(identifier: id, content: content, trigger: trigger))
    }
}

/// Shows a notification that fires while the app is open as a banner with sound, like Android;
/// without a delegate iOS would swallow it in the foreground. A tap hands the request's route to
/// `onRoute` on the main thread (Android's `navigate_to`); the rest timer has none and just
/// brings the app forward.
final class ForegroundNotificationPresenter: NSObject, UNUserNotificationCenterDelegate {
    var onRoute: ((String) -> Void)?

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let info = response.notification.request.content.userInfo
        let route = info[LocalNotificationScheduler.routeKey] as? String ?? ""
        // Only the tap on the notification itself opens a place; dismissing it does nothing.
        if response.actionIdentifier == UNNotificationDefaultActionIdentifier && !route.isEmpty {
            let handler = onRoute
            DispatchQueue.main.async { handler?(route) }
        }
        completionHandler()
    }
}
