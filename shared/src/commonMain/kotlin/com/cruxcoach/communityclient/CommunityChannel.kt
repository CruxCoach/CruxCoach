package com.cruxcoach.communityclient

/** The channel types the relay returns to a client that does not ask for others (NIP-CL legacy types). */
enum class ChannelType(val wire: String) {
    STREAM("stream"),
    FORUM("forum"),
    DM("dm"),
    WORKFLOW("workflow"),
    OTHER("");

    companion object {
        fun fromWire(value: String?): ChannelType = entries.firstOrNull { it.wire == value && it != OTHER } ?: OTHER
    }
}

/**
 * A room as the relay describes it in its signed kind:39000 group metadata.
 *
 * Tag rules follow NIP-29 and Buzz's NIP-CL: the first `t` tag is the channel type and later
 * `t` tags are labels; `public`/`private`, `restricted`, `closed` and `hidden` are flags;
 * `join_role` says what joining gives (`guest` means read-only).
 */
data class CommunityChannel(
    val id: String,
    val name: String,
    val type: ChannelType,
    val about: String,
    val topic: String?,
    val isPrivate: Boolean,
    val isHidden: Boolean,
    val isArchived: Boolean,
    /** `member` or `guest`; null when the relay did not say. */
    val joinRole: String?,
    val labels: List<String>,
    val creator: String?,
    /** Creation time from the `created_at` tag; the event time is the last edit. */
    val createdAt: Long?,
    /** Event time of the metadata revision, used to keep the newest one. */
    val revisionAt: Long,
) {
    /** People who join get the guest role and cannot write. */
    val isReadOnlyOnJoin: Boolean get() = joinRole == "guest"

    companion object {
        fun fromEvent(event: CommunityEvent): CommunityChannel? {
            if (event.kind != CommunityKinds.GROUP_METADATA) return null
            val id = event.tagValue("d")?.takeIf { it.isNotBlank() } ?: return null
            val tValues = event.tagValues("t")
            val hasPublic = event.hasTag("public")
            val hasPrivate = event.hasTag("private")
            return CommunityChannel(
                id = id,
                name = event.tagValue("name").orEmpty().ifBlank { id.take(8) },
                type = ChannelType.fromWire(tValues.firstOrNull()),
                about = event.tagValue("about").orEmpty(),
                topic = event.tagValue("topic"),
                // NIP-29: no `private` means public; an explicit `public` wins.
                isPrivate = hasPrivate && !hasPublic,
                isHidden = event.hasTag("hidden"),
                isArchived = event.tagValue("archived") == "true",
                joinRole = event.tagValue("join_role"),
                labels = tValues.drop(1),
                creator = event.tagValue("P"),
                createdAt = event.tagValue("created_at")?.toLongOrNull(),
                revisionAt = event.createdAt,
            )
        }
    }
}

/** A member entry from a relay-signed kind:39002 member list. */
data class ChannelMember(val pubkey: String, val role: String?)

/** Parsed kind:39002 member list of one channel. */
data class ChannelMembers(val channelId: String, val members: List<ChannelMember>, val revisionAt: Long) {
    fun contains(pubkey: String): Boolean = members.any { it.pubkey == pubkey }
    fun roleOf(pubkey: String): String? = members.firstOrNull { it.pubkey == pubkey }?.role

    companion object {
        fun fromEvent(event: CommunityEvent): ChannelMembers? {
            if (event.kind != CommunityKinds.GROUP_MEMBERS) return null
            val id = event.tagValue("d")?.takeIf { it.isNotBlank() } ?: return null
            // NIP-29 shape: ["p", <pubkey>, <relay url, empty>, <role>].
            val members = event.tags
                .filter { it.size >= 2 && it[0] == "p" && it[1].isHex(64) }
                .map { ChannelMember(it[1], it.getOrNull(3)?.takeIf { role -> role.isNotBlank() }) }
                .distinctBy { it.pubkey }
            return ChannelMembers(id, members, event.createdAt)
        }
    }
}

/** One row of the room list. */
data class RoomListItem(
    val channel: CommunityChannel,
    val isMember: Boolean,
    /** Time of the newest message we know of, for sorting. */
    val lastActivityAt: Long?,
    /** My role from the relay's member list (`owner`, `admin`, `member`, `guest`, …). */
    val myRole: String? = null,
) {
    /** Members can write unless they joined as a guest (announce rooms). */
    val canWrite: Boolean get() = isMember && myRole != "guest"
}

/**
 * Builds the room list from the newest metadata per channel and the set of
 * channels the user belongs to. Archived and hidden rooms and DMs are left out
 * of the spike's list; rooms are grouped streams first, then forums, each by
 * activity and then name.
 */
fun buildRoomList(
    channels: Collection<CommunityChannel>,
    memberChannelIds: Set<String>,
    lastActivity: Map<String, Long>,
    myRoles: Map<String, String?> = emptyMap(),
): List<RoomListItem> = channels
    .groupBy { it.id }
    .map { (_, revisions) -> revisions.maxBy { it.revisionAt } }
    .filter { !it.isArchived && it.type != ChannelType.DM && it.type != ChannelType.OTHER }
    // A private or hidden room shows only to members; the relay already filters, this keeps it so.
    .filter { it.id in memberChannelIds || (!it.isPrivate && !it.isHidden) }
    .map { RoomListItem(it, it.id in memberChannelIds, lastActivity[it.id], myRoles[it.id]) }
    .sortedWith(
        compareBy<RoomListItem> { roomTypeOrder(it.channel.type) }
            .thenByDescending { it.isMember }
            .thenByDescending { it.lastActivityAt ?: 0L }
            .thenBy { it.channel.name.lowercase() },
    )

private fun roomTypeOrder(type: ChannelType): Int = when (type) {
    ChannelType.STREAM -> 0
    ChannelType.FORUM -> 1
    ChannelType.WORKFLOW -> 2
    else -> 3
}
