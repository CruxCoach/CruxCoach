package com.cruxcoach.android.nostr.relaydiscovery

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Opens short-lived WebSocket connections to NIP-65 bootstrap relays, issues
 * a REQ for Kind 10002 events by [pubkey], and returns the highest-`created_at`
 * event across all successful responses.
 *
 * Invariants:
 *  - Never throws out of [fetch]. Network, parse, and timeout failures return
 *    `null` — the caller falls back to defaults.
 *  - Defense in depth: the relay enforces Schnorr signature validity before
 *    delivering; [fetch] additionally requires `event.pubkey == pubkey`.
 *  - Deliberately bypasses [com.cruxcoach.android.nostr.NostrRelayPool] —
 *    the pool's relay list is what this fetcher resolves, so going through
 *    it would be a dependency cycle.
 */
@Singleton
class Nip65RelayListFetcher @Inject constructor(
    @Named("nostr") private val okHttpClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @param bootstrapRelays wss:// URLs. Default is [DEFAULT_BOOTSTRAP].
     * @param timeoutMs hard cap on the whole fetch operation across all
     *        relays. FEAT-001 §6.4 pins this at 3 s for the fresh-install
     *        synchronous path.
     */
    suspend fun fetch(
        pubkey: String,
        bootstrapRelays: List<String> = DEFAULT_BOOTSTRAP,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Kind10002Event? {
        if (pubkey.isBlank()) return null
        if (bootstrapRelays.isEmpty()) return null
        val start = System.currentTimeMillis()
        Log.d(TAG, "event=fetch_start bootstrapCount=${bootstrapRelays.size} timeoutMs=$timeoutMs")

        val candidates: List<Kind10002Event?> = try {
            withTimeout(timeoutMs) {
                coroutineScope {
                    bootstrapRelays.map { url ->
                        async { fetchFromSingle(url, pubkey, timeoutMs) }
                    }.awaitAll()
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.d(TAG, "event=fetch_timeout durationMs=${System.currentTimeMillis() - start}")
            emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "event=fetch_all_failed durationMs=${System.currentTimeMillis() - start} error=${e.javaClass.simpleName}")
            emptyList()
        }

        val valid = candidates.filterNotNull().filter { it.pubkey == pubkey }
        val winner = valid.maxByOrNull { it.createdAt }

        val duration = System.currentTimeMillis() - start
        if (winner != null) {
            Log.d(TAG, "event=fetch_success relayCount=${winner.relays.size} durationMs=$duration")
        } else {
            Log.d(TAG, "event=fetch_no_result durationMs=$duration")
        }
        return winner
    }

    private suspend fun fetchFromSingle(
        url: String,
        pubkey: String,
        timeoutMs: Long,
    ): Kind10002Event? {
        val result = CompletableDeferred<Kind10002Event?>()
        var best: Kind10002Event? = null
        val subId = SUB_ID_PREFIX + System.nanoTime().toString(16)
        val filter = """{"kinds":[10002],"authors":["$pubkey"],"limit":1}"""
        val reqMessage = """["REQ","$subId",$filter]"""

        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                ws.send(reqMessage)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                val completed = handleMessage(text, pubkey) { parsed ->
                    val current = best
                    if (current == null || parsed.createdAt > current.createdAt) best = parsed
                }
                if (completed) {
                    ws.send("""["CLOSE","$subId"]""")
                    ws.close(1000, "eose")
                    result.complete(best)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (!result.isCompleted) result.complete(best)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (!result.isCompleted) result.complete(best)
            }
        }

        val request = Request.Builder().url(url).build()
        val ws = okHttpClient.newWebSocket(request, listener)
        return try {
            withTimeout(timeoutMs) { result.await() }
        } catch (e: TimeoutCancellationException) {
            null
        } finally {
            runCatching { ws.cancel() }
        }
    }

    /** Returns `true` when the relay has finished responding (EOSE or CLOSED). */
    private inline fun handleMessage(
        text: String,
        expectedPubkey: String,
        onEvent: (Kind10002Event) -> Unit,
    ): Boolean {
        return try {
            val arr = json.parseToJsonElement(text).jsonArray
            if (arr.isEmpty()) return false
            when (arr[0].jsonPrimitive.content) {
                "EVENT" -> {
                    if (arr.size < 3) return false
                    val eventObj = arr[2].jsonObject
                    val parsed = parseKind10002(eventObj)
                    if (parsed != null && parsed.pubkey == expectedPubkey) onEvent(parsed)
                    false
                }
                "EOSE", "CLOSED" -> true
                "NOTICE" -> false
                else -> false
            }
        } catch (e: Exception) {
            Log.w(TAG, "event=parse_failed", e)
            false
        }
    }

    private fun parseKind10002(event: JsonObject): Kind10002Event? {
        val kind = (event["kind"] as? JsonPrimitive)?.jsonPrimitive?.long ?: return null
        if (kind != 10002L) return null
        val pubkey = (event["pubkey"] as? JsonPrimitive)?.jsonPrimitive?.content ?: return null
        val createdAt = (event["created_at"] as? JsonPrimitive)?.jsonPrimitive?.long ?: return null
        val tagsElem = event["tags"] ?: return null

        val tagArray = runCatching { tagsElem.jsonArray }.getOrNull() ?: return null
        val tagLists = tagArray.mapNotNull { tagElem ->
            runCatching {
                tagElem.jsonArray.map { it.jsonPrimitive.content }
            }.getOrNull()
        }

        val relays = Nip65TagParser.parse(tagLists)
        return Kind10002Event(
            pubkey = pubkey,
            createdAt = createdAt,
            relays = relays,
        )
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 3_000L
        val DEFAULT_BOOTSTRAP: List<String> = listOf(
            "wss://purplepag.es",
            "wss://relay.nostr.band",
        )
        private const val TAG = "Nip65Discovery"
        private const val SUB_ID_PREFIX = "nip65-"
    }
}
