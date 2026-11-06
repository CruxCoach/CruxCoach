package com.cruxcoach.communityclient

/**
 * Event shapes taken from the Buzz relay's documentation at the pinned version
 * (NOSTR.md, docs/nips/NIP-CL.md, docs/nips/NIP-CW.md) and from the tags the Buzz
 * phone app writes (mobile/lib/features/channels/send_message_provider.dart,
 * features/forum/forum_provider.dart). Ids and signatures are placeholders: parsing
 * does not check signatures, the transport does.
 */
internal object CommunityTestEvents {
    const val CHANNEL = "9b353519-f4fe-4757-aef4-bec6cc0ae54c"
    val RELAY = "aa".repeat(32)
    val ALICE = "a1".repeat(32)
    val BOB = "b0".repeat(32)
    val SIG = "5a".repeat(64)

    fun id(n: Int): String = n.toString(16).padStart(64, '0')

    fun event(
        kind: Int,
        tags: List<List<String>>,
        content: String = "",
        pubkey: String = ALICE,
        createdAt: Long = 1_760_025_600,
        id: String = id(kind * 1000 + tags.size),
    ) = CommunityEvent(id, pubkey, createdAt, kind, tags, content, SIG)

    /** NIP-CL example kind:39000 (stream channel with one label). */
    val groupMetadata = event(
        kind = 39000,
        pubkey = RELAY,
        tags = listOf(
            listOf("d", CHANNEL),
            listOf("name", "agent-config"),
            listOf("t", "stream"),
            listOf("t", "agent-config"),
            listOf("P", ALICE),
            listOf("created_at", "1759939200"),
            listOf("public"),
            listOf("join_role", "member"),
        ),
    )

    /** NOSTR.md: an open forum channel whose joiners become guests (announce channel). */
    val forumMetadata = event(
        kind = 39000,
        pubkey = RELAY,
        id = id(2),
        tags = listOf(
            listOf("d", "1f2e3d4c-0000-4000-8000-000000000001"),
            listOf("name", "ideas-and-bugs"),
            listOf("t", "forum"),
            listOf("P", ALICE),
            listOf("created_at", "1759939300"),
            listOf("join_role", "guest"),
            listOf("public"),
            listOf("restricted"),
            listOf("about", "Ideas and bug reports"),
        ),
    )

    /** NOSTR.md: a private channel carries `private`, `restricted`, `closed`. */
    val privateMetadata = event(
        kind = 39000,
        pubkey = RELAY,
        id = id(3),
        tags = listOf(
            listOf("d", "2f2e3d4c-0000-4000-8000-000000000002"),
            listOf("name", "test-room"),
            listOf("t", "stream"),
            listOf("P", ALICE),
            listOf("created_at", "1759939400"),
            listOf("join_role", "member"),
            listOf("private"),
            listOf("restricted"),
            listOf("closed"),
        ),
    )

    /** Relay member list: ["p", pubkey, "", role] (side_effects.rs group_members_tags). */
    val groupMembers = event(
        kind = 39002,
        pubkey = RELAY,
        id = id(4),
        tags = listOf(
            listOf("d", CHANNEL),
            listOf("t", "stream"),
            listOf("P", ALICE),
            listOf("p", ALICE, "", "owner"),
            listOf("p", BOB, "", "member"),
        ),
    )

    val message = event(kind = 9, id = id(10), tags = listOf(listOf("h", CHANNEL)), content = "hello")

    /** Direct reply to a thread head: one `reply`-marked `e` tag. */
    val reply = event(kind = 9, id = id(11), pubkey = BOB, createdAt = 1_760_025_700,
        tags = listOf(listOf("h", CHANNEL), listOf("e", id(10), "", "reply")), content = "a reply")

    /** Nested reply: `root` + `reply` markers. */
    val nestedReply = event(kind = 9, id = id(12), createdAt = 1_760_025_800,
        tags = listOf(listOf("h", CHANNEL), listOf("e", id(10), "", "root"), listOf("e", id(11), "", "reply")), content = "nested")

    /** NIP-CW example kind:39005. */
    val threadSummary = event(
        kind = 39005,
        pubkey = RELAY,
        id = id(20),
        tags = listOf(listOf("e", id(10)), listOf("d", id(10)), listOf("h", CHANNEL)),
        content = """{"reply_count":4,"descendant_count":7,"last_reply_at":1751500123,"participants":["${BOB}"]}""",
    )

    /** NIP-CW example kind:39006 answering a head request. */
    fun windowBounds(dSuffix: String = "head", hasMore: Boolean = true, next: String? = """{"created_at":1751499000,"id":"${id(10)}"}""") = event(
        kind = 39006,
        pubkey = RELAY,
        id = id(21),
        tags = listOf(listOf("d", "$CHANNEL:$dSuffix"), listOf("h", CHANNEL)),
        content = """{"has_more":$hasMore,"next_cursor":${next ?: "null"}}""",
    )
}
