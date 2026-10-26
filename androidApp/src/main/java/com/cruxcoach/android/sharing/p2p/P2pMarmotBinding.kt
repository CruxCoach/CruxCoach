package com.cruxcoach.android.sharing.p2p

import com.cruxcoach.android.sharing.MarmotBinding
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * The second [MarmotBinding] (spike M0): the native Olm engine plus direct
 * transports. [com.cruxcoach.android.sharing.MarmotHost], the sync layer and
 * the UI are unchanged; this binding answers the same operations.
 *
 * * `set_online` starts or stops listening and advertising on every transport.
 * * `sync` runs the native upkeep, then exchange steps with every endpoint in
 *   reach that the native side identifies by a beacon: a friend, an inviter,
 *   or the device of a scanned contact code. Unidentified endpoints get
 *   nothing.
 * * `status` reports the transports where v2 reported relays, and counts as
 *   "pending" only messages for friends in reach right now: a friend who is
 *   not nearby is not a delivery failure, so a pass does not wait for them.
 *
 * While online, a light poll ([pollMs]) exchanges with whoever is in reach:
 * the direct counterpart of v2's live relay subscriptions. Without it, two
 * idle open apps would not notice each other until the next local change.
 *
 * Holds no protocol data: containers pass through in memory; only the last
 * time each friend was reachable is kept (short-lived lifecycle state).
 */
