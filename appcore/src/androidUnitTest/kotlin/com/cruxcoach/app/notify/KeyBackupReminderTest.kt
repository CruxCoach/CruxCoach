package com.cruxcoach.app.notify

import com.cruxcoach.app.backup.BackupState
import com.cruxcoach.app.logbook.MapKeyValueStore
import com.cruxcoach.app.settings.SettingsStore
import com.cruxcoach.app.testing.JvmHashing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Android `BackupSettingsSection`: the card while the backup is in use and the key is not confirmed. */
class KeyBackupReminderTest {
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)

    private fun due(kv: MapKeyValueStore, settings: SettingsStore): Boolean {
        val backup = BackupState(kv, JvmHashing)
        return KeyBackupReminder.due(backup.backupEnabled, backup.lastBackupSync, settings.snapshot.keyBackedUp)
    }

    @Test
    fun `the card follows the backup in use and the confirmed key`() {
        assertFalse(KeyBackupReminder.due(backupEnabled = false, lastBackupSync = null, keyBackedUp = false))
        assertFalse(KeyBackupReminder.due(backupEnabled = false, lastBackupSync = 0L, keyBackedUp = false))
        assertTrue(KeyBackupReminder.due(backupEnabled = true, lastBackupSync = null, keyBackedUp = false))
        assertTrue(KeyBackupReminder.due(backupEnabled = false, lastBackupSync = 1_760_000_000L, keyBackedUp = false))
        assertFalse(KeyBackupReminder.due(backupEnabled = true, lastBackupSync = 1_760_000_000L, keyBackedUp = true))
    }

    @Test
    fun `the confirmation is Android's key_backed_up flag of this identity`() {
        val kv = MapKeyValueStore()
        val settings = SettingsStore(kv, alice)
        // No backup yet: nothing to lose, no card.
        assertFalse(due(kv, settings))
        // A backup made from this device (the iPhone has no switch that anything reads).
        BackupState(kv, JvmHashing).lastBackupSync = 1_760_000_000L
        assertTrue(due(kv, settings))
        // Android's backup switch alone counts as in use too.
        BackupState(kv, JvmHashing).lastBackupSync = null
        BackupState(kv, JvmHashing).backupEnabled = true
        assertTrue(due(kv, settings))
        // Confirmed on the account screen: the same scoped key Android writes.
        settings.setKeyBackedUp(true)
        assertEquals("true", kv.map["key/${"a".repeat(16)}/key_backed_up"])
        assertFalse(due(kv, settings))
        // Another identity has not confirmed its key.
        settings.setIdentity(bob)
        assertTrue(due(kv, settings))
    }
}
