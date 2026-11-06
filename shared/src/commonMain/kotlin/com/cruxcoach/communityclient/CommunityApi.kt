package com.cruxcoach.communityclient

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.io.encoding.Base64

/** What the relay says about itself (NIP-11). */
data class RelayInfo(val name: String?, val relayPubkey: String?, val icon: String?)

/** One page of a NIP-CW channel window. */
data class WindowPage(
    val rows: List<CommunityEvent>,
    val aux: List<CommunityEvent>,
    val summaries: List<ThreadSummary>,
    val bounds: WindowBounds,
)

/**
 * The community's HTTP endpoints. Authenticated routes carry a NIP-98 header signed
 * for exactly this URL, method and body.
 */
class CommunityApi(
    private val endpoint: CommunityEndpoint,
    private val http: CommunityHttp,
    private val signer: CommunitySigner,
    private val verifier: EventVerifier,
    private val clock: UnixClock,
) {
    /** NIP-11 relay information; `self` is the key that signs relay-only kinds. */
    suspend fun relayInfo(): RelayInfo {
        val result = http.get(endpoint.httpOrigin + "/", mapOf("Accept" to "application/nostr+json"))
        if (result.status !in 200..299) throw CommunityException("relay info: HTTP ${result.status}")
        val obj = runCatching { CommunityJson.parseToJsonElement(result.body).jsonObject }.getOrNull()
            ?: throw CommunityException("relay info: not JSON")
        fun str(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return RelayInfo(name = str("name"), relayPubkey = str("self")?.takeIf { it.isHex(64) }, icon = str("icon"))
    }

    /** The community rules, or null if the community has none. */
    suspend fun joinPolicy(): JoinPolicy? {
        val result = http.get(endpoint.httpUrl("/api/join-policy"))
        if (result.status !in 200..299) throw CommunityException("community rules: HTTP ${result.status}")
        return JoinPolicy.parseResponse(result.body).getOrElse { throw CommunityException("community rules: ${it.message}") }
    }

    /** Exchanges the user's explicit acceptance for a short-lived receipt bound to the invite code. */
    suspend fun acceptPolicy(code: String, policy: JoinPolicy, ageConfirmed: Boolean): String {
        val body = InviteApi.acceptPolicyBody(code, policy.version, ageConfirmed)
        val result = http.post(endpoint.httpUrl("/api/invites/accept-policy"), body, JSON_HEADERS)
        if (result.status !in 200..299) throw CommunityException("accepting the rules failed: HTTP ${result.status}")
        return InviteApi.parseReceipt(result.body) ?: throw CommunityException("accepting the rules: no receipt")
    }

    /** Redeems the invite for the signed-in key (NIP-98). */
    suspend fun claimInvite(code: String, policyReceipt: String?): ClaimResult {
        val url = endpoint.httpUrl("/api/invites/claim")
        val body = InviteApi.claimBody(code, policyReceipt)
        val result = http.post(url, body, JSON_HEADERS + authHeader(url, "POST", body))
        return InviteApi.parseClaim(result.status, result.body)
    }

    /**
     * Raw `POST /query`: a JSON array of filters in, a JSON array of events out.
     * Events with a bad signature are dropped.
     */
    suspend fun query(filters: List<RelayFilter>): List<CommunityEvent> {
        val url = endpoint.httpUrl("/query")
        val body = buildJsonArray { filters.forEach { add(it.toJsonObject()) } }.toString()
        val result = http.post(url, body, JSON_HEADERS + authHeader(url, "POST", body))
        if (result.status !in 200..299) throw CommunityException("query: HTTP ${result.status} ${result.body.take(200)}")
        val array = runCatching { CommunityJson.parseToJsonElement(result.body).jsonArray }.getOrNull()
            ?: throw CommunityException("query: not a JSON array")
        return parseVerified(array)
    }

    /** One NIP-CW channel window page (top-level rows, newest first). */
    suspend fun channelWindow(channelId: String, cursor: WindowCursor?, limit: Int = 50, relayPubkey: String?): WindowPage {
        val events = query(listOf(CommunityFilters.channelWindow(channelId, limit, cursor)))
        return splitWindowPage(events, channelId, cursor, relayPubkey)
            ?: throw CommunityException("query: page without valid bounds")
    }

    private fun parseVerified(array: JsonArray): List<CommunityEvent> =
        array.mapNotNull { CommunityEvent.fromJson(it) }.filter { runCatching { verifier.verify(it) }.getOrDefault(false) }

    private suspend fun authHeader(url: String, method: String, body: String?): Map<String, String> {
        val payload = body?.let { sha256Hex(it.encodeToByteArray()) }
        val event = signer.sign(CommunityEventTemplates.httpAuth(url, method, clock.nowSeconds(), payload))
        return mapOf("Authorization" to "Nostr " + Base64.encode(event.toJson().encodeToByteArray()))
    }

    companion object {
        private val JSON_HEADERS = mapOf("Content-Type" to "application/json")
    }
}

/**
 * Splits a window response into rows, aux, summaries and the one bounds overlay.
 * Returns null when the page is unusable: no bounds, several, or bounds for another request.
 */
fun splitWindowPage(events: List<CommunityEvent>, channelId: String, cursor: WindowCursor?, relayPubkey: String?): WindowPage? {
    fun fromRelay(e: CommunityEvent) = relayPubkey == null || e.pubkey == relayPubkey
    val boundsEvents = events.filter { it.kind == CommunityKinds.WINDOW_BOUNDS }
    if (boundsEvents.size != 1 || !fromRelay(boundsEvents[0])) return null
    val bounds = WindowBounds.fromEvent(boundsEvents[0], channelId, cursor) ?: return null
    val rows = events.filter { it.kind in CommunityKinds.STREAM_CONTENT && it.channelId == channelId }
    val aux = events.filter { it.kind in CommunityKinds.AUX }
    val summaries = events.filter { it.kind == CommunityKinds.THREAD_SUMMARY && fromRelay(it) }.mapNotNull { ThreadSummary.fromEvent(it) }
    return WindowPage(rows, aux, summaries, bounds)
}
