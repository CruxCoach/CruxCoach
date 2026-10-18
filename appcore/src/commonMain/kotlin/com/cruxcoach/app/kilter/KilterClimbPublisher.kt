package com.cruxcoach.app.kilter

import com.cruxcoach.app.community.CommunityEventTime
import com.cruxcoach.app.platform.KeyValueStore
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.data.repository.KilterClaim
import com.cruxcoach.data.repository.KilterPublishOp
import com.cruxcoach.data.repository.KilterPublishOutcomeKind
import com.cruxcoach.domain.community.ClimbEditorState
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * layout_id → Kilter product_name, as Android's `kilterProductName`: the portal
 * resolves placement ids against the product named here, so a Homewall climb
 * sent as "Original" fails with a 500. Other layouts default to Original and
 * the server rejects them.
 */
internal fun kilterProductName(layoutId: Long): String = when (layoutId) {
    HOMEWALL_LAYOUT_ID -> "Kilter Board Homewall"
    else -> "Kilter Board Original"
}

/** Android `BoardConstants.KILTER_HOMEWALL_LAYOUT`. */
private const val HOMEWALL_LAYOUT_ID = 8L

/** Result of one create/update call, Android's `KilterPublishResult`. */
internal sealed class KilterPublishResult {
    class Success(val climbUuid: String) : KilterPublishResult()
    object NotAuthenticated : KilterPublishResult()
    class TransientError(val message: String) : KilterPublishResult()
    class PermanentError(val message: String, val httpCode: Int) : KilterPublishResult()
    class RateLimited(val retryAfterSeconds: Long?, val message: String) : KilterPublishResult()
}

/** Body of `POST /climbs/create-climb/transaction` and `PATCH /climbs/update-climb/transaction`. */
@Serializable
internal class KilterCreateClimbTransaction(val climb: KilterClimbCreatePayload)

/**
 * Android's `ClimbCreatePayload`, field for field: camelCase (snake_case keys
 * are silently dropped by the portal), `climbConcat` in hole ids, `origin`
 * NATIVE for an app-authored climb. Sent with every default spelled out.
 */
@Serializable
internal class KilterClimbCreatePayload(
    val climbUuid: String,
    val userUuid: String,
    val username: String,
    val name: String,
    val description: String,
    val climbConcat: String,
    val productName: String,
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
    val origin: String = "NATIVE",
    val createdAt: String,
    val updatedAt: String,
)

/**
 * The Kilter-side outcome of a publish, Android's `KilterClimbPublisher.Outcome`
 * flattened for the UI: [code] synced | failed | diverged | skipped; [messageKey]
 * the Android string the editor shows (empty for synced/skipped); [httpCode] the
 * portal's status for the `_with_code` messages; [reason] the skip reason.
 */
class KilterClimbPublishOutcome(
    val code: String,
    val messageKey: String,
    val httpCode: Int,
    val reason: String,
) {
    companion object {
        val SYNCED = KilterClimbPublishOutcome("synced", "", 0, "")
        fun skipped(reason: String) = KilterClimbPublishOutcome("skipped", "", 0, reason)
        fun failed(messageKey: String, httpCode: Int = 0) = KilterClimbPublishOutcome("failed", messageKey, httpCode, "")
        fun diverged(httpCode: Int) =
            KilterClimbPublishOutcome("diverged", "kilter_publish_diverged_with_code", httpCode, "")
    }

    override fun equals(other: Any?): Boolean = other is KilterClimbPublishOutcome &&
        other.code == code && other.messageKey == messageKey && other.httpCode == httpCode && other.reason == reason

    override fun hashCode(): Int = code.hashCode() * 31 + reason.hashCode()

    override fun toString(): String = "KilterClimbPublishOutcome($code, $messageKey, $httpCode, $reason)"
}

/**
 * The Kilter account the own-climb paths act as: login state, user uuid and the
 * public setter name. Android resolves the name at login; the iOS login predates
 * this, so it is resolved here on first use with Android's chain — the portal's
 * `/users/{uuid}.username`, then the token's `name` claim, then "CruxCoach" — and
 * never an e-mail address, which would publish the login as the public handle.
 */
