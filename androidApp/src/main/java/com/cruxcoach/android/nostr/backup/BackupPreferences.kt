package com.cruxcoach.android.nostr.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DataStore-Preferences-backed state for FEAT-002 (Nostr Encrypted Backup).
 *
 * Lives in the same store as [com.cruxcoach.android.data.UserPreferences].
 * Kept as a dedicated class so Backup code doesn't have to pull in the full
 * UserPreferences surface (and vice versa).
 *
 * All values except the feature flag and the onboarding-seen bit are
 * sensitive-but-self-protecting: the wrapped dataKey is NIP-44 ciphertext
 * (opaque without the user's privkey), the d-tags are derived
 * pseudorandom hex strings, and the previous-blob-sha is a public hash.
 * Storing them in plain DataStore-Preferences therefore matches FEAT-002
 * §11.2's "no ESP" decision.
 */
@Singleton
class BackupPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    val backupEnabled: Flow<Boolean> = dataStore.data.map {
        it[Keys.BACKUP_ENABLED] ?: false
    }

    suspend fun isBackupEnabled(): Boolean =
        dataStore.data.first()[Keys.BACKUP_ENABLED] ?: false

    suspend fun setBackupEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.BACKUP_ENABLED] = enabled }
    }

    /** Default `true` — the dev-side kill switch from FEAT-002 §19. */
    val backupFeatureEnabled: Flow<Boolean> = dataStore.data.map {
        it[Keys.BACKUP_FEATURE_ENABLED] ?: true
    }

    suspend fun isBackupFeatureEnabled(): Boolean =
        dataStore.data.first()[Keys.BACKUP_FEATURE_ENABLED] ?: true

    suspend fun setBackupFeatureEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.BACKUP_FEATURE_ENABLED] = enabled }
    }

    /** Onboarding seen flag so the opt-in step isn't shown twice. */
    suspend fun isBackupOnboardingSeen(): Boolean =
        dataStore.data.first()[Keys.BACKUP_ONBOARDING_SEEN] ?: false

    suspend fun setBackupOnboardingSeen(seen: Boolean) {
        dataStore.edit { it[Keys.BACKUP_ONBOARDING_SEEN] = seen }
    }

    /**
     * Marker that survives the app-restart triggered by
     * [com.cruxcoach.android.ui.settings.KeyImportScreen] after a successful
     * import. Set by the onboarding right before navigating to KeyImport;
     * read on the next cold start so onboarding knows to jump back to the
     * Privacy step with the "restore" radio pre-selected and auto-trigger
     * `checkForBackup`. Always cleared after it's acted on.
     */
    suspend fun isBackupRestoreIntent(): Boolean =
        dataStore.data.first()[Keys.BACKUP_RESTORE_INTENT] ?: false

    suspend fun setBackupRestoreIntent(intent: Boolean) {
        dataStore.edit {
            if (intent) it[Keys.BACKUP_RESTORE_INTENT] = true
            else it.remove(Keys.BACKUP_RESTORE_INTENT)
        }
    }

    // ---- DataKey: NIP-44-wrapped ciphertext (hex-encoded raw bytes wrap) ----

    suspend fun getWrappedDataKey(): String? =
        dataStore.data.first()[Keys.WRAPPED_DATA_KEY]

    suspend fun setWrappedDataKey(wrapped: String?) {
        dataStore.edit {
            if (wrapped == null) it.remove(Keys.WRAPPED_DATA_KEY)
            else it[Keys.WRAPPED_DATA_KEY] = wrapped
        }
    }

    // ---- D-tag cache (identifier → hex d-tag) ----

    suspend fun getDTag(identifier: String): String? =
        dataStore.data.first()[dTagKey(identifier)]

    suspend fun setDTag(identifier: String, dTag: String) {
        dataStore.edit { it[dTagKey(identifier)] = dTag }
    }

    suspend fun clearDTags() {
        dataStore.edit { prefs ->
            prefs.remove(dTagKey(IDENTIFIER_BACKUP))
            prefs.remove(dTagKey(IDENTIFIER_KEY))
        }
    }

    // ---- Previous blob SHA-256 for cleanup after pointer publish ----

    suspend fun getPreviousBlobSha256(): String? =
        dataStore.data.first()[Keys.PREVIOUS_BLOB_SHA256]

    suspend fun setPreviousBlobSha256(sha: String?) {
        dataStore.edit {
            if (sha == null) it.remove(Keys.PREVIOUS_BLOB_SHA256)
            else it[Keys.PREVIOUS_BLOB_SHA256] = sha
        }
    }

    // ---- BUD-06 content-type preflight cache (server URL → result) ----

    enum class ContentTypeProbe { ACCEPTED, REJECTED_OCTET, INCOMPATIBLE }

    suspend fun getContentTypeProbe(server: String): ContentTypeProbe? {
        val raw = dataStore.data.first()[contentTypeKey(server)] ?: return null
        return runCatching { ContentTypeProbe.valueOf(raw) }.getOrNull()
    }

    suspend fun setContentTypeProbe(server: String, result: ContentTypeProbe) {
        dataStore.edit { it[contentTypeKey(server)] = result.name }
    }

    // ---- Stable device-id for the pointer event (not a tracking id) ----

    suspend fun getOrCreateDeviceId(): String {
        dataStore.data.first()[Keys.DEVICE_ID]?.let { return it }
        val fresh = java.util.UUID.randomUUID().toString()
        dataStore.edit { it[Keys.DEVICE_ID] = fresh }
        return fresh
    }

    // ---- Last successful backup timestamp (epoch seconds) ----

    val lastBackupSync: Flow<Long?> = dataStore.data.map {
        it[Keys.LAST_BACKUP_SYNC]
    }

    suspend fun setLastBackupSync(epochSeconds: Long) {
        dataStore.edit { prefs ->
            prefs[Keys.LAST_BACKUP_SYNC] = epochSeconds
        }
    }

    // ---- Clear identity-scoped state on logout / key switch ----

    suspend fun clearAllIdentityState() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.WRAPPED_DATA_KEY)
            prefs.remove(dTagKey(IDENTIFIER_BACKUP))
            prefs.remove(dTagKey(IDENTIFIER_KEY))
            prefs.remove(Keys.PREVIOUS_BLOB_SHA256)
            prefs.remove(Keys.LAST_BACKUP_SYNC)
            // Intentionally NOT cleared: BACKUP_ENABLED, BACKUP_FEATURE_ENABLED,
            // BACKUP_ONBOARDING_SEEN, DEVICE_ID — these survive identity changes.
        }
    }

    private fun dTagKey(identifier: String) = stringPreferencesKey("backup_dtag_${identifier.hashCode()}")
    private fun contentTypeKey(server: String) = stringPreferencesKey("backup_ct_${server.hashCode()}")

    object Keys {
        val BACKUP_ENABLED = booleanPreferencesKey("backup_enabled")
        val BACKUP_FEATURE_ENABLED = booleanPreferencesKey("backup_feature_enabled")
        val BACKUP_ONBOARDING_SEEN = booleanPreferencesKey("backup_onboarding_seen")
        val BACKUP_RESTORE_INTENT = booleanPreferencesKey("backup_restore_intent")
        val WRAPPED_DATA_KEY = stringPreferencesKey("backup_wrapped_data_key")
        val PREVIOUS_BLOB_SHA256 = stringPreferencesKey("backup_previous_blob_sha256")
        val DEVICE_ID = stringPreferencesKey("backup_device_id")
        val LAST_BACKUP_SYNC = androidx.datastore.preferences.core.longPreferencesKey("backup_last_sync")
    }

    companion object {
        const val IDENTIFIER_BACKUP = "cruxcoach/backup/v1"
        const val IDENTIFIER_KEY = "cruxcoach/key/v1"
    }
}
