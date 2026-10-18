import CruxCoachCore
import SwiftUI

/// Encrypted Nostr backup, in the same format the Android app writes: the data
/// is encrypted on the device, stored on Blossom, and only the key and pointer
/// travel through Nostr. Restoring needs the same account.
struct BackupView: View {
    let core: AppCore
    @State private var host: ScreenHost<BackupScreenModel, BackupScreenState>?
    @State private var confirmRestore = false
    @State private var confirmDeleteRemote = false

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
            KeyBackupCard(core: core, revision: ui.lastBackupAt)
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
            Section {
                Button(LI("backup_now")) { model.backUpNow(exportedAt: ISO8601DateFormatter().string(from: Date())) }
                    .disabled(ui.busy)
                if ui.lastBackupAt > 0 {
                    Text(LI("backup_last", dateText(ui.lastBackupAt))).font(.footnote).foregroundStyle(.secondary)
                }
            } header: {
                Text(LI("backup_own"))
            } footer: {
                Text(LI("backup_own_hint"))
            }
            // Android's active opt-out (FEAT-002 §20.2), behind a confirmation.
            Section {
                Button(role: .destructive) {
                    confirmDeleteRemote = true
                } label: {
                    Text(ui.deletingRemote ? L("settings_backup_delete_remote_in_progress") : L("settings_backup_delete_remote"))
                }
                .disabled(ui.busy)
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
        .modifier(BackupDeleteRemotePrompts(model: model, ui: ui, confirm: $confirmDeleteRemote))
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

/// Android's `DeleteRemoteBackupsDialog` and the report after it: how many index servers (relays)
/// and storage servers (Blossom) confirmed, the caveat, and a line per shortfall. Only confirmed
/// counts appear; a server that did not answer is never shown as having deleted anything.
private struct BackupDeleteRemotePrompts: ViewModifier {
    let model: BackupScreenModel
    let ui: BackupScreenState
    @Binding var confirm: Bool

    func body(content: Content) -> some View {
        content
            .alert(L("settings_backup_delete_remote_dialog_title"), isPresented: $confirm) {
                Button(L("settings_backup_restore_cancel"), role: .cancel) { }
                Button(L("settings_backup_delete_remote_confirm"), role: .destructive) { model.deleteRemote() }
            } message: {
                Text(L("settings_backup_delete_remote_dialog_body"))
            }
            .alert(header, isPresented: Binding(
                get: { ui.deleteFinished },
                set: { if !$0 { model.dismissDeleteResult() } })) {
                Button(L("action_ok")) { model.dismissDeleteResult() }
            } message: {
                Text(report)
            }
    }

    private var header: String {
        ui.deleteComplete ? L("settings_backup_delete_remote_done_header") : L("settings_backup_delete_remote_partial_header")
    }

    private var report: String {
        let relays: String = L("settings_backup_delete_remote_relays_line", Int(ui.deleteRelaysAccepted), Int(ui.deleteRelaysAttempted))
        let blossom: String = ui.deleteBlossomAttempted == 0
            ? L("settings_backup_delete_remote_no_blob")
            : L("settings_backup_delete_remote_blossom_line", Int(ui.deleteBlossomAccepted), Int(ui.deleteBlossomAttempted))
        let caveat: String = L("settings_backup_delete_remote_caveat")
        let notes: [BackupDeleteNoteUi] = ui.deleteNotes
        let lines: [String] = notes.map { (note: BackupDeleteNoteUi) -> String in "• " + BackupDeleteRemotePrompts.noteText(note) }
        let block: String = lines.isEmpty ? "" : "\n\n" + lines.joined(separator: "\n")
        return relays + "\n" + blossom + "\n\n" + caveat + block
    }

    /// Android `localizedDeleteRemoteNote`.
    static func noteText(_ note: BackupDeleteNoteUi) -> String {
        switch note.code {
        case "dtagDerivationFailed": return L("delete_remote_note_dtag_derivation_failed")
        case "noWriteRelays": return L("delete_remote_note_no_write_relays")
        case "noRelayAccepted": return L("delete_remote_note_no_relay_accepted")
        case "partialRelayAccept": return L("delete_remote_note_partial_relay_accept", Int(note.accepted), Int(note.attempted))
        case "relayPublishThrew": return L("delete_remote_note_relay_publish_threw")
        case "blossomAuthFailed": return L("delete_remote_note_blossom_auth_failed")
        case "blossomFullyRejected": return L("delete_remote_note_blossom_fully_rejected")
        case "blossomPartial": return L("delete_remote_note_blossom_partial", Int(note.accepted), Int(note.attempted))
        case "tombstonePublishFailed":
            return L("delete_remote_note_tombstone_publish_failed", Int(note.backupAccepted), Int(note.keyAccepted), Int(note.attempted))
        default: return L("delete_remote_note_unexpected_error", note.errorType)
        }
    }
}
