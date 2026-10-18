import CruxCoachCore
import SwiftUI

/// Bug reports, feature requests and the maintainer's replies, as NIP-17
/// private messages. Fetching is explicit: iOS cannot hold a subscription in
/// the background, and the screen should not pretend otherwise.
struct DevContactView: View {
    let core: AppCore
    /// Opened from a notification: "announcements" or "dev_chat" scrolls there once loaded.
    var focus: String = ""
    @State private var host: ScreenHost<DevContactScreenModel, DevContactScreenState>?
    /// The focus was scrolled to; later updates leave the position to the user.
    @State private var focused = false

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
        ScrollViewReader { proxy in
            list(model: model, ui: ui)
                .onAppear { scrollToFocus(proxy) }
                .onChange(of: ui.announcements.count) { _, _ in scrollToFocus(proxy) }
                .onChange(of: ui.messages.count) { _, _ in scrollToFocus(proxy) }
        }
    }

    /// Android opens the announcements or the chat as pages of their own; here they are parts of
    /// one page, so a notification's route scrolls to its part, once, when it has rows.
    private func scrollToFocus(_ proxy: ScrollViewProxy) {
        guard !focused, !focus.isEmpty, let ui = host?.state else { return }
        var target = ""
        if focus == "announcements" {
            target = ui.announcements.first?.id ?? ""
        } else if focus == "dev_chat" {
            let reply: DevMessageRowUi? = ui.messages.first { !$0.outgoing }
            target = reply?.id ?? ui.messages.first?.id ?? ""
        }
        guard !target.isEmpty else { return }
        focused = true
        withAnimation { proxy.scrollTo(target, anchor: .top) }
    }

    @ViewBuilder
    private func list(model: DevContactScreenModel, ui: DevContactScreenState) -> some View {
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
                // Android's gift: a zap to the maintainer through the user's own wallet app.
                ZapGiftRow(core: core)
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
