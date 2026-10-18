package com.cruxcoach.app.notify

/**
 * When Android's `BackupKeyWarningCard` reminds the user to save the account key: in Settings →
 * cloud backup (`BackupSettingsSection`) while the cloud backup is in use and the user has not
 * confirmed that the key is stored outside this device — `key_backed_up`, scoped to the identity,
 * the flag Android's account screen ("I've saved my key") sets.
 *
 * Android's "in use" is the backup switch. The iPhone has no scheduled backup, so nothing there
 * sets or reads that switch; a cloud backup made from this device (its last-backup time) counts
 * as in use too, because that backup is exactly what is lost with the key.
 */
object KeyBackupReminder {
    fun due(backupEnabled: Boolean, lastBackupSync: Long?, keyBackedUp: Boolean): Boolean =
        !keyBackedUp && (backupEnabled || (lastBackupSync ?: 0L) > 0L)
}
