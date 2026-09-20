import CruxCoachCore
import SwiftUI

struct RootView: View {
    @Environment(AppEnvironment.self) private var environment

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
        .task { environment.start() }
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

    var body: some View {
        Group {
            if let sync {
                if sync.state.installedCount == 0 {
                    NavigationStack { CatalogueView(core: core, sync: sync, isOnboarding: true) }
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
