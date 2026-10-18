import CruxCoachCore
import SwiftUI

struct RootView: View {
    @Environment(AppEnvironment.self) private var environment
    @Environment(\.scenePhase) private var scenePhase

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
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { environment.refreshReminders() }
        }
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
                // Shown until it was finished or skipped once (persisted, as on Android);
                // an older install with catalogues but no flag is not sent through it again.
                if sync.state.installedCount == 0 && !onboardingDone && !core.isOnboardingCompleted() {
                    OnboardingView(core: core, sync: sync) {
                        onboardingDone = true
                        // Android starts the guided browse right after the
                        // first run, and only there.
                        core.makeBrowserTour().start(replay: false)
                    }
                } else {
                    BrowserView(core: core, sync: sync)
                        // A fresh install has nothing "new" to announce (Android: watermark set, queue empty).
                        .modifier(WhatsNewOverlay(core: core, onboardingDone: core.onboardingCompletedAtStart))
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
    @Environment(AppEnvironment.self) private var environment
    @State private var model: WhatsNewScreenModel?
    @State private var showing = ""

    func body(content: Content) -> some View {
        content
            // A notification tap waits until the note is read: no cover comes up over this sheet.
            .onChange(of: showing, initial: true) { _, id in environment.router.held = !id.isEmpty }
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

/// Follows a tapped notification's route (Android `navigate_to`) from the board browser, which
/// presents the training area: "today" opens it (Today is its first tab), "body" opens the body
/// page above it, so closing that page leads into the training area, as Android's Back does.
struct NotificationRouting: ViewModifier {
    let router: AppRouter
    let athleteAvailable: Bool
    @Binding var trainingOpen: Bool
    @Binding var bodyOpen: Bool
    /// Closes a sheet of the browser; true when one was open (a cover cannot come up over it).
    let closeSheet: () -> Bool

    func body(content: Content) -> some View {
        content
            // A cold start from a tap: the browser just appeared, and the release notes may be
            // about to come up over it, so look once things have settled.
            .task { follow(router.pending, settleFirst: true) }
            .onChange(of: router.pending) { _, route in
                follow(route, settleFirst: false)
            }
            .onChange(of: router.held) { _, held in
                if !held { follow(router.pending, settleFirst: true) }
            }
    }

    private func follow(_ route: String, settleFirst: Bool) {
        guard !route.isEmpty, !router.held else { return }
        let hadSheet: Bool = closeSheet() || settleFirst
        Task {
            // A sheet still going down, or the training area still coming up, refuses the next presentation.
            if hadSheet { try? await Task.sleep(nanoseconds: Self.settle) }
            // Covered meanwhile: the route waits for `held` to clear; taken by another call: done.
            guard !router.held, !router.pending.isEmpty else { return }
            let taken: String = router.take()
            let wasOpen: Bool = trainingOpen
            trainingOpen = true
            guard taken == AppRouter.body, athleteAvailable else { return }
            if !wasOpen { try? await Task.sleep(nanoseconds: Self.settle) }
            bodyOpen = true
        }
    }

    private static let settle: UInt64 = 700_000_000
}

/// The body page over the training area, opened by a weigh-in or measuring reminder.
struct NotificationBodySheet: View {
    let core: AppCore
    let onClose: () -> Void

    var body: some View {
        NavigationStack {
            ProgressBodyView(core: core)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(L("action_close"), action: onClose)
                    }
                }
        }
    }
}
