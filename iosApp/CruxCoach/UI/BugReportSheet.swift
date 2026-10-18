import CruxCoachCore
import SwiftUI

/// What a "Report bug" button hands the form (Android's `Routes.bugReport(title, description)`).
struct BugReportDraft: Identifiable {
    let id = UUID()
    let title: String
    let details: String
}

/// Android's bug report form (`BugReportScreen`): title, description and optional steps, with
/// the device line Android adds, sent as a NIP-17 bug report to the maintainer. Prefilled by
/// the screen that offers "Report bug" (the data import's error).
struct BugReportSheet: View {
    let core: AppCore
    let draft: BugReportDraft
    let onDone: () -> Void
    @State private var host: ScreenHost<DevContactScreenModel, DevContactScreenState>?
    @State private var title = ""
    @State private var details = ""
    @State private var steps = ""
    @State private var prepared = false

    var body: some View {
        Form {
            fields()
            deviceSection()
            if let host, host.state.isSending {
                Section { HStack { ProgressView(); Text(L("action_send")) } }
            }
        }
        .navigationTitle(L("devcontact_report_bug"))
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button(L("action_cancel")) { onDone() }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button(L("action_send")) { send() }
                    .disabled(!canSend())
            }
        }
        .task {
            if !prepared {
                prepared = true
                title = String(draft.title.prefix(Self.titleLimit))
                details = draft.details
            }
            guard host == nil else { return }
            let model = core.makeDevContactScreen(language: Self.language())
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
        }
        .onChange(of: host?.state.lastSentId ?? "") { _, sent in
            if !sent.isEmpty {
                host?.model.consumeSent()
                onDone()
            }
        }
        .alert(L("devcontact_send_failed"),
               isPresented: Binding(get: { (host?.state.errorCode ?? "none") != "none" },
                                    set: { if !$0 { host?.model.consumeError() } })) {
            Button(L("action_close")) { host?.model.consumeError() }
        }
    }

    @ViewBuilder
    private func fields() -> some View {
        Section {
            TextField(L("devcontact_title_required"), text: Binding(
                get: { title }, set: { title = String($0.prefix(Self.titleLimit)) }))
            TextField(L("devcontact_description_required"), text: $details, axis: .vertical)
                .lineLimit(4...12)
            TextField(L("devcontact_steps_optional"), text: $steps, axis: .vertical)
                .lineLimit(3...8)
        } footer: {
            Text("\(title.count)/\(Self.titleLimit)")
        }
    }

    /// Android's device information card: what the report says about the phone.
    @ViewBuilder
    private func deviceSection() -> some View {
        Section(header: Text(L("devcontact_device_info"))) {
            LabeledContent(L("devcontact_app_version"), value: Self.appVersion())
            LabeledContent(L("devcontact_language"), value: Self.language())
        }
    }

    private func canSend() -> Bool {
        guard let host, !host.state.isSending else { return false }
        let hasTitle: Bool = !title.trimmingCharacters(in: .whitespaces).isEmpty
        let hasDetails: Bool = !details.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        return hasTitle && hasDetails
    }

    private func send() {
        guard let model = host?.model else { return }
        let version = ProcessInfo.processInfo.operatingSystemVersion
        let osVersion: String = "\(version.majorVersion).\(version.minorVersion)"
        let memory = Int64(clamping: ProcessInfo.processInfo.physicalMemory)
        model.sendBugReport(title: title, details: details, steps: steps, osVersion: osVersion,
                            physicalMemoryBytes: memory, language: Self.language(),
                            appVersion: Self.appVersion())
    }

    /// Android's 100-character title limit.
    private static let titleLimit = 100

    private static func language() -> String {
        Locale.current.language.languageCode?.identifier ?? "en"
    }

    /// "name (build)", as Android's "VERSION_NAME (VERSION_CODE)".
    private static func appVersion() -> String {
        let info = Bundle.main.infoDictionary
        let name: String = info?["CFBundleShortVersionString"] as? String ?? "?"
        let build: String = info?["CFBundleVersion"] as? String ?? "?"
        return "\(name) (\(build))"
    }
}
