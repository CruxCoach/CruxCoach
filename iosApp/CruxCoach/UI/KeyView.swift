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
    /// The ncryptsec password; cleared as soon as it is handed to the core.
    @State private var password = ""
    /// Android's public-id QR dialog (`showPublicQr`).
    @State private var showPublicQr = false

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
        .modifier(KeyPasswordPrompt(model: model, needsPassword: ui.needsPassword, password: $password))
        .modifier(KeyPublicQrSheet(npub: ui.activeNpub ?? "", isPresented: $showPublicQr))
        .onChange(of: ui.imported) { _, imported in
            // The new key is stored; the core restarts on that identity's own databases.
            if imported { environment.restart() }
        }
    }

    @ViewBuilder
    private func accountSection(ui: KeyScreenState) -> some View {
        Section {
            Text(L("settings_account_key_reminder")).font(.footnote).foregroundStyle(.secondary)
            if core.isKeyBackedUp {
                Label(L("account_backup_acknowledged"), systemImage: "checkmark.shield").font(.footnote)
            }
            if let npub = ui.activeNpub {
                Text(npub).font(.caption.monospaced()).textSelection(.enabled)
                Button(L("account_copy_id"), systemImage: "doc.on.doc") { UIPasteboard.general.string = npub }
                Button(L("account_show_qr"), systemImage: "qrcode") { showPublicQr = true }
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
                Button(L("account_flow_store_only"), systemImage: "checkmark.shield") {
                    core.confirmKeyBackedUp()
                    model.hideSecretKey()
                }
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
            detectedRow(ui: ui)
            if ui.isWorking {
                HStack(spacing: 8) {
                    ProgressView()
                    Text(L("account_import_checking")).font(.footnote)
                }
            }
            Button(L("key_import_button")) { model.preview() }
                .disabled(ui.isWorking || input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
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

    /// Android's "Detected: …" line; recovery words get their own name.
    @ViewBuilder
    private func detectedRow(ui: KeyScreenState) -> some View {
        let format: String = ui.detectedFormat
        if format != "UNKNOWN" {
            let name: String = format == "MNEMONIC" ? L("account_recovery_words") : format.lowercased()
            Text(L("key_import_detected", name)).font(.caption)
        }
    }

    private func failureText(_ code: String) -> String {
        switch code {
        case "INVALID_NSEC": return L("key_import_invalid_nsec")
        case "UNKNOWN_FORMAT", "INVALID_HEX", "INVALID_KEY": return L("key_import_hint_unrecognized")
        case "NCRYPTSEC_TOO_EXPENSIVE": return LI("key_ncryptsec_too_costly")
        case "INVALID_MNEMONIC_LENGTH", "UNKNOWN_MNEMONIC_WORD", "INVALID_MNEMONIC_CHECKSUM": return LI("key_words_invalid")
        case "DEVICE_AUTH_FAILED": return LI("key_auth_failed")
        // INVALID_NCRYPTSEC, WRONG_PASSWORD, STORE_WRITE_FAILED: Android's generic message.
        default: return L("account_import_error")
        }
    }
}

/// Android's ncryptsec password dialog. The password goes to the core once and the
/// field is cleared; the core normalises it (NFKC), runs scrypt and wipes its bytes.
private struct KeyPasswordPrompt: ViewModifier {
    let model: KeyScreenModel
    let needsPassword: Bool
    @Binding var password: String

    func body(content: Content) -> some View {
        content.alert(L("key_import_password_title"), isPresented: Binding(
            get: { needsPassword },
            set: { if !$0 { model.cancelPassword() } })) {
            SecureField(L("key_import_password_label"), text: $password)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Button(L("action_cancel"), role: .cancel) {
                password = ""
                model.cancelPassword()
            }
            Button(L("key_import_decrypt_button")) {
                let entered: String = password
                password = ""
                model.submitPassword(password: entered)
            }
            .disabled(password.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
    }
}

/// Android's public account id dialog: the QR code of `nostr:<npub>` (ZXing there, CoreImage
/// here) under the title "Your account", closed with "Close".
private struct KeyPublicQrSheet: ViewModifier {
    let npub: String
    @Binding var isPresented: Bool

    func body(content: Content) -> some View {
        content.sheet(isPresented: $isPresented) {
            NavigationStack {
                KeyPublicQrContent(npub: npub)
                    .navigationTitle(L("account_public_id"))
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button(L("action_close")) { isPresented = false }
                        }
                    }
            }
            .presentationDetents([.medium, .large])
        }
    }
}

private struct KeyPublicQrContent: View {
    let npub: String
    @State private var qr: UIImage?

    var body: some View {
        VStack(spacing: 16) {
            if let qr {
                Image(uiImage: qr)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 240, height: 240)
                    .padding(8)
                    .background(Color.white)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .accessibilityLabel(L("account_show_qr"))
            }
            Text(npub)
                .font(.caption.monospaced())
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .textSelection(.enabled)
        }
        .padding()
        .task(id: npub) {
            // Android encodes the NIP-21 URI so wallets and clients open the profile directly.
            qr = npub.isEmpty ? nil : ZapQrCode.image("nostr:" + npub)
        }
    }
}
