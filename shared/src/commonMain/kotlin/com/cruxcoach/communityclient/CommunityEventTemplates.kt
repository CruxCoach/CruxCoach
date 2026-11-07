package com.cruxcoach.communityclient

/**
 * Builds the unsigned events the client sends. Tag shapes match what the Buzz
 * apps write at the pinned version, so the result looks right there.
 */
object CommunityEventTemplates {

    /** A stream message (kind:9), optionally a thread reply. */
    fun streamMessage(
        channelId: String,
        content: String,
        createdAt: Long,
        replyTo: ThreadRef? = null,
        mentions: List<String> = emptyList(),
    ): UnsignedEvent = UnsignedEvent(
        createdAt = createdAt,
        kind = CommunityKinds.STREAM_MESSAGE,
        tags = buildList {
            add(listOf("h", channelId))
            if (replyTo != null) addAll(replyTags(replyTo))
            mentions.distinct().forEach { add(listOf("p", it)) }
        },
        content = content,
    )

    /** A forum post (kind:45001): a new top-level topic. */
    fun forumPost(channelId: String, content: String, createdAt: Long, mentions: List<String> = emptyList()): UnsignedEvent =
        UnsignedEvent(
            createdAt = createdAt,
            kind = CommunityKinds.FORUM_POST,
            tags = buildList {
                add(listOf("h", channelId))
                mentions.distinct().forEach { add(listOf("p", it)) }
            },
            content = content,
        )

    /** A forum comment (kind:45003) on a post. The Buzz apps tag only the direct parent. */
    fun forumComment(channelId: String, parentId: String, content: String, createdAt: Long, mentions: List<String> = emptyList()): UnsignedEvent {
        require(parentId.isHex(64)) { "not an event id: $parentId" }
        return UnsignedEvent(
            createdAt = createdAt,
            kind = CommunityKinds.FORUM_COMMENT,
            tags = buildList {
                add(listOf("h", channelId))
                add(listOf("e", parentId, "", "reply"))
                mentions.distinct().forEach { add(listOf("p", it)) }
            },
            content = content,
        )
    }

    /** Join an open room (NIP-29 kind:9021). */
    fun joinRequest(channelId: String, createdAt: Long): UnsignedEvent =
        UnsignedEvent(createdAt, CommunityKinds.GROUP_JOIN_REQUEST, listOf(listOf("h", channelId)), "")

    /** NIP-42 answer to the relay's challenge. [relayUrl] is the bare WebSocket origin. */
    fun auth(relayUrl: String, challenge: String, createdAt: Long): UnsignedEvent =
        UnsignedEvent(
            createdAt = createdAt,
            kind = CommunityKinds.AUTH,
            tags = listOf(listOf("relay", relayUrl), listOf("challenge", challenge)),
            content = "",
        )

    /**
     * NIP-98 HTTP auth for one request. Buzz requires exactly one `u` and one `method`
     * tag, and a `payload` tag (hex SHA-256 of the body) on POST routes with a body.
     */
    fun httpAuth(url: String, method: String, createdAt: Long, payloadSha256Hex: String?): UnsignedEvent =
        UnsignedEvent(
            createdAt = createdAt,
            kind = CommunityKinds.HTTP_AUTH,
            tags = buildList {
                add(listOf("u", url))
                add(listOf("method", method.uppercase()))
                if (payloadSha256Hex != null) add(listOf("payload", payloadSha256Hex))
            },
            content = "",
        )

    /**
     * Direct reply to a thread head: one `reply` tag; deeper: `root` + `reply`.
     * Refuses ids that are not event ids, which would turn the reply into a top-level message.
     */
    internal fun replyTags(ref: ThreadRef): List<List<String>> {
        require(ref.rootId.isHex(64) && ref.parentId.isHex(64)) { "not an event id: $ref" }
        return if (ref.rootId == ref.parentId) {
            listOf(listOf("e", ref.parentId, "", "reply"))
        } else {
            listOf(listOf("e", ref.rootId, "", "root"), listOf("e", ref.parentId, "", "reply"))
        }
    }
}
