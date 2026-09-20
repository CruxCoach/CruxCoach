package com.cruxcoach.app.kilter

import com.cruxcoach.app.platform.HttpFailure
import com.cruxcoach.app.platform.HttpResult
import com.cruxcoach.app.platform.HttpTransport
import com.cruxcoach.app.platform.WallClock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One ascent as the Kilter portal reports it. Field names are the portal's. */
@Serializable
data class KilterLog(
    @SerialName("log_uuid") val logUuid: String = "",
    @SerialName("user_uuid") val userUuid: String = "",
    @SerialName("climb_uuid") val climbUuid: String = "",
    val angle: Int = 0,
    val flashed: Boolean = false,
    val topped: Boolean = false,
    val attempts: Int = 1,
    @SerialName("created_at") val createdAt: String = "",
    val comment: String? = null,
)

@Serializable
private data class KilterLogsResponse(val logs: List<KilterLog> = emptyList())

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("expires_in") val expiresIn: Long = 0,
)

/** Why a Kilter call did not succeed. Never carries a token or a password. */
enum class KilterFailure {
    NONE,
    INVALID_CREDENTIALS,
    THROTTLED,
    OFFLINE,
    TIMEOUT,
    SERVER_ERROR,
    MALFORMED_RESPONSE,
    NOT_SIGNED_IN,
}

class KilterAuthOutcome(val userUuid: String, val failure: KilterFailure, val retryAfterSeconds: Long = 0)

class KilterLogsOutcome(val logs: List<KilterLog>, val failure: KilterFailure)

/**
 * Kilter portal client, ported from Android's `KilterApiClient`.
 *
 * The password is used once for the token request and is never stored. Tokens
 * live in the platform [com.cruxcoach.app.platform.SecretStore], keyed by the
 * active identity, exactly as Android keys them by pubkey prefix.
 */
