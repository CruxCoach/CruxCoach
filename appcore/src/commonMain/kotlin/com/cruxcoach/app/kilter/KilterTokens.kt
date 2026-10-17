package com.cruxcoach.app.kilter

import com.cruxcoach.app.platform.KeyValueStore
import com.cruxcoach.app.platform.SecretStore

/**
 * Kilter session material for one CruxCoach identity.
 *
 * Tokens go in the [SecretStore] (Keychain), never in preferences: they grant
 * access to the user's Kilter account. Only the non-secret expiry, the user
 * uuid and the sign-in throttle live in [KeyValueStore], mirroring Android's
 * split between `kilter_secure_prefs` and ordinary preferences.
 *
 * Every key is prefixed with the identity, so switching accounts cannot leak a
 * session across identities.
 */
class KilterTokens(
    private val secrets: SecretStore,
    private val keyValues: KeyValueStore,
    private val identityPrefix: String,
) : KilterUploadSession {
    private fun secretKey(name: String) = "kilter_${identityPrefix}_$name"
    private fun prefKey(name: String) = "kilter_${identityPrefix}_$name"

    fun accessToken(): String? = secrets.read(secretKey(ACCESS))?.decodeToString()?.ifBlank { null }

    fun refreshToken(): String? = secrets.read(secretKey(REFRESH))?.decodeToString()?.ifBlank { null }

    fun expiresAtEpochSeconds(): Long = keyValues.getString(prefKey(EXPIRES_AT))?.toLongOrNull() ?: 0L

    override fun userUuid(): String = keyValues.getString(prefKey(USER_UUID)) ?: ""

    val isSignedIn: Boolean get() = refreshToken() != null || accessToken() != null

    override fun hasCredentials(): Boolean = isSignedIn

    /** The gym, wall and layout the uploads name, resolved once from the account's own logs. */
    override fun wallContext(): KilterWallContext? {
        val gym = keyValues.getString(prefKey(WALL_GYM))?.takeIf { it.isNotBlank() } ?: return null
        val wall = keyValues.getString(prefKey(WALL_WALL))?.takeIf { it.isNotBlank() } ?: return null
        val layout = keyValues.getString(prefKey(WALL_LAYOUT))?.takeIf { it.isNotBlank() } ?: return null
        return KilterWallContext(gym, wall, layout)
    }

    override fun storeWallContext(context: KilterWallContext) {
        keyValues.putString(prefKey(WALL_GYM), context.gymUuid)
        keyValues.putString(prefKey(WALL_WALL), context.wallUuid)
        keyValues.putString(prefKey(WALL_LAYOUT), context.productLayoutUuid)
    }

    fun store(access: String, refresh: String, expiresAtEpochSeconds: Long) {
        secrets.write(secretKey(ACCESS), access.encodeToByteArray())
        if (refresh.isNotBlank()) secrets.write(secretKey(REFRESH), refresh.encodeToByteArray())
        keyValues.putString(prefKey(EXPIRES_AT), expiresAtEpochSeconds.toString())
    }

    fun storeUserUuid(uuid: String) = keyValues.putString(prefKey(USER_UUID), uuid)

    /**
     * Forgets the session but keeps the user uuid, as Android's
     * `clearTokensKeepIdentity` does. The wall context goes: another account
     * signed in next must not file its logs against this one's board.
     */
    fun clear() {
        secrets.delete(secretKey(ACCESS))
        secrets.delete(secretKey(REFRESH))
        keyValues.putString(prefKey(EXPIRES_AT), null)
        for (key in listOf(WALL_GYM, WALL_WALL, WALL_LAYOUT)) keyValues.putString(prefKey(key), null)
    }

    /** Seconds the caller must wait before another sign-in attempt, or 0. */
    fun throttleRemainingSeconds(email: String, nowEpochSeconds: Long): Long {
        val until = keyValues.getString(throttleKey(email))?.toLongOrNull() ?: return 0
        return (until - nowEpochSeconds).coerceAtLeast(0)
    }

    /** Doubles the back-off after a rejected sign-in, so a wrong password cannot be hammered. */
    fun recordFailedSignIn(email: String, nowEpochSeconds: Long): Long {
        val key = throttleKey(email)
        val previous = keyValues.getString(backoffKey(email))?.toLongOrNull() ?: 0L
        val next = if (previous <= 0) FIRST_BACKOFF_SECONDS else (previous * 2).coerceAtMost(MAX_BACKOFF_SECONDS)
        keyValues.putString(backoffKey(email), next.toString())
        keyValues.putString(key, (nowEpochSeconds + next).toString())
        return next
    }

    fun clearThrottle(email: String) {
        keyValues.putString(throttleKey(email), null)
        keyValues.putString(backoffKey(email), null)
    }

    /** The address is not stored: only a stable, non-reversible label for the throttle. */
    private fun throttleKey(email: String) = prefKey("throttle_${emailLabel(email)}")

    private fun backoffKey(email: String) = prefKey("backoff_${emailLabel(email)}")

    private fun emailLabel(email: String): String {
        var hash = 0L
        for (c in email.lowercase().trim()) hash = (hash * 31 + c.code) and 0xffffffffL
        return hash.toString(16)
    }

    companion object {
        const val ACCESS = "access_token"
        const val REFRESH = "refresh_token"
        const val EXPIRES_AT = "access_token_expiry"
        const val USER_UUID = "user_uuid"
        const val WALL_GYM = "gym_uuid"
        const val WALL_WALL = "wall_uuid"
        const val WALL_LAYOUT = "product_layout_uuid"
        const val FIRST_BACKOFF_SECONDS = 5L
        const val MAX_BACKOFF_SECONDS = 300L
    }
}