class KilterClimbAccount(
    private val api: KilterApi,
    private val tokens: KilterTokens,
    private val keyValues: KeyValueStore,
    private val identityPrefix: String,
) {
    private val usernameKey get() = "kilter_${identityPrefix}_username"

    fun isSignedIn(): Boolean = tokens.isSignedIn

    fun userUuid(): String = tokens.userUuid()

    /** The cached setter name, or "" when none was resolved yet or it is e-mail shaped. */
    fun cachedUsername(): String =
        keyValues.getString(usernameKey)?.takeIf { it.isNotBlank() && !it.contains("@") } ?: ""

    /** The setter name for a publish, resolving and caching it when needed; "" without a login. */
    internal suspend fun resolveUsername(): String {
        cachedUsername().takeIf { it.isNotEmpty() }?.let { return it }
        val uuid = tokens.userUuid().takeIf { it.isNotBlank() } ?: return ""
        val display = fetchDisplayUsername(uuid)
        if (display != null) {
            keyValues.putString(usernameKey, display)
            return display
        }
        val nameClaim = tokens.accessToken()?.let { claimOf(it, "name") }?.takeIf { it.isNotBlank() && !it.contains("@") }
        if (nameClaim != null) {
            keyValues.putString(usernameKey, nameClaim)
            return nameClaim
        }
        // Android's last resort, not cached: the next publish tries the portal again.
        return "CruxCoach"
    }

    /** Re-reads the name from the portal (the profile's "Use Kilter username"); null when unavailable. */
    internal suspend fun refreshDisplayUsername(): String? {
        val uuid = tokens.userUuid().takeIf { it.isNotBlank() } ?: return null
        val display = fetchDisplayUsername(uuid) ?: return null
        keyValues.putString(usernameKey, display)
        return display
    }

    fun forgetUsername() = keyValues.putString(usernameKey, null)

    private suspend fun fetchDisplayUsername(uuid: String): String? {
        if (!SAFE_SEGMENT.matches(uuid)) return null
        val call = api.climbCall("GET", "users/$uuid", null, KilterApi.MAX_TOKEN_BYTES, KilterApi.TIMEOUT_SECONDS)
        if (call.failure != KilterFailure.NONE || call.status !in 200..299) return null
        return try {
            Json.parseToJsonElement(call.body).jsonObject["username"]?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            null
        }?.takeIf { it.isNotBlank() && !it.contains("@") }
    }

    companion object {
        private val SAFE_SEGMENT = Regex("[A-Za-z0-9_-]+")

        /** A string claim of a JWT, unverified: only a label, the server stays the authority. */
        @OptIn(ExperimentalEncodingApi::class)
        internal fun claimOf(jwt: String, claim: String): String? = try {
            val part = jwt.split('.').getOrNull(1) ?: return null
            val bytes = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(part)
            Json.parseToJsonElement(bytes.decodeToString()).jsonObject[claim]?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Posts the user's own CruxCoach climbs to Kilter through their own account —
 * port of Android's `KilterClimbPublisher` and the create/update half of its
 * `KilterApiClient`. The climb keeps the uuid its Nostr event carries.
 *
 * Nostr is the source of truth and was accepted before this runs, so a Kilter
 * failure never fails the publish: it is written to `kilter_status` /
 * `kilter_error` and [KilterClimbPublishRetry] picks the row up again.
 *
 * iOS has no one-time import mode (a sign-in keeps the session until sign-out),
 * so Android's "persistently connected" gate is the signed-in session itself.
 */
class KilterClimbPublisher(
    private val api: KilterApi,
    private val account: KilterClimbAccount,
    private val boardRepository: BoardRepository,
    /** Android's `kilterClimbPublishEnabled` (opt-in, per identity). */
    private val publishEnabled: () -> Boolean,
    private val clockMillis: () -> Long,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun publish(
        uuid: String,
        layoutId: Long,
        state: ClimbEditorState,
        boardSize: BoardSize?,
        framesClimbConcat: String,
    ): KilterClimbPublishOutcome = submit(uuid, layoutId, state, boardSize, framesClimbConcat)

    /**
     * Same as [publish]: whether the portal gets create or update is decided by
     * the slot claim's pre-state (`kilter_synced_at`), never by the entry point.
     */
    suspend fun update(
        uuid: String,
        layoutId: Long,
        state: ClimbEditorState,
        boardSize: BoardSize?,
        framesClimbConcat: String,
    ): KilterClimbPublishOutcome = submit(uuid, layoutId, state, boardSize, framesClimbConcat)

    private suspend fun submit(
        uuid: String,
        layoutId: Long,
        state: ClimbEditorState,
        boardSize: BoardSize?,
        framesClimbConcat: String,
    ): KilterClimbPublishOutcome {
        if (!publishEnabled()) return KilterClimbPublishOutcome.skipped("user-opted-out")
        if (boardSize == null) return KilterClimbPublishOutcome.skipped("no-board-size")
        if (!account.isSignedIn() || account.userUuid().isBlank()) {
            return KilterClimbPublishOutcome.skipped("no-kilter-login")
        }
        // Atomic claim against the retry path: only one flow calls the portal per climb.
        val claim = boardRepository.claimKilterPublishSlot(uuid)
        if (claim !is KilterClaim.Won) return KilterClimbPublishOutcome.skipped("slot-busy")
        val isUpdate = claim.previouslySyncedAtEpochSeconds != null
        val result = sendClimb(
            isUpdate = isUpdate,
            uuid = uuid,
            name = state.name,
            description = state.description,
            framesClimbConcat = framesClimbConcat,
            layoutId = layoutId,
            boardSize = boardSize,
            // The editor always has an angle; 40° is Android's fallback for a required field.
            angle = state.angle ?: 40,
        )
        val op = if (isUpdate) KilterPublishOp.UPDATE else KilterPublishOp.CREATE
        val attemptedAt = clockMillis()
        return try {
            when (result) {
                is KilterPublishResult.Success -> {
                    boardRepository.markKilterPublishSynced(uuid, "self", attemptedAt / 1000)
                    recordAttempt(boardRepository, uuid, attemptedAt, op, KilterPublishOutcomeKind.SUCCESS, null, null)
                    KilterClimbPublishOutcome.SYNCED
                }
                is KilterPublishResult.NotAuthenticated -> {
                    boardRepository.markKilterPublishFailed(uuid, "token expired")
                    recordAttempt(boardRepository, uuid, attemptedAt, op, KilterPublishOutcomeKind.AUTH, null, "token expired")
                    KilterClimbPublishOutcome.failed("kilter_publish_session_expired")
                }
                is KilterPublishResult.TransientError -> {
                    boardRepository.markKilterPublishFailed(uuid, "transient: ${result.message}")
                    recordAttempt(boardRepository, uuid, attemptedAt, op, KilterPublishOutcomeKind.TRANSIENT, null, result.message)
                    KilterClimbPublishOutcome.failed("kilter_publish_transient")
                }
                is KilterPublishResult.RateLimited -> {
                    boardRepository.markKilterPublishFailed(uuid, "rate-limited: ${result.message}")
                    recordAttempt(boardRepository, uuid, attemptedAt, op, KilterPublishOutcomeKind.RATE_LIMITED, 429, result.message)
                    KilterClimbPublishOutcome.failed("kilter_publish_transient")
                }
                is KilterPublishResult.PermanentError -> {
                    val excerpt = "http=${result.httpCode}: ${result.message.take(200)}"
                    markTerminal(isUpdate, uuid, excerpt)
                    recordAttempt(
                        boardRepository, uuid, attemptedAt, op, KilterPublishOutcomeKind.PERMANENT,
                        result.httpCode, result.message,
                    )
                    // Update refused: Kilter keeps its copy, no more retries ('diverged').
                    // Create refused: the payload itself is rejected ('rejected'), terminal too.
                    if (isUpdate) {
                        KilterClimbPublishOutcome.diverged(result.httpCode)
                    } else {
                        KilterClimbPublishOutcome.failed("kilter_publish_rejected_with_code", result.httpCode)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A row left 'pending' is invisible to the retry queue: downgrade it.
            runCatching {
                boardRepository.markKilterPublishFailed(
                    uuid, "post-api mark threw: ${e.message?.take(200) ?: e::class.simpleName}",
                )
            }
            KilterClimbPublishOutcome.failed("kilter_publish_transient")
        }
    }

    private fun markTerminal(isUpdate: Boolean, uuid: String, excerpt: String) {
        if (isUpdate) {
            boardRepository.markKilterPublishDiverged(uuid, excerpt)
        } else {
            boardRepository.markKilterPublishRejected(uuid, excerpt)
        }
    }

    /**
     * Create (`POST create-climb/transaction`) or update (`PATCH
     * update-climb/transaction`, POST answers 405) one climb, with Android's
     * status mapping: 2xx success, 401/403 not authenticated, 429 rate limited,
     * other 4xx permanent, 5xx and transport failures transient. Error bodies are
     * cut to 200 characters at this boundary, before anything stores them.
     */
    internal suspend fun sendClimb(
        isUpdate: Boolean,
        uuid: String,
        name: String,
        description: String,
        framesClimbConcat: String,
        layoutId: Long,
        boardSize: BoardSize,
        angle: Int,
    ): KilterPublishResult {
        val userUuid = account.userUuid().takeIf { it.isNotBlank() } ?: return KilterPublishResult.NotAuthenticated
        val username = account.resolveUsername()
        if (username.isBlank() || username.contains("@")) return KilterPublishResult.NotAuthenticated
        val now = CommunityEventTime.isoFromEpochSeconds(clockMillis() / 1000)
        val payload = KilterCreateClimbTransaction(
            KilterClimbCreatePayload(
                climbUuid = uuid,
                userUuid = userUuid,
                username = username,
                name = name,
                description = description,
                climbConcat = framesClimbConcat,
                productName = kilterProductName(layoutId),
                // The portal's id of the (product, size) variant: our product size id.
                productLayoutUuid = boardSize.id.toString(),
                angle = angle,
                edgeLeft = boardSize.edgeLeft.toInt(),
                edgeRight = boardSize.edgeRight.toInt(),
                edgeBottom = boardSize.edgeBottom.toInt(),
                edgeTop = boardSize.edgeTop.toInt(),
                createdAt = now,
                updatedAt = now,
            ),
        )
        val body = json.encodeToString(KilterCreateClimbTransaction.serializer(), payload)
        val call = api.climbCall(
            method = if (isUpdate) "PATCH" else "POST",
            path = if (isUpdate) "climbs/update-climb/transaction" else "climbs/create-climb/transaction",
            jsonBody = body,
            maxResponseBytes = KilterApi.MAX_TOKEN_BYTES,
            timeoutSeconds = KilterApi.TIMEOUT_SECONDS,
        )
        return mapCall(call, uuid)
    }

    companion object {
        internal const val MAX_ERR_BODY = 200

        internal fun mapCall(call: KilterClimbCall, uuid: String): KilterPublishResult {
            when (call.failure) {
                KilterFailure.NONE -> Unit
                KilterFailure.NOT_SIGNED_IN -> return KilterPublishResult.NotAuthenticated
                else -> return KilterPublishResult.TransientError(call.body.take(MAX_ERR_BODY).ifBlank { "network error" })
            }
            if (call.status in 200..299) return KilterPublishResult.Success(uuid)
            val excerpt = call.body.take(MAX_ERR_BODY)
            return when (call.status) {
                401, 403 -> KilterPublishResult.NotAuthenticated
                429 -> {
                    val raw = call.retryAfter?.trim()
                    KilterPublishResult.RateLimited(
                        retryAfterSeconds = raw?.toLongOrNull()?.coerceAtLeast(0),
                        message = "HTTP 429" + (if (raw != null) " retry-after=$raw" else ""),
                    )
                }
                in 400..499 -> KilterPublishResult.PermanentError(excerpt, call.status)
                else -> KilterPublishResult.TransientError("HTTP ${call.status}: $excerpt")
            }
        }

        /** Audit row for every terminal branch; best effort, the status columns stay authoritative. */
        internal fun recordAttempt(
            boardRepository: BoardRepository,
            uuid: String,
            attemptedAtMs: Long,
            op: KilterPublishOp,
            outcome: KilterPublishOutcomeKind,
            httpCode: Int?,
            errorExcerpt: String?,
        ) {
            runCatching {
                boardRepository.recordKilterPublishAttempt(
                    climbUuid = uuid,
                    attemptedAtMs = attemptedAtMs,
                    op = op,
                    via = "self",
                    outcome = outcome,
                    httpCode = httpCode,
                    errorExcerpt = errorExcerpt?.take(MAX_ERR_BODY),
                )
            }
        }
    }
}
