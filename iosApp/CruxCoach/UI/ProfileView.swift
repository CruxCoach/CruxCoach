import CruxCoachCore
import PhotosUI
import SwiftUI

/// The user's own Nostr profile — what other climbers see next to a problem
/// they published. Editing is local; publishing is a separate, explicit step.
struct ProfileView: View {
    let core: AppCore
    @State private var host: ScreenHost<ProfileScreenModel, ProfileScreenState>?
    @State private var confirmingPublish = false
    @State private var pickedPicture: PhotosPickerItem?
    @State private var pickedBanner: PhotosPickerItem?

    var body: some View {
        Group {
            if let host {
                screen(model: host.model, ui: host.state)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(L("nostr_profile_title"))
        .task {
            guard host == nil else { return }
            let model = core.makeProfileScreen()
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
            model.load()
        }
    }

    @ViewBuilder
    private func screen(model: ProfileScreenModel, ui: ProfileScreenState) -> some View {
        Form {
            Section {
                if ui.isLoading { ProgressView().frame(maxWidth: .infinity) }
                LabeledContent(LI("profile_npub")) {
                    Text(ui.npub).font(.caption.monospaced()).textSelection(.enabled)
                }
            }
            Section {
                TextField(L("nostr_profile_display_name"),
                          text: binding(ui.displayName) { model.setDisplayName(value: $0) })
                TextField(L("nostr_profile_about"),
                          text: binding(ui.about) { model.setAbout(value: $0) }, axis: .vertical)
                    .lineLimit(2...6)
            }
            Section(header: Text(LI("profile_images"))) {
                TextField(L("nostr_profile_picture_change"),
                          text: binding(ui.pictureUrl) { model.setPictureUrl(value: $0) })
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                PhotosPicker(selection: $pickedPicture, matching: .images) {
                    Label(LI("profile_pick_picture"), systemImage: "photo")
                }
                .disabled(ui.isUploading)
                TextField(L("nostr_profile_banner_label"),
                          text: binding(ui.bannerUrl) { model.setBannerUrl(value: $0) })
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                PhotosPicker(selection: $pickedBanner, matching: .images) {
                    Label(LI("profile_pick_banner"), systemImage: "photo.on.rectangle")
                }
                .disabled(ui.isUploading)
                if ui.isUploading {
                    HStack { ProgressView(); Text(LI("profile_uploading")) }
                }
                Text(LI("profile_image_upload_hint")).font(.footnote).foregroundStyle(.secondary)
            }
            .onChange(of: pickedPicture) { _, item in upload(item, asBanner: false, model: model) }
            .onChange(of: pickedBanner) { _, item in upload(item, asBanner: true, model: model) }
            Section {
                TextField(L("nostr_profile_nip05_label"),
                          text: binding(ui.nip05) { model.setNip05(value: $0) })
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                Button(LI("profile_verify_nip05")) { model.verifyNip05() }
                    .disabled(ui.nip05.isEmpty || ui.nip05StatusCode == "checking")
                if let note = nip05Note(ui.nip05StatusCode) {
                    Text(note).font(.footnote)
                        .foregroundStyle(ui.nip05StatusCode == "matches" ? Color.green : .secondary)
                }
                Text(L("nostr_profile_nip05_hint")).font(.footnote).foregroundStyle(.secondary)
            }
            Section {
                TextField(L("nostr_profile_lightning"),
                          text: binding(ui.lightningAddress) { model.setLightningAddress(value: $0) })
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                Text(L("nostr_profile_lightning_hint")).font(.footnote).foregroundStyle(.secondary)
                TextField(LI("profile_website"),
                          text: binding(ui.website) { model.setWebsite(value: $0) })
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                Text(L("nostr_profile_website_hint")).font(.footnote).foregroundStyle(.secondary)
            }
            Section {
                Button(L("nostr_profile_save")) { model.saveLocal() }
                    .disabled(!ui.isDirty)
                Button(L("nostr_profile_publish_action")) { confirmingPublish = true }
                    .disabled(ui.isPublishing)
                if ui.isPublishing {
                    HStack { ProgressView(); Text(L("nostr_profile_publishing")) }
                } else if ui.publishedTo > 0 {
                    Text(LI("profile_published_to", Int(ui.publishedTo), Int(ui.publishAttempted)))
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
        }
        .alert(L("nostr_profile_publish_warning_title"), isPresented: $confirmingPublish) {
            Button(L("action_cancel"), role: .cancel) {}
            Button(L("nostr_profile_publish_confirm")) { model.publish() }
        } message: {
            Text(L("nostr_profile_publish_warning_body"))
        }
        .alert(LI(errorKey(ui.errorCode)),
               isPresented: Binding(get: { ui.errorCode != "none" }, set: { if !$0 { model.consumeError() } })) {
            Button(L("action_done")) { model.consumeError() }
        }
    }

    /// Downsizes and JPEG-encodes before anything leaves the device: a
    /// modern phone photo is several megabytes, and a profile picture that
    /// size is a needless upload and a slow render for everyone else.
    private func upload(_ item: PhotosPickerItem?, asBanner: Bool, model: ProfileScreenModel) {
        guard let item else { return }
        Task {
            guard let data = try? await item.loadTransferable(type: Data.self),
                  let image = UIImage(data: data),
                  let jpeg = Self.encode(image, maxEdge: asBanner ? 1200 : 512) else { return }
            model.uploadImage(bytes: jpeg.toKotlinByteArray(), asBanner: asBanner)
        }
    }

    private static func encode(_ image: UIImage, maxEdge: CGFloat) -> Data? {
        let longest = max(image.size.width, image.size.height)
        let scale = longest > maxEdge ? maxEdge / longest : 1
        let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let renderer = UIGraphicsImageRenderer(size: size)
        let resized = renderer.image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
        return resized.jpegData(compressionQuality: 0.85)
    }

    private func binding(_ value: String, _ set: @escaping (String) -> Void) -> Binding<String> {
        Binding(get: { value }, set: set)
    }

    private func nip05Note(_ code: String) -> String? {
        switch code {
        case "matches": return L("nostr_profile_nip05_verified")
        case "mismatch": return L("nostr_profile_nip05_mismatch")
        case "unreachable": return L("nostr_profile_nip05_unreachable")
        case "checking": return LI("profile_verifying")
        default: return nil
        }
    }

    private func errorKey(_ code: String) -> String {
        switch code {
        case "noIdentity": return "profile_error_no_identity"
        case "signingFailed": return "profile_error_signing"
        case "noRelayAccepted": return "profile_error_no_relay"
        case "invalidUrl": return "profile_error_invalid_url"
        case "uploadFailed": return "profile_error_upload"
        default: return "profile_error_load"
        }
    }
}
