import CruxCoachCore
import SwiftUI

/// Android's start-up crash dialog (`CrashReportDialog`): a report the last crash left is offered
/// once per launch — send, no thanks, or view it first, with "Remember choice". With automatic
/// sending switched on it goes without asking; switched off, it is deleted unread (Kotlin
/// `CrashReportPromptModel`).
///
/// Attach once above the main screen: `.modifier(CrashReportPrompt(core: core))`.
struct CrashReportPrompt: ViewModifier {
    let core: AppCore
    @State private var host: ScreenHost<CrashReportPromptModel, CrashPromptUi>?

    func body(content: Content) -> some View {
        content
            .task {
                guard host == nil else { return }
                let model = core.makeCrashReportPrompt()
                host = ScreenHost(model: model, initial: model.currentState,
                                  subscribe: { model, onState in model.watch(onState: onState) },
                                  onClose: { model in model.close() })
                model.start()
            }
            .sheet(isPresented: askBinding) {
                if let host {
                    CrashReportSheet(model: host.model, ui: host.state)
                }
            }
            .alert(noticeTitle, isPresented: noticeBinding) {
                Button(L("action_ok")) { host?.model.consumeNotice() }
            }
    }

    private var phase: String { host?.state.phaseCode ?? "idle" }

    /// Dismissing the prompt is Android's "dismiss": the report is deleted, the choice stays open.
    private var askBinding: Binding<Bool> {
        Binding(
            get: { phase == "ask" || phase == "sending" },
            set: { shown in
                if !shown, phase == "ask" { host?.model.decline(rememberChoice: false) }
            })
    }

    private var noticeBinding: Binding<Bool> {
        Binding(
            get: { (host?.state.noticeCode ?? "none") != "none" },
            set: { shown in
                if !shown { host?.model.consumeNotice() }
            })
    }

    private var noticeTitle: String {
        host?.state.noticeCode == "sent" ? L("crash_send_success") : L("crash_send_failure")
    }
}

private struct CrashReportSheet: View {
    let model: CrashReportPromptModel
    let ui: CrashPromptUi
    @State private var remember = false

    private var sending: Bool { ui.phaseCode == "sending" }

    var body: some View {
        NavigationStack {
            Form {
                messageSection
                rememberSection
                actionSection
            }
            .navigationBarTitleDisplayMode(.inline)
        }
        .interactiveDismissDisabled(sending)
    }

    private var messageSection: some View {
        Section {
            Text(L("crash_dialog_title")).font(.headline)
            Text(L("crash_dialog_message"))
        }
    }

    private var rememberSection: some View {
        Section {
            Toggle(L("crash_dialog_dont_ask_again"), isOn: $remember)
                .disabled(sending)
        }
    }

    @ViewBuilder
    private var actionSection: some View {
        Section {
            Button(L("crash_dialog_send")) { model.send(rememberChoice: remember) }
                .disabled(sending)
            if sending {
                ProgressView().frame(maxWidth: .infinity)
            }
            Button(L("crash_dialog_dismiss"), role: .destructive) { model.decline(rememberChoice: remember) }
                .disabled(sending)
            NavigationLink(L("crash_dialog_view")) { CrashReportViewer(text: ui.reportText) }
        }
    }
}

private struct CrashReportViewer: View {
    let text: String

    var body: some View {
        ScrollView {
            Text(text)
                .font(.caption.monospaced())
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding()
        }
        .navigationTitle(L("crash_viewer_title"))
    }
}

/// Android's "Send crash reports" switch (developer-contact part of the settings).
struct CrashReportSettingsRow: View {
    let core: AppCore
    @State private var model: CrashReportSettingsModel?
    @State private var on = false

    var body: some View {
        Toggle(isOn: Binding(get: { on }, set: { value in
            on = value
            model?.setAutoSend(on: value)
        })) {
            VStack(alignment: .leading, spacing: 4) {
                Text(L("devcontact_send_crash_reports"))
                Text(LI("crash_reports_desc_ios")).font(.footnote).foregroundStyle(.secondary)
            }
        }
        .disabled(model == nil)
        .task {
            guard model == nil else { return }
            let settings = core.makeCrashReportSettings()
            on = settings.isAutoSendOn()
            model = settings
        }
    }
}
