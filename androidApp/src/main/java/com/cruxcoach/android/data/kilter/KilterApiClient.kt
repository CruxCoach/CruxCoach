package com.cruxcoach.android.data.kilter

import android.util.Log
import com.cruxcoach.android.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * Snake-cased fields mirror the column names of the `climbs` table in
 * the Aurora board catalog. The endpoint name suggests the upstream
 * server wraps the climb + climb-stats inserts in a single PowerSync-style
 * transaction; for v1 we ship just the climb half — stats get aggregated
 * server-side from ratings/logs.
 */
@Serializable
private data class CreateClimbTransaction(
    val climb: ClimbCreatePayload,
)

/**
 * Kilter create/update-climb payload — empirically reverse-engineered
 * from the Kilter Flutter app's outgoing JSON shape. Every field below
 * has been live-tested against `portal.kiltergrips.com/api/climbs/
 * create-climb/transaction` with a probe account; missing or mis-cased
 * fields cause the server to throw a generic "An error occurred when
 * running the transaction." HTTP 500 with no detail.
 *
 * Naming: camelCase. Earlier iterations sent snake_case (which is what
 * the Kilter app's *local* SQLite uses), but the API itself is camelCase
 * — snake_case fields are silently dropped, so a snake-cased payload
 * leaves NOT-NULL columns NULL and the transaction fails.
 *
 * `origin` is an enum: MIGRATED | IMPORTED | NATIVE — climbs we author
 * via the editor are NATIVE.
 */
