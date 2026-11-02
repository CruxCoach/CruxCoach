package com.cruxcoach.android.data.shinywall

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** A signed-in Shiny Wall account. Never holds the password. */
data class ShinyWallSession(
    val uid: String,
    val idToken: String,
    val refreshToken: String,
    /** Wall clock (ms) when [idToken] expires. */
    val idTokenExpiresAtMs: Long,
    /** Shown in Settings: the Shiny Wall username, else the sign-in email. */
    val displayName: String,
)

/** Storage of the Shiny Wall session and import bookkeeping. */
interface ShinyWallTokenStorage {
    fun session(): ShinyWallSession?
    fun storeSession(session: ShinyWallSession)
    fun updateTokens(idToken: String, refreshToken: String, expiresInSeconds: Long)
    fun updateDisplayName(displayName: String)
    fun hasSession(): Boolean = session() != null

    /** Wall clock (ms) of the last successful import, or null. */
    fun lastImportAtMs(): Long?

    /** Newest timestamp (epoch ms) of a history entry an import of [uid] placed in the logbook, or null. */
    fun historyWatermarkMs(uid: String): Long?
    fun recordImport(uid: String, atMs: Long, watermarkMs: Long?)

    /** Forget the session; the per-account watermark stays (see [ShinyWallSyncEngine]). */
    fun clearSession()
}

/**
 * EncryptedSharedPreferences (Android Keystore, AES-256-GCM) holding the
 * Shiny Wall sign-in tokens, the same pattern as the Kilter account.
 *
 * The file name is fixed (`shinywall_secure_prefs`) so the backup and
 * data-extraction rules can exclude it by exact path. Every key is prefixed
 * with the Nostr pubkey prefix, so switching identities never shows another
 * identity's Shiny Wall session.
 */
class ShinyWallTokenStore(
    private val context: Context,
    pubkeyPrefix: String,
) : ShinyWallTokenStorage {
    private companion object {
        const val TAG = "ShinyWallTokenStore"
        const val PREFS_FILE = "shinywall_secure_prefs"
    }

    private val keyUid = "${pubkeyPrefix}_uid"
    private val keyIdToken = "${pubkeyPrefix}_id_token"
    private val keyRefreshToken = "${pubkeyPrefix}_refresh_token"
    private val keyExpiry = "${pubkeyPrefix}_id_token_expiry"
    private val keyDisplayName = "${pubkeyPrefix}_display_name"
    private val keyLastImport = "${pubkeyPrefix}_last_import"
    // Versioned: when the import learns to place entries it used to skip, the
    // next version drops the older watermark, which may lie past them. v1 (no
    // suffix) also counted skipped entries.
    private val keyWatermarkPrefix = "${pubkeyPrefix}_watermark_v2_"
    private val keyOldWatermarkPrefix = "${pubkeyPrefix}_watermark_"
    private fun keyWatermark(uid: String) = keyWatermarkPrefix + uid

    private val prefs: SharedPreferences by lazy { openOrRecreatePrefs() }

    private fun openOrRecreatePrefs(): SharedPreferences = try {
        createEncryptedPrefs()
    } catch (e: Exception) {
        Log.e(TAG, "EncryptedSharedPreferences corrupted, regenerating", e)
        context.deleteSharedPreferences(PREFS_FILE)
        createEncryptedPrefs()
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun session(): ShinyWallSession? {
        val uid = prefs.getString(keyUid, null)?.takeIf { it.isNotBlank() } ?: return null
        val refresh = prefs.getString(keyRefreshToken, null)?.takeIf { it.isNotBlank() } ?: return null
        return ShinyWallSession(
            uid = uid,
            idToken = prefs.getString(keyIdToken, null).orEmpty(),
            refreshToken = refresh,
            idTokenExpiresAtMs = prefs.getLong(keyExpiry, 0L),
            displayName = prefs.getString(keyDisplayName, null).orEmpty(),
        )
    }

    override fun storeSession(session: ShinyWallSession) {
        prefs.edit()
            .putString(keyUid, session.uid)
            .putString(keyIdToken, session.idToken)
            .putString(keyRefreshToken, session.refreshToken)
            .putLong(keyExpiry, session.idTokenExpiresAtMs)
            .putString(keyDisplayName, session.displayName)
            .apply()
    }

    override fun updateTokens(idToken: String, refreshToken: String, expiresInSeconds: Long) {
        prefs.edit()
            .putString(keyIdToken, idToken)
            .putString(keyRefreshToken, refreshToken)
            .putLong(keyExpiry, System.currentTimeMillis() + expiresInSeconds * 1000)
            .apply()
    }

    override fun updateDisplayName(displayName: String) {
        prefs.edit().putString(keyDisplayName, displayName).apply()
    }

    override fun lastImportAtMs(): Long? = prefs.getLong(keyLastImport, 0L).takeIf { it > 0 }

    override fun historyWatermarkMs(uid: String): Long? = prefs.getLong(keyWatermark(uid), 0L).takeIf { it > 0 }

    override fun recordImport(uid: String, atMs: Long, watermarkMs: Long?) {
        val edit = prefs.edit().putLong(keyLastImport, atMs).remove(keyOldWatermarkPrefix + uid)
        if (watermarkMs != null) edit.putLong(keyWatermark(uid), watermarkMs)
        edit.apply()
    }

    override fun clearSession() {
        prefs.edit()
            .remove(keyUid)
            .remove(keyIdToken)
            .remove(keyRefreshToken)
            .remove(keyExpiry)
            .remove(keyDisplayName)
            .remove(keyLastImport)
            .apply()
    }
}
