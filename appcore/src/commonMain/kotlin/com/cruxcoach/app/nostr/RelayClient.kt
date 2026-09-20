package com.cruxcoach.app.nostr

import com.cruxcoach.app.platform.Hashing
import com.cruxcoach.app.platform.WebSocketConnector
import com.cruxcoach.app.platform.WebSocketHandle
import com.cruxcoach.app.platform.WebSocketListener
import com.cruxcoach.app.util.toHex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.concurrent.Volatile

/** Relay set and timeouts copied from Android's `NostrConfig`. */
object NostrRelays {
    /**
     * The app's publish + subscribe set. Ordering and membership are Android's:
     * nos.lol is the relay that empirically retains one-time kind-30078 events
     * long-term, the others provide failure-domain diversity.
     */
    val DEFAULT_RELAYS: List<String> = listOf(
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://relay.primal.net",
        "wss://nostr-pub.wellorder.net",
        "wss://nostr.oxtr.dev",
    )

    const val RELAY_TIMEOUT_MS = 10_000L
}

/**
 * Pre-parser bounds on a relay frame; same limits as Android's `RelayInputGuard`.
 *
 * NOTE for integration: `com.cruxcoach.app.sync.ManifestFetcher` carries an
 * identical internal copy. One of the two should be deleted and the other
 * imported — they must not drift.
 */
internal object RelayInputGuard {
    const val MAX_BYTES = 1024 * 1024
    private const val MAX_DEPTH = 32

    fun accepts(text: String): Boolean {
        if (text.length > MAX_BYTES) return false
        var bytes = 0
        var depth = 0
        var quoted = false
        var escaped = false
        for (c in text) {
            // Counting surrogate pairs as six bytes is conservative, and avoids
            // allocating another attacker-sized UTF-8 array to measure one.
            bytes += when { c.code < 128 -> 1; c.code < 2048 -> 2; else -> 3 }
            if (bytes > MAX_BYTES) return false
            if (quoted) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '[', '{' -> if (++depth > MAX_DEPTH) return false
                ']', '}' -> if (--depth < 0) return false
            }
        }
        return !quoted && depth == 0
    }
}

/**
 * Minimal NIP-01 relay client over short-lived sockets: a one-shot REQ/EOSE
 * query and a publish with honest per-relay accounting. It deliberately has no
 * long-lived subscription and no reconnect ladder — the backup pipeline only
 * ever does one-shot work, and a pool that outlives it would keep sockets open
 * for a feature the user runs twice a day.
 *
 * Relay answers are a bandwidth hint, never trust: every event is checked with
 * [NostrEvents.verify] (id binds the body AND the signature verifies) before it
 * is returned, and each relay's contribution is bounded in count and size.
 */
