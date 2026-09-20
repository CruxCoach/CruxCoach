package com.cruxcoach.app.kilter

import com.cruxcoach.app.platform.HttpFailure
import com.cruxcoach.app.platform.HttpResult
import com.cruxcoach.app.platform.HttpTransport
import com.cruxcoach.app.platform.WallClock
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One ascent as the Kilter portal reports it.
 *
 * The portal speaks **camelCase** — that is what Android's `KilterApiClient`
 * decodes into and posts, and it is the shape the live logbook returns. The
 * snake_case alternatives are accepted as well so a portal that ever switches
 * does not silently produce a logbook of blank rows: every field has a
 * default, so a name mismatch parses as empty rather than failing loudly.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class KilterLog(
    @SerialName("logUuid") @JsonNames("log_uuid") val logUuid: String = "",
    @SerialName("userUuid") @JsonNames("user_uuid") val userUuid: String = "",
    @SerialName("climbUuid") @JsonNames("climb_uuid") val climbUuid: String = "",
    /** Wall context the portal requires when a log is uploaded. */
    @SerialName("gymUuid") @JsonNames("gym_uuid") val gymUuid: String = "",
    @SerialName("wallUuid") @JsonNames("wall_uuid") val wallUuid: String = "",
    @SerialName("productLayoutUuid") @JsonNames("product_layout_uuid")
    val productLayoutUuid: String = "",
    val angle: Int = 0,
    val flashed: Boolean = false,
    val topped: Boolean = false,
    val attempts: Int = 1,
    @SerialName("createdAt") @JsonNames("created_at") val createdAt: String = "",
    val comment: String? = null,
)

/** Gym, wall and layout a log has to name for the portal to accept it. */
class KilterWallContext(
    val gymUuid: String,
    val wallUuid: String,
    val productLayoutUuid: String,
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

    /** The portal already has a log with this id but different content. */
    CONFLICT,
}

/** How many logs the portal took, and why it stopped if it did. */
class KilterUploadOutcome(val uploaded: Int, val failure: KilterFailure)

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
    // encodeDefaults matches Android's client: the portal is sent every field
    // explicitly, so an attempt really says `topped: false` rather than
    // leaving the server to assume what a missing key meant.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

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

    /**
     * Posts logs the portal does not have yet.
     *
     * Kilter's bulk insert is not an upsert — a log uuid it already knows
     * returns 500 — so the remote logbook is read first and anything already
     * there is dropped. A row that exists remotely with *different* content is
     * a conflict rather than a duplicate: it is left alone and reported, since
     * overwriting someone's portal data from here is not this app's call.
     */
    suspend fun uploadLogs(
        logs: List<KilterLog>,
        /** Remote logs the caller has already fetched; null fetches them. */
        remote: List<KilterLog>? = null,
    ): KilterUploadOutcome {
        if (logs.isEmpty()) return KilterUploadOutcome(0, KilterFailure.NONE)
        val token = validAccessToken() ?: return KilterUploadOutcome(0, KilterFailure.NOT_SIGNED_IN)
        // Legacy catalogue ids are case-sensitive upstream: compact ids go up
        // uppercase. Hyphenated native ids and local identities stay as they are.
        val wire = logs.map { log ->
            if (COMPACT_CLIMB_UUID.matches(log.climbUuid)) {
                log.copy(climbUuid = log.climbUuid.uppercase())
            } else log
        }
        val known = if (remote != null) {
            remote.associateBy { it.logUuid }
        } else {
            val existing = fetchLogs()
            if (existing.failure != KilterFailure.NONE) return KilterUploadOutcome(0, existing.failure)
            existing.logs.associateBy { it.logUuid }
        }
        var conflicts = 0
        val missing = wire.filter { log ->
            val remote = known[log.logUuid]
            if (remote != null && !sameUploadedLog(log, remote)) conflicts++
            remote == null
        }
        if (conflicts > 0) return KilterUploadOutcome(0, KilterFailure.CONFLICT)
        if (missing.isEmpty()) return KilterUploadOutcome(0, KilterFailure.NONE)

        val payload = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(KilterLog.serializer()), missing,
        )
        val result = http.request(
            method = "POST",
            url = "$apiBase/logs/bulk",
            headers = mapOf(
                "Authorization" to "Bearer $token",
                "Content-Type" to "application/json",
                "Accept" to "application/json",
            ),
            body = payload.encodeToByteArray(),
            maxResponseBytes = MAX_TOKEN_BYTES,
            timeoutSeconds = TIMEOUT_SECONDS,
        )
        val response = when (result) {
            is HttpResult.Failed -> return KilterUploadOutcome(0, result.reason.toFailure())
            is HttpResult.Ok -> result.response
        }
        // The body is never kept: an error response can echo private log data.
        if (response.status == 401) return KilterUploadOutcome(0, KilterFailure.NOT_SIGNED_IN)
        if (response.status !in 200..299) return KilterUploadOutcome(0, KilterFailure.SERVER_ERROR)
        return KilterUploadOutcome(missing.size, KilterFailure.NONE)
    }

    /**
     * Gym, wall and layout taken from a log the user already has upstream.
     *
     * Null means "no log carries one yet", which is a different answer from a
     * failed fetch: a transient error must never be allowed to replace a real
     * wall context with a guess.
     */
    fun wallContextFromLogs(logs: List<KilterLog>): KilterWallContext? = logs
        .firstOrNull {
            it.gymUuid.isNotEmpty() && it.wallUuid.isNotEmpty() && it.productLayoutUuid.isNotEmpty()
        }
        ?.let { KilterWallContext(it.gymUuid, it.wallUuid, it.productLayoutUuid) }

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

        /** A catalogue id without hyphens; the portal matches those uppercase. */
        val COMPACT_CLIMB_UUID = Regex("[0-9a-fA-F]{32}")

        /** Two logs are the same upload when every field the portal stores matches. */
        internal fun sameUploadedLog(local: KilterLog, remote: KilterLog): Boolean =
            local.climbUuid.equals(remote.climbUuid, ignoreCase = true) &&
                local.angle == remote.angle &&
                local.topped == remote.topped &&
                local.attempts == remote.attempts

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
