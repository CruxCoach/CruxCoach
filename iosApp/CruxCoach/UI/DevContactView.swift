import CruxCoachCore
import SwiftUI

/// Bug reports, feature requests and the maintainer's replies, as NIP-17
/// private messages. Fetching is explicit: iOS cannot hold a subscription in
/// the background, and the screen should not pretend otherwise.
struct DevContactView: View {
    let core: AppCore
    @State private var host: ScreenHost<DevContactScreenModel, DevContactScreenState>?

    var body: some View {
        Group {
            if let host {
                screen(model: host.model, ui: host.state)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(L("devcontact_title"))
        .task {
            guard host == nil else { return }
            let model = core.makeDevContactScreen(
                language: Locale.current.language.languageCode?.identifier ?? "en")
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
            model.fetch()
        }
    }

    @ViewBuilder
    private func screen(model: DevContactScreenModel, ui: DevContactScreenState) -> some View {
        List {
            Section {
                Picker(L("devcontact_message"), selection: Binding(
                    get: { ui.typeCode }, set: { model.setTypeCode(code: $0) })) {
                    Text(L("devcontact_bug_reports")).tag("bug")
                    Text(L("devcontact_wishes")).tag("feature")
                    Text(L("devcontact_message")).tag("chat")
                }
                .pickerStyle(.segmented)
                TextField(LI("devcontact_subject"),
                          text: Binding(get: { ui.subject }, set: { model.setSubject(value: $0) }))
                TextField(L("devcontact_write_reply"),
                          text: Binding(get: { ui.draft }, set: { model.setDraft(value: $0) }),
                          axis: .vertical)
                    .lineLimit(3...8)
                Button(L("action_send")) { model.send() }
                    .disabled(ui.isSending || ui.draft.isEmpty)
                if ui.isSending { HStack { ProgressView(); Text(L("action_send")) } }
                Text(LI("devcontact_privacy_hint")).font(.footnote).foregroundStyle(.secondary)
            }
            Section(header: Text(L("devcontact_support_developer"))) {
                if let kofi = URL(string: core.kofiUrl) {
                    Link(L("payment_kofi_title"), destination: kofi)
                }
                LabeledContent(L("payment_lightning_title")) {
                    Text(core.maintainerLightningAddress)
                        .font(.caption).textSelection(.enabled).multilineTextAlignment(.trailing)
                }
                // No in-app payment: sending sats needs a wallet, and the
                // address is here to be copied into one.
                Text(LI("payment_no_wallet_hint")).font(.footnote).foregroundStyle(.secondary)
            }
            if !ui.announcements.isEmpty {
                Section(header: Text(L("devcontact_announcements"))) {
                    ForEach(ui.announcements, id: \.id) { item in
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Label(L(Self.categoryKey[item.category] ?? "devcontact_category_general"),
                                      systemImage: Self.categoryIcon[item.category] ?? "megaphone")
                                    .font(.caption.bold())
                                Spacer()
                                Text(date(item.createdAtEpochSeconds))
                                    .font(.caption2).foregroundStyle(.secondary)
                            }
                            Text(item.content)
                        }
                        .listRowBackground(item.read ? Color.clear : Color.accentColor.opacity(0.08))
                        .onAppear { if !item.read { model.markAnnouncementRead(id: item.id) } }
                    }
                }
            }
            Section(header: header(model: model, ui: ui)) {
                if ui.isLoading && ui.messages.isEmpty {
                    ProgressView().frame(maxWidth: .infinity)
                } else if ui.messages.isEmpty {
                    Text(L("devcontact_chat_empty")).foregroundStyle(.secondary)
                }
                ForEach(ui.messages, id: \.id) { message in
                    row(message).onAppear {
                        if !message.read && !message.outgoing { model.markRead(id: message.id) }
                    }
                }
            }
        }
        .refreshable { model.fetch() }
        .alert(LI(errorKey(ui.errorCode)),
               isPresented: Binding(get: { ui.errorCode != "none" }, set: { if !$0 { model.consumeError() } })) {
            Button(L("action_done")) { model.consumeError() }
        }
    }

    @ViewBuilder
    private func header(model: DevContactScreenModel, ui: DevContactScreenState) -> some View {
        HStack {
            Text(L("devcontact_messages"))
            Spacer()
            if ui.isFetching {
                ProgressView()
            } else {
                Button(LI("devcontact_fetch")) { model.fetch() }.font(.caption)
            }
        }
    }

    @ViewBuilder
    private func row(_ message: DevMessageRowUi) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(message.outgoing ? LI("devcontact_you") : L("devcontact_developer"))
                    .font(.caption.bold())
                if !message.subject.isEmpty {
                    Text(message.subject).font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Text(date(message.createdAtEpochSeconds)).font(.caption2).foregroundStyle(.secondary)
            }
            Text(message.content)
            if message.outgoing {
                Text(message.delivered
                     ? L("devcontact_status_delivered")
                     : L("devcontact_status_not_delivered"))
                    .font(.caption2)
                    .foregroundStyle(message.delivered ? Color.secondary : .orange)
            }
        }
        .listRowBackground(message.read || message.outgoing ? Color.clear : Color.accentColor.opacity(0.08))
    }

    private func date(_ epochSeconds: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(epochSeconds))
        return date.formatted(date: .abbreviated, time: .shortened)
    }

    private static let categoryKey = [
        "release": "devcontact_category_release", "issue": "devcontact_category_issue",
        "tip": "devcontact_category_tip", "general": "devcontact_category_general",
    ]
    private static let categoryIcon = [
        "release": "shippingbox", "issue": "exclamationmark.triangle",
        "tip": "lightbulb", "general": "megaphone",
    ]

    private func errorKey(_ code: String) -> String {
        switch code {
        case "noIdentity": return "profile_error_no_identity"
        case "empty": return "devcontact_error_empty"
        case "notDelivered": return "devcontact_error_not_delivered"
        case "fetchFailed": return "devcontact_error_fetch"
        default: return "devcontact_error_send"
        }
    }
}
