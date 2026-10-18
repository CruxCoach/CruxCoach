import BackgroundTasks
import CruxCoachCore
import Observation
import UIKit
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

    // MARK: Background refresh

    /// The `BGTaskSchedulerPermittedIdentifiers` entry in Info.plist.
    static let refreshTaskIdentifier = "org.cruxcoach.ios.refresh"
    /// The refresh of the running core; rebuilt when a key import starts a new core.
    @ObservationIgnored private var backgroundRefresh: BackgroundRefreshModel?
    @ObservationIgnored private var backgroundRefreshCore: AppCore?

    /// Android runs its periodic workers through WorkManager; iOS hands an app short background
    /// slots of its own choosing (`BGAppRefreshTask`, about 30 s, best effort). Called once,
    /// while the app launches.
    func registerBackgroundRefresh() {
        _ = BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.refreshTaskIdentifier, using: DispatchQueue.main) { [weak self] task in
            guard let self else {
                task.setTaskCompleted(success: false)
                return
            }
            self.runBackgroundRefresh(task)
        }
    }

    /// Asks for the next slot no earlier than Android's shortest period. Fails quietly where the
    /// system offers none (the simulator, Background App Refresh switched off).
    func scheduleBackgroundRefresh() {
        let request = BGAppRefreshTaskRequest(identifier: Self.refreshTaskIdentifier)
        let seconds: Int32 = backgroundRefresh?.nextRunSeconds ?? 900
        request.earliestBeginDate = Date(timeIntervalSinceNow: TimeInterval(seconds))
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            // Nothing to fall back to: the foreground refresh still runs.
        }
    }

    /// Every return to the foreground: the same steps, each only when it is due.
    func refreshInForeground() {
        start()
        guard let refresh = currentBackgroundRefresh() else { return }
        refresh.runInForeground { _ in }
    }

    private func runBackgroundRefresh(_ task: BGTask) {
        // The next slot first: a run the system cuts off must not end the chain.
        scheduleBackgroundRefresh()
        start()
        if case .failed = phase, UIApplication.shared.applicationState == .background {
            // Before the first unlock after a restart the Keychain is closed. A start that failed
            // in the background is forgotten, so opening the app tries again instead of showing it.
            phase = .starting
        }
        guard let refresh = currentBackgroundRefresh() else {
            task.setTaskCompleted(success: false)
            return
        }
        var completed = false
        let finish: (Bool) -> Void = { success in
            guard !completed else { return }
            completed = true
            task.setTaskCompleted(success: success)
        }
        task.expirationHandler = {
            let stop: () -> Void = {
                refresh.cancel()
                finish(false)
            }
            if Thread.isMainThread { stop() } else { DispatchQueue.main.async(execute: stop) }
        }
        refresh.runInBackground { summary in
            finish(summary.success)
        }
    }

    private func currentBackgroundRefresh() -> BackgroundRefreshModel? {
        guard case .ready(let core) = phase else { return nil }
        if let refresh = backgroundRefresh, backgroundRefreshCore === core { return refresh }
        let language: String = Bundle.main.preferredLocalizations.first ?? "en"
        let refresh = core.makeBackgroundRefresh(notifications: LocalNotificationScheduler(), language: language)
        backgroundRefresh = refresh
        backgroundRefreshCore = core
        return refresh
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
    /// True while a sheet covers the board browser from above (the release notes): no cover can
    /// come up over it, so a route waits until it is gone.
    var held = false

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
