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
                    OnboardingView(core: core, sync: sync) {
                        onboardingDone = true
                        // Android starts the guided browse right after the
                        // first run, and only there.
                        core.makeBrowserTour().start(replay: false)
                    }
                } else {
                    BrowserView(core: core, sync: sync)
                        .modifier(WhatsNewOverlay(core: core, onboardingDone: true))
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

/// Shows each unread release note once, after an upgrade.
///
/// The watermark is written by the Kotlin model only after the whole queue
/// has been read, so a note dismissed by accident comes back next launch
/// rather than being lost.
private struct WhatsNewOverlay: ViewModifier {
    let core: AppCore
    let onboardingDone: Bool
    @State private var model: WhatsNewScreenModel?
    @State private var showing = ""

    func body(content: Content) -> some View {
        content
            .task {
                guard model == nil else { return }
                let created = core.makeWhatsNewScreen()
                created.start(onboardingCompleted: onboardingDone)
                model = created
                showing = created.currentId
            }
            .sheet(isPresented: Binding(get: { !showing.isEmpty }, set: { if !$0 { advance() } })) {
                WhatsNewSheet(id: showing) { advance() }
            }
    }

    private func advance() {
        model?.dismiss()
        showing = model?.currentId ?? ""
    }
}

/// One release note. The strings are Android's, under the same keys.
private struct WhatsNewSheet: View {
    let id: String
    let onDismiss: () -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text(L(Self.bodyKey[id] ?? ""))
                    if let hint = Self.hintKey[id] {
                        Text(L(hint)).font(.footnote).foregroundStyle(.secondary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding()
            }
            .navigationTitle(L(Self.titleKey[id] ?? ""))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("whatsnew_done")) { onDismiss() }
                }
            }
        }
        .interactiveDismissDisabled()
    }

    private static let titleKey = [
        "nostr-backup": "whatsnew_nostr_backup_title",
        "aurora-json-import": "whatsnew_aurora_import_title",
        "release-0.2.1": "whatsnew_021_title",
        "release-0.2.2": "whatsnew_022_title",
        "release-0.2.3": "whatsnew_023_title",
    ]
    private static let bodyKey = [
        "nostr-backup": "whatsnew_nostr_backup_body",
        "aurora-json-import": "whatsnew_aurora_import_body",
        "release-0.2.1": "whatsnew_021_body",
        "release-0.2.2": "whatsnew_022_body",
        "release-0.2.3": "whatsnew_023_body",
    ]
    private static let hintKey = [
        "aurora-json-import": "whatsnew_aurora_import_hint",
        "release-0.2.1": "whatsnew_021_hint",
        "release-0.2.2": "whatsnew_022_hint",
    ]
}
