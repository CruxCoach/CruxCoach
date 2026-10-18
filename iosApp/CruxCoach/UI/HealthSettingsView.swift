import CruxCoachCore
import SwiftUI

/// The Apple Health link in the training settings (Android `HealthConnectSettingsCard`): one switch per
/// direction — the coach reads sleep and climbing workouts, nutrition sends meals and water — each with
/// its own access request, "Sync now" with the last result, and the privacy line. Nothing is uploaded
/// anywhere else. HealthKit never tells an app whether reading was allowed, and the card says so.
struct HealthSettingsView: View {
    let core: AppCore

    @State private var host: ScreenHost<HealthScreenModel, HealthScreenState>?
    @State private var unavailable = false
    @Environment(\.scenePhase) private var scenePhase

    init(core: AppCore) {
        self.core = core
    }

    var body: some View {
        Group {
            if let host, !host.state.loading {
                HealthSettingsForm(model: host.model, ui: host.state)
            } else if unavailable {
                ContentUnavailableView(LI("library_unavailable"), systemImage: "lock.trianglebadge.exclamationmark")
            } else {
                ProgressView()
            }
        }
        .navigationTitle(LI("health_title"))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard host == nil else { return }
            guard let model = core.makeHealthScreen() else {
                unavailable = true
                return
            }
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
            model.refresh()
        }
        // Back from the Health app: access may have changed there.
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { host?.model.refresh() }
        }
    }
}

private struct HealthSettingsForm: View {
    let model: HealthScreenModel
    let ui: HealthScreenState

    @Environment(\.openURL) private var openURL

    private var usable: Bool { ui.available && !ui.accessFailed }

    var body: some View {
        Form {
            Section {
                Text(LI("health_summary"))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Toggle(isOn: Binding(get: { ui.readEnabled }, set: { model.setReadEnabled(on: $0) })) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L("trd_hc_read_switch"))
                        Text(L("trd_hc_read_hint")).font(.footnote).foregroundStyle(.secondary)
                    }
                }
                .disabled(!usable && !ui.readEnabled)
                .accessibilityIdentifier("health_read_switch")
                Toggle(isOn: Binding(get: { ui.exportEnabled }, set: { model.setExportEnabled(on: $0) })) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(LI("health_export_switch"))
                        Text(LI("health_export_hint")).font(.footnote).foregroundStyle(.secondary)
                    }
                }
                .disabled(!usable && !ui.exportEnabled)
                .accessibilityIdentifier("health_export_switch")
            }

            Section {
                status
            }

            Section {
                Text(LI("health_privacy"))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder
    private var status: some View {
        if !ui.available {
            Label(LI("health_unavailable"), systemImage: "heart.slash")
                .accessibilityIdentifier("health_unavailable")
        } else if ui.accessFailed {
            Label(LI("health_access_failed"), systemImage: "exclamationmark.triangle")
                .accessibilityIdentifier("health_access_failed")
        } else if ui.readEnabled || ui.exportEnabled {
            if ui.needsAccess {
                Text(LI("health_permission_hint")).font(.footnote)
                Button(L("trd_hc_permit")) { model.permit() }
                    .accessibilityIdentifier("health_permit")
            }
            if ui.writeDenied {
                Text(LI("health_write_denied")).font(.footnote)
            }
            if ui.canSync {
                Button {
                    model.syncNow()
                } label: {
                    HStack {
                        Text(ui.syncing ? L("trd_hc_syncing") : L("trd_hc_sync_now"))
                        if ui.syncing {
                            Spacer()
                            ProgressView()
                        }
                    }
                }
                .disabled(ui.syncing)
                .accessibilityIdentifier("health_sync")
            }
            Button(LI("health_open_app")) {
                if let url = URL(string: "x-apple-health://") { openURL(url) }
            }
            if ui.readEnabled && ui.readAsked {
                Text(LI("health_read_unknown")).font(.footnote).foregroundStyle(.secondary)
            }
            if ui.hasLastSync {
                Text(ui.lastSyncFailed
                     ? LI("health_sync_failed")
                     : L("trd_hc_sync_result", Int(ui.added), Int(ui.updated), Int(ui.skippedManual)))
                    .font(.footnote)
                    .accessibilityIdentifier("health_result")
            }
            if ui.hasSleep {
                Text(L("trd_hc_sleep_last_night", ui.sleepHours.formatted(.number.precision(.fractionLength(0...1)))))
                    .font(.footnote)
            }
            if ui.exportEnabled, let exportKey = exportKey(ui.lastExport) {
                Text(LI(exportKey)).font(.footnote)
            }
        }
    }

    private func exportKey(_ code: String) -> String? {
        switch code {
        case "done": return "health_export_done"
        case "unchanged": return "health_export_unchanged"
        case "failed", "noAccess": return "health_export_failed"
        default: return nil
        }
    }
}
