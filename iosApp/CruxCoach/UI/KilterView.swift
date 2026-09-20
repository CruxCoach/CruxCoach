import CruxCoachCore
import SwiftUI

/// Kilter account: sign in and pull the account's ascents into the logbook.
/// The password goes straight into the sign-in call and is never stored.
struct KilterView: View {
    let core: AppCore
    @State private var host: ScreenHost<KilterScreenModel, KilterScreenState>?
    @State private var email = ""
    @State private var password = ""

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
            let model = core.kilterScreen
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
                    Button(L("kilter_push_label")) { model.pushLogs() }.disabled(ui.busy)
                    Text(L("kilter_push_desc")).font(.footnote).foregroundStyle(.secondary)
                    Button(LI("kilter_sign_out"), role: .destructive) { model.signOut() }.disabled(ui.busy)
                }
                if ui.phase == "pushed" {
                    Section {
                        Label(L("kilter_upload_counts", Int(ui.uploaded), Int(ui.pendingUpload)),
                              systemImage: "arrow.up.circle")
                            .foregroundStyle(ui.pendingUpload == 0 ? Color.green : .primary)
                        Text(ui.pendingUpload == 0
                             ? L("kilter_upload_done")
                             : L("kilter_upload_pending"))
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                }
                if ui.missingWallContext {
                    Section {
                        Label(L("kilter_upload_wall"), systemImage: "exclamationmark.triangle")
                            .font(.footnote)
                        Text(LI("kilter_upload_wall_hint")).font(.footnote).foregroundStyle(.secondary)
                    }
                }
                if ui.phase == "done" {
                    Section {
                        Label(LI("kilter_imported", Int(ui.imported), Int(ui.alreadyPresent)), systemImage: "checkmark.circle")
                            .foregroundStyle(.green)
                        if ui.unknownClimb > 0 {
                            Text(LI("kilter_unknown", Int(ui.unknownClimb))).font(.footnote).foregroundStyle(.secondary)
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
            if ui.busy {
                Section { HStack { ProgressView(); Text(busyLabel(ui)) } }
            }
            if ui.failure != "none" {
                Section {
                    Label(failureText(ui), systemImage: "exclamationmark.triangle").foregroundStyle(.red)
                    Button(L("action_close")) { model.dismissFailure() }
                }
            }
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
