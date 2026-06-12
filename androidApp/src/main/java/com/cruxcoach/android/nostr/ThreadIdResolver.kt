package com.cruxcoach.android.nostr

/**
 * Minimal projection of a stored message row used for thread-id resolution.
 *
 * @property id Local row id (the NIP-17 SELF-wrap id for own messages,
 *  the received wrap id for dev messages).
 * @property replyToId Local id of the thread root this row replies to,
 *  or null when the row IS a root.
 * @property threadAnchorId The RECIPIENT-wrap id of an own message — the
 *  id under which the other side (dashboard) knows this message.
 * @property type Message type label (chat | bug-report | …), when known.
 */
data class ThreadMemberRef(
    val id: String,
    val replyToId: String?,
    val threadAnchorId: String? = null,
    val type: String? = null
)

/**
 * Everything an outgoing reply needs to stay in the right thread on BOTH
 * sides of the NIP-17 wrap asymmetry.
 *
 * @property localRootId Id of the local thread-root row. Used for the
 *  local insert's reply_to_id so getThread keeps matching.
 * @property wireReplyToId Id to put in the outgoing ["e", …, "reply"] tag:
 *  the root's thread_anchor_id when present (the id the dashboard stored
 *  the root under), otherwise the local root id.
 * @property typeLabel Resolved thread type label, or null when neither the
 *  root row nor any thread member could provide one (caller should fall
 *  back to chat — loudly, not silently).
 */
data class ReplyContext(
    val localRootId: String,
    val wireReplyToId: String,
    val typeLabel: String?
)

/**
 * Pure thread-id resolution across the NIP-17 wrap asymmetry.
 *
 * Every NIP-17 message exists under TWO event ids: the self-wrap id (our
 * local row id) and the recipient-wrap id (what the dashboard stored).
 * Incoming reply references and notification deep-links can carry either,
 * so all of them are normalized to the LOCAL root id here. Logic is kept
 * lookup-agnostic (lambdas) because the secure DB is not plain-JVM
 * testable — tests drive these functions with map-backed fakes.
 */
object ThreadIdResolver {

    /**
     * Resolves an arbitrary event id — local row id OR recipient-wrap
     * thread_anchor_id — to the LOCAL id of its thread root. Threads are
     * flat (every reply points directly at the root), so a single hop
     * through [ThreadMemberRef.replyToId] reaches the root.
     *
     * @param lookup matches a row by `id == eventId OR thread_anchor_id == eventId`.
     * @return the local root id, or null when the id matches no local row.
     */
    fun resolveLocalRootId(
        eventId: String,
        lookup: (String) -> ThreadMemberRef?
    ): String? {
        val row = lookup(eventId) ?: return null
        return row.replyToId ?: row.id
    }

    /**
     * Normalizes an incoming reply reference to the local thread-root id
     * before it is stored. Falls back to the raw id when it doesn't
     * resolve (e.g. the root hasn't been ingested yet) so the reference
     * is at least preserved for getThread's anchor-id clause.
     */
    fun normalizeReplyToId(
        rawReplyToId: String?,
        lookup: (String) -> ThreadMemberRef?
    ): String? = rawReplyToId?.let { resolveLocalRootId(it, lookup) ?: it }

    /**
     * Resolves everything an outgoing reply needs from a (possibly stale
     * or foreign) root reference: the local root id, the wire e-tag id,
     * and the thread type. Type comes from the root row; when the root
     * row is missing, it is inherited from the latest thread member.
     *
     * @param lookup matches a row by `id == eventId OR thread_anchor_id == eventId`.
     * @param getById exact local row lookup (carries anchor id + type).
     * @param latestThreadMember newest member of the thread for [getThread]-style
     *  semantics, used as the type fallback when the root row is absent.
     */
    fun resolveReplyContext(
        rootId: String,
        lookup: (String) -> ThreadMemberRef?,
        getById: (String) -> ThreadMemberRef?,
        latestThreadMember: (String) -> ThreadMemberRef?
    ): ReplyContext {
        val localRootId = resolveLocalRootId(rootId, lookup) ?: rootId
        val rootRow = getById(localRootId)
        val typeLabel = rootRow?.type ?: latestThreadMember(localRootId)?.type
        return ReplyContext(
            localRootId = localRootId,
            wireReplyToId = rootRow?.threadAnchorId ?: localRootId,
            typeLabel = typeLabel
        )
    }
}
