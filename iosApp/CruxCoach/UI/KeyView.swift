import CruxCoachCore
import SwiftUI

/// Android's account key screens: the account id, the private key behind Face ID /
/// passcode (to store it in a password manager), and importing a saved key to use an
/// existing account — e.g. the one from Android — on this iPhone.
struct KeyView: View {
    let core: AppCore
    @Environment(AppEnvironment.self) private var environment
    @State private var host: ScreenHost<KeyScreenModel, KeyScreenState>?
    @State private var input = ""
    @State private var confirmSwitch = false

    var body: some View {
        Group {
            if let host {
                form(model: host.model, ui: host.state)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(L("key_management_title"))
        .task {
            guard host == nil else { return }
            let model = core.makeKeyScreen()
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) })
        }
        .onDisappear { host?.model.hideSecretKey() }
    }

    @ViewBuilder
    private func form(model: KeyScreenModel, ui: KeyScreenState) -> some View {
        Form {
            accountSection(ui: ui)
            secretSection(model: model, ui: ui)
            importSection(model: model, ui: ui)
            if let failure = ui.failureCode {
                Section {
                    Label(failureText(failure), systemImage: "exclamationmark.triangle").foregroundStyle(.orange)
                    Button(L("action_close")) { model.dismissFailure() }
                }
            }
        }
        .alert(L("key_import_overwrite_title"), isPresented: $confirmSwitch) {
            Button(L("action_cancel"), role: .cancel) { }
            Button(L("key_import_overwrite_confirm"), role: .destructive) { model.confirmImport() }
        } message: {
            Text(L("account_access_different"))
        }
        .onChange(of: ui.imported) { _, imported in
            // The new key is stored; the core restarts on that identity's own databases.
            if imported { environment.restart() }
        }
    }

    @ViewBuilder
    private func accountSection(ui: KeyScreenState) -> some View {
        Section {
            Text(L("settings_account_key_reminder")).font(.footnote).foregroundStyle(.secondary)
            if let npub = ui.activeNpub {
                Text(npub).font(.caption.monospaced()).textSelection(.enabled)
                Button(L("account_copy_id"), systemImage: "doc.on.doc") { UIPasteboard.general.string = npub }
            }
        }
    }

    @ViewBuilder
    private func secretSection(model: KeyScreenModel, ui: KeyScreenState) -> some View {
        Section(L("account_flow_title")) {
            Text(L("account_flow_intro")).font(.footnote)
            if let nsec = ui.revealedNsec {
                Text(nsec).font(.caption.monospaced()).textSelection(.enabled)
                Button(L("account_copy_secret"), systemImage: "key") {
                    // Clipboard entry expires after two minutes and stays on this device.
                    UIPasteboard.general.setItems([["public.utf8-plain-text": nsec]],
                                                  options: [.expirationDate: Date().addingTimeInterval(120), .localOnly: true])
                }
                Text(L("account_flow_store")).font(.footnote).foregroundStyle(.secondary)
                Button(L("key_import_hide")) { model.hideSecretKey() }
            } else {
                Button(L("account_flow_copy"), systemImage: "faceid") {
                    model.revealSecretKey(reason: L("account_flow_copy"))
                }
            }
        }
    }

    @ViewBuilder
    private func importSection(model: KeyScreenModel, ui: KeyScreenState) -> some View {
        Section(L("key_import_title")) {
            Text(L("key_import_prompt")).font(.footnote)
            SecureField(L("key_import_label"), text: $input)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .onChange(of: input) { _, text in model.updateInput(text: text) }
            Text(L("key_import_supported_formats")).font(.caption).foregroundStyle(.secondary)
            Button(L("key_import_button")) { model.preview() }
                .disabled(input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            if let preview = ui.previewNpub {
                Text(L("key_import_confirm_title")).font(.subheadline.weight(.semibold))
                Text(preview).font(.caption.monospaced()).textSelection(.enabled)
                if ui.sameAccount {
                    Text(L("account_access_same")).font(.footnote)
                } else if ui.replacesLocalKey {
                    Text(L("account_import_replaces_local")).font(.footnote).foregroundStyle(.orange)
                }
                Button(L("key_import_confirm_button")) {
                    if ui.sameAccount { model.confirmImport() } else { confirmSwitch = true }
                }
                .buttonStyle(.borderedProminent)
                Button(L("action_cancel")) { model.cancelImport(); input = "" }
            }
        }
    }

    private func failureText(_ code: String) -> String {
        switch code {
        case "INVALID_NSEC": return L("key_import_invalid_nsec")
        case "UNKNOWN_FORMAT", "INVALID_HEX", "INVALID_KEY": return L("key_import_hint_unrecognized")
        case "NCRYPTSEC_UNSUPPORTED", "MNEMONIC_UNSUPPORTED": return LI("key_format_not_on_iphone")
        case "DEVICE_AUTH_FAILED": return LI("key_auth_failed")
        default: return L("account_import_error")
        }
    }
}
