package com.cruxcoach.android.data.kilter

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

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
        return try {
            createEncryptedPrefs()
        } catch (e: Exception) {
            Log.e(TAG, "EncryptedSharedPreferences corrupted, regenerating", e)
            context.deleteSharedPreferences(prefsFile)
            createEncryptedPrefs()
        }
    }

    private fun createEncryptedPrefs(): SharedPreferences {
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
    fun getUserUuid(): String? = prefs.getString(keyUserUuid, null)
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
}
