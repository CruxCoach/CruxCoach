package com.cruxcoach.android.sharing.mailbox

import com.cruxcoach.android.nostr.RelayInputGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.SecureRandom

/** What a relay answered for one event. */
sealed interface Placed {
    data object Accepted : Placed

    /** `OK false`. [rateLimited]: the NIP-01 reason prefix says so; wait before the next one. */
    data class Refused(val reason: String, val rateLimited: Boolean) : Placed

    /** No answer in time, or the connection failed. */
    data object NoAnswer : Placed
}

/** Events of one fetch; [complete]: every request ended with EOSE (nothing cut off). */
data class Fetched(val events: List<String>, val complete: Boolean)

/** The NIP-11 facts a mailbox must have (SP2): deletions, expiry, room for an event, open writes. */
data class MailboxInfo(
    val supportsDeletion: Boolean,
    val supportsExpiration: Boolean,
    val maxMessageLength: Int?,
    val authRequired: Boolean,
    val paymentRequired: Boolean,
    val restrictedWrites: Boolean,
) {
    /** A missing field counts as fine; the relay's OK answers decide. */
    val usable get() = !authRequired && !paymentRequired && !restrictedWrites &&
        (maxMessageLength == null || maxMessageLength >= MIN_MESSAGE_LENGTH)

    companion object {
        const val MIN_MESSAGE_LENGTH = 70_000
    }
}

/**
 * Mailbox traffic to one relay over its own short WebSocket. It shares no
 * connection with anything that knows the npub, adds no header of its own and
 * logs nothing. NIP-01 only: `EVENT`/`OK` to place, `REQ`/`EVENT`/`EOSE`
 * (paged) to fetch. Events pass through as JSON text; the native side checks,
 * opens and signs them.
 */
