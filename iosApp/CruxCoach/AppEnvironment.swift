import CruxCoachCore
import Observation
import UserNotifications

/// Composition root: hands the four Swift-implemented platform services to the Kotlin core.
@Observable
final class AppEnvironment {
    enum Phase {
        case starting
        case ready(AppCore)
        case failed(code: String, detail: String)
    }

    private(set) var phase: Phase = .starting
    /// Where a tapped notification leads; the board browser follows it.
    let router = AppRouter()
    @ObservationIgnored private let notificationPresenter = ForegroundNotificationPresenter()
    /// Keeps the training-day reminders following the week plan, pauses and trainings.
    @ObservationIgnored private var trainingReminders: Subscription?

    init() {
        // A tap that launches the app is handed to the delegate set while it launches, so the
        // delegate is set here, before the first frame, not once the core has started.
        let router = self.router
        notificationPresenter.onRoute = { route in router.open(route) }
        UNUserNotificationCenter.current().delegate = notificationPresenter
    }

    /// After a key import: start the core again, so the new identity's own databases open.
    func restart() {
        phase = .starting
        start()
    }

    func start() {
        guard case .starting = phase else { return }
        let result = AppCore.companion.start(
            aead: CryptoKitAead(),
            secrets: KeychainSecretStore(),
            zstd: ZstdFileDecompressor(),
            deviceAuth: BiometricAuthenticator(),
            nip44: LibraryNip44Cipher()
        )
        if let core = result.core {
            core.startConnectivity()
            // Again on the instance that is kept: the centre holds its delegate weakly.
            UNUserNotificationCenter.current().delegate = notificationPresenter
            _ = core.attachNotifications(scheduler: LocalNotificationScheduler())
            core.attachHealthStore(store: HealthKitStore())
            // Weigh-in and measuring reminders are scheduled ahead; refresh them at every start.
            core.rescheduleBodyReminders(notifications: LocalNotificationScheduler())
            // Training-day reminders are scheduled ahead and follow every change that decides them.
            trainingReminders = core.followTrainingReminders(notifications: LocalNotificationScheduler())
            phase = .ready(core)
        } else {
            phase = .failed(code: result.failureCode, detail: result.detail)
        }
    }

    /// The reminders are scheduled ahead and nothing of ours runs when one fires, so every return
    /// to the foreground schedules them again (a day has passed, a value or a training was logged).
    func refreshReminders() {
        guard case .ready(let core) = phase else { return }
        core.rescheduleBodyReminders(notifications: LocalNotificationScheduler())
        core.rescheduleTrainingReminders(notifications: LocalNotificationScheduler())
    }
}

/// The place a tapped notification opens, as Android's `navigate_to` extra. Like Android's
/// `MainActivity.safeNavigateToRoute`, only the routes the app's own reminders carry are taken
/// (Kotlin `NotificationRoutes`): "today" (training reminder) and "body" (weigh-in, measuring).
/// A route waits here until the view that can open it is on screen (a cold start goes through
/// the start-up and possibly the first-run setup first).
@Observable
final class AppRouter {
    static let today = "today"
    static let body = "body"

    /// The waiting route, "" when none.
    private(set) var pending = ""
    /// Counts the opened training-area routes, so an already open training area can go back to
    /// its Today tab (Android navigates to Today single-top).
    private(set) var todayRequests = 0

    func open(_ route: String) {
        guard route == Self.today || route == Self.body else { return }
        pending = route
    }

    /// Hands the waiting route out once.
    func take() -> String {
        let route = pending
        pending = ""
        if !route.isEmpty { todayRequests += 1 }
        return route
    }
}