class P2pMarmotBinding(
    private val native: MarmotBinding,
    private val transports: List<ShareTransport>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val budgetMs: Long = 10_000,
    private val pollMs: Long = 5_000,
    /** Delivery through mailboxes while online; null: direct transports only. */
    private val mailboxes: com.cruxcoach.android.sharing.mailbox.MailboxDelivery? = null,
    /** Direct transports (LAN, BLE) run only while this holds: the app is in front. */
    private val direct: () -> Boolean = { true },
) : MarmotBinding {
    @Volatile private var online = false
    private val mailboxNative = com.cruxcoach.android.sharing.mailbox.MailboxNative { op, fields -> value(op, fields) }
    @Volatile private var poller: Thread? = null
    @Volatile private var handle = 0L
    @Volatile private var lastResult = "none"
    private val reachable = ConcurrentHashMap<String, Long>()
    private val exchanging = ReentrantLock()

    private fun request(op: String, fields: JsonObjectBuilder.() -> Unit = {}) =
        buildJsonObject { put("op", op); fields() }.toString()

    /** A native operation from a transport thread; null on any refusal. */
    private fun value(op: String, fields: JsonObjectBuilder.() -> Unit = {}): JsonElement? {
        val id = handle.takeIf { it != 0L } ?: return null
        val response = runCatching { Json.parseToJsonElement(native.call(id, request(op, fields))).jsonObject }.getOrNull() ?: return null
        return if (response["ok"]?.jsonPrimitive?.boolean == true) response["value"] else null
    }

    private fun seen(value: JsonElement?) {
        val now = clock()
        value?.jsonObject?.get("peers")?.jsonArray?.forEach { reachable[it.jsonPrimitive.content] = now }
    }

    /** What every transport serves to nearby devices. */
    val server = object : ShareExchangeServer {
        override fun hello(): ShareHello? = value("hello")?.let(ShareHello::from)

        override fun exchange(request: ByteArray, maxBytes: Int): ByteArray? {
            val value = value("exchange_serve") {
                put("container", Base64.getEncoder().encodeToString(request))
                put("max_bytes", maxBytes)
            } ?: return null
            seen(value)
            return runCatching { Base64.getDecoder().decode(value.jsonObject["container"]!!.jsonPrimitive.content) }.getOrNull()
        }
    }

    override fun call(handle: Long, command: String): String {
        this.handle = handle
        val op = runCatching { Json.parseToJsonElement(command).jsonObject["op"]?.jsonPrimitive?.content }.getOrNull()
        val response = native.call(handle, command)
        if (!ok(response)) return response
        return when (op) {
            "set_online" -> {
                val online = runCatching { Json.parseToJsonElement(command).jsonObject["online"]!!.jsonPrimitive.boolean }.getOrDefault(false)
                val meet = online && direct()
                transports.forEach { t -> runCatching { if (meet && !t.running) t.start(server) else if (!meet) t.stop() } }
                this.online = online
                if (online) poll() else poller = null
                response
            }
            "sync" -> {
                if (transports.any { it.running }) exchangeAll()
                if (online) runCatching { mailboxes?.pass(mailboxNative) }
                response
            }
            "status" -> status(response)
            else -> response
        }
    }

    override fun close(handle: Long) {
        poller = null
        transports.forEach { runCatching { it.stop() } }
        if (this.handle == handle) this.handle = 0
        native.close(handle)
    }

    @Synchronized private fun poll() {
        if (pollMs <= 0 || poller != null) return
        poller = kotlin.concurrent.thread(isDaemon = true, name = "share-reach") {
            val me = Thread.currentThread()
            while (poller === me) {
                runCatching { Thread.sleep(pollMs) }
                if (poller === me && transports.any { it.running }) runCatching { exchangeAll() }
                // Fetching is paced inside; this keeps an open app close to live.
                if (poller === me && online) runCatching { mailboxes?.pass(mailboxNative) }
            }
        }
    }

    private fun ok(response: String) =
        runCatching { Json.parseToJsonElement(response).jsonObject["ok"]!!.jsonPrimitive.boolean }.getOrDefault(false)

    /** Exchange with every endpoint in reach, bounded in time; one pass at a time. */
    private fun exchangeAll() {
        if (!exchanging.tryLock()) return
        try {
            val deadline = clock() + budgetMs
            val own = server.hello()?.beacons.orEmpty().toSet()
            for (transport in transports.filter { it.running }) {
                for (endpoint in runCatching { transport.endpoints() }.getOrDefault(emptyList())) {
                    if (clock() > deadline) return
                    val hello = endpoint.hello ?: continue
                    if (hello.beacons.any { it in own }) continue // our own advertisement
                    val peer = value("identify") { put("beacons", buildJsonArray { hello.beacons.forEach { add(JsonPrimitive(it)) } }) }
                        ?.jsonObject?.get("peer")?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
                    if (peer == null) continue
                    reachable[peer] = clock()
                    steps(transport, endpoint, peer, deadline)
                }
            }
        } finally {
            exchanging.unlock()
        }
    }

    private fun steps(transport: ShareTransport, endpoint: ShareEndpoint, peer: String, deadline: Long) {
        repeat(MAX_STEPS) {
            if (clock() > deadline) return
            val begin = value("exchange_begin") {
                put("peer", peer)
                put("max_bytes", transport.maxContainer)
            } ?: return
            val container = begin.jsonObject["container"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content ?: return
            val response = runCatching { transport.exchange(endpoint, Base64.getDecoder().decode(container)) }.getOrNull()
            if (response == null) {
                // Not in reach any more: its queue no longer counts as deliverable.
                lastResult = "unavailable"
                reachable.remove(peer)
                return
            }
            lastResult = "accepted"
            val end = value("exchange_end") {
                put("container", Base64.getEncoder().encodeToString(response))
                put("max_bytes", transport.maxContainer)
            } ?: return
            seen(end)
            if (end.jsonObject["more"]?.jsonPrimitive?.boolean != true) return
        }
    }

    /** v2's relay projection carries the transports; pending counts friends in reach. */
    private fun status(response: String): String = runCatching {
        val root = Json.parseToJsonElement(response).jsonObject
        val value = root["value"]!!.jsonObject
        val now = clock()
        val deliverable = value["peers"]!!.jsonArray.sumOf { p ->
            val peer = p.jsonObject
            val near = (reachable[peer["account"]!!.jsonPrimitive.content] ?: 0) > now - REACHABLE_MS
            if (near) peer["outbound_pending"]!!.jsonPrimitive.long else 0L
        }
        val queued = mailboxes?.pending()?.toLong() ?: 0L
        val local = JsonObject(value["local"]!!.jsonObject + ("outbound_pending" to JsonPrimitive(deliverable + queued)))
        val relays = buildJsonArray {
            mailboxes?.states()?.forEach { m ->
                add(buildJsonObject {
                    put("url", m.url)
                    put("connection", if (m.connected) "connected" else "offline")
                    put("last_result", m.lastResult)
                    put("nip77", false)
                })
            }
            transports.forEach { t ->
                add(buildJsonObject {
                    put("url", "p2p:${t.name}")
                    put("connection", if (t.running) "connected" else "offline")
                    put("last_result", lastResult)
                    put("nip77", false)
                })
            }
        }
        JsonObject(root + ("value" to JsonObject(value + ("local" to local) + ("relays" to relays)))).toString()
    }.getOrDefault(response)

    companion object {
        const val MAX_STEPS = 16
        /** A friend counts as "in reach" this long after the last exchange. */
        const val REACHABLE_MS = 60_000L
    }
}
