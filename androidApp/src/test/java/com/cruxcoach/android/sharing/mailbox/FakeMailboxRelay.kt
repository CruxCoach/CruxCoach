package com.cruxcoach.android.sharing.mailbox

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A minimal NIP-01 relay for tests: stores events, answers `REQ` by kind,
 * `#z`, `since`, `until` and `limit` (never more than [cap] per answer, like
 * real relays), applies a deletion from the same key, and can refuse, stay
 * silent or be down. Signatures are not checked here; the native side does.
 */
class FakeMailboxRelay(private val cap: Int = 500) : AutoCloseable {
    private val server = MockWebServer()
    val events = ConcurrentHashMap<String, JsonObject>()
    val filters = CopyOnWriteArrayList<JsonObject>()
    val headers = CopyOnWriteArrayList<okhttp3.Headers>()
    /** A reason refuses the event (`OK false`), null accepts it. */
    @Volatile var refuse: (JsonObject) -> String? = { null }
    /** Takes events without ever answering. */
    @Volatile var silent = false
    @Volatile var info: String? = """{"supported_nips":[1,9,11,40],"limitation":{"max_message_length":131072}}"""
    /** False: a relay that keeps everything and ignores deletions. */
    @Volatile var deletes = true
    /** Sent with every answer, whatever was asked (a hostile relay). */
    val junk = CopyOnWriteArrayList<String>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                headers += request.headers
                return if (request.getHeader("Upgrade").equals("websocket", ignoreCase = true)) {
                    MockResponse().withWebSocketUpgrade(listener())
                } else {
                    info?.let { MockResponse().setHeader("Content-Type", "application/nostr+json").setBody(it) }
                        ?: MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    val url: String get() = "ws://${server.hostName}:${server.port}/"

    /** Stop answering for good (a relay that is down). */
    fun down() = server.shutdown()

    override fun close() = runCatching { server.shutdown() }.let { }

    private fun listener() = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = Json.parseToJsonElement(text).jsonArray
            when (message[0].jsonPrimitive.content) {
                "EVENT" -> {
                    val event = message[1].jsonObject
                    if (silent) return
                    val id = event["id"]!!.jsonPrimitive.content
                    val reason = refuse(event)
                    if (reason == null) store(event)
                    webSocket.send(buildJsonArray {
                        add(JsonPrimitive("OK")); add(JsonPrimitive(id)); add(JsonPrimitive(reason == null)); add(JsonPrimitive(reason.orEmpty()))
                    }.toString())
                }
                "REQ" -> {
                    val subscription = message[1].jsonPrimitive.content
                    val filter = message[2].jsonObject
                    filters += filter
                    val limit = minOf(filter["limit"]?.jsonPrimitive?.int ?: cap, cap)
                    events.values.filter { matches(it, filter) }
                        .sortedByDescending { it["created_at"]!!.jsonPrimitive.long }
                        .take(limit)
                        .forEach { webSocket.send(buildJsonArray { add(JsonPrimitive("EVENT")); add(JsonPrimitive(subscription)); add(it) }.toString()) }
                    junk.forEach { webSocket.send("""["EVENT","$subscription",$it]""") }
                    webSocket.send(buildJsonArray { add(JsonPrimitive("EOSE")); add(JsonPrimitive(subscription)) }.toString())
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = Unit
    }

    private fun tags(event: JsonObject): List<JsonArray> = event["tags"]!!.jsonArray.map { it.jsonArray }

    private fun store(event: JsonObject) {
        if (event["kind"]!!.jsonPrimitive.int == 5 && deletes) {
            val author = event["pubkey"]!!.jsonPrimitive.content
            // NIP-09: only the author's own events go.
            tags(event).filter { it[0].jsonPrimitive.content == "e" }.map { it[1].jsonPrimitive.content }.forEach { id ->
                if (events[id]?.get("pubkey")?.jsonPrimitive?.content == author) events.remove(id)
            }
        }
        events[event["id"]!!.jsonPrimitive.content] = event
    }

    private fun matches(event: JsonObject, filter: JsonObject): Boolean {
        val kinds = filter["kinds"]?.jsonArray?.map { it.jsonPrimitive.int }
        if (kinds != null && event["kind"]!!.jsonPrimitive.int !in kinds) return false
        val created = event["created_at"]!!.jsonPrimitive.long
        filter["since"]?.jsonPrimitive?.long?.let { if (created < it) return false }
        filter["until"]?.jsonPrimitive?.long?.let { if (created > it) return false }
        val addresses = filter["#z"]?.jsonArray?.map { it.jsonPrimitive.content } ?: return true
        return tags(event).any { it[0].jsonPrimitive.content == "z" && it[1].jsonPrimitive.content in addresses }
    }
}
