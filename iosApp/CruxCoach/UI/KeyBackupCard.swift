import CruxCoachCore
import SwiftUI

/// Android `BackupKeyWarningCard`, as a `Form` section: while the cloud backup is in use and the
/// account key was not confirmed as stored outside this device (`key_backed_up`, the flag Android's
/// account screen sets), it reminds that the backup cannot be restored without the key. Android's
/// button opens the account screen to show, copy and confirm the key; so does this one (`KeyView`).
/// With `openBackup` (placed outside the backup page) it leads there.
/// `revision` is any value that changes when a backup was made, so the card is checked again.
struct KeyBackupCard: View {
    let core: AppCore
    var openBackup: Bool = false
    var revision: Int64 = 0

    @State private var showInfo = false

    var body: some View {
        if core.isKeyBackupWarningDue() {
            Section {
                content
                if openBackup {
                    NavigationLink(LI("backup_open")) { BackupView(core: core) }
                }
            }
            .alert(L("backup_key_warning_title"), isPresented: $showInfo) {
                Button(L("action_close"), role: .cancel) { }
            } message: {
                Text(L("backup_key_warning_body_local"))
            }
        }
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 12) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundStyle(Color.accentColor)
                Text(L("backup_key_warning_title")).bold()
                Spacer()
                Button { showInfo = true } label: {
                    Image(systemName: "info.circle")
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(L("backup_key_warning_title"))
            }
            Text(L("backup_key_short_local"))
            NavigationLink(L("account_flow_copy")) { KeyView(core: core) }
        }
        .accessibilityIdentifier("key_backup_card")
    }
}
