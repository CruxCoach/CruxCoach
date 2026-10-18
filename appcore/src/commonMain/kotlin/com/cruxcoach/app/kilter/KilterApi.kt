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
) {
    companion object {
        /**
         * Gym, wall and layout taken from a log the user already has upstream.
         * Null means "no log carries one yet", which is a different answer from
         * a failed fetch: a transient error must never replace a real wall
         * context with a guess.
         */
        fun fromLogs(logs: List<KilterLog>): KilterWallContext? = logs
            .firstOrNull {
                it.gymUuid.isNotEmpty() && it.wallUuid.isNotEmpty() && it.productLayoutUuid.isNotEmpty()
            }
            ?.let { KilterWallContext(it.gymUuid, it.wallUuid, it.productLayoutUuid) }
    }
}

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

class KilterAuthOutcome(val userUuid: String, val failure: KilterFailure, val retryAfterSeconds: Long = 0)

/** [httpStatus] is the portal's status when it answered with an error, 0 otherwise. */
class KilterLogsOutcome(val logs: List<KilterLog>, val failure: KilterFailure, val httpStatus: Int = 0)

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
) : KilterUploadApi {
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
    override suspend fun fetchLogs(): KilterLogsOutcome {
        val access = accessToken()
        if (access.offline) return KilterLogsOutcome(emptyList(), KilterFailure.OFFLINE)
        val token = access.token ?: return KilterLogsOutcome(emptyList(), KilterFailure.NOT_SIGNED_IN)
        val result = http.request(
            method = "GET",
            url = "$apiBase/logs",
            headers = mapOf("Authorization" to "Bearer $token", "Accept" to "application/json"),
            body = null,
            maxResponseBytes = MAX_LOGS_BYTES,
            // The upload reads the whole logbook first; under load Kilter answers slowly.
            timeoutSeconds = UPLOAD_TIMEOUT_SECONDS,
        )
        val response = when (result) {
            is HttpResult.Failed -> return KilterLogsOutcome(emptyList(), result.reason.toFailure())
            is HttpResult.Ok -> result.response
        }
        if (response.status == 401) return KilterLogsOutcome(emptyList(), KilterFailure.NOT_SIGNED_IN, 401)
        if (response.status !in 200..299) {
            return KilterLogsOutcome(emptyList(), KilterFailure.SERVER_ERROR, response.status)
        }

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

    /** A confirmed deletion (including an already absent log) clears the retry queue. */
    override suspend fun deleteLog(logUuid: String): KilterFailure {
        // IDs are one path segment; never let stored/imported text alter the endpoint.
        if (!SAFE_PATH_SEGMENT.matches(logUuid)) return KilterFailure.MALFORMED_RESPONSE
        val access = accessToken()
        if (access.offline) return KilterFailure.OFFLINE
        val token = access.token ?: return KilterFailure.NOT_SIGNED_IN
        val result = http.request(
            method = "DELETE", url = "$apiBase/logs/$logUuid",
            headers = mapOf("Authorization" to "Bearer $token"), body = null,
            maxResponseBytes = MAX_TOKEN_BYTES, timeoutSeconds = TIMEOUT_SECONDS,
        )
        val response = when (result) {
            is HttpResult.Failed -> return result.reason.toFailure()
            is HttpResult.Ok -> result.response
        }
        return when (response.status) {
            in 200..299, 404 -> KilterFailure.NONE
            401, 403 -> KilterFailure.NOT_SIGNED_IN
            429 -> KilterFailure.THROTTLED
            else -> KilterFailure.SERVER_ERROR
        }
    }

    /**
     * `DELETE /api/logs/{uuid}` answered as whether Kilter held the log: 200
     * when it did (it is gone now), 204 (or 404) when it did not (verified
     * live 2026-10-07, attempts and ascents alike). Kilter takes the same uuid
     * again afterwards. The upload uses it to find out whether a log it cannot
     * see in the logbook is there, and to replace a copy Kilter keeps.
     */
    override suspend fun deleteLogIfHeld(logUuid: String): KilterDeleteOutcome {
        if (!SAFE_PATH_SEGMENT.matches(logUuid)) return KilterDeleteOutcome.FAILED
        val token = accessToken().token ?: return KilterDeleteOutcome.FAILED
        val result = http.request(
            method = "DELETE", url = "$apiBase/logs/$logUuid",
            headers = mapOf("Authorization" to "Bearer $token"), body = null,
            maxResponseBytes = MAX_TOKEN_BYTES, timeoutSeconds = TIMEOUT_SECONDS,
        )
        val response = (result as? HttpResult.Ok)?.response ?: return KilterDeleteOutcome.FAILED
        return when (response.status) {
            200 -> KilterDeleteOutcome.HELD
            204, 404 -> KilterDeleteOutcome.NOT_HELD
            else -> KilterDeleteOutcome.FAILED
        }
    }

    /**
     * Posts [logs] to Kilter's bulk endpoint exactly as given.
     *
     * The caller ([KilterUploadEngine]) owns everything around it: climb ids
     * already in the spelling Kilter stores ([KilterClimbWireIds]), and no log
     * uuid Kilter already holds — bulk is an insert, not an upsert, and a
     * known uuid fails the request. Kilter treats the request as a unit: one
     * row it refuses fails all of them and writes nothing, so a failed
     * request can be split and retried without duplicating anything.
     *
     * The response body is never kept: an error response can echo private log
     * data. NSURLSession does not resend a POST on its own, so a request that
     * broke off was sent at most once.
     */
    override suspend fun uploadLogs(logs: List<KilterLog>): KilterBulkOutcome {
        if (logs.isEmpty()) return KilterBulkOutcome(200, KilterFailure.NONE, mayHaveReachedKilter = false)
        val access = accessToken()
        // The token endpoint was unreachable: the upload itself never went out.
        if (access.offline) return KilterBulkOutcome(0, KilterFailure.OFFLINE, mayHaveReachedKilter = false)
        val token = access.token ?: return KilterBulkOutcome(0, KilterFailure.NOT_SIGNED_IN, mayHaveReachedKilter = false)
        val payload = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(KilterLog.serializer()), logs)
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
            timeoutSeconds = UPLOAD_TIMEOUT_SECONDS,
        )
        return when (result) {
            is HttpResult.Failed -> KilterBulkOutcome(
                0, KilterUploadFailure.reasonFor(result.reason), KilterUploadFailure.mayHaveReachedKilter(result.detail),
            )
            is HttpResult.Ok -> KilterBulkOutcome(result.response.status, KilterFailure.NONE, mayHaveReachedKilter = true)
        }
    }

    /** See [KilterWallContext.fromLogs]. */
    fun wallContextFromLogs(logs: List<KilterLog>): KilterWallContext? = KilterWallContext.fromLogs(logs)

    /**
     * One authorized portal call for the own-climb paths ([KilterClimbPublisher],
     * [KilterClimbBackfiller]): `$apiBase/[path]` with the account's Bearer token.
     * A 401 gets exactly one forced token refresh and one resend, as Android's
     * `submitClimb` does. [path] is built by the caller from validated segments.
     */
    internal suspend fun climbCall(
        method: String,
        path: String,
        jsonBody: String?,
        maxResponseBytes: Long,
        timeoutSeconds: Int,
    ): KilterClimbCall {
        val access = accessToken()
        if (access.offline) return KilterClimbCall(0, "", KilterFailure.OFFLINE, null)
        var token = access.token ?: return KilterClimbCall(0, "", KilterFailure.NOT_SIGNED_IN, null)
        suspend fun send(bearer: String): HttpResult = http.request(
            method = method,
            url = "$apiBase/$path",
            headers = buildMap {
                put("Authorization", "Bearer $bearer")
                put("Accept", "application/json")
                if (jsonBody != null) put("Content-Type", "application/json")
            },
            body = jsonBody?.encodeToByteArray(),
            maxResponseBytes = maxResponseBytes,
            timeoutSeconds = timeoutSeconds,
        )
        var result = send(token)
        if (result is HttpResult.Ok && result.response.status == 401) {
            val refresh = tokens.refreshToken()
            val refreshed = refresh?.let { r ->
                (refreshAccessToken(r) as? HttpResult.Ok)?.let { storeRefreshed(it.response, r) }
            }
            if (refreshed != null) {
                token = refreshed
                result = send(token)
            }
        }
        return when (result) {
            is HttpResult.Failed -> KilterClimbCall(0, result.detail, result.reason.toFailure(), null)
            is HttpResult.Ok -> KilterClimbCall(
                result.response.status,
                result.response.body.decodeToString(),
                KilterFailure.NONE,
                result.response.headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }?.value,
            )
        }
    }

    /**
     * Android's app-start sync refreshes an expiring access token before it downloads anything.
     * True when a usable access token is stored afterwards; false offline, without a session,
     * or when the portal refused the refresh.
     */
    suspend fun ensureAccessToken(): Boolean = accessToken().token != null

    /** An access token, or why there is none: [offline] when the token endpoint could not be reached. */
    private class Access(val token: String?, val offline: Boolean)

    /**
     * The access token, refreshed once if it expires within a minute. Offline
     * (the token endpoint unreachable) is no expired session: the upload must
     * not ask for a new login then, so it is reported apart.
     */
    private suspend fun accessToken(): Access {
        val current = tokens.accessToken()
        if (current != null && clock.epochSeconds() < tokens.expiresAtEpochSeconds() - EXPIRY_MARGIN_SECONDS) {
            return Access(current, offline = false)
        }
        val refresh = tokens.refreshToken() ?: return Access(current, offline = false)
        return when (val refreshed = refreshAccessToken(refresh)) {
            is HttpResult.Failed -> if (refreshed.reason == HttpFailure.OFFLINE || refreshed.reason == HttpFailure.TIMEOUT) {
                Access(null, offline = true)
            } else {
                Access(null, offline = false)
            }
            is HttpResult.Ok -> Access(storeRefreshed(refreshed.response, refresh), offline = false)
        }
    }

    private suspend fun refreshAccessToken(refresh: String): HttpResult = http.request(
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

    /** Stores a refreshed token; null when the portal refused the refresh. */
    private fun storeRefreshed(response: com.cruxcoach.app.platform.HttpResponse, refresh: String): String? {
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

        /**
         * Idle timeout of the upload's calls (bulk upload, logbook read): under
         * load Kilter took longer than 30 s for a 200-row request (field report,
         * 2026-10-05), and a request it answers too late leaves its rows in doubt.
         */
        const val UPLOAD_TIMEOUT_SECONDS = 90

        /** IDs are one path segment; never let stored/imported text alter the endpoint. */
        private val SAFE_PATH_SEGMENT = Regex("[A-Za-z0-9_-]+")

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

/**
 * Raw answer of [KilterApi.climbCall]: [status] 0 when no response arrived
 * ([failure] then says why; [body] is the transport's detail). The caller bounds
 * what it keeps of an error body.
 */
internal class KilterClimbCall(
    val status: Int,
    val body: String,
    val failure: KilterFailure,
    val retryAfter: String?,
)
