import CruxCoachCore
import SwiftUI

struct RootView: View {
    @Environment(AppEnvironment.self) private var environment

    private var keepScreenOn: Bool {
        guard case .ready(let core) = environment.phase else { return false }
        return core.settings.keepScreenOn
    }

    /// Android offers system/light/dark; nil means "follow the system".
    private var colorScheme: ColorScheme? {
        guard case .ready(let core) = environment.phase else { return nil }
        switch core.settings.darkMode {
        case "light": return .light
        case "dark": return .dark
        default: return nil
        }
    }

    var body: some View {
        Group {
            switch environment.phase {
            case .starting:
                ProgressView()
            case .ready(let core):
                MainView(core: core)
            case .failed(let code, let detail):
                StartFailureView(code: code, detail: detail)
            }
        }
        .preferredColorScheme(colorScheme)
        .task { environment.start() }
        .onChange(of: keepScreenOn) { _, keepOn in
            UIApplication.shared.isIdleTimerDisabled = keepOn
        }
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
    }
}

/// Start-up never falls back to unencrypted or throw-away storage; it stops and says why.
struct StartFailureView: View {
    let code: String
    let detail: String

    private var message: String {
        switch code {
        case "keychainUnavailable": return LI("start_failure_keychain")
        case "storedKeyInvalid": return LI("start_failure_key_invalid")
        case "encryptionUnavailable": return LI("start_failure_encryption")
        default: return LI("start_failure_database")
        }
    }

    var body: some View {
        ContentUnavailableView {
            Label(LI("start_failure_title"), systemImage: "lock.trianglebadge.exclamationmark")
        } description: {
            Text(message)
            if !detail.isEmpty {
                Text(detail).font(.footnote).textSelection(.enabled)
            }
        }
    }
}

struct MainView: View {
    let core: AppCore
    @State private var sync: ScreenHost<SyncScreenModel, SyncScreenState>?
    @State private var onboardingDone = false

    var body: some View {
        Group {
            if let sync, sync.state.installedKnown {
                if sync.state.installedCount == 0 && !onboardingDone {
                    OnboardingView(core: core, sync: sync) { onboardingDone = true }
                } else {
                    BrowserView(core: core, sync: sync)
                }
            } else {
                ProgressView()
            }
        }
        .task {
            guard sync == nil else { return }
            let model = core.syncScreen
            sync = ScreenHost(model: model, initial: model.currentState) { model, onState in
                model.watch(onState: onState)
            }
        }
    }
}
