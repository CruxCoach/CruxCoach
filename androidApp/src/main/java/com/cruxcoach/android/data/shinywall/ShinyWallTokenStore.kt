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

    /** New tokens of [uid]'s session; nothing when that session is gone (a refresh that ends after a disconnect). */
    fun updateTokens(uid: String, idToken: String, refreshToken: String, expiresInSeconds: Long)
    fun updateDisplayName(displayName: String)
    fun hasSession(): Boolean = session() != null

    /** Wall clock (ms) of the last successful import, or null. */
    fun lastImportAtMs(): Long?

    /** Newest timestamp (epoch ms) of a history entry an import of [uid] placed in the logbook, or null. */
    fun historyWatermarkMs(uid: String): Long?

    /**
     * Logbook uuids of the history entries imports of [uid] have placed, by entry time
     * (epoch ms). One the logbook lacks was deleted there, or went into its send.
     */
    fun placedLogs(uid: String): Map<String, Long>
    fun recordImport(uid: String, atMs: Long, watermarkMs: Long?, placedLogs: Map<String, Long>)

    /** External ids of the lists imports of [uid] have created. One the logbook lacks was deleted there. */
    fun importedLists(uid: String): Set<String>
    fun recordImportedLists(uid: String, externalIds: Set<String>)

    /**
     * Forget what imports have placed, for every account: after the logbook is cleared, the
     * next import brings everything in again. The session stays.
     */
    fun resetImportRecords()

    /** Forget the session; the per-account watermark, placed entries and lists stay (see [ShinyWallSyncEngine]). */
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
class ShinyWallTokenStore internal constructor(
    pubkeyPrefix: String,
    openPrefs: () -> SharedPreferences,
) : ShinyWallTokenStorage {
    constructor(context: Context, pubkeyPrefix: String) : this(pubkeyPrefix, { openOrRecreatePrefs(context) })

    private companion object {
        const val TAG = "ShinyWallTokenStore"
        const val PREFS_FILE = "shinywall_secure_prefs"

        fun openOrRecreatePrefs(context: Context): SharedPreferences = try {
            createEncryptedPrefs(context)
        } catch (e: Exception) {
            Log.e(TAG, "EncryptedSharedPreferences corrupted, regenerating", e)
            context.deleteSharedPreferences(PREFS_FILE)
            createEncryptedPrefs(context)
        }

        fun createEncryptedPrefs(context: Context): SharedPreferences {
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
    // "<uuid> <epoch ms>" per entry.
    private val keyPlacedPrefix = "${pubkeyPrefix}_placed_v1_"
    private fun keyPlaced(uid: String) = keyPlacedPrefix + uid
    private val keyListsPrefix = "${pubkeyPrefix}_lists_v1_"
    private fun keyLists(uid: String) = keyListsPrefix + uid

    private val prefs: SharedPreferences by lazy(openPrefs)

    // A token refresh runs on another thread than a disconnect: check and write as one step.
    private val lock = Any()

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
        synchronized(lock) {
            prefs.edit()
                .putString(keyUid, session.uid)
                .putString(keyIdToken, session.idToken)
                .putString(keyRefreshToken, session.refreshToken)
                .putLong(keyExpiry, session.idTokenExpiresAtMs)
                .putString(keyDisplayName, session.displayName)
                .apply()
        }
    }

    override fun updateTokens(uid: String, idToken: String, refreshToken: String, expiresInSeconds: Long) {
        synchronized(lock) {
            if (prefs.getString(keyUid, null) != uid) return
            prefs.edit()
                .putString(keyIdToken, idToken)
                .putString(keyRefreshToken, refreshToken)
                .putLong(keyExpiry, System.currentTimeMillis() + expiresInSeconds * 1000)
                .apply()
        }
    }

    override fun updateDisplayName(displayName: String) {
        prefs.edit().putString(keyDisplayName, displayName).apply()
    }

    override fun lastImportAtMs(): Long? = prefs.getLong(keyLastImport, 0L).takeIf { it > 0 }

    override fun historyWatermarkMs(uid: String): Long? = prefs.getLong(keyWatermark(uid), 0L).takeIf { it > 0 }

    override fun placedLogs(uid: String): Map<String, Long> =
        prefs.getStringSet(keyPlaced(uid), null).orEmpty().mapNotNull { item ->
            val (uuid, ms) = item.split(' ').takeIf { it.size == 2 } ?: return@mapNotNull null
            ms.toLongOrNull()?.let { uuid to it }
        }.toMap()

    override fun recordImport(uid: String, atMs: Long, watermarkMs: Long?, placedLogs: Map<String, Long>) {
        val edit = prefs.edit()
            .putLong(keyLastImport, atMs)
            .remove(keyOldWatermarkPrefix + uid)
            .putStringSet(keyPlaced(uid), placedLogs.map { (uuid, ms) -> "$uuid $ms" }.toSet())
        if (watermarkMs != null) edit.putLong(keyWatermark(uid), watermarkMs)
        edit.apply()
    }

    override fun importedLists(uid: String): Set<String> = prefs.getStringSet(keyLists(uid), null).orEmpty().toSet()

    override fun recordImportedLists(uid: String, externalIds: Set<String>) {
        prefs.edit().putStringSet(keyLists(uid), externalIds).apply()
    }

    override fun resetImportRecords() {
        val edit = prefs.edit()
        prefs.all.keys
            // Every watermark version shares the v1 prefix.
            .filter { it.startsWith(keyOldWatermarkPrefix) || it.startsWith(keyPlacedPrefix) || it.startsWith(keyListsPrefix) }
            .forEach { edit.remove(it) }
        edit.apply()
    }

    override fun clearSession() {
        synchronized(lock) {
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
}
