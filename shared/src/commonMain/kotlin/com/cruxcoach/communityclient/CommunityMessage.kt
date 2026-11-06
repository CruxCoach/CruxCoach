package com.cruxcoach.communityclient

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Where a message sits in a thread, read from NIP-10 marked `e` tags.
 *
 * Buzz writes a direct reply to a thread head as `["e", head, "", "reply"]` and a
 * nested reply as `["e", root, "", "root"]` + `["e", parent, "", "reply"]`.
 * An event without a `reply`-marked `e` tag is not a reply (NIP-CW).
 */
data class ThreadRef(val rootId: String, val parentId: String) {
    companion object {
        fun of(event: CommunityEvent): ThreadRef? {
            val marked = event.tags.filter { it.size >= 4 && it[0] == "e" && it[1].isHex(64) }
            val parent = marked.firstOrNull { it[3] == "reply" }?.get(1) ?: return null
            val root = marked.firstOrNull { it[3] == "root" }?.get(1) ?: parent
            return ThreadRef(rootId = root, parentId = parent)
        }
    }
}

/** A message row in a stream room, a forum post or a reply. */
data class CommunityMessage(
    val id: String,
    val channelId: String,
    val author: String,
    val createdAt: Long,
    val kind: Int,
    val content: String,
    val thread: ThreadRef?,
    /** A reply that its author also showed in the channel (`["broadcast", "1"]`). */
    val isBroadcast: Boolean,
    val mentions: List<String>,
    /** Content of the newest edit by the author, if any. */
    val editedContent: String? = null,
    val isDeleted: Boolean = false,
    /** Emoji → authors who reacted with it. */
    val reactions: Map<String, Set<String>> = emptyMap(),
) {
    val isReply: Boolean get() = thread != null
    val displayContent: String get() = editedContent ?: content

    /** A top-level row in the channel timeline (NIP-CW: depth 0, or a broadcast reply). */
    val isTopLevel: Boolean get() = thread == null || isBroadcast

    companion object {
        private val MESSAGE_KINDS = setOf(
            CommunityKinds.STREAM_MESSAGE,
            CommunityKinds.STREAM_MESSAGE_V2,
            CommunityKinds.SYSTEM_MESSAGE,
            CommunityKinds.FORUM_POST,
            CommunityKinds.FORUM_COMMENT,
        )

        fun fromEvent(event: CommunityEvent): CommunityMessage? {
            if (event.kind !in MESSAGE_KINDS) return null
            val channelId = event.channelId ?: return null
            return CommunityMessage(
                id = event.id,
                channelId = channelId,
                author = event.pubkey,
                createdAt = event.createdAt,
                kind = event.kind,
                content = event.content,
                thread = ThreadRef.of(event),
                isBroadcast = event.tags.any { it.size >= 2 && it[0] == "broadcast" && it[1] == "1" },
                mentions = event.tagValues("p").filter { it.isHex(64) }.distinct(),
            )
        }
    }
}

