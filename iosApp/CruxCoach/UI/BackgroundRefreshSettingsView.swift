import CruxCoachCore
import SwiftUI

/// What the background refresh obeys: Android's "Background announcements" with its four
/// categories, and the cloud backup's interval. iOS chooses the background slots itself, which
/// the footer says instead of promising a fixed schedule.
struct BackgroundRefreshSettingsView: View {
    let core: AppCore

    @State private var host: ScreenHost<BackgroundSettingsModel, BackgroundSettingsState>?

    var body: some View {
        Group {
            if let host {
                BackgroundRefreshSettingsForm(model: host.model, ui: host.state)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(LI("background_title"))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard host == nil else { return }
            let model = core.makeBackgroundSettings()
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) })
        }
    }
}

private struct BackgroundRefreshSettingsForm: View {
    let model: BackgroundSettingsModel
    let ui: BackgroundSettingsState

    var body: some View {
        Form {
            announcementsSection
            backupSection
            Section {
                Text(LI("background_footer"))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var announcementsSection: some View {
        Section(L("devcontact_announcements")) {
            Toggle(isOn: Binding(get: { ui.announcementsEnabled }, set: { model.setAnnouncementsEnabled(value: $0) })) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(L("devcontact_notifications"))
                    Text(LI("background_announcements_desc")).font(.footnote).foregroundStyle(.secondary)
                }
            }
            if ui.announcementsEnabled {
                categoryToggles
            }
        }
    }

    @ViewBuilder
    private var categoryToggles: some View {
        Toggle(isOn: Binding(get: { ui.releases }, set: { model.setReleases(value: $0) })) {
            CategoryLabel(title: L("announcements_badge_release"), detail: L("announcement_cat_release_desc"))
        }
        Toggle(isOn: Binding(get: { ui.issues }, set: { model.setIssues(value: $0) })) {
            CategoryLabel(title: L("announcements_badge_issue"), detail: L("announcement_cat_issue_desc"))
        }
        Toggle(isOn: Binding(get: { ui.tips }, set: { model.setTips(value: $0) })) {
            CategoryLabel(title: L("announcements_badge_tip"), detail: L("announcement_cat_tip_desc"))
        }
        Toggle(isOn: Binding(get: { ui.general }, set: { model.setGeneral(value: $0) })) {
            CategoryLabel(title: L("announcements_badge_general"), detail: L("announcement_cat_general_desc"))
        }
    }

    private var backupSection: some View {
        Section(LI("background_backup_header")) {
            Picker(L("settings_backup_interval"), selection: Binding(get: { ui.backupInterval }, set: { model.setBackupInterval(value: $0) })) {
                Text(L("sync_interval_daily")).tag("DAILY")
                Text(L("sync_interval_weekly")).tag("WEEKLY")
                Text(L("sync_interval_manual")).tag("MANUAL")
            }
            .pickerStyle(.menu)
            Text(LI(ui.backupEnabled ? "background_backup_desc" : "background_backup_off"))
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }
}

private struct CategoryLabel: View {
    let title: String
    let detail: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
            Text(detail).font(.footnote).foregroundStyle(.secondary)
        }
    }
}