class MailboxRelayClient(
    private val http: OkHttpClient,
    private val answerMs: Long = 10_000,
    private val random: SecureRandom = SecureRandom(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    private inner class Socket(url: String) : WebSocketListener() {
        val incoming = Channel<String>(Channel.UNLIMITED)
        private val socket: WebSocket = http.newWebSocket(Request.Builder().url(url).build(), this)

        override fun onMessage(webSocket: WebSocket, text: String) {
            // Bounded before parsing; whatever a relay sends is data, never trusted.
            if (RelayInputGuard.accepts(text)) incoming.trySend(text)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            incoming.close()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            incoming.close()
        }

        fun send(text: String) = socket.send(text)

        fun close() {
            socket.close(1000, null)
            incoming.close()
        }
    }

    private suspend fun <T> connected(url: String, block: suspend (Socket) -> T): T = withContext(Dispatchers.IO) {
        val socket = Socket(url)
        try {
            block(socket)
        } finally {
            socket.close()
        }
    }

    private fun parse(text: String): JsonArray? = runCatching { json.parseToJsonElement(text).jsonArray }.getOrNull()

    /** Place [events] (`id` to event JSON) on [url]; every event gets an answer or [Placed.NoAnswer]. */
    suspend fun place(url: String, events: Map<String, String>): Map<String, Placed> {
        if (events.isEmpty()) return emptyMap()
        return runCatching {
            connected(url) { socket ->
                val answers = mutableMapOf<String, Placed>()
                for (event in events.values) {
                    // The event JSON comes from the native side, already canonical.
                    if (!socket.send("[\"EVENT\",$event]")) break
                }
                withTimeoutOrNull(answerMs) {
                    for (text in socket.incoming) {
                        val message = parse(text) ?: continue
                        if (message.getOrNull(0)?.jsonPrimitive?.content != "OK" || message.size < 3) continue
                        val id = message[1].jsonPrimitive.content
                        if (id !in events || id in answers) continue
                        val ok = message[2].jsonPrimitive.booleanOrNull ?: continue
                        val reason = message.getOrNull(3)?.jsonPrimitive?.content.orEmpty()
                        answers[id] = if (ok) Placed.Accepted else Placed.Refused(reason.take(200), reason.startsWith("rate-limited"))
                        if (answers.size == events.size) break
                    }
                }
                events.keys.associateWith { answers[it] ?: Placed.NoAnswer }
            }
        }.getOrElse { events.keys.associateWith { Placed.NoAnswer } }
    }

    /**
     * Mailbox events under [addresses] on [url], newest first, paged backwards
     * where a relay caps one answer at [limit]; requests stay small (at most
     * [ADDRESSES_PER_REQUEST] addresses each).
     */
    suspend fun fetch(url: String, kind: Int, addresses: List<String>, since: Long?, limit: Int = 500): Fetched {
        if (addresses.isEmpty()) return Fetched(emptyList(), true)
        return runCatching {
            connected(url) { socket ->
                val events = LinkedHashMap<String, String>()
                var complete = true
                for (chunk in addresses.distinct().chunked(ADDRESSES_PER_REQUEST)) {
                    var until: Long? = null
                    for (round in 0 until MAX_PAGES) {
                        val id = subscription()
                        val filter = buildJsonObject {
                            put("kinds", buildJsonArray { add(JsonPrimitive(kind)) })
                            put("#z", buildJsonArray { chunk.forEach { add(JsonPrimitive(it)) } })
                            since?.let { put("since", it) }
                            until?.let { put("until", it) }
                            put("limit", limit)
                        }
                        if (!socket.send(buildJsonArray { add(JsonPrimitive("REQ")); add(JsonPrimitive(id)); add(filter) }.toString())) {
                            return@connected Fetched(events.values.toList(), false)
                        }
                        val (found, ended) = collect(socket, id)
                        socket.send(buildJsonArray { add(JsonPrimitive("CLOSE")); add(JsonPrimitive(id)) }.toString())
                        var oldest: Long? = null
                        var fresh = 0
                        for ((eventId, createdAt, text) in found) {
                            if (events.put(eventId, text) == null) fresh++
                            oldest = minOf(oldest ?: createdAt, createdAt)
                        }
                        if (!ended) complete = false
                        // A full page may hide older events: ask again up to the oldest one
                        // seen. Relays cap answers below what was asked (100 is the smallest
                        // cap seen), so a page of that size counts as full.
                        if (!ended || found.size < minOf(limit, SMALLEST_RELAY_CAP) || fresh == 0 || oldest == null) break
                        until = oldest
                        if (round == MAX_PAGES - 1) complete = false
                    }
                }
                Fetched(events.values.toList(), complete)
            }
        }.getOrElse { Fetched(emptyList(), false) }
    }

    /** `(id, created_at, json)` of one subscription, and whether it ended with EOSE. */
    private suspend fun collect(socket: Socket, subscription: String): Pair<List<Triple<String, Long, String>>, Boolean> {
        val out = mutableListOf<Triple<String, Long, String>>()
        var ended = false
        withTimeoutOrNull(answerMs) {
            for (text in socket.incoming) {
                val message = parse(text) ?: continue
                if (message.getOrNull(1)?.jsonPrimitive?.content != subscription) continue
                when (message[0].jsonPrimitive.content) {
                    "EVENT" -> {
                        val event = message.getOrNull(2) as? JsonObject ?: continue
                        val id = event["id"]?.jsonPrimitive?.content ?: continue
                        val createdAt = event["created_at"]?.jsonPrimitive?.long ?: continue
                        out += Triple(id, createdAt, event.toString())
                    }
                    "EOSE" -> { ended = true; break }
                    "CLOSED" -> break
                }
            }
        }
        return out to ended
    }

    private fun subscription(): String = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    /** NIP-11 of [url] (`wss://` asked over `https://`), or null when it gives none. */
    suspend fun info(url: String): MailboxInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val http = url.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://")
            this@MailboxRelayClient.http.newCall(Request.Builder().url(http).header("Accept", "application/nostr+json").build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string()?.takeIf { it.length <= 64_000 } ?: return@use null
                val info = json.parseToJsonElement(body).jsonObject
                val nips = info["supported_nips"]?.jsonArray?.mapNotNull { it.jsonPrimitive.intOrNull }.orEmpty()
                val limitation = info["limitation"] as? JsonObject
                fun flag(name: String) = limitation?.get(name)?.jsonPrimitive?.booleanOrNull == true
                MailboxInfo(
                    supportsDeletion = 9 in nips,
                    supportsExpiration = 40 in nips,
                    maxMessageLength = limitation?.get("max_message_length")?.jsonPrimitive?.intOrNull,
                    authRequired = flag("auth_required"),
                    paymentRequired = flag("payment_required"),
                    restrictedWrites = flag("restricted_writes"),
                )
            }
        }.getOrNull()
    }

    companion object {
        /** Keeps one REQ near 10 KB; relays cut off far larger ones. */
        const val ADDRESSES_PER_REQUEST = 400
        const val MAX_PAGES = 20
        const val SMALLEST_RELAY_CAP = 100
    }
}
