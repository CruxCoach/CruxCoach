package com.cruxcoach.android.data.kilter

import android.util.Log
import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * HTTP client for the CruxCoach-bundled publish service.
 *
 * **Status: stub.** The endpoint at `https://cruxcoach.org/api/v1/
 * publish-climb` is not deployed yet; every call resolves to
 * `TransientError` until DNS + service are live. The client exists so
 * the orchestrator can be wired end-to-end and the wire contract is
 * pinned in code (forces server + app to agree before either ships).
 *
 * ## Three identity modes
 *
 * | Mode                | Nostr signing       | Kilter setter       |
 * |---------------------|---------------------|---------------------|
 * | `KilterOnly`        | user's own (already)| service account     |
 * | `NostrOnly`         | service shared key  | (skipped)           |
 * | `KilterAndNostr`    | service shared key  | service account     |
 *
 * - **KilterOnly** is the user "I have a Nostr key but not a Kilter
 *   account" — Mode B in the design doc. Client provides a signed event;
 *   service forwards to Kilter via its own credentials.
 * - **NostrOnly** is rare: user has a Kilter account but doesn't want
 *   their npub publicly attributed. Bundled signing only.
 * - **KilterAndNostr** is "fully anonymous via CruxCoach" — Mode C.
 *   Client sends the raw climb payload; service signs Nostr + pushes
 *   Kilter, returns both event id and Kilter status.
 *
 * ## Wire contract (proposed v2)
 *
 * Request body:
 * ```json
 * {
 *   "version": 2,
 *   "mode": "kilter-only" | "nostr-only" | "kilter-and-nostr",
 *
 *   // Required for "kilter-only" — a Kind-30078 climb event the user
 *   // has already signed locally. Server verifies sig + pushes Kilter.
 *   "nostr_event": <event JSON> | null,
 *
 *   // Required for "nostr-only" and "kilter-and-nostr" — the raw climb
 *   // payload. Server builds + signs the Kind-30078 event itself, plus
 *   // (for kilter-and-nostr) submits to Kilter.
 *   "raw_climb": {
 *     "uuid": "...",
 *     "name": "...",
 *     "description": "...",
 *     "frames": "<aurora-delta or kilter-range>",
 *     "frames_climb_concat": "<kilter-range>",
 *     "layout_id": 1,
 *     "size_label": "12x12 with kickboard",
 *     "setter_grade_id": 18,
 *     "angle": 40,
 *     "edges": { "left": -..., "right": ..., "bottom": ..., "top": ... },
 *     "display_name": "Anon-Crux-12345"
 *   } | null,
 *
 *   // Stable handle for rate-limiting per app installation.
 *   "client_attestation": "<device-derived token>"
 * }
 * ```
 *
 * Response:
 * ```json
 * {
 *   "ok": true | false,
 *   "climb_uuid": "...",
 *
 *   // Set when the server signed Nostr (modes nostr-only,
 *   // kilter-and-nostr). Echoed from request when kilter-only.
 *   "nostr_event_id": "...",
 *   "nostr_d_tag": "...",
 *   "nostr_pubkey": "<service-pubkey-hex>",
 *
 *   // Set when the server attempted Kilter (modes kilter-only,
 *   // kilter-and-nostr).
 *   "kilter_status": "synced" | "queued" | "rejected",
 *   "kilter_error": null | "..."
 * }
 * ```
 */
