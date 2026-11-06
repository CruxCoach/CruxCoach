package com.cruxcoach.communityclient

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * A NIP-01 filter, with tag filters (`#h`, `#e`, …) and relay extension fields
 * (NIP-CW `top_level`, `before_id`, …) kept as given.
 */
data class RelayFilter(
    val kinds: List<Int>? = null,
    val ids: List<String>? = null,
    val authors: List<String>? = null,
    val tags: Map<Char, List<String>> = emptyMap(),
    val since: Long? = null,
    val until: Long? = null,
    val limit: Int? = null,
    val extensions: Map<String, JsonElement> = emptyMap(),
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        kinds?.let { k -> putJsonArray("kinds") { k.forEach { add(JsonPrimitive(it)) } } }
        ids?.let { v -> putJsonArray("ids") { v.forEach { add(JsonPrimitive(it)) } } }
        authors?.let { v -> putJsonArray("authors") { v.forEach { add(JsonPrimitive(it)) } } }
        tags.forEach { (name, values) -> putJsonArray("#$name") { values.forEach { add(JsonPrimitive(it)) } } }
        since?.let { put("since", it) }
        until?.let { put("until", it) }
        limit?.let { put("limit", it) }
        extensions.forEach { (k, v) -> put(k, v) }
    }

    fun toJson(): String = toJsonObject().toString()
}

/** Frames the client sends over the WebSocket. */
object ClientFrames {
    fun req(subscriptionId: String, filters: List<RelayFilter>): String = buildJsonArray {
        add(JsonPrimitive("REQ"))
        add(JsonPrimitive(subscriptionId))
        filters.forEach { add(it.toJsonObject()) }
    }.toString()

    fun close(subscriptionId: String): String = buildJsonArray {
        add(JsonPrimitive("CLOSE"))
        add(JsonPrimitive(subscriptionId))
    }.toString()

    fun event(event: CommunityEvent): String = "[\"EVENT\",${event.toJson()}]"

    fun auth(event: CommunityEvent): String = "[\"AUTH\",${event.toJson()}]"
}

/** Frames the relay sends. Unknown or malformed frames parse to null. */
sealed interface RelayFrame {
    data class Event(val subscriptionId: String, val event: CommunityEvent) : RelayFrame
    data class Eose(val subscriptionId: String) : RelayFrame
    data class Closed(val subscriptionId: String, val reason: String) : RelayFrame
    data class Ok(val eventId: String, val accepted: Boolean, val message: String) : RelayFrame
    data class Auth(val challenge: String) : RelayFrame
    data class Notice(val message: String) : RelayFrame

    companion object {
        fun parse(text: String): RelayFrame? = runCatching {
            val arr = CommunityJson.parseToJsonElement(text).jsonArray
            when (arr[0].jsonPrimitive.content) {
                "EVENT" -> CommunityEvent.fromJson(arr[2])?.let { Event(arr[1].jsonPrimitive.content, it) }
                "EOSE" -> Eose(arr[1].jsonPrimitive.content)
                "CLOSED" -> Closed(arr[1].jsonPrimitive.content, arr.stringAt(2))
                "OK" -> Ok(arr[1].jsonPrimitive.content, arr[2].jsonPrimitive.booleanOrNull ?: false, arr.stringAt(3))
                "AUTH" -> Auth(arr[1].jsonPrimitive.content)
                "NOTICE" -> Notice(arr.stringAt(1))
                else -> null
            }
        }.getOrNull()

        private fun JsonArray.stringAt(i: Int): String =
            getOrNull(i)?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty().take(512)
    }
}

/** The machine-readable prefix of an OK/CLOSED message (NIP-01), e.g. `auth-required`. */
fun relayReasonPrefix(message: String): String = message.substringBefore(':', "").trim()

