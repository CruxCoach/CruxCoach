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
            case .failed(let failure, let detail):
                StartFailureView(failure: failure, detail: detail)
            }
        }
        .task { environment.start() }
    }
}

/// Start-up never falls back to unencrypted or throw-away storage; it stops and says why.
struct StartFailureView: View {
    let failure: AppStartFailure
    let detail: String

    private var message: String {
        switch failure {
        case .keychainUnavailable: return LI("start_failure_keychain")
        case .storedKeyInvalid: return LI("start_failure_key_invalid")
        case .encryptionUnavailable: return LI("start_failure_encryption")
        default: return LI("start_failure_database")
        }
    }

    var body: some View {
        ContentUnavailableView {
            Label(LI("start_failure_title"), systemImage: "lock.trianglebadge.exclamationmark")
        } description: {
            Text(message)
            if !detail.isEmpty { Text(detail).font(.footnote).textSelection(.enabled) }
        }
    }
}

struct MainView: View {
    let core: AppCore
    @State private var sync: Observed<CatalogueSyncState>

    init(core: AppCore) {
        self.core = core
        _sync = State(initialValue: Observed(core.catalogueSync.state, initial: core.catalogueSync.state.value as! CatalogueSyncState))
    }

    var body: some View {
        if sync.value.installedBrands.isEmpty {
            NavigationStack { CatalogueView(core: core, sync: sync, isOnboarding: true) }
        } else {
            BrowserView(core: core, sync: sync)
        }
    }
}
