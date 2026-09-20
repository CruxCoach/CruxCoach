import CruxCoachCore
import SwiftUI

/// The settings that already take effect on iOS. Uses Android's preference keys and values.
struct SettingsView: View {
    let core: AppCore


    var body: some View {
        Form {
            Section {
                NavigationLink(LI("settings_all")) { SettingsDetailView(core: core) }
            } footer: {
                Text(LI("settings_grade_restart"))
            }
            Section(LI("settings_account")) {
                LabeledContent(LI("settings_pubkey")) {
                    Text(core.npub.isEmpty ? core.pubkeyHex : core.npub)
                        .font(.footnote.monospaced())
                        .textSelection(.enabled)
                        .lineLimit(3)
                }
                Text(LI("settings_account_hint")).font(.footnote).foregroundStyle(.secondary)
            }
            Section(LI("import_title")) {
                NavigationLink(LI("import_title")) { ImportView(core: core) }
            }
            Section(LI("kilter_title")) {
                NavigationLink(LI("kilter_title")) { KilterView(core: core) }
            }
            Section(LI("backup_title")) {
                NavigationLink(LI("backup_open")) { BackupView(core: core) }
                NavigationLink(LI("data_exchange_open")) { DataExchangeView(core: core) }
                NavigationLink(LI("profile_open")) { ProfileView(core: core) }
                NavigationLink(LI("devcontact_open")) { DevContactView(core: core) }
            }
            Section(LI("settings_about")) {
                LabeledContent("CruxCoach iOS", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "")
                LabeledContent(LI("settings_storage"), value: "SQLCipher \(core.cipherVersion)")
                Text(LI("settings_preview_notice")).font(.footnote).foregroundStyle(.secondary)
            }
        }
        .navigationTitle(L("settings_title"))
    }
}
