import CruxCoachCore
import SwiftUI

/// Encrypted Nostr backup, in the same format the Android app writes: the data
/// is encrypted on the device, stored on Blossom, and only the key and pointer
/// travel through Nostr. Restoring needs the same account.
struct BackupView: View {
    let core: AppCore
    @State private var host: ScreenHost<BackupScreenModel, BackupScreenState>?
    @State private var confirmRestore = false

    var body: some View {
        Group {
            if let host {
                screen(model: host.model, ui: host.state)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(LI("backup_title"))
        .task {
            guard host == nil else { return }
            let model = core.backupScreen
            host = ScreenHost(model: model, initial: model.currentState) { model, onState in
                model.watch(onState: onState)
            }
            model.checkForBackup()
        }
    }

    @ViewBuilder
    private func screen(model: BackupScreenModel, ui: BackupScreenState) -> some View {
        Form {
            Section {
                Text(LI("backup_explanation"))
            }
            Section(LI("backup_remote")) {
                if ui.busy {
                    HStack { ProgressView(); Text(phaseText(ui.phase)) }
                } else if ui.hasBackup {
                    LabeledContent(LI("backup_found"), value: sizeText(ui.backupSizeBytes))
                    if ui.ascentsInBackup > 0 || ui.bidsInBackup > 0 || ui.listsInBackup > 0 {
                        Text(LI("backup_contents", Int(ui.ascentsInBackup), Int(ui.bidsInBackup), Int(ui.listsInBackup)))
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                    Button(LI("backup_restore")) { confirmRestore = true }
                } else if ui.phase == BackupScreenState.companion.PHASE_UNREACHABLE {
                    Text(LI("backup_unreachable")).foregroundStyle(.orange)
                } else {
                    Text(LI("backup_none")).foregroundStyle(.secondary)
                }
                Button(LI("backup_check")) { model.checkForBackup() }.disabled(ui.busy)
            }
            if ui.phase == BackupScreenState.companion.PHASE_RESTORED {
                Section {
                    Label(LI("backup_restored", Int(ui.rowsImported), Int(ui.skippedDuplicates)), systemImage: "checkmark.circle")
                        .foregroundStyle(.green)
                }
            }
            Section(LI("backup_own")) {
                Button(LI("backup_now")) { model.backUpNow(exportedAt: ISO8601DateFormatter().string(from: Date())) }
                    .disabled(ui.busy)
                if ui.lastBackupAt > 0 {
                    Text(LI("backup_last", dateText(ui.lastBackupAt))).font(.footnote).foregroundStyle(.secondary)
                }
            } footer: {
                Text(LI("backup_own_hint"))
            }
            if let failure = ui.failureCode {
                Section {
                    Label(LI("backup_failed", failure), systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.red)
                    Button(L("action_close")) { model.dismissFailure() }
                }
            }
        }
        .alert(LI("backup_restore_confirm_title"), isPresented: $confirmRestore) {
            Button(L("action_cancel"), role: .cancel) { }
            Button(LI("backup_restore")) { model.restore() }
        } message: {
            Text(LI("backup_restore_confirm"))
        }
    }

    private func phaseText(_ phase: String) -> String {
        switch phase {
        case BackupScreenState.companion.PHASE_CHECKING: return LI("backup_checking")
        case BackupScreenState.companion.PHASE_RESTORING: return LI("backup_restoring")
        case BackupScreenState.companion.PHASE_BACKING_UP: return LI("backup_uploading")
        default: return LI("catalogue_checking")
        }
    }

    private func sizeText(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }

    private func dateText(_ epochSeconds: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(epochSeconds))
        return DateFormatter.localizedString(from: date, dateStyle: .medium, timeStyle: .short)
    }
}
