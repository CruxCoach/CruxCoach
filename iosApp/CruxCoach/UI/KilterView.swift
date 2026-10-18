import CruxCoachCore
import SwiftUI

/// Kilter account: sign in, pull the account's ascents into the logbook (and
/// its circuits into climb lists) and upload this iPhone's entries. The password
/// goes straight into the sign-in call and is never stored.
struct KilterView: View {
    let core: AppCore
    @State private var host: ScreenHost<KilterScreenModel, KilterScreenState>?
    @State private var email = ""
    @State private var password = ""
    @State private var showEntries = false
    @State private var confirmImported = false
    @State private var report: KilterReportDraft?

    var body: some View {
        Group {
            if let host {
                screen(model: host.model, ui: host.state)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(LI("kilter_title"))
        .task {
            guard host == nil else { return }
            let model = core.kilterScreenWithCircuits()
            host = ScreenHost(model: model, initial: model.currentState) { model, onState in
                model.watch(onState: onState)
            }
        }
    }

    @ViewBuilder
    private func screen(model: KilterScreenModel, ui: KilterScreenState) -> some View {
        Form {
            if ui.signedIn {
                Section(LI("kilter_account")) {
                    if !ui.userUuid.isEmpty {
                        LabeledContent(LI("kilter_user"), value: String(ui.userUuid.prefix(8)))
                    }
                    Button(LI("kilter_import")) { model.importLogs() }.disabled(ui.busy)
                    Button(LI("kilter_sign_out"), role: .destructive) { model.signOut() }.disabled(ui.busy)
                }
                uploadSection(model: model, ui: ui)
                if ui.phase == "done" {
                    Section {
                        Label(LI("kilter_imported", Int(ui.imported), Int(ui.alreadyPresent)), systemImage: "checkmark.circle")
                            .foregroundStyle(.green)
                        if ui.unknownClimb > 0 {
                            Text(LI("kilter_unknown", Int(ui.unknownClimb))).font(.footnote).foregroundStyle(.secondary)
                        }
                        if ui.circuits > 0 {
                            Text(L("kilter_import_part_circuits", Int(ui.circuits))).font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                }
            } else {
                Section {
                    TextField(LI("kilter_email"), text: $email)
                        .textContentType(.emailAddress)
                        .keyboardType(.emailAddress)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    SecureField(LI("kilter_password"), text: $password)
                        .textContentType(.password)
                    Button(LI("kilter_sign_in")) {
                        model.signIn(email: email, password: password)
                        password = ""
                    }
                    .disabled(ui.busy || email.isEmpty || password.isEmpty)
                } header: {
                    Text(LI("kilter_sign_in"))
                } footer: {
                    Text(LI("kilter_privacy"))
                }
            }
            if ui.busy && !ui.uploading {
                Section { HStack { ProgressView(); Text(busyLabel(ui)) } }
            }
            if ui.failure != "none" {
                Section {
                    Label(failureText(ui), systemImage: "exclamationmark.triangle").foregroundStyle(.red)
                    Button(L("action_close")) { model.dismissFailure() }
                }
            }
        }
        .sheet(isPresented: $showEntries) {
            NavigationStack {
                KilterNotUploadedList(ui: ui) { text in
                    showEntries = false
                    model.loadDiagnostics()
                    report = KilterReportDraft(body: text)
                } onClose: {
                    showEntries = false
                }
            }
        }
        .sheet(item: $report) { draft in
            NavigationStack {
                KilterReportSheet(core: core, kilter: model, ui: ui, draft: draft) { report = nil }
            }
        }
        .alert(L("kilter_upload_imported_title"), isPresented: $confirmImported) {
            Button(L("kilter_upload_imported_confirm")) { model.uploadImported() }
            Button(L("action_cancel"), role: .cancel) {}
        } message: {
            Text(L("kilter_upload_imported_message"))
        }
    }

    /// Android's logbook sync status block. On iOS the upload is always a tap,
    /// so "Sync now" stays available rather than only after a failure.
    @ViewBuilder
    private func uploadSection(model: KilterScreenModel, ui: KilterScreenState) -> some View {
        Section {
            if ui.uploading {
                HStack { ProgressView(); Text(L("kilter_upload_syncing")) }
            } else {
                ForEach(Array(ui.uploadLines.enumerated()), id: \.offset) { index, line in
                    Text(lineText(line))
                        .font(index == 0 ? .body : .footnote)
                        .foregroundStyle(index == 0 && ui.uploadProblem ? Color.red : (index == 0 ? Color.primary : Color.secondary))
                }
            }
            if ui.uploadNeedsLogin {
                Button(L("kilter_login_button")) { model.signInAgain() }.disabled(ui.busy)
            } else {
                Button(L("kilter_sync_now")) { model.pushLogs() }.disabled(ui.busy)
            }
            if ui.uploadProblem {
                Button(L("devcontact_report_bug")) {
                    model.loadDiagnostics()
                    report = KilterReportDraft(body: "")
                }
            }
            if ui.uploadShowEntries && !ui.uploading {
                Button(L("kilter_upload_show_entries")) {
                    model.loadNotUploaded()
                    showEntries = true
                }
            }
            if ui.uploadShowImported && !ui.uploading {
                Button(L("kilter_upload_imported_action")) { confirmImported = true }.disabled(ui.busy)
            }
        } header: {
            Text(L("kilter_push_label"))
        } footer: {
            Text(LI("kilter_push_manual_hint"))
        }
    }

    private func lineText(_ line: KilterStatusLineUi) -> String {
        if line.plural { return LP(line.key, Int(line.count)) }
        return line.iosOnly ? LI(line.key) : L(line.key)
    }

    private func busyLabel(_ ui: KilterScreenState) -> String {
        switch ui.phase {
        case "importing": return LI("kilter_importing")
        case "pushing": return LI("kilter_pushing")
        default: return LI("kilter_signing_in")
        }
    }

    private func failureText(_ ui: KilterScreenState) -> String {
        switch ui.failure {
        case "invalidCredentials": return LI("kilter_bad_credentials")
        case "throttled": return LI("kilter_throttled", Int(ui.retryAfterSeconds))
        case "offline": return LI("catalogue_offline")
        case "timeout": return LI("kilter_timeout")
        case "notSignedIn": return LI("kilter_session_expired")
        case "conflict": return LI("kilter_conflict")
        default: return LI("kilter_server_error")
        }
    }
}

/// What the report form opens with; Identifiable so it can drive `.sheet(item:)`.
struct KilterReportDraft: Identifiable {
    let id = UUID()
    let body: String
}

/// The entries the upload could not hand to Kilter, with the reason (Android's
/// `KilterNotUploadedDialog`), and a way to report them.
private struct KilterNotUploadedList: View {
    let ui: KilterScreenState
    let onReport: (String) -> Void
    let onClose: () -> Void

    var body: some View {
        List {
            Text(L("kilter_upload_list_intro")).font(.footnote).foregroundStyle(.secondary)
            switch ui.notUploadedState {
            case "failed":
                Text(L("kilter_upload_list_failed"))
            case "loaded":
                if ui.notUploaded.isEmpty {
                    Text(L("kilter_upload_list_empty"))
                } else {
                    ForEach(ui.notUploaded, id: \.logUuid) { row in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(Self.headline(row)).font(.body.weight(.semibold))
                            Text(Self.details(row)).font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                }
            default:
                ProgressView().frame(maxWidth: .infinity)
            }
        }
        .navigationTitle(L("kilter_upload_list_title"))
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button(L("action_close")) { onClose() }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button(L("kilter_upload_list_report")) { onReport(Self.report(ui.notUploaded, version: ui.appVersion)) }
                    .disabled(ui.notUploadedState != "loaded" || ui.notUploaded.isEmpty)
            }
        }
    }

    static func headline(_ row: KilterNotUploadedRowUi) -> String {
        row.climbName.isEmpty ? L("kilter_upload_unknown_climb") : row.climbName
    }

    static func details(_ row: KilterNotUploadedRowUi) -> String {
        var parts = ["\(row.angle)°", row.date]
        if !row.isAscent { parts.append(L("kilter_upload_entry_bid")) }
        switch row.reason {
        case "notOnKilter": parts.append(L("kilter_upload_reason_not_on_kilter"))
        case "retryLater": parts.append(L("kilter_upload_reason_retry_later"))
        case "conflict": parts.append(L("kilter_upload_reason_conflict"))
        default: parts.append(L("kilter_upload_reason_invalid_date"))
        }
        return parts.joined(separator: " · ")
    }

    /// Plain text for the bug report: what the user saw, plus the climb ids needed to look the climbs up.
    static func report(_ rows: [KilterNotUploadedRowUi], version: String) -> String {
        let limit = 60
        var lines = [L("kilter_upload_report_list_header", version)]
        for row in rows.prefix(limit) {
            lines.append("- \(headline(row)) · \(details(row)) · id \(row.reportId)")
        }
        if rows.count > limit {
            lines.append(L("kilter_upload_report_list_more", rows.count - limit))
        }
        return lines.joined(separator: "\n")
    }
}

/// Android's bug report form for the Kilter upload: title, description and the
/// optional upload diagnostics, sent as a NIP-17 message to the maintainer.
private struct KilterReportSheet: View {
    let core: AppCore
    let kilter: KilterScreenModel
    let ui: KilterScreenState
    let draft: KilterReportDraft
    let onDone: () -> Void
    @State private var host: ScreenHost<DevContactScreenModel, DevContactScreenState>?
    @State private var title = L("kilter_upload_report_title")
    @State private var details = ""
    @State private var attach = false
    @State private var prepared = false

    var body: some View {
        Form {
            Section {
                TextField(L("devcontact_title_required"), text: $title)
                TextField(L("devcontact_description_required"), text: $details, axis: .vertical)
                    .lineLimit(4...12)
            }
            if !ui.diagnostics.isEmpty {
                Section {
                    Toggle(L("kilter_diagnostics_attach"), isOn: $attach)
                    Text(L("kilter_diagnostics_explanation")).font(.footnote).foregroundStyle(.secondary)
                    Text(ui.diagnostics).font(.caption2.monospaced()).textSelection(.enabled)
                }
            }
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
                    .disabled(host == nil || host?.state.isSending == true
                              || title.trimmingCharacters(in: .whitespaces).isEmpty
                              || details.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .task {
            if !prepared {
                prepared = true
                details = draft.body
                // A list of entries goes with the diagnostics, as on Android.
                attach = !draft.body.isEmpty
            }
            guard host == nil else { return }
            let model = core.makeDevContactScreen(
                language: Locale.current.language.languageCode?.identifier ?? "en")
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

    private func send() {
        guard let model = host?.model else { return }
        let os = ProcessInfo.processInfo.operatingSystemVersion.majorVersion
        let language = Locale.current.language.languageCode?.identifier ?? ""
        let text = kilter.bugReportText(title: title, body: details,
                                        deviceLine: "iOS \(os) | \(language)", attachDiagnostics: attach)
        model.setTypeCode(code: "bug")
        model.setSubject(value: title.trimmingCharacters(in: .whitespaces))
        model.setDraft(value: text)
        model.send()
    }
}
