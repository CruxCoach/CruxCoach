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
 *  the root under), otherwise the wire id recovered from the raw e-tags of
 *  the thread's replies (anchor wiped by a recovery migration), otherwise
 *  the local root id as a last resort.
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
     * before it is stored.
     *
     * Resolution order:
     *  1. the raw wire e-tag (recipient-wrap id of an own root, or — on
     *     dev replies — whatever id the dashboard knows the root under),
     *  2. the self-root hint tag own replies carry (the LOCAL root id,
     *     see [NostrConfig.RUMOR_TAG_SELF_ROOT]),
     *  3. unresolved fallback: prefer the hint — it IS the local root id,
     *     so the row threads correctly as soon as the root row (re)appears
     *     under that id — otherwise the raw id, preserved for getThread's
     *     anchor-id clause.
     */
    fun normalizeReplyToId(
        rawReplyToId: String?,
        selfRootHint: String? = null,
        lookup: (String) -> ThreadMemberRef?
    ): String? {
        if (rawReplyToId == null && selfRootHint == null) return null
        rawReplyToId?.let { resolveLocalRootId(it, lookup) }?.let { return it }
        selfRootHint?.let { resolveLocalRootId(it, lookup) }?.let { return it }
        return selfRootHint ?: rawReplyToId
    }

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
     * @param wireIdFromReplies recovers the root's wire (recipient-wrap) id
     *  from the raw e-tags its replies arrived with, for roots whose
     *  thread_anchor_id was wiped by a recovery migration / reinstall.
     */
    fun resolveReplyContext(
        rootId: String,
        lookup: (String) -> ThreadMemberRef?,
        getById: (String) -> ThreadMemberRef?,
        latestThreadMember: (String) -> ThreadMemberRef?,
        wireIdFromReplies: (String) -> String? = { null }
    ): ReplyContext {
        val localRootId = resolveLocalRootId(rootId, lookup) ?: rootId
        val rootRow = getById(localRootId)
        val typeLabel = rootRow?.type ?: latestThreadMember(localRootId)?.type
        return ReplyContext(
            localRootId = localRootId,
            wireReplyToId = rootRow?.threadAnchorId
                ?: wireIdFromReplies(localRootId)
                ?: localRootId,
            typeLabel = typeLabel
        )
    }
}
