import SwiftUI
import UIKit

@main
struct CruxCoachApp: App {
    /// Owns the environment: a background launch for the refresh task may run before, or
    /// without, any window, and must reach the same core the window later shows.
    @UIApplicationDelegateAdaptor(AppLaunchDelegate.self) private var launchDelegate
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(launchDelegate.environment)
                .tint(Color("AccentOrange", bundle: nil))
        }
        .onChange(of: scenePhase) { _, phase in
            launchDelegate.scenePhaseChanged(phase)
        }
    }
}

/// `BGTaskScheduler` takes a task's launch handler only while the app is launching, so the
/// background refresh is registered here, before `didFinishLaunching` returns.
final class AppLaunchDelegate: NSObject, UIApplicationDelegate {
    let environment = AppEnvironment()

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        environment.registerBackgroundRefresh()
        return true
    }

    /// Approach 2 beside the system's background slots: every return to the foreground runs the
    /// same refresh (each step keeps Android's spacing), and every move to the background asks
    /// for the next slot.
    func scenePhaseChanged(_ phase: ScenePhase) {
        switch phase {
        case .active:
            environment.refreshInForeground()
        case .background:
            environment.scheduleBackgroundRefresh()
        default:
            break
        }
    }
}
