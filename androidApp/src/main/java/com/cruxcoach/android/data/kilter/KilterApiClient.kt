package com.cruxcoach.android.data.kilter

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

// --- Data classes ---

@Serializable
data class KilterLog(
    val logUuid: String,
    val userUuid: String = "",
    val climbUuid: String,
    val gymUuid: String = "",
    val wallUuid: String = "",
    val productLayoutUuid: String = "",
    val angle: Int = 0,
    val flashed: Boolean = false,
    val topped: Boolean = false,
    val attempts: Int = 1,
    val createdAt: String = "",
    val comment: String? = null
)

@Serializable
data class WallContext(
    val gymUuid: String,
    val wallUuid: String,
    val productLayoutUuid: String
)

@Serializable
private data class CustomWallRequest(
    val wallUuid: String,
    val gymUuid: String,
    val name: String,
    val productName: String,
    val productLayoutUuid: String,
    val isAdjustable: Boolean = true,
    val minAngle: Int = 0,
    val maxAngle: Int = 70,
    val angleIncrements: Int = 5,
    val angle: Int = 40,
    val serialNumber: String? = null
)

/**
 * Wire shape for `POST /api/climbs/create-climb/transaction`.
 *
 * Snake-cased fields match the column names from the FINDINGS.md schema
 * dump. The endpoint name suggests Kilter wraps the climb + climb-stats
 * inserts in a single PowerSync-style transaction; for v1 we ship just
 * the climb half — stats get aggregated server-side from ratings/logs.
 */
@Serializable
private data class CreateClimbTransaction(
    val climb: ClimbCreatePayload,
)

@Serializable
private data class ClimbCreatePayload(
    val climb_uuid: String,
    val setter_uuid: String,
    val name: String,
    val description: String,
    val frames: String,                    // climbConcat-format `h{id}p{ref}…`
    val product_name: String,              // e.g. "Kilter Board Original"
    val is_listed: Boolean = true,
    val is_draft: Boolean = false,
    val frames_count: Int = 1,
    val frames_pace: Int = 0,
    val hsm: Int = 0,
    val edge_left: Int,
    val edge_right: Int,
    val edge_bottom: Int,
    val edge_top: Int,
    val created_at: String,
    val updated_at: String,
)

/** Outcome of a Kilter publish. Distinct from a generic Result so callers
 *  can react to the auth-missing case (offer login UI) vs. transient errors
 *  (queue retry) vs. permanent rejections (e.g. uuid conflict). */
sealed class KilterPublishResult {
    /** Kilter accepted the climb. `climbUuid` echoes what we sent. */
    data class Success(val climbUuid: String) : KilterPublishResult()
    /** No valid token — user needs to log in (or the bundled path applies). */
    object NotAuthenticated : KilterPublishResult()
    /** Network/server error; retry candidate. `message` carries the body. */
    data class TransientError(val message: String) : KilterPublishResult()
    /** Server rejected the payload (4xx); usually a content/validation issue. */
    data class PermanentError(val message: String, val httpCode: Int) : KilterPublishResult()
}

@Serializable
private data class KilterTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("expires_in") val expiresIn: Long = 14400
)

@Serializable
private data class KilterLogsResponse(
    val logs: List<KilterLog> = emptyList()
)

sealed class KilterAuthResult {
    data class Success(
        val userUuid: String,
        val username: String,
        val accessToken: String,
        val refreshToken: String,
        val expiresIn: Long
    ) : KilterAuthResult()

    data class Error(val message: String) : KilterAuthResult()
}

// --- API Client ---

