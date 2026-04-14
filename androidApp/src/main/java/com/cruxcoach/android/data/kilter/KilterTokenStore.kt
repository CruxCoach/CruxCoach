package com.cruxcoach.android.data.kilter

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure storage for Kilter account credentials (refresh token, access token).
 * Uses EncryptedSharedPreferences backed by Android Keystore (AES-256-GCM).
 * Per-key: each Nostr identity gets its own file (kilter_secure_prefs_{prefix}).
 */
class KilterTokenStore(
    private val context: Context,
    pubkeyPrefix: String
) {
    private companion object {
        const val TAG = "KilterTokenStore"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_ACCESS_TOKEN_EXPIRY = "access_token_expiry"
        const val KEY_USER_UUID = "user_uuid"
        const val KEY_USERNAME = "username"
        const val KEY_GYM_UUID = "gym_uuid"
        const val KEY_WALL_UUID = "wall_uuid"
        const val KEY_PRODUCT_LAYOUT_UUID = "product_layout_uuid"
    }

    private val prefsFile = "kilter_secure_prefs_$pubkeyPrefix"

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
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putLong(KEY_ACCESS_TOKEN_EXPIRY, System.currentTimeMillis() + expiresInSeconds * 1000)
            .putString(KEY_USER_UUID, userUuid)
            .putString(KEY_USERNAME, username)
            .apply()
    }

    fun updateAccessToken(accessToken: String, expiresInSeconds: Long) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putLong(KEY_ACCESS_TOKEN_EXPIRY, System.currentTimeMillis() + expiresInSeconds * 1000)
            .apply()
    }

    fun updateRefreshToken(refreshToken: String) {
        prefs.edit().putString(KEY_REFRESH_TOKEN, refreshToken).apply()
    }

    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)
    fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)
    fun getUserUuid(): String? = prefs.getString(KEY_USER_UUID, null)
    fun getUsername(): String? = prefs.getString(KEY_USERNAME, null)

    fun isAccessTokenExpired(): Boolean {
        val expiry = prefs.getLong(KEY_ACCESS_TOKEN_EXPIRY, 0)
        return System.currentTimeMillis() >= expiry - 60_000 // 1 min buffer
    }

    fun hasCredentials(): Boolean = getRefreshToken() != null

    /** Store the Kilter wall context (gym/wall/layout) for log uploads. */
    fun setWallContext(gymUuid: String, wallUuid: String, productLayoutUuid: String) {
        prefs.edit()
            .putString(KEY_GYM_UUID, gymUuid)
            .putString(KEY_WALL_UUID, wallUuid)
            .putString(KEY_PRODUCT_LAYOUT_UUID, productLayoutUuid)
            .apply()
    }

    fun getGymUuid(): String? = prefs.getString(KEY_GYM_UUID, null)
    fun getWallUuid(): String? = prefs.getString(KEY_WALL_UUID, null)
    fun getProductLayoutUuid(): String? = prefs.getString(KEY_PRODUCT_LAYOUT_UUID, null)
    fun hasWallContext(): Boolean = getGymUuid() != null && getWallUuid() != null

    fun clear() {
        prefs.edit().clear().apply()
    }
}