/** Relay-computed reply stats for one row (kind:39005, NIP-CW). */
data class ThreadSummary(
    val rootId: String,
    val replyCount: Int,
    val descendantCount: Int,
    val lastReplyAt: Long?,
    val participants: List<String>,
) {
    companion object {
        /** Accepts only the exact NIP-CW shape: one `e`, one `d` (same id), one `h`, JSON content. */
        fun fromEvent(event: CommunityEvent): ThreadSummary? {
            if (event.kind != CommunityKinds.THREAD_SUMMARY) return null
            val e = event.tagValues("e")
            val d = event.tagValues("d")
            if (e.size != 1 || d.size != 1 || e[0] != d[0] || event.tagValues("h").size != 1) return null
            val obj = parseObject(event.content) ?: return null
            val replyCount = obj["reply_count"]?.jsonPrimitive?.longOrNull ?: return null
            val descendants = obj["descendant_count"]?.jsonPrimitive?.longOrNull ?: return null
            val lastReply = obj["last_reply_at"]?.let { if (it is JsonNull) null else it.jsonPrimitive.longOrNull }
            val participants = runCatching {
                obj["participants"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            }.getOrDefault(emptyList()).filter { it.isHex(64) }
            return ThreadSummary(e[0], replyCount.toInt(), descendants.toInt(), lastReply, participants)
        }
    }
}

/** Composite NIP-CW cursor `(created_at, id)`. */
data class WindowCursor(val createdAt: Long, val id: String) {
    /** Canonical `d`-tag suffix of the request this cursor starts. */
    val dSuffix: String get() = "$createdAt:$id"
}

/** Relay-signed exhaustion fact for one channel window page (kind:39006, NIP-CW). */
data class WindowBounds(val channelId: String, val requestCursor: WindowCursor?, val hasMore: Boolean, val nextCursor: WindowCursor?) {
    companion object {
        /**
         * Parses and checks a bounds overlay against the request it must answer.
         * Returns null when the overlay is malformed or bound to another request;
         * the page must then be discarded (NIP-CW client rule 5).
         */
        fun fromEvent(event: CommunityEvent, channelId: String, requestCursor: WindowCursor?): WindowBounds? {
            if (event.kind != CommunityKinds.WINDOW_BOUNDS) return null
            val d = event.tagValues("d")
            val h = event.tagValues("h")
            if (d.size != 1 || h.size != 1 || h[0] != channelId) return null
            val expectedD = "$channelId:" + (requestCursor?.dSuffix ?: "head")
            if (d[0] != expectedD) return null
            val obj = parseObject(event.content) ?: return null
            val hasMore = obj["has_more"]?.jsonPrimitive?.booleanOrNull ?: return null
            val next = obj["next_cursor"]
            val nextCursor = when {
                next == null || next is JsonNull -> null
                else -> {
                    val n = runCatching { next.jsonObject }.getOrNull() ?: return null
                    val ts = n["created_at"]?.jsonPrimitive?.longOrNull ?: return null
                    val id = (n["id"] as? JsonPrimitive)?.content?.takeIf { it.isHex(64) } ?: return null
                    WindowCursor(ts, id)
                }
            }
            if (hasMore != (nextCursor != null)) return null
            return WindowBounds(channelId, requestCursor, hasMore, nextCursor)
        }
    }
}

private fun parseObject(content: String): JsonObject? =
    runCatching { CommunityJson.parseToJsonElement(content).jsonObject }.getOrNull()

/**
 * Applies edits, deletions and reactions to message rows.
 *
 * - An edit (kind:40003) counts only from the message's own author; the newest wins.
 * - A deletion (kind:5 from the author, or kind:9005 which the relay only accepts
 *   from the author or a channel admin) marks the row deleted.
 * - A reaction (kind:7) targets the last `e` tag; a deleted reaction is dropped.
 */
fun applyAux(rows: Collection<CommunityMessage>, aux: Collection<CommunityEvent>): List<CommunityMessage> {
    val byId = rows.associateBy { it.id }
    val deleted = HashSet<String>()
    for (event in aux) {
        when (event.kind) {
            CommunityKinds.GROUP_DELETE_EVENT -> deleted += event.tagValues("e")
            CommunityKinds.DELETION -> event.tagValues("e").forEach { target ->
                val row = byId[target]
                // NIP-09: only the author's own deletion counts. Unknown targets may be reactions.
                if (row == null || row.author == event.pubkey) deleted += target
            }
        }
    }
    val edits = HashMap<String, CommunityEvent>()
    val reactions = HashMap<String, MutableMap<String, MutableSet<String>>>()
    for (event in aux) {
        if (event.id in deleted) continue
        when (event.kind) {
            CommunityKinds.STREAM_MESSAGE_EDIT -> {
                val target = event.tagValues("e").lastOrNull() ?: continue
                val row = byId[target] ?: continue
                if (row.author != event.pubkey) continue
                val previous = edits[target]
                if (previous == null || event.createdAt >= previous.createdAt) edits[target] = event
            }
            CommunityKinds.REACTION -> {
                val target = event.tagValues("e").lastOrNull() ?: continue
                if (target !in byId) continue
                val emoji = event.content.trim().ifEmpty { "+" }
                reactions.getOrPut(target) { HashMap() }.getOrPut(emoji) { HashSet() } += event.pubkey
            }
        }
    }
    return rows.map { row ->
        row.copy(
            editedContent = edits[row.id]?.content ?: row.editedContent,
            isDeleted = row.isDeleted || row.id in deleted,
            reactions = reactions[row.id]?.mapValues { it.value.toSet() } ?: row.reactions,
        )
    }
}