/** Filters the client uses, in one place so tests and docs can see them. */
object CommunityFilters {
    /** Channels I belong to: relay-signed member lists that name me. */
    fun myMemberships(me: String): RelayFilter =
        RelayFilter(kinds = listOf(CommunityKinds.GROUP_MEMBERS), tags = mapOf('p' to listOf(me)), limit = 500)

    /** Metadata for the given channels. */
    fun channelMetadata(ids: List<String>): RelayFilter =
        RelayFilter(kinds = listOf(CommunityKinds.GROUP_METADATA), tags = mapOf('d' to ids), limit = ids.size)

    /** Every channel the relay lets me see (open ones and mine). */
    fun channelDirectory(limit: Int = 500): RelayFilter =
        RelayFilter(kinds = listOf(CommunityKinds.GROUP_METADATA), limit = limit)

    /** Membership changes that concern me, live. */
    fun myMembershipNotices(me: String, since: Long): RelayFilter = RelayFilter(
        kinds = listOf(CommunityKinds.MEMBER_ADDED, CommunityKinds.MEMBER_REMOVED),
        tags = mapOf('p' to listOf(me)),
        since = since,
    )

    /** Live activity of one stream room: new rows plus reactions, edits and deletions. */
    fun streamLive(channelId: String, since: Long): RelayFilter = RelayFilter(
        kinds = CommunityKinds.STREAM_CONTENT + CommunityKinds.AUX,
        tags = mapOf('h' to listOf(channelId)),
        since = since,
    )

    /** Plain history of a stream room, used when the window query is not available. */
    fun streamHistory(channelId: String, limit: Int = 200, until: Long? = null): RelayFilter = RelayFilter(
        kinds = CommunityKinds.STREAM_CONTENT + CommunityKinds.AUX,
        tags = mapOf('h' to listOf(channelId)),
        until = until,
        limit = limit,
    )

    /** All replies under one thread root plus their aux events, history and live. */
    fun thread(channelId: String, rootId: String): RelayFilter = RelayFilter(
        kinds = listOf(CommunityKinds.STREAM_MESSAGE, CommunityKinds.STREAM_MESSAGE_V2, CommunityKinds.FORUM_COMMENT) + CommunityKinds.AUX,
        tags = mapOf('h' to listOf(channelId), 'e' to listOf(rootId)),
        limit = 500,
    )

    /** The head of a thread or a forum topic, scoped to its room like the Buzz apps' lookup. */
    fun threadRoot(channelId: String, rootId: String): RelayFilter = RelayFilter(
        ids = listOf(rootId),
        kinds = listOf(CommunityKinds.STREAM_MESSAGE, CommunityKinds.STREAM_MESSAGE_V2, CommunityKinds.FORUM_POST, CommunityKinds.FORUM_COMMENT),
        tags = mapOf('h' to listOf(channelId)),
        limit = 1,
    )

    /** Forum topics in one forum room, history and live. */
    fun forumPosts(channelId: String, limit: Int = 50): RelayFilter = RelayFilter(
        kinds = listOf(CommunityKinds.FORUM_POST),
        tags = mapOf('h' to listOf(channelId)),
        limit = limit,
    )

    /**
     * NIP-CW channel window: top-level rows newest first, with thread summaries,
     * aux closure and one signed bounds overlay. HTTP `POST /query` only.
     */
    fun channelWindow(channelId: String, limit: Int = 50, cursor: WindowCursor? = null): RelayFilter = RelayFilter(
        kinds = listOf(CommunityKinds.STREAM_MESSAGE, CommunityKinds.STREAM_MESSAGE_V2, CommunityKinds.SYSTEM_MESSAGE),
        tags = mapOf('h' to listOf(channelId)),
        limit = limit,
        until = cursor?.createdAt,
        extensions = buildMap {
            put("top_level", JsonPrimitive(true))
            put("include_summaries", JsonPrimitive(true))
            put("include_aux", JsonPrimitive(true))
            cursor?.let { put("before_id", JsonPrimitive(it.id)) }
        },
    )
}