@Serializable
private data class ClimbCreatePayload(
    val climbUuid: String,
    val userUuid: String,
    val username: String,
    val name: String,
    val description: String,
    /** Concat of placement+role per hold: `h{placementId}p{roleId}…`. */
    val climbConcat: String,
    val productName: String,
    /** Server-side identifier of the layout (board size + variant).
     *  Stored as a numeric string in Kilter's API ("10", "27", etc.). */
    val productLayoutUuid: String,
    val angle: Int,
    val frameCount: Int = 1,
    val framesPace: Int = 0,
    val edgeLeft: Int,
    val edgeRight: Int,
    val edgeBottom: Int,
    val edgeTop: Int,
    val allowMatch: Boolean = true,
    val isDraft: Boolean = false,
    val isListed: Boolean = true,
    val isDeleted: Boolean = false,
    val accumulatedHoldSetValue: Int = 1,
    /** Enum: MIGRATED | IMPORTED | NATIVE. App-authored = NATIVE. */
    val origin: String = "NATIVE",
    val createdAt: String,
    val updatedAt: String,
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
    /**
     * Server returned 429 Too Many Requests. Distinct from PermanentError
     * because the right action is "back off and retry later", not "stop
     * retrying". `retryAfterSeconds` is the parsed `Retry-After` header
     * value (null if missing/unparseable).
     */
    data class RateLimited(val retryAfterSeconds: Long?, val message: String) : KilterPublishResult()
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

/**
 * Tolerated envelope wrappers for `GET /circuits/{userUuid}` in case the
 * server ever wraps the (currently bare-array) response — either the
 * paginated `{items:[...],total}` shape the `/circuits` collection endpoint
 * uses, or a `{circuits:[...]}` object. [circuitsOrItems] merges both.
 */
@Serializable
private data class KilterCircuitsResponse(
    val circuits: List<KilterCircuit> = emptyList(),
    val items: List<KilterCircuit> = emptyList(),
) {
    fun circuitsOrItems(): List<KilterCircuit> = if (circuits.isNotEmpty()) circuits else items
}

/**
 * One climb row from `GET /api/climbs/logged` — the user's OWN logged
 * climbs, in the SAME envelope shape as `/climbs/curated`. Crucially this
 * INCLUDES new-world (PowerSync-only) climbs that never made it into our
 * curated board-DB mirror, so it's the backfill source for the
 * "Climb nicht gefunden" gap (a logbook ascent whose uuid the board DB
 * lacks). `climbConcat` (the `h<holdId>p<role>` form) is directly storable
 * as the board-DB `frames` value — the app already parses it via
 * [com.cruxcoach.domain.board.BoardClimbParser.isClimbConcat].
 *
 * Only the fields we actually persist (or need to key on) are modeled;
 * `ignoreUnknownKeys` drops the rest. camelCase mirrors the wire shape.
 */
@Serializable
data class KilterLoggedClimb(
    val climbUuid: String,
    val climbConcat: String = "",
    val name: String = "",
    val angle: Int = 0,
    val description: String = "",
    val edgeLeft: Int? = null,
    val edgeRight: Int? = null,
    val edgeBottom: Int? = null,
    val edgeTop: Int? = null,
    val frameCount: Int = 1,
    val framesPace: Int = 0,
    val productLayoutUuid: String = "",
    val productName: String = "",
    val isListed: Boolean = true,
    val isDraft: Boolean = false,
    val origin: String = "",
    val userUuid: String = "",
    val username: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
)

/**
 * One climb row from `GET /api/climbs/climbdetails/user` — the user's OWN
 * AUTHORED climbs. The wire shape differs from `/climbs/logged` only in the
 * envelope (a BARE top-level array, no `climbStats[]`); the per-climb fields
 * are the same camelCase set, so the model is shared with
 * [KilterLoggedClimb]. `userUuid`/`username` here are by definition the
 * AUTHOR'S identity (== the authenticated account), which is what the
 * own-climb publish gate persists as `kilter_author_uuid`.
 */
typealias KilterAuthoredClimb = KilterLoggedClimb

/**
 * One stat row from `GET /api/climbs/logged`. Mirrors the curated
 * envelope's `climbStats[]` entry. Keyed by (climbUuid, angle) — the same
 * key the board DB's `climb_stats` table uses.
 */
@Serializable
data class KilterLoggedClimbStat(
    val climbUuid: String,
    val angle: Int = 0,
    val difficultyAverage: Double? = null,
    val qualityAverage: Double? = null,
    val ascentCount: Int? = null,
    val currentDifficultyId: Int? = null,
    val faUsername: String? = null,
    val faAt: String? = null,
)

/**
 * Wire envelope for `GET /api/climbs/logged` — identical in shape to the
 * curated-climbs response: a top-level object with `climbs[]` and
 * `climbStats[]`.
 */
@Serializable
data class KilterLoggedClimbsResponse(
    val climbs: List<KilterLoggedClimb> = emptyList(),
    val climbStats: List<KilterLoggedClimbStat> = emptyList(),
)

/**
 * One member row inside a circuit. The official app's `circuit_climbs`
 * table is `(circuit_uuid, climb_uuid, sort_order)`; the wire form uses the
 * same camelCase keys its Dart models serialize with. Only `climbUuid` is
 * load-bearing for us; `sortOrder` (when present) preserves the user's
 * intended ordering.
 */
@Serializable
data class KilterCircuitClimb(
    val climbUuid: String = "",
    val sortOrder: Int? = null,
)

/**
 * One circuit ("list") from `GET /api/circuits/{userUuid}` — the
 * authenticated user's own circuits, returned as a bare JSON array.
 *
 * ⚠ The wire shape of a NON-EMPTY circuit is inferred, not yet observed
 * live: the probe account carries zero circuits, so the exact membership
 * embedding could not be captured. The official app has no
 * `/circuits/{uuid}/climbs` sub-route (confirmed 404), so members are
 * assumed embedded in the circuit object. We tolerate every plausible
 * embedding — [circuitClimbs] (objects), [climbUuids] (bare uuids), and
 * [climbs] (bare uuids) — and merge whichever the server actually sends via
 * [memberClimbUuids]. Field names mirror the app binary's Dart model
 * (circuitUuid/isPublic/creatorName/…); `ignoreUnknownKeys` drops the rest.
 *
 * Compliance: a SINGLE GET of the user's own circuits with their own Bearer
 * token; no bulk crawl, no other users' data.
 */
@Serializable
data class KilterCircuit(
    val circuitUuid: String = "",
    val name: String = "",
    val description: String? = null,
    /** Hex color without leading `#` (e.g. `"FF0000"`); stored verbatim. */
    val color: String? = null,
    val isPublic: Boolean = false,
    val userUuid: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
    val circuitClimbs: List<KilterCircuitClimb> = emptyList(),
    val climbUuids: List<String> = emptyList(),
    val climbs: List<String> = emptyList(),
) {
    /**
     * Member climb uuids in intended order, merged across every tolerated
     * embedding and de-duplicated. Objects with a [KilterCircuitClimb.sortOrder]
     * are ordered by it (stable for ties / nulls-last); bare-uuid arrays keep
     * their given order.
     */
    fun memberClimbUuids(): List<String> {
        val fromObjects = circuitClimbs
            .filter { it.climbUuid.isNotBlank() }
            .sortedBy { it.sortOrder ?: Int.MAX_VALUE }
            .map { it.climbUuid }
        return (fromObjects + climbUuids + climbs)
            .filter { it.isNotBlank() }
            .distinct()
    }
}

sealed class KilterAuthResult {
    data class Success(
        val userUuid: String,
        val username: String,
        val accessToken: String,
        val refreshToken: String,
        val expiresIn: Long
    ) : KilterAuthResult()

    /**
     * Typed authentication failure. `reason` is the canonical category
     * (the UI layer maps it to a localized R.string); `httpCode` and
     * `throttleSec` carry context for reasons that need it; `cause` is
     * a free-form diagnostic string for logcat (never user-visible).
     *
     * Pre-fix this carried a single hardcoded German message that the
     * UI surfaced verbatim (English-locale users saw German text), and
     * KilterSyncEngine pattern-matched on the text.
     */
    data class Error(
        val reason: Reason,
        val httpCode: Int? = null,
        val throttleSec: Long? = null,
        val cause: String? = null,
    ) : KilterAuthResult() {
        sealed interface Reason {
            data object InvalidCredentials : Reason
            data object EmptyResponse : Reason
            data object NetworkError : Reason
            data object Throttled : Reason
            data object NotAuthenticated : Reason
            data object InvalidJwt : Reason
            data object HttpFailure : Reason
        }
    }
}

/**
 * Typed exception thrown by [KilterApiClient] non-auth methods that
 * return [Result] (fetchLogs / uploadLogs / submitClimb wrappers).
 * Lets [KilterSyncEngine] dispatch on `reason` instead of pattern-
 * matching on `e.message` — pre-fix the latter was brittle to any
 * i18n change of the embedded German strings.
 */
class KilterApiException(
    val reason: KilterAuthResult.Error.Reason,
    message: String,
) : Exception(message)

// --- API Client ---

@Singleton
class KilterApiClient @Inject constructor(
    private val tokenStore: KilterTokenStore,
    @Named("kilter") private val httpClient: OkHttpClient
) {
    private companion object {
        const val TAG = "KilterApiClient"
        const val PROD_TOKEN_URL = "https://idp.kiltergrips.com/realms/kilter/protocol/openid-connect/token"
        const val PROD_LOGOUT_URL = "https://idp.kiltergrips.com/realms/kilter/protocol/openid-connect/logout"
        const val PROD_API_BASE = "https://portal.kiltergrips.com/api"
        const val CLIENT_ID = "kilter"
        // Cap on Kilter error-response bodies before they enter the
        // KilterPublishResult envelope (and from there logcat / DB
        // `kilter_error` column / Android backup blob). 5xx renders can
        // be 50–500 KB; truncating at the network boundary keeps every
        // downstream consumer bounded without scattered take(200)s.
        const val MAX_ERR_BODY = 200
    }

    // URL endpoints — `var` so unit tests can swap them for a
    // MockWebServer base URL via [setEndpointsForTesting]. Production
    // never mutates these; the @VisibleForTesting accessor below is
    // the only writer.
    private var tokenUrl: String = PROD_TOKEN_URL
    private var logoutUrl: String = PROD_LOGOUT_URL
    private var apiBase: String = PROD_API_BASE

    @androidx.annotation.VisibleForTesting
    internal fun setEndpointsForTesting(base: String) {
        tokenUrl = "$base/realms/kilter/protocol/openid-connect/token"
        logoutUrl = "$base/realms/kilter/protocol/openid-connect/logout"
        apiBase = "$base/api"
    }

    // encodeDefaults = true: the Kilter create-climb payload has many
    // NOT-NULL columns server-side that we model with sensible Kotlin
    // defaults (allowMatch, isDraft, isListed, isDeleted, frameCount,
    // framesPace, accumulatedHoldSetValue, origin). Without this flag
    // kotlinx-serialization silently drops every field whose runtime
    // value equals its declaration default — and the API then NPEs on
    // the resulting NULL columns ("Cannot invoke Boolean.booleanValue()
    // because isDraft is null"), with the error wrapped as a generic
    // HTTP 500 transaction-error so the cause is invisible client-side.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val refreshMutex = Mutex()

    // Client-side login throttle. Per-email exponential backoff in
    // process memory only — survives the in-app retry pattern but not
    // a process-kill (acceptable: an attacker that can restart the
    // process can also wipe other forms of state). Defends against
    // accidental brute-force from a stuck-finger user *and* against
    // Wi-Fi-local helper apps that try to lock the user out of their
    // Keycloak account by triggering N rapid 401s.
    private val authBackoff = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val authFailures = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * Authenticate with Kilter Keycloak using password grant.
     * Extracts user UUID and username from JWT claims.
     */
    suspend fun authenticate(email: String, password: String): KilterAuthResult =
        withContext(Dispatchers.IO) {
            val throttleKey = email.lowercase().trim()
            val nextAllowed = authBackoff[throttleKey] ?: 0L
            val now = System.currentTimeMillis()
            if (now < nextAllowed) {
                val waitSec = ((nextAllowed - now) / 1000L).coerceAtLeast(1)
                Log.w(TAG, "auth throttled for ${throttleKey.take(3)}*** waitSec=$waitSec")
                return@withContext KilterAuthResult.Error(
                    reason = KilterAuthResult.Error.Reason.Throttled,
                    throttleSec = waitSec,
                )
            }
            try {
                val body = FormBody.Builder()
                    .add("grant_type", "password")
                    .add("client_id", CLIENT_ID)
                    .add("username", email)
                    .add("password", password)
                    .add("scope", "openid offline_access")
                    .build()

                val request = Request.Builder().url(tokenUrl).post(body).build()
                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: ""
                    Log.w(TAG, "Auth failed: HTTP ${response.code}, $errorBody")
                    bumpAuthBackoff(throttleKey)
                    return@withContext KilterAuthResult.Error(
                        reason = if (response.code == 401)
                            KilterAuthResult.Error.Reason.InvalidCredentials
                        else KilterAuthResult.Error.Reason.HttpFailure,
                        httpCode = response.code,
                        cause = errorBody.take(200),
                    )
                }

                val responseBody = response.body?.string()
                    ?: run {
                        bumpAuthBackoff(throttleKey)
                        return@withContext KilterAuthResult.Error(
                            reason = KilterAuthResult.Error.Reason.EmptyResponse,
                        )
                    }
                val tokenResponse = json.decodeFromString<KilterTokenResponse>(responseBody)

                // Extract user UUID and username from JWT access token.
                // Pre-fix parseJwtClaims silently returned an empty map on
                // parse failure, which left userUuid="" — the user looked
                // logged in to the app but every subsequent API call would
                // fail obscurely. Now we surface InvalidJwt as a typed
                // error so the login UI shows a meaningful message.
                val claims = parseJwtClaimsOrNull(tokenResponse.accessToken)
                val userUuid = claims?.get("sub")
                if (claims == null || userUuid.isNullOrBlank()) {
                    bumpAuthBackoff(throttleKey)
                    return@withContext KilterAuthResult.Error(
                        reason = KilterAuthResult.Error.Reason.InvalidJwt,
                        cause = "missing sub claim",
                    )
                }
                // Display-name resolution chain — never leak the email
                // address as the public setter handle on Kilter. Kilter's
                // realm uses email as `preferred_username` (the login
                // handle), so the JWT claim alone gives us PII.
                //
                // 1. /api/users/{uuid}.username — the user-chosen display
                //    name they registered with on Kilter Portal. Kilter
                //    registration enforces uniqueness + non-empty
                //    ("IllegalArgumentException: The username is already
                //    in use"; "Please enter your Username"), so this is
                //    guaranteed to exist for any active Kilter account.
                //    The only failure mode is a transient network error
                //    during this auth call.
                // 2. JWT `name` claim — the user's full name from their
                //    Kilter profile. Stand-in when (1) is unreachable.
                // 3. Hard placeholder "CruxCoach" — final safety net so
                //    we never fall through to the email itself even if
                //    every upstream-resolved string looks email-shaped.
                //    Identifiable as a CruxCoach-app-published climb on
                //    the Kilter side rather than a generic anonymous
                //    handle.
                //
                // The chosen value is cached in KilterTokenStore and
                // becomes the `username` field on every published climb
                // (visible to all Kilter users browsing public climbs).
                val displayUsername = runCatching {
                    fetchDisplayUsername(tokenResponse.accessToken, userUuid)
                }.getOrNull()
                val nameClaim = claims["name"]?.takeIf { it.isNotBlank() }
                fun emailShaped(s: String): Boolean = s.contains("@")
                val username = when {
                    !displayUsername.isNullOrBlank() && !emailShaped(displayUsername) -> displayUsername
                    nameClaim != null && !emailShaped(nameClaim) -> nameClaim
                    else -> "CruxCoach"
                }

                // Successful login → clear the backoff state for this email.
                authFailures.remove(throttleKey)
                authBackoff.remove(throttleKey)

                KilterAuthResult.Success(
                    userUuid = userUuid,
                    username = username,
                    accessToken = tokenResponse.accessToken,
                    refreshToken = tokenResponse.refreshToken,
                    expiresIn = tokenResponse.expiresIn
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Auth error", e)
                bumpAuthBackoff(throttleKey)
                KilterAuthResult.Error(
                    reason = KilterAuthResult.Error.Reason.NetworkError,
                    cause = e.message?.take(200),
                )
            }
        }

    /**
     * Record a failed authentication attempt for [emailKey] and compute
     * the next-allowed timestamp using exponential backoff capped at
     * 5 minutes. The cap matches the typical Keycloak per-account
     * brute-force lockout window — we want to be at least as cautious
     * as the server side without locking the user out of their own
     * account longer than necessary.
     */
    private fun bumpAuthBackoff(emailKey: String) {
        val attempts = (authFailures[emailKey] ?: 0) + 1
        authFailures[emailKey] = attempts
        // 1s, 2s, 4s, 8s, 16s, 32s, 64s, 128s, 256s → cap at 300s.
        val backoffMs = (1000L * (1L shl (attempts - 1).coerceAtMost(8))).coerceAtMost(300_000L)
        authBackoff[emailKey] = System.currentTimeMillis() + backoffMs
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

                val request = Request.Builder().url(tokenUrl).post(body).build()
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
            } catch (e: CancellationException) {
                throw e
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
            val request = Request.Builder().url(logoutUrl).post(body).build()
            val response = httpClient.newCall(request).execute()
            response.close()
            val ok = response.isSuccessful
            if (!ok) Log.w(TAG, "Logout HTTP ${response.code}")
            ok
        } catch (e: CancellationException) {
            throw e
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
            ?: return@withContext Result.failure(KilterApiException(KilterAuthResult.Error.Reason.NotAuthenticated, "no valid token"))

        try {
            val request = Request.Builder()
                .url("$apiBase/logs")
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "fetchLogs failed", e)
            Result.failure(e)
        }
    }

    /**
     * Fetch the authenticated user's OWN logged climbs from
     * `GET /api/climbs/logged`. The response envelope is identical in shape
     * to `/climbs/curated` (an object with `climbs[]` + `climbStats[]`), but
     * it ALSO carries new-world (PowerSync-only) climbs that never landed in
     * our curated board-DB mirror — exactly the rows a logbook ascent can
     * reference yet fail to resolve in board-climb-detail.
     *
     * Compliance: a SINGLE GET of the user's own logged climbs with their
     * own Bearer token (mirrors the official app's "logged" screen). No
     * params, no bulk/all-climbs crawl. Token via the same
     * [ensureValidToken] refresh path as [fetchLogs].
     */
    suspend fun fetchLoggedClimbs(): Result<KilterLoggedClimbsResponse> = withContext(Dispatchers.IO) {
        val token = ensureValidToken()
            ?: return@withContext Result.failure(KilterApiException(KilterAuthResult.Error.Reason.NotAuthenticated, "no valid token"))

        try {
            val request = Request.Builder()
                .url("$apiBase/climbs/logged")
                .addHeader("Authorization", "Bearer $token")
                .build()
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("HTTP ${response.code}: ${response.body?.string()?.take(MAX_ERR_BODY)}")
                )
            }

            val body = response.body?.string()
                ?: return@withContext Result.failure(Exception("empty /climbs/logged response"))
            Result.success(json.decodeFromString<KilterLoggedClimbsResponse>(body))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "fetchLoggedClimbs failed", e)
            Result.failure(e)
        }
    }

    /**
     * Fetch the authenticated user's OWN AUTHORED climbs from
     * `GET /api/climbs/climbdetails/user`. Unlike `/climbs/logged` the
     * response is a BARE JSON array (no `climbs[]`/`climbStats[]` envelope)
     * and carries no stats; the per-climb fields are the same camelCase set
     * as [KilterLoggedClimb]. This is the backfill source for climbs the
     * user authored in the official app that never reached our curated
     * mirror, and the authoritative source of the author identity
     * (`userUuid`) the own-climb publish gate persists.
     *
     * Compliance: a SINGLE GET of the user's own authored climbs with their
     * own Bearer token. No params, no bulk/all-climbs crawl. Token via the
     * same [ensureValidToken] refresh path as [fetchLogs]; the User-Agent is
     * the client's honest interceptor-set UA.
     */
    suspend fun fetchOwnAuthoredClimbs(): Result<List<KilterAuthoredClimb>> = withContext(Dispatchers.IO) {
        val token = ensureValidToken()
            ?: return@withContext Result.failure(KilterApiException(KilterAuthResult.Error.Reason.NotAuthenticated, "no valid token"))

        try {
            val request = Request.Builder()
                .url("$apiBase/climbs/climbdetails/user")
                .addHeader("Authorization", "Bearer $token")
                .build()
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("HTTP ${response.code}: ${response.body?.string()?.take(MAX_ERR_BODY)}")
                )
            }

            val body = response.body?.string()
                ?: return@withContext Result.failure(Exception("empty /climbs/climbdetails/user response"))
            // Live shape is a bare array; tolerate a future envelope wrap
            // the same way fetchLogs does for /logs.
            val climbs = if (body.trimStart().startsWith("[")) {
                json.decodeFromString<List<KilterAuthoredClimb>>(body)
            } else {
                json.decodeFromString<KilterLoggedClimbsResponse>(body).climbs
            }
            Result.success(climbs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "fetchOwnAuthoredClimbs failed", e)
            Result.failure(e)
        }
    }

    /**
     * Fetch the authenticated user's OWN circuits ("lists") from
     * `GET /api/circuits/{userUuid}`. Live shape is a bare JSON array; we
     * tolerate a `{items:[...]}`/`{circuits:[...]}` envelope the same way
     * [fetchLogs] tolerates the `/logs` variants, so a future server wrap
     * doesn't silently drop every circuit.
     *
     * Compliance: a SINGLE GET of the user's own circuits with their own
     * Bearer token. No params, no bulk/all crawl — mirrors the official
     * app's "circuits" screen. Token via the same [ensureValidToken]
     * refresh path as [fetchLogs].
     */
    suspend fun fetchCircuits(): Result<List<KilterCircuit>> = withContext(Dispatchers.IO) {
        val token = ensureValidToken()
            ?: return@withContext Result.failure(KilterApiException(KilterAuthResult.Error.Reason.NotAuthenticated, "no valid token"))
        val userUuid = tokenStore.getUserUuid()
            ?: return@withContext Result.failure(KilterApiException(KilterAuthResult.Error.Reason.NotAuthenticated, "no user uuid"))

        try {
            val request = Request.Builder()
                .url("$apiBase/circuits/$userUuid")
                .addHeader("Authorization", "Bearer $token")
                .build()
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("HTTP ${response.code}: ${response.body?.string()?.take(MAX_ERR_BODY)}")
                )
            }

            val body = response.body?.string()
                ?: return@withContext Result.failure(Exception("empty /circuits response"))
            val circuits = if (body.trimStart().startsWith("[")) {
                json.decodeFromString<List<KilterCircuit>>(body)
            } else {
                json.decodeFromString<KilterCircuitsResponse>(body).circuitsOrItems()
            }
            Result.success(circuits)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "fetchCircuits failed", e)
            Result.failure(e)
        }
    }

    /**
     * Upload local ascents to Kilter in bulk.
     */
    suspend fun uploadLogs(logs: List<KilterLog>): Result<Unit> = withContext(Dispatchers.IO) {
        if (logs.isEmpty()) return@withContext Result.success(Unit)

        val token = ensureValidToken()
            ?: return@withContext Result.failure(KilterApiException(KilterAuthResult.Error.Reason.NotAuthenticated, "no valid token"))

        try {
            val payload = json.encodeToString(logs)
            val requestBody = payload.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$apiBase/logs/bulk")
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
        } catch (e: CancellationException) {
            throw e
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
                .url("$apiBase/walls/custom-wall")
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
        } catch (e: CancellationException) {
            throw e
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
        productLayoutUuid: String,
        angle: Int,
        edgeLeft: Int,
        edgeRight: Int,
        edgeBottom: Int,
        edgeTop: Int,
    ): KilterPublishResult = submitClimb(
        endpointPath = "create-climb/transaction",
        op = "publishClimb",
        method = "POST",
        climbUuid = climbUuid,
        name = name,
        description = description,
        framesClimbConcat = framesClimbConcat,
        productName = productName,
        productLayoutUuid = productLayoutUuid,
        angle = angle,
        edgeLeft = edgeLeft,
        edgeRight = edgeRight,
        edgeBottom = edgeBottom,
        edgeTop = edgeTop,
    )

    /**
     * Update an already-published climb on the upstream Aurora API:
     * `POST /api/climbs/update-climb/transaction`.
     *
     * Same payload shape as create. The upstream UI only lets the setter
     * trigger this for `is_draft=1` climbs, but the API does not visibly
     * gate against published rows, so we use it for the edit-flow of
     * a CruxCoach climb that was previously synced. If the API rejects
     * it (4xx for "published cannot be edited" or similar), the caller
     * marks the climb `kilter_status='diverged'` so the retry worker
     * stops poking it.
     */
    suspend fun updateClimb(
        climbUuid: String,
        name: String,
        description: String,
        framesClimbConcat: String,
        productName: String,
        productLayoutUuid: String,
        angle: Int,
        edgeLeft: Int,
        edgeRight: Int,
        edgeBottom: Int,
        edgeTop: Int,
    ): KilterPublishResult = submitClimb(
        endpointPath = "update-climb/transaction",
        op = "updateClimb",
        // The update endpoint requires PATCH — POST returns 405. Probed
        // empirically against the live API.
        method = "PATCH",
        climbUuid = climbUuid,
        name = name,
        description = description,
        framesClimbConcat = framesClimbConcat,
        productName = productName,
        productLayoutUuid = productLayoutUuid,
        angle = angle,
        edgeLeft = edgeLeft,
        edgeRight = edgeRight,
        edgeBottom = edgeBottom,
        edgeTop = edgeTop,
    )

    /**
     * Delete an own climb. Method `DELETE /api/climbs/{uuid}` — verified
     * empirically; the server returns "The climbs have been deleted
     * successfully." on success. Note: there is no PATCH-with-isDeleted
     * shortcut; sending isDeleted=true via update-climb does not actually
     * mark the row deleted server-side, you have to use this endpoint.
     */
    suspend fun deleteClimb(climbUuid: String): KilterPublishResult = withContext(Dispatchers.IO) {
        val token = ensureValidToken()
            ?: return@withContext KilterPublishResult.NotAuthenticated
        val request = Request.Builder()
            .url("$apiBase/climbs/$climbUuid")
            .addHeader("Authorization", "Bearer $token")
            .delete()
            .build()
        try {
            httpClient.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    Log.i(TAG, "deleteClimb ok uuid=$climbUuid")
                    return@withContext KilterPublishResult.Success(climbUuid)
                }
                val responseBody = resp.body?.string().orEmpty().take(MAX_ERR_BODY)
                Log.w(TAG, "deleteClimb HTTP ${resp.code}: $responseBody")
                return@withContext when (resp.code) {
                    401, 403 -> KilterPublishResult.NotAuthenticated
                    in 400..499 -> KilterPublishResult.PermanentError(responseBody, resp.code)
                    else -> KilterPublishResult.TransientError("HTTP ${resp.code}: $responseBody")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "deleteClimb exception uuid=$climbUuid", e)
            return@withContext KilterPublishResult.TransientError(e.message ?: "network error")
        }
    }

    /**
     * Shared transport for both create and update flows — same payload,
     * same auth, same error mapping; only the endpoint path differs. `op`
     * is a label for log lines so the two flows stay distinguishable.
     */
    private suspend fun submitClimb(
        endpointPath: String,
        op: String,
        method: String,
        climbUuid: String,
        name: String,
        description: String,
        framesClimbConcat: String,
        productName: String,
        productLayoutUuid: String,
        angle: Int,
        edgeLeft: Int,
        edgeRight: Int,
        edgeBottom: Int,
        edgeTop: Int,
    ): KilterPublishResult = withContext(Dispatchers.IO) {
        val token = ensureValidToken()
            ?: return@withContext KilterPublishResult.NotAuthenticated
        val userUuid = tokenStore.getUserUuid()?.takeIf { it.isNotBlank() }
            ?: return@withContext KilterPublishResult.NotAuthenticated
        // Username = the user's chosen display name on Kilter Portal,
        // resolved at login via fetchDisplayUsername with email-shape
        // defense (see authenticate()). Defence-in-depth here too: if
        // the stored value somehow ended up email-shaped (e.g. a stale
        // login from a pre-fix build before fetchDisplayUsername
        // existed, or a future schema change), refuse to publish
        // rather than leak the email as the public setter handle. The
        // user re-logs in and the new authenticate() path resolves a
        // safe name. NotAuthenticated keeps the kilter_status='failed'
        // path alive so the retry-worker re-attempts after re-login.
        val storedUsername = tokenStore.getUsername()?.takeIf { it.isNotBlank() }
            ?: return@withContext KilterPublishResult.NotAuthenticated
        if (storedUsername.contains("@")) {
            Log.w(TAG, "$op refusing to publish — stored username is email-shaped (re-login required)")
            return@withContext KilterPublishResult.NotAuthenticated
        }
        val username = storedUsername

        val nowIso = java.time.Instant.now().toString()
        val payload = CreateClimbTransaction(
            climb = ClimbCreatePayload(
                climbUuid = climbUuid,
                userUuid = userUuid,
                username = username,
                name = name,
                description = description,
                climbConcat = framesClimbConcat,
                productName = productName,
                productLayoutUuid = productLayoutUuid,
                angle = angle,
                edgeLeft = edgeLeft,
                edgeRight = edgeRight,
                edgeBottom = edgeBottom,
                edgeTop = edgeTop,
                createdAt = nowIso,
                updatedAt = nowIso,
            )
        )
        val bodyJson = json.encodeToString(CreateClimbTransaction.serializer(), payload)
        // Debug-only payload dump for triaging server-side 500s without
        // reproducing locally. climbConcat is the most-likely culprit
        // (placement IDs not in the product layout), productLayoutUuid +
        // edges next. Release builds redact aggressively because the
        // payload carries the Kilter username and the user-supplied climb
        // name/description — both PII at the boundary even though name
        // is public on Nostr afterwards.
        if (BuildConfig.DEBUG) {
            val redacted = bodyJson
                .replace(Regex("\"userUuid\":\"[^\"]+\""), "\"userUuid\":\"<redacted>\"")
                .replace(Regex("\"username\":\"[^\"]*\""), "\"username\":\"<redacted>\"")
                .replace(Regex("\"name\":\"[^\"]*\""), "\"name\":\"<redacted>\"")
                .replace(Regex("\"description\":\"[^\"]*\""), "\"description\":\"<redacted>\"")
            Log.d(TAG, "$op outgoing payload (PII redacted): $redacted")
        }
        val body = bodyJson.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$apiBase/climbs/$endpointPath")
            .addHeader("Authorization", "Bearer $token")
            .method(method, body)
            .build()

        try {
            var response = httpClient.newCall(request).execute()
            // 401 means our access token expired since `ensureValidToken`
            // last checked (window race). Try exactly one silent refresh
            // + retry — same pattern as the ascent fetch/upload path. If
            // it still fails, surface NotAuthenticated so the orchestrator
            // can defer.
            if (response.code == 401) {
                response.close()
                val refreshed = if (refreshAccessToken()) tokenStore.getAccessToken() else null
                if (refreshed != null) {
                    Log.i(TAG, "$op 401 → refreshed + retrying once")
                    val retryRequest = request.newBuilder()
                        .header("Authorization", "Bearer $refreshed")
                        .build()
                    response = httpClient.newCall(retryRequest).execute()
                }
            }
            response.use { resp ->
                if (resp.isSuccessful) {
                    // Don't include setter_uuid (the user's Kilter account
                    // identifier): it's a stable PII handle that bug-report
                    // logcat dumps would otherwise leak. The climb_uuid is
                    // public on Nostr already so it's safe to log.
                    Log.i(TAG, "$op ok uuid=$climbUuid")
                    return@withContext KilterPublishResult.Success(climbUuid)
                }
                // Truncate at the network boundary so every downstream
                // consumer (logcat line, KilterPublishResult envelope,
                // `kilter_error` DB column, retry-worker log) sees the
                // already-bounded body. Pre-fix the transient branch
                // persisted the raw response body verbatim, which on a 5xx
                // HTML stack-trace render could be 50–500 KB into the
                // unencrypted board DB and into Android Auto-Backup.
                val responseBody = resp.body?.string().orEmpty().take(MAX_ERR_BODY)
                Log.w(TAG, "$op HTTP ${resp.code}: $responseBody")
                return@withContext when (resp.code) {
                    401, 403 -> KilterPublishResult.NotAuthenticated
                    429 -> {
                        // Retry-After per RFC 7231: integer seconds OR HTTP-date.
                        // We only parse the integer form; date-form falls back
                        // to null and the caller picks a default backoff.
                        val raw = resp.header("Retry-After")?.trim()
                        val seconds = raw?.toLongOrNull()?.coerceAtLeast(0)
                        KilterPublishResult.RateLimited(
                            retryAfterSeconds = seconds,
                            message = "HTTP 429${if (raw != null) " retry-after=$raw" else ""}",
                        )
                    }
                    in 400..499 -> KilterPublishResult.PermanentError(responseBody, resp.code)
                    else -> KilterPublishResult.TransientError("HTTP ${resp.code}: $responseBody")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "$op exception uuid=$climbUuid", e)
            return@withContext KilterPublishResult.TransientError(e.message ?: "network error")
        }
    }

    private suspend fun ensureValidToken(): String? {
        val token = tokenStore.getAccessToken() ?: return null
        if (!tokenStore.isAccessTokenExpired()) return token
        return if (refreshAccessToken()) tokenStore.getAccessToken() else null
    }

    /**
     * Backfill the cached display username when the previously-stored
     * value is missing or email-shaped. Pre-username-fix builds stored
     * the JWT `preferred_username` claim which equals the email handle
     * for Kilter's Keycloak realm — the new publish path now refuses
     * to send email-shaped usernames as the public setter handle, so
     * stale cache entries from those builds would block publishing
     * until a manual re-login. This silently catches them up at
     * app-start instead. Skipped (no-op) when the cached value is
     * already a valid non-email username — no extra HTTP call in the
     * steady state. Token + userUuid pulled from tokenStore; failure
     * is non-fatal (the publish-path guard remains as last-resort
     * defense).
     */
    suspend fun refreshUsernameIfStale() = withContext(Dispatchers.IO) {
        val cached = tokenStore.getUsername()
        if (!cached.isNullOrBlank() && !cached.contains("@")) return@withContext
        val userUuid = tokenStore.getUserUuid()?.takeIf { it.isNotBlank() } ?: return@withContext
        val token = ensureValidToken() ?: return@withContext
        val fresh = fetchDisplayUsername(token, userUuid)
        if (!fresh.isNullOrBlank() && !fresh.contains("@")) {
            Log.i(TAG, "Backfilled display username from /users/{uuid}")
            tokenStore.updateUsername(fresh)
        }
    }

    /**
     * Fetch the user's display name from `/api/users/{uuid}.username`.
     * Returns null on any failure (network, HTTP non-2xx, missing field,
     * JSON shape change). Caller falls back to the JWT preferred_username
     * which is the email-shaped login handle in the Kilter realm.
     */
    private suspend fun fetchDisplayUsername(token: String, userUuid: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$apiBase/users/$userUuid")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                json.parseToJsonElement(body).jsonObject["username"]?.jsonPrimitive?.contentOrNull
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "fetchDisplayUsername failed for uuid=${userUuid.take(8)}…", e)
            null
        }
    }

    /**
     * Extract claims from a JWT by base64-decoding the payload segment.
     * No signature verification needed — we trust the Keycloak server response.
     */
    /**
     * Returns parsed JWT claims, or null if the token is malformed.
     * Pre-fix this returned an empty map on any parse failure, which
     * collapsed cleanly into `claims["sub"] ?: ""` — userUuid="" became
     * indistinguishable from a successful login with a missing claim.
     * Returning null lets callers surface an InvalidJwt error.
     */
    private fun parseJwtClaimsOrNull(jwt: String): Map<String, String>? = try {
        parseJwtClaimsPure(jwt, json)
    } catch (e: Exception) {
        Log.w(TAG, "JWT parse failed", e)
        null
    }
}

/**
 * Pure JWT-claims parser, extracted from [KilterApiClient.parseJwtClaims]
 * so JVM unit tests can exercise it without an Android-stub Base64. Uses
 * [java.util.Base64] (URL-safe decoder, padding-tolerant per the Java
 * spec) which is available on the project's minSdk 26.
 *
 * Returns an empty map for any malformed input — unchanged behavior from
 * the wrapping `parseJwtClaims`. Throws on `IllegalArgumentException`
 * from Base64 / kotlinx.serialization so callers can decide what to do
 * (the in-class wrapper catches + logs).
 */
internal fun parseJwtClaimsPure(
    jwt: String,
    json: kotlinx.serialization.json.Json,
): Map<String, String> {
    val parts = jwt.split(".")
    if (parts.size != 3) return emptyMap()
    val payload = String(
        java.util.Base64.getUrlDecoder().decode(parts[1]),
        Charsets.UTF_8,
    )
    val element = json.parseToJsonElement(payload)
    val obj = element as? kotlinx.serialization.json.JsonObject ?: return emptyMap()
    return obj.entries.associate { (k, v) ->
        k to (v as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
    }
}