@Singleton
class KilterApiClient @Inject constructor(
    private val tokenStore: KilterTokenStore,
    @Named("kilter") private val httpClient: OkHttpClient
) {
    private companion object {
        const val TAG = "KilterApiClient"
        const val TOKEN_URL = "https://idp.kiltergrips.com/realms/kilter/protocol/openid-connect/token"
        const val LOGOUT_URL = "https://idp.kiltergrips.com/realms/kilter/protocol/openid-connect/logout"
        const val API_BASE = "https://portal.kiltergrips.com/api"
        const val CLIENT_ID = "kilter"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val refreshMutex = Mutex()

    /**
     * Authenticate with Kilter Keycloak using password grant.
     * Extracts user UUID and username from JWT claims.
     */
    suspend fun authenticate(email: String, password: String): KilterAuthResult =
        withContext(Dispatchers.IO) {
            try {
                val body = FormBody.Builder()
                    .add("grant_type", "password")
                    .add("client_id", CLIENT_ID)
                    .add("username", email)
                    .add("password", password)
                    .add("scope", "openid offline_access")
                    .build()

                val request = Request.Builder().url(TOKEN_URL).post(body).build()
                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: ""
                    Log.w(TAG, "Auth failed: HTTP ${response.code}, $errorBody")
                    return@withContext KilterAuthResult.Error(
                        if (response.code == 401) "Ungültige Zugangsdaten"
                        else "Anmeldung fehlgeschlagen (HTTP ${response.code})"
                    )
                }

                val responseBody = response.body?.string()
                    ?: return@withContext KilterAuthResult.Error("Leere Antwort vom Server")
                val tokenResponse = json.decodeFromString<KilterTokenResponse>(responseBody)

                // Extract user UUID and username from JWT access token
                val claims = parseJwtClaims(tokenResponse.accessToken)
                val userUuid = claims["sub"] ?: ""
                val username = claims["preferred_username"] ?: email

                KilterAuthResult.Success(
                    userUuid = userUuid,
                    username = username,
                    accessToken = tokenResponse.accessToken,
                    refreshToken = tokenResponse.refreshToken,
                    expiresIn = tokenResponse.expiresIn
                )
            } catch (e: Exception) {
                Log.e(TAG, "Auth error", e)
                KilterAuthResult.Error("Verbindung fehlgeschlagen: ${e.message}")
            }
        }

    /**
     * Refresh the access token using the offline refresh token.
     * With `offline_access` scope, the refresh token is valid for ~30 days
     * and renews on each use — effectively unlimited as long as the user
     * opens the app within 30 days. Returns false if the token is expired
     * (UI shows session-expired → user re-logs in manually).
     */
    suspend fun refreshAccessToken(): Boolean = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
            val refreshToken = tokenStore.getRefreshToken() ?: return@withContext false
            try {
                val body = FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("client_id", CLIENT_ID)
                    .add("refresh_token", refreshToken)
                    .build()

                val request = Request.Builder().url(TOKEN_URL).post(body).build()
                val response = httpClient.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: return@withContext false
                    val tokenResponse = json.decodeFromString<KilterTokenResponse>(responseBody)
                    tokenStore.updateAccessToken(tokenResponse.accessToken, tokenResponse.expiresIn)
                    if (tokenResponse.refreshToken.isNotEmpty()) {
                        tokenStore.updateRefreshToken(tokenResponse.refreshToken)
                    }
                    return@withContext true
                }
                Log.w(TAG, "Token refresh failed: HTTP ${response.code}")
            } catch (e: Exception) {
                Log.e(TAG, "Token refresh error", e)
            }
            false
        }
    }

    /**
     * Revoke the refresh token on Keycloak. Call this before clearing the
     * local token store on logout — otherwise the refresh token remains
     * valid server-side for its full TTL, and anyone with a stale copy
     * (adb backup on rooted device, filesystem extraction) can keep using
     * the account indefinitely.
     *
     * Best-effort: if the call fails (offline, server down), we still
     * proceed with the local clear. Returns true on 2xx, false otherwise.
     */
    suspend fun revokeRefreshToken(): Boolean = withContext(Dispatchers.IO) {
        val refreshToken = tokenStore.getRefreshToken() ?: return@withContext true
        try {
            val body = FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("refresh_token", refreshToken)
                .build()
            val request = Request.Builder().url(LOGOUT_URL).post(body).build()
            val response = httpClient.newCall(request).execute()
            response.close()
            val ok = response.isSuccessful
            if (!ok) Log.w(TAG, "Logout HTTP ${response.code}")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "Logout call failed — continuing with local clear", e)
            false
        }
    }

    /**
     * Fetch all ascent logs for the authenticated user.
     */
    suspend fun fetchLogs(): Result<List<KilterLog>> = withContext(Dispatchers.IO) {
        val token = ensureValidToken()
            ?: return@withContext Result.failure(Exception("Nicht angemeldet"))

        try {
            val request = Request.Builder()
                .url("$API_BASE/logs")
                .addHeader("Authorization", "Bearer $token")
                .build()
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("HTTP ${response.code}: ${response.body?.string()}")
                )
            }

            val body = response.body?.string()
                ?: return@withContext Result.failure(Exception("Leere Antwort"))
            // API may return {"logs": [...]} or raw [...] — handle both
            val logs = if (body.trimStart().startsWith("[")) {
                json.decodeFromString<List<KilterLog>>(body)
            } else {
                json.decodeFromString<KilterLogsResponse>(body).logs
            }
            Result.success(logs)
        } catch (e: Exception) {
            Log.e(TAG, "fetchLogs failed", e)
            Result.failure(e)
        }
    }

    /**
     * Upload local ascents to Kilter in bulk.
     */
    suspend fun uploadLogs(logs: List<KilterLog>): Result<Unit> = withContext(Dispatchers.IO) {
        if (logs.isEmpty()) return@withContext Result.success(Unit)

        val token = ensureValidToken()
            ?: return@withContext Result.failure(Exception("Nicht angemeldet"))

        try {
            val payload = json.encodeToString(logs)
            val requestBody = payload.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$API_BASE/logs/bulk")
                .addHeader("Authorization", "Bearer $token")
                .post(requestBody)
                .build()
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("HTTP ${response.code}: ${response.body?.string()}")
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "uploadLogs failed", e)
            Result.failure(e)
        }
    }

    /**
     * Possible outcomes when trying to extract wall context from existing logs.
     * Distinguishes "user has no logs" (→ fall back to custom wall) from
     * "network/API error" (→ do NOT fall back, retry later) — otherwise a
     * transient error could wrongly overwrite a real wall context with a fake one.
     */
    sealed class ResolveResult {
        data class Found(val context: WallContext) : ResolveResult()
        object NoLogsYet : ResolveResult()
        data class Error(val message: String) : ResolveResult()
    }

    /**
     * Resolve the wall context (gym/wall/layout UUIDs) required for log uploads.
     * Strategy: extract from the user's existing Kilter logs.
     */
    suspend fun resolveWallContext(): ResolveResult = withContext(Dispatchers.IO) {
        val result = fetchLogs()
        val logs = result.getOrElse {
            return@withContext ResolveResult.Error(it.message ?: "fetch failed")
        }
        val log = logs.firstOrNull {
            it.gymUuid.isNotEmpty() && it.wallUuid.isNotEmpty() && it.productLayoutUuid.isNotEmpty()
        } ?: return@withContext ResolveResult.NoLogsYet
        ResolveResult.Found(WallContext(log.gymUuid, log.wallUuid, log.productLayoutUuid))
    }

    /**
     * Create a custom wall for the user's home board.
     *
     * This tries two strategies:
     *  1. POST to /api/walls/custom-wall with a complete Wall schema (server-side registration)
     *  2. If the server rejects it, fall back to locally-generated UUIDs — Kilter has loose
     *     referential integrity on gym/wall IDs (1031/1143 custom walls in PowerSync reference
     *     gym_uuids that don't exist in the gyms table), so log uploads work regardless.
     *
     * Defaults target "Kilter Board Original with Kickboard" (productLayoutUuid="10"),
     * which is the most common home setup.
     */
    suspend fun createCustomWall(
        name: String,
        productName: String = "Kilter Board Original",
        productLayoutUuid: String = "10"
    ): WallContext = withContext(Dispatchers.IO) {
        // Generate client-side identifiers (mirrors how Kilter's own custom walls look)
        val wallUuid = java.util.UUID.randomUUID().toString()
        val gymUuid = (100_000..999_999).random().toString() // numeric string, like real custom walls
        val localContext = WallContext(gymUuid, wallUuid, productLayoutUuid)

        val token = ensureValidToken() ?: return@withContext localContext
        try {
            val body = json.encodeToString(CustomWallRequest(
                wallUuid = wallUuid,
                gymUuid = gymUuid,
                name = name,
                productName = productName,
                productLayoutUuid = productLayoutUuid
            ))
            val request = Request.Builder()
                .url("$API_BASE/walls/custom-wall")
                .addHeader("Authorization", "Bearer $token")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                Log.i(TAG, "Custom wall registered on Kilter: wall=$wallUuid")
            } else {
                // Kilter rejected it — use the locally-generated context anyway.
                // Uploads still work because the log endpoint doesn't validate wall FKs.
                Log.w(TAG, "Custom wall server registration failed (HTTP ${response.code}) — using local context")
            }
            localContext
        } catch (e: Exception) {
            Log.w(TAG, "Custom wall registration error — using local context", e)
            localContext
        }
    }

    /**
     * Publish a CruxCoach-authored climb to Kilter's official server DB.
     *
     * Endpoint per RE: `POST /api/climbs/create-climb/transaction`. The
     * `setter_uuid` is read from the cached Keycloak token (sub-claim);
     * the `climb_uuid` is the same UUID we already generated in the
     * editor and pinned in the Nostr Kind 30078 event, so the row stays
     * deduplicated when the daily Kilter-API harvester pulls it back.
     *
     * Idempotent: re-calling with the same climb_uuid is a server-side
     * no-op (Kilter treats duplicate UUID as already-exists). The caller
     * doesn't need to track "did this attempt actually create or just
     * idempotency-replay" for status flags — both count as success.
     */
    suspend fun publishClimb(
        climbUuid: String,
        name: String,
        description: String,
        framesClimbConcat: String,
        productName: String,
        edgeLeft: Int,
        edgeRight: Int,
        edgeBottom: Int,
        edgeTop: Int,
    ): KilterPublishResult = withContext(Dispatchers.IO) {
        val token = ensureValidToken()
            ?: return@withContext KilterPublishResult.NotAuthenticated
        val setterUuid = tokenStore.getUserUuid()?.takeIf { it.isNotBlank() }
            ?: return@withContext KilterPublishResult.NotAuthenticated

        val nowIso = java.time.Instant.now().toString()
        val payload = CreateClimbTransaction(
            climb = ClimbCreatePayload(
                climb_uuid = climbUuid,
                setter_uuid = setterUuid,
                name = name,
                description = description,
                frames = framesClimbConcat,
                product_name = productName,
                edge_left = edgeLeft,
                edge_right = edgeRight,
                edge_bottom = edgeBottom,
                edge_top = edgeTop,
                created_at = nowIso,
                updated_at = nowIso,
            )
        )
        val body = json.encodeToString(CreateClimbTransaction.serializer(), payload)
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$API_BASE/climbs/create-climb/transaction")
            .addHeader("Authorization", "Bearer $token")
            .post(body)
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                Log.i(TAG, "publishClimb ok uuid=$climbUuid setter=$setterUuid")
                return@withContext KilterPublishResult.Success(climbUuid)
            }
            val responseBody = response.body?.string().orEmpty()
            Log.w(TAG, "publishClimb HTTP ${response.code}: $responseBody")
            return@withContext when (response.code) {
                401, 403 -> KilterPublishResult.NotAuthenticated
                in 400..499 -> KilterPublishResult.PermanentError(responseBody, response.code)
                else -> KilterPublishResult.TransientError("HTTP ${response.code}: $responseBody")
            }
        } catch (e: Exception) {
            Log.e(TAG, "publishClimb exception uuid=$climbUuid", e)
            return@withContext KilterPublishResult.TransientError(e.message ?: "network error")
        }
    }

    private suspend fun ensureValidToken(): String? {
        val token = tokenStore.getAccessToken() ?: return null
        if (!tokenStore.isAccessTokenExpired()) return token
        return if (refreshAccessToken()) tokenStore.getAccessToken() else null
    }

    /**
     * Extract claims from a JWT by base64-decoding the payload segment.
     * No signature verification needed — we trust the Keycloak server response.
     */
    private fun parseJwtClaims(jwt: String): Map<String, String> {
        return try {
            val parts = jwt.split(".")
            if (parts.size != 3) return emptyMap()
            val payload = String(
                android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING),
                Charsets.UTF_8
            )
            val element = json.parseToJsonElement(payload)
            element.let { el ->
                val obj = el as? kotlinx.serialization.json.JsonObject ?: return emptyMap()
                obj.entries.associate { (k, v) ->
                    k to (v as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "JWT parse failed", e)
            emptyMap()
        }
    }
}
