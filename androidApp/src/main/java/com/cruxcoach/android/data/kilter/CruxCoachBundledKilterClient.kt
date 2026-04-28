package com.cruxcoach.android.data.kilter

import android.util.Log
import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
 * HTTP client for the optional CruxCoach-bundled Kilter publish service.
 *
 * **Status: stub.** The server endpoint `https://cruxcoach.org/api/v1/
 * publish-climb` (or wherever it ends up) is not yet deployed. This
 * client implements the wire contract so the orchestrator
 * ([KilterClimbPublisher]) can be built end-to-end today; the actual
 * HTTP roundtrip will succeed once the backend ships.
 *
 * ## Wire contract (proposed)
 *
 * Request: `POST /api/v1/publish-climb`
 * ```json
 * {
 *   "version": 1,
 *   "nostr_event": <Kind-30078 event JSON, signed by user>,
 *   "layout_id": <int>,
 *   "size_label": "12x12 with kickboard",
 *   "edges": { "left": -..., "right": ..., "bottom": ..., "top": ... }
 * }
 * ```
 *
 * Server actions:
 * 1. Verify `nostr_event.sig` against `nostr_event.pubkey`.
 * 2. Verify `nostr_event.id` matches the event hash (no tampering after signing).
 * 3. Check per-pubkey quota (rate-limit anti-spam).
 * 4. Authenticate against Kilter using the **CruxCoach service account**.
 * 5. Build a Kilter `create-climb/transaction` payload, using
 *    `nostr_event.tags['frames']` as the source of truth + the user's
 *    Nostr-event-bound `climb_uuid`. Description gets a `[via CruxCoach]`
 *    suffix so Kilter users know the provenance.
 * 6. POST to Kilter; relay the result back to the app.
 *
 * Response:
 * ```json
 * { "ok": true,  "climb_uuid": "..." }
 * { "ok": false, "error": "..." }
 * ```
 *
 * ## Trust + abuse model
 *
 * - The user's Nostr signature proves they own the pubkey — no separate
 *   auth needed app-side. The service is the only one that can submit to
 *   Kilter on their behalf, so all spam protection lives there:
 *   per-pubkey daily quota, per-IP rate limit, optional reputation
 *   threshold ("must have N successful Nostr publishes first").
 * - Attribution: Kilter-side `setter_uuid` will be the CruxCoach service
 *   account. The original setter's npub is preserved in the description
 *   text, so Kilter users can copy-paste it into a Nostr client.
 * - Failure modes return `TransientError` for retry, `PermanentError` for
 *   server-side rejection (quota exceeded, bad payload, blocked pubkey).
 */
@Singleton
class CruxCoachBundledKilterClient @Inject constructor(
    @Named("kilter") private val httpClient: OkHttpClient,
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun publishClimb(
        signedEvent: Event,
        layoutId: Long,
        sizeLabel: String,
        edgeLeft: Int,
        edgeRight: Int,
        edgeBottom: Int,
        edgeTop: Int,
    ): KilterPublishResult = withContext(Dispatchers.IO) {
        val payload = buildBundledPayload(
            event = signedEvent,
            layoutId = layoutId,
            sizeLabel = sizeLabel,
            edgeLeft = edgeLeft,
            edgeRight = edgeRight,
            edgeBottom = edgeBottom,
            edgeTop = edgeTop,
        )
        val body = payload.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(BUNDLED_PUBLISH_URL)
            .post(body)
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val rb = response.body?.string().orEmpty()
                val parsed = runCatching { json.decodeFromString(BundledResponse.serializer(), rb) }.getOrNull()
                if (parsed != null && parsed.ok) {
                    Log.i(TAG, "bundled publish ok uuid=${parsed.climb_uuid ?: "n/a"}")
                    return@withContext KilterPublishResult.Success(parsed.climb_uuid ?: signedEvent.id)
                }
                return@withContext KilterPublishResult.TransientError(
                    parsed?.error ?: "Bundled service returned non-ok body"
                )
            }
            val responseBody = response.body?.string().orEmpty()
            Log.w(TAG, "bundled publish HTTP ${response.code}: $responseBody")
            return@withContext when (response.code) {
                in 400..499 -> KilterPublishResult.PermanentError(responseBody, response.code)
                else -> KilterPublishResult.TransientError("HTTP ${response.code}: $responseBody")
            }
        } catch (e: Exception) {
            Log.w(TAG, "bundled publish exception", e)
            return@withContext KilterPublishResult.TransientError(e.message ?: "network error")
        }
    }

    private fun buildBundledPayload(
        event: Event,
        layoutId: Long,
        sizeLabel: String,
        edgeLeft: Int,
        edgeRight: Int,
        edgeBottom: Int,
        edgeTop: Int,
    ): String {
        // Hand-build the JSON body so we don't need a full Event-serializer
        // dance — Quartz's Event class has lots of metadata fields we'd
        // rather not re-encode and risk drifting from the canonical signed form.
        val tagsJson = JsonArray(event.tags.map { tag ->
            JsonArray(tag.map { JsonPrimitive(it) })
        })
        val nostrEventJson = JsonObject(mapOf(
            "id" to JsonPrimitive(event.id),
            "pubkey" to JsonPrimitive(event.pubKey),
            "created_at" to JsonPrimitive(event.createdAt),
            "kind" to JsonPrimitive(event.kind),
            "tags" to tagsJson,
            "content" to JsonPrimitive(event.content),
            "sig" to JsonPrimitive(event.sig),
        ))
        val edgesJson = JsonObject(mapOf(
            "left" to JsonPrimitive(edgeLeft),
            "right" to JsonPrimitive(edgeRight),
            "bottom" to JsonPrimitive(edgeBottom),
            "top" to JsonPrimitive(edgeTop),
        ))
        val root = JsonObject(mapOf(
            "version" to JsonPrimitive(1),
            "nostr_event" to nostrEventJson,
            "layout_id" to JsonPrimitive(layoutId),
            "size_label" to JsonPrimitive(sizeLabel),
            "edges" to edgesJson,
        ))
        return json.encodeToString(JsonObject.serializer(), root)
    }

    @Serializable
    private data class BundledResponse(
        val ok: Boolean,
        @SerialName("climb_uuid") val climb_uuid: String? = null,
        val error: String? = null,
    )

    private companion object {
        const val TAG = "CCBundledKilter"
        // Will resolve once the cruxcoach.org service is deployed. Until
        // then the client returns TransientError on every call, which
        // the orchestrator treats as "leave kilter_status='failed' and
        // surface a Snackbar". No app-side change needed when the URL
        // actually starts serving — DNS + 200 → orchestrator flips the
        // row to 'synced'.
        const val BUNDLED_PUBLISH_URL = "https://cruxcoach.org/api/v1/publish-climb"
    }
}