class RelayClient(
    private val webSockets: WebSocketConnector,
    private val hashing: Hashing,
    private val relayUrls: List<String> = NostrRelays.DEFAULT_RELAYS,
) {
    /** Every verified event any relay returned for [filters], de-duplicated by id. */
    suspend fun query(
        filters: List<String>,
        relays: List<String> = relayUrls,
        timeoutMs: Long = NostrRelays.RELAY_TIMEOUT_MS,
    ): List<NostrEvent> {
        if (filters.isEmpty()) return emptyList()
        val perRelay = coroutineScope {
            relays.map { url -> async { queryRelay(url, filters, timeoutMs) } }.awaitAll()
        }
        val byId = LinkedHashMap<String, NostrEvent>()
        for (events in perRelay) for (event in events) if (!byId.containsKey(event.id)) byId[event.id] = event
        return byId.values.toList()
    }

    suspend fun query(
        filter: String,
        relays: List<String> = relayUrls,
        timeoutMs: Long = NostrRelays.RELAY_TIMEOUT_MS,
    ): List<NostrEvent> = query(listOf(filter), relays, timeoutMs)

    /**
     * Publishes to every relay and reports `(attempted, accepted)`. Only an
     * explicit `["OK", <id>, true, …]` counts as accepted: a socket that merely
     * swallowed the frame has not stored anything.
     *
     * [attempted] counts the relays actually dialed — a URL that fails the wss
     * gate is never contacted and would overstate the redundancy.
     */
    suspend fun publish(event: NostrEvent, relays: List<String> = relayUrls): Pair<Int, Int> {
        val dialable = relays.filter { UrlValidation.isValidRelay(it) }
        if (dialable.isEmpty()) return 0 to 0
        val message = """["EVENT",${JSON.encodeToString(JsonObject.serializer(), event.toJson())}]"""
        val accepted = coroutineScope {
            dialable.map { url -> async { publishToRelay(url, event.id, message) } }.awaitAll()
        }
        return dialable.size to accepted.count { it }
    }

    private suspend fun queryRelay(url: String, filters: List<String>, timeoutMs: Long): List<NostrEvent> {
        if (!UrlValidation.isValidRelay(url)) return emptyList()
        val subscriptionId = "cc-" + hashing.randomBytes(8).toHex()
        val request = """["REQ","$subscriptionId",${filters.joinToString(",")}]"""
        val done = CompletableDeferred<Unit>()
        val socket = SocketRef()
        val opened = CompletableDeferred<Unit>()
        val sendClaim = CompletableDeferred<Unit>()
        val collected = ArrayList<NostrEvent>()

        // onOpen can fire inside connect(), before the handle exists, or later on
        // another thread. Whichever side sees both first claims the single send.
        fun sendRequestOnce() {
            val handle = socket.handle ?: return
            if (opened.isCompleted && sendClaim.complete(Unit)) handle.send(request)
        }

        val listener = object : WebSocketListener {
            private var seen = 0

            override fun onOpen() {
                opened.complete(Unit)
                sendRequestOnce()
            }

            override fun onText(text: String) {
                if (done.isCompleted) return
                // An oversized or absurdly nested frame is hostile: stop reading
                // this relay rather than handing it to the parser.
                if (!RelayInputGuard.accepts(text)) {
                    done.complete(Unit)
                    return
                }
                try {
                    val frame = JSON.parseToJsonElement(text) as? JsonArray ?: return
                    when ((frame.getOrNull(0) as? JsonPrimitive)?.takeIf { it.isString }?.content) {
                        "EVENT" -> {
                            if (string(frame.getOrNull(1)) != subscriptionId) return
                            // A relay that keeps streaming cannot grow our heap forever.
                            if (++seen > MAX_EVENTS_PER_RELAY) {
                                done.complete(Unit)
                                return
                            }
                            val event = NostrEvent.fromJson(frame.getOrNull(2) ?: return) ?: return
                            if (acceptable(event)) collected.add(event)
                        }
                        "EOSE" -> if (string(frame.getOrNull(1)) == subscriptionId) done.complete(Unit)
                        // The relay refused or dropped the subscription; nothing more is coming.
                        "CLOSED" -> if (string(frame.getOrNull(1)) == subscriptionId) done.complete(Unit)
                    }
                } catch (e: Exception) {
                    done.complete(Unit)
                }
            }

            override fun onClosed(reason: String) {
                done.complete(Unit)
            }
        }

        return try {
            socket.handle = webSockets.connect(url, RelayInputGuard.MAX_BYTES, listener) ?: return emptyList()
            sendRequestOnce()
            // On timeout we keep what already arrived: one slow relay must not
            // discard the events the others already delivered on this socket.
            withTimeoutOrNull(timeoutMs) { done.await() }
            // Latch the subscription closed before snapshotting, so a frame still
            // in flight on the socket thread cannot append while we copy.
            done.complete(Unit)
            collected.toList()
        } catch (e: Exception) {
            collected.toList()
        } finally {
            socket.handle?.let { handle ->
                try {
                    handle.send("""["CLOSE","$subscriptionId"]""")
                } catch (e: Exception) {
                    // The socket is going away anyway; closing it is what matters.
                }
                handle.close()
            }
        }
    }

    private suspend fun publishToRelay(url: String, eventId: String, message: String): Boolean {
        val result = CompletableDeferred<Boolean>()
        val socket = SocketRef()
        val opened = CompletableDeferred<Unit>()
        val sendClaim = CompletableDeferred<Unit>()

        fun sendOnce() {
            val handle = socket.handle ?: return
            if (opened.isCompleted && sendClaim.complete(Unit)) handle.send(message)
        }

        val listener = object : WebSocketListener {
            override fun onOpen() {
                opened.complete(Unit)
                sendOnce()
            }

            override fun onText(text: String) {
                if (result.isCompleted) return
                if (!RelayInputGuard.accepts(text)) {
                    result.complete(false)
                    return
                }
                try {
                    val frame = JSON.parseToJsonElement(text) as? JsonArray ?: return
                    if ((frame.getOrNull(0) as? JsonPrimitive)?.takeIf { it.isString }?.content != "OK") return
                    if (string(frame.getOrNull(1)) != eventId) return
                    val ok = (frame.getOrNull(2) as? JsonPrimitive)?.strictBoolean()
                    result.complete(ok == true)
                } catch (e: Exception) {
                    result.complete(false)
                }
            }

            override fun onClosed(reason: String) {
                // Closed before an OK: the relay never said it stored the event.
                result.complete(false)
            }
        }

        return try {
            socket.handle = webSockets.connect(url, RelayInputGuard.MAX_BYTES, listener) ?: return false
            sendOnce()
            withTimeoutOrNull(NostrRelays.RELAY_TIMEOUT_MS) { result.await() } ?: false
        } catch (e: Exception) {
            false
        } finally {
            socket.handle?.close()
        }
    }

    /** Signature, body binding, and the tag bounds Android applies at the same gate. */
    private fun acceptable(event: NostrEvent): Boolean {
        if (event.tags.size > MAX_TAGS || event.tags.any { it.size > MAX_TAG_VALUES }) return false
        return NostrEvents.verify(hashing, event)
    }

    private fun string(element: kotlinx.serialization.json.JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** NIP-01 says the OK flag is a JSON boolean; a quoted "true" is not one. */
    private fun JsonPrimitive.strictBoolean(): Boolean? =
        if (isString) null else content.toBooleanStrictOrNull()

    private class SocketRef {
        @Volatile
        var handle: WebSocketHandle? = null
    }

    private companion object {
        /** Bounds one relay's contribution to a single query. */
        const val MAX_EVENTS_PER_RELAY = 256
        const val MAX_TAGS = 4096
        const val MAX_TAG_VALUES = 256
        val JSON = Json
    }
}
