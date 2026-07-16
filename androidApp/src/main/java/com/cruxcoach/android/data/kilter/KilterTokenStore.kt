package com.cruxcoach.android.data.kilter

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

/**
 * Secure storage for Kilter account credentials (refresh token, access token).
 * Uses EncryptedSharedPreferences backed by Android Keystore (AES-256-GCM).
 *
 * File name is fixed (kilter_secure_prefs) so the backup/data-extraction rules
 * can exclude it by exact path — Android NSC/backup rules don't support
 * wildcards. Per-identity isolation is preserved by prefixing every key with
 * the Nostr pubkey prefix, so switching identities never surfaces another
 * identity's tokens.
 */
class KilterTokenStore(
    private val context: Context,
    pubkeyPrefix: String
) {
    private companion object {
        const val TAG = "KilterTokenStore"
        const val PREFS_FILE = "kilter_secure_prefs"
    }

    private val keyAccessToken = "${pubkeyPrefix}_access_token"
    private val keyRefreshToken = "${pubkeyPrefix}_refresh_token"
    private val keyAccessTokenExpiry = "${pubkeyPrefix}_access_token_expiry"
    private val keyUserUuid = "${pubkeyPrefix}_user_uuid"
    private val keyUsername = "${pubkeyPrefix}_username"
    private val keyGymUuid = "${pubkeyPrefix}_gym_uuid"
    private val keyWallUuid = "${pubkeyPrefix}_wall_uuid"
    private val keyProductLayoutUuid = "${pubkeyPrefix}_product_layout_uuid"
    private val prefsFile = PREFS_FILE

    private val prefs: SharedPreferences by lazy { openOrRecreatePrefs() }

    private fun openOrRecreatePrefs(): SharedPreferences {
        val storageFile = File(context.dataDir, "shared_prefs/$prefsFile.xml")
        return openEncryptedPrefsPreservingExisting(
            storageFile = storageFile,
            create = ::createEncryptedPrefs,
            resetEmptyStore = {
                Log.w(TAG, "No encrypted credential data exists; creating a fresh store")
                context.deleteSharedPreferences(prefsFile)
            },
        )
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        // Deprecated wrapper retained strictly for the established on-disk
        // format. Keep Tink patched independently and migrate read-old/write-new.
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            prefsFile,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun storeTokens(
        accessToken: String,
        refreshToken: String,
        expiresInSeconds: Long,
        userUuid: String,
        username: String
    ) {
        prefs.edit()
            .putString(keyAccessToken, accessToken)
            .putString(keyRefreshToken, refreshToken)
            .putLong(keyAccessTokenExpiry, System.currentTimeMillis() + expiresInSeconds * 1000)
            .putString(keyUserUuid, userUuid)
            .putString(keyUsername, username)
            .apply()
    }

    fun updateAccessToken(accessToken: String, expiresInSeconds: Long) {
        prefs.edit()
            .putString(keyAccessToken, accessToken)
            .putLong(keyAccessTokenExpiry, System.currentTimeMillis() + expiresInSeconds * 1000)
            .apply()
    }

    fun updateRefreshToken(refreshToken: String) {
        prefs.edit().putString(keyRefreshToken, refreshToken).apply()
    }

    /** Replace the cached display username — used by the app-start
     *  username-backfill path when the previously-cached value was
     *  pre-username-fix email-shaped. */
    fun updateUsername(username: String) {
        prefs.edit().putString(keyUsername, username).apply()
    }

    fun getAccessToken(): String? = prefs.getString(keyAccessToken, null)
    fun getRefreshToken(): String? = prefs.getString(keyRefreshToken, null)
    fun getUserUuid(): String? {
        prefs.getString(keyUserUuid, null)?.takeIf { it.isNotBlank() }?.let { return it }
        // Self-heal: a session connected on an older build — or kept alive
        // purely by refresh-token renewal (updateAccessToken never writes the
        // uuid) — has valid tokens but no stored userUuid. That permanently
        // breaks the own-climb authorship gate (Meine Climbs + publish/claim)
        // with no user-facing recovery. Derive the uuid from the Keycloak
        // JWT `sub` of the access token, falling back to the refresh token
        // (guaranteed present whenever hasCredentials() is true), and persist
        // it so subsequent reads are cheap.
        val sub = (getAccessToken()?.let { extractJwtSub(it) }
            ?: getRefreshToken()?.let { extractJwtSub(it) })
            ?.takeIf { it.isNotBlank() } ?: return null
        runCatching { prefs.edit().putString(keyUserUuid, sub).apply() }
        Log.i(TAG, "userUuid self-healed from JWT sub")
        return sub
    }

    /** Best-effort JWT `sub` extraction (base64url payload → JSON). No
     *  signature check — we only read an immutable identity claim from our
     *  OWN token to recover a value the login path failed to persist. */
    private fun extractJwtSub(jwt: String): String? = try {
        val parts = jwt.split(".")
        if (parts.size < 2) {
            null
        } else {
            val payload = android.util.Base64.decode(
                parts[1],
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
            )
            org.json.JSONObject(String(payload, Charsets.UTF_8))
                .optString("sub")
                .takeIf { it.isNotBlank() }
        }
    } catch (e: Exception) {
        Log.w(TAG, "JWT sub extraction failed", e)
        null
    }
    fun getUsername(): String? = prefs.getString(keyUsername, null)

    fun isAccessTokenExpired(): Boolean {
        val expiry = prefs.getLong(keyAccessTokenExpiry, 0)
        return System.currentTimeMillis() >= expiry - 60_000 // 1 min buffer
    }

    fun hasCredentials(): Boolean = getRefreshToken() != null

    /** Store the Kilter wall context (gym/wall/layout) for log uploads. */
    fun setWallContext(gymUuid: String, wallUuid: String, productLayoutUuid: String) {
        prefs.edit()
            .putString(keyGymUuid, gymUuid)
            .putString(keyWallUuid, wallUuid)
            .putString(keyProductLayoutUuid, productLayoutUuid)
            .apply()
    }

    fun getGymUuid(): String? = prefs.getString(keyGymUuid, null)
    fun getWallUuid(): String? = prefs.getString(keyWallUuid, null)
    fun getProductLayoutUuid(): String? = prefs.getString(keyProductLayoutUuid, null)
    fun hasWallContext(): Boolean = getGymUuid() != null && getWallUuid() != null

    /** Clear only this identity's entries, leaving other identities' keys intact. */
    fun clear() {
        prefs.edit()
            .remove(keyAccessToken)
            .remove(keyRefreshToken)
            .remove(keyAccessTokenExpiry)
            .remove(keyUserUuid)
            .remove(keyUsername)
            .remove(keyGymUuid)
            .remove(keyWallUuid)
            .remove(keyProductLayoutUuid)
            .apply()
    }

    /**
     * Discard the Kilter SESSION (access + refresh tokens, expiry, wall
     * context) after a one-time import, but KEEP the account identity
     * (userUuid + username). The userUuid is NOT a credential — it is the
     * only value the own-climb authorship gate (Meine Climbs + publish/claim)
     * can match the backfilled `kilter_author_uuid` against. [clear] wipes it
     * too, which makes the authored-climb import pointless: the climbs are
     * imported but can never be recognized as the user's own, and there is no
     * token left for the [getUserUuid] self-heal to recover from. Claiming
     * republishes via Nostr and never needs a Kilter token (the Kilter mirror
     * leg is gated on `kilterSyncEnabled` + a live access token, both absent
     * here), so keeping just the identity is sufficient AND minimal — the
     * connection still reads as "not connected" since [hasCredentials] checks
     * the (now-removed) refresh token.
     */
    fun clearTokensKeepIdentity() {
        prefs.edit()
            .remove(keyAccessToken)
            .remove(keyRefreshToken)
            .remove(keyAccessTokenExpiry)
            .remove(keyGymUuid)
            .remove(keyWallUuid)
            .remove(keyProductLayoutUuid)
            .apply()
    }
}

/**
 * A crypto/provider failure is not evidence that ciphertext is corrupt. Never
 * turn a transient or upgrade-related open failure into credential loss.
 */
internal fun openEncryptedPrefsPreservingExisting(
    storageFile: File,
    create: () -> SharedPreferences,
    resetEmptyStore: () -> Unit,
): SharedPreferences = try {
    create()
} catch (error: Exception) {
    if (storageFile.exists() && storageFile.length() > 0L) {
        throw IllegalStateException(
            "Encrypted credentials are unreadable; refusing to delete existing ciphertext",
            error,
        )
    }
    resetEmptyStore()
    create()
}