@Singleton
class CruxCoachBundledPublishClient @Inject constructor(
    @Named("kilter") private val httpClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Lightweight payload used by the `nostr-only` and
     * `kilter-and-nostr` modes — what the server needs to build a
     * Kind-30078 climb event identical to what the local NostrSigner
     * would have produced.
     */
    data class RawClimbPayload(
        val uuid: String,
        val name: String,
        val description: String,
        val framesAurora: String,             // "p{id}r{role}…" delta format
        val framesClimbConcat: String,        // "h{id}p{ref}…" Kilter range format
        val layoutId: Long,
        val sizeLabel: String,
        val setterGradeId: Int?,
        val angle: Int?,
        val edgeLeft: Int,
        val edgeRight: Int,
        val edgeBottom: Int,
        val edgeTop: Int,
        val displayName: String?,             // optional setter alias
    )

    suspend fun publish(
        mode: Mode,
        signedEvent: Event? = null,
        rawClimb: RawClimbPayload? = null,
        clientAttestation: String,
    ): BundledPublishResult = withContext(Dispatchers.IO) {
        val body = buildPayload(mode, signedEvent, rawClimb, clientAttestation)
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(BUNDLED_PUBLISH_URL)
            .post(body)
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            val rb = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.w(TAG, "bundled publish HTTP ${response.code}: $rb")
                return@withContext when (response.code) {
                    in 400..499 -> BundledPublishResult.PermanentError(rb, response.code)
                    else -> BundledPublishResult.TransientError("HTTP ${response.code}: $rb")
                }
            }
            val parsed = runCatching {
                json.decodeFromString(BundledResponse.serializer(), rb)
            }.getOrElse {
                return@withContext BundledPublishResult.TransientError(
                    "malformed server response: ${it.message}"
                )
            }
            if (!parsed.ok) {
                return@withContext BundledPublishResult.TransientError(
                    parsed.error ?: "Bundled service returned ok=false"
                )
            }
            BundledPublishResult.Success(
                climbUuid = parsed.climb_uuid ?: signedEvent?.id ?: rawClimb?.uuid.orEmpty(),
                nostrEventId = parsed.nostr_event_id,
                nostrDTag = parsed.nostr_d_tag,
                nostrPubkey = parsed.nostr_pubkey,
                kilterStatus = parsed.kilter_status,
                kilterError = parsed.kilter_error,
            )
        } catch (e: Exception) {
            Log.w(TAG, "bundled publish exception", e)
            return@withContext BundledPublishResult.TransientError(e.message ?: "network error")
        }
    }

    private fun buildPayload(
        mode: Mode,
        signedEvent: Event?,
        rawClimb: RawClimbPayload?,
        clientAttestation: String,
    ): String {
        val members = LinkedHashMap<String, JsonElement>()
        members["version"] = JsonPrimitive(2)
        members["mode"] = JsonPrimitive(mode.wire)
        members["nostr_event"] = signedEvent?.let { encodeNostrEvent(it) } ?: JsonNull
        members["raw_climb"] = rawClimb?.let { encodeRawClimb(it) } ?: JsonNull
        members["client_attestation"] = JsonPrimitive(clientAttestation)
        return json.encodeToString(JsonObject.serializer(), JsonObject(members))
    }

    private fun encodeNostrEvent(event: Event): JsonObject {
        // Hand-build to mirror the canonical signed form exactly — using a
        // higher-level Quartz serializer would risk re-encoding numeric
        // fields with different representations and breaking the sig.
        val tagsJson = JsonArray(event.tags.map { t -> JsonArray(t.map { JsonPrimitive(it) }) })
        return JsonObject(mapOf(
            "id" to JsonPrimitive(event.id),
            "pubkey" to JsonPrimitive(event.pubKey),
            "created_at" to JsonPrimitive(event.createdAt),
            "kind" to JsonPrimitive(event.kind),
            "tags" to tagsJson,
            "content" to JsonPrimitive(event.content),
            "sig" to JsonPrimitive(event.sig),
        ))
    }

    private fun encodeRawClimb(p: RawClimbPayload): JsonObject {
        val edges = JsonObject(mapOf(
            "left" to JsonPrimitive(p.edgeLeft),
            "right" to JsonPrimitive(p.edgeRight),
            "bottom" to JsonPrimitive(p.edgeBottom),
            "top" to JsonPrimitive(p.edgeTop),
        ))
        return JsonObject(mapOf(
            "uuid" to JsonPrimitive(p.uuid),
            "name" to JsonPrimitive(p.name),
            "description" to JsonPrimitive(p.description),
            "frames" to JsonPrimitive(p.framesAurora),
            "frames_climb_concat" to JsonPrimitive(p.framesClimbConcat),
            "layout_id" to JsonPrimitive(p.layoutId),
            "size_label" to JsonPrimitive(p.sizeLabel),
            "setter_grade_id" to (p.setterGradeId?.let { JsonPrimitive(it) } ?: JsonNull),
            "angle" to (p.angle?.let { JsonPrimitive(it) } ?: JsonNull),
            "edges" to edges,
            "display_name" to (p.displayName?.let { JsonPrimitive(it) } ?: JsonNull),
        ))
    }

    @Serializable
    private data class BundledResponse(
        val ok: Boolean,
        @SerialName("climb_uuid") val climb_uuid: String? = null,
        @SerialName("nostr_event_id") val nostr_event_id: String? = null,
        @SerialName("nostr_d_tag") val nostr_d_tag: String? = null,
        @SerialName("nostr_pubkey") val nostr_pubkey: String? = null,
        @SerialName("kilter_status") val kilter_status: String? = null,
        @SerialName("kilter_error") val kilter_error: String? = null,
        val error: String? = null,
    )

    enum class Mode(val wire: String) {
        KilterOnly("kilter-only"),
        NostrOnly("nostr-only"),
        KilterAndNostr("kilter-and-nostr"),
    }

    private companion object {
        const val TAG = "CCBundledPublish"
        const val BUNDLED_PUBLISH_URL = "https://cruxcoach.org/api/v1/publish-climb"
    }
}

/**
 * Outcome of a bundled-publish call. Carries both the Nostr and Kilter
 * sides since one call can do both.
 */
sealed class BundledPublishResult {
    data class Success(
        val climbUuid: String,
        val nostrEventId: String?,
        val nostrDTag: String?,
        val nostrPubkey: String?,
        val kilterStatus: String?,            // 'synced' | 'queued' | 'rejected' | null
        val kilterError: String?,
    ) : BundledPublishResult()
    data class TransientError(val message: String) : BundledPublishResult()
    data class PermanentError(val message: String, val httpCode: Int) : BundledPublishResult()
}