class KilterApi(
    private val http: HttpTransport,
    private val clock: WallClock,
    private val tokens: KilterTokens,
    private val tokenUrl: String = TOKEN_URL,
    private val logoutUrl: String = LOGOUT_URL,
    private val apiBase: String = API_BASE,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Password grant. On success the tokens are stored and only the user uuid is returned. */
    suspend fun signIn(email: String, password: String): KilterAuthOutcome {
        val throttled = tokens.throttleRemainingSeconds(email, clock.epochSeconds())
        if (throttled > 0) return KilterAuthOutcome("", KilterFailure.THROTTLED, throttled)

        val form = buildForm(
            "grant_type" to "password",
            "client_id" to CLIENT_ID,
            "username" to email,
            "password" to password,
            "scope" to "openid offline_access",
        )
        val result = http.request(
            method = "POST",
            url = tokenUrl,
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            body = form.encodeToByteArray(),
            maxResponseBytes = MAX_TOKEN_BYTES,
            timeoutSeconds = TIMEOUT_SECONDS,
        )
        val response = when (result) {
            is HttpResult.Failed -> return KilterAuthOutcome("", result.reason.toFailure(), 0)
            is HttpResult.Ok -> result.response
        }
        if (response.status == 401) {
            val wait = tokens.recordFailedSignIn(email, clock.epochSeconds())
            return KilterAuthOutcome("", KilterFailure.INVALID_CREDENTIALS, wait)
        }
        if (response.status !in 200..299) {
            tokens.recordFailedSignIn(email, clock.epochSeconds())
            return KilterAuthOutcome("", KilterFailure.SERVER_ERROR, 0)
        }
        val parsed = try {
            json.decodeFromString(TokenResponse.serializer(), response.body.decodeToString())
        } catch (e: Exception) {
            return KilterAuthOutcome("", KilterFailure.MALFORMED_RESPONSE, 0)
        }
        if (parsed.accessToken.isBlank()) return KilterAuthOutcome("", KilterFailure.MALFORMED_RESPONSE, 0)

        tokens.store(
            access = parsed.accessToken,
            refresh = parsed.refreshToken,
            expiresAtEpochSeconds = clock.epochSeconds() + parsed.expiresIn.coerceAtLeast(0),
        )
        tokens.clearThrottle(email)
        val userUuid = subjectOf(parsed.accessToken)
        if (userUuid.isNotEmpty()) tokens.storeUserUuid(userUuid)
        return KilterAuthOutcome(userUuid, KilterFailure.NONE)
    }

    /** Revokes the refresh token and forgets everything about the session. */
    suspend fun signOut() {
        val refresh = tokens.refreshToken()
        if (refresh != null) {
            http.request(
                method = "POST",
                url = logoutUrl,
                headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                body = buildForm("client_id" to CLIENT_ID, "refresh_token" to refresh).encodeToByteArray(),
                maxResponseBytes = MAX_TOKEN_BYTES,
                timeoutSeconds = TIMEOUT_SECONDS,
            )
        }
        tokens.clear()
    }

    /** Ascents for the signed-in account, refreshing the access token once if needed. */
    suspend fun fetchLogs(): KilterLogsOutcome {
        val token = validAccessToken() ?: return KilterLogsOutcome(emptyList(), KilterFailure.NOT_SIGNED_IN)
        val result = http.request(
            method = "GET",
            url = "$apiBase/logs",
            headers = mapOf("Authorization" to "Bearer $token", "Accept" to "application/json"),
            body = null,
            maxResponseBytes = MAX_LOGS_BYTES,
            timeoutSeconds = TIMEOUT_SECONDS,
        )
        val response = when (result) {
            is HttpResult.Failed -> return KilterLogsOutcome(emptyList(), result.reason.toFailure())
            is HttpResult.Ok -> result.response
        }
        if (response.status == 401) return KilterLogsOutcome(emptyList(), KilterFailure.NOT_SIGNED_IN)
        if (response.status !in 200..299) return KilterLogsOutcome(emptyList(), KilterFailure.SERVER_ERROR)

        val text = response.body.decodeToString()
        // The portal has shipped both a bare array and an object with a `logs` key.
        val logs = try {
            json.decodeFromString(KilterLogsResponse.serializer(), text).logs
        } catch (e: Exception) {
            try {
                json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(KilterLog.serializer()), text)
            } catch (e2: Exception) {
                return KilterLogsOutcome(emptyList(), KilterFailure.MALFORMED_RESPONSE)
            }
        }
        return KilterLogsOutcome(logs.filter { it.climbUuid.isNotBlank() }, KilterFailure.NONE)
    }

    private suspend fun validAccessToken(): String? {
        val current = tokens.accessToken()
        if (current != null && clock.epochSeconds() < tokens.expiresAtEpochSeconds() - EXPIRY_MARGIN_SECONDS) {
            return current
        }
        val refresh = tokens.refreshToken() ?: return current
        val result = http.request(
            method = "POST",
            url = tokenUrl,
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            body = buildForm(
                "grant_type" to "refresh_token",
                "client_id" to CLIENT_ID,
                "refresh_token" to refresh,
            ).encodeToByteArray(),
            maxResponseBytes = MAX_TOKEN_BYTES,
            timeoutSeconds = TIMEOUT_SECONDS,
        )
        val response = (result as? HttpResult.Ok)?.response ?: return null
        if (response.status !in 200..299) return null
        val parsed = try {
            json.decodeFromString(TokenResponse.serializer(), response.body.decodeToString())
        } catch (e: Exception) {
            return null
        }
        if (parsed.accessToken.isBlank()) return null
        tokens.store(
            access = parsed.accessToken,
            refresh = parsed.refreshToken.ifBlank { refresh },
            expiresAtEpochSeconds = clock.epochSeconds() + parsed.expiresIn.coerceAtLeast(0),
        )
        return parsed.accessToken
    }

    private fun HttpFailure.toFailure(): KilterFailure = when (this) {
        HttpFailure.OFFLINE -> KilterFailure.OFFLINE
        HttpFailure.TIMEOUT -> KilterFailure.TIMEOUT
        else -> KilterFailure.SERVER_ERROR
    }

    companion object {
        const val TOKEN_URL = "https://idp.kiltergrips.com/realms/kilter/protocol/openid-connect/token"
        const val LOGOUT_URL = "https://idp.kiltergrips.com/realms/kilter/protocol/openid-connect/logout"
        const val API_BASE = "https://portal.kiltergrips.com/api"
        const val CLIENT_ID = "kilter"
        const val TIMEOUT_SECONDS = 30
        const val MAX_TOKEN_BYTES = 64L * 1024
        const val MAX_LOGS_BYTES = 32L * 1024 * 1024
        const val EXPIRY_MARGIN_SECONDS = 60L

        /** `sub` claim of a JWT, without verifying it: the server is the authority, this is only a label. */
        fun subjectOf(accessToken: String): String {
            val parts = accessToken.split('.')
            if (parts.size < 2) return ""
            val payload = base64UrlDecode(parts[1]) ?: return ""
            val text = payload.decodeToString()
            val marker = "\"sub\""
            val at = text.indexOf(marker)
            if (at < 0) return ""
            val colon = text.indexOf(':', at + marker.length)
            if (colon < 0) return ""
            val open = text.indexOf('"', colon)
            if (open < 0) return ""
            val close = text.indexOf('"', open + 1)
            if (close < 0) return ""
            return text.substring(open + 1, close)
        }

        private fun base64UrlDecode(value: String): ByteArray? {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
            var buffer = 0
            var bits = 0
            val out = ArrayList<Byte>(value.length)
            for (c in value) {
                if (c == '=') continue
                val index = alphabet.indexOf(c)
                if (index < 0) return null
                buffer = (buffer shl 6) or index
                bits += 6
                if (bits >= 8) {
                    bits -= 8
                    out.add(((buffer shr bits) and 0xff).toByte())
                }
            }
            return out.toByteArray()
        }
    }
}

private fun buildForm(vararg pairs: Pair<String, String>): String =
    pairs.joinToString("&") { (key, value) -> "${urlEncode(key)}=${urlEncode(value)}" }

private fun urlEncode(value: String): String {
    val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"
    val out = StringBuilder(value.length)
    for (byte in value.encodeToByteArray()) {
        val c = byte.toInt().toChar()
        if (c in unreserved) {
            out.append(c)
        } else {
            out.append('%').append(((byte.toInt() and 0xff) shr 4).toString(16).uppercase())
                .append((byte.toInt() and 0x0f).toString(16).uppercase())
        }
    }
    return out.toString()
}
