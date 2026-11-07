package com.cruxcoach.communityclient

/**
 * In-memory state of one room's message list: rows by id, the aux events that change
 * them, and reply stats. Pure and synchronous; [CommunityClient] feeds it from the
 * window query and the live subscription.
 */
class TimelineBuffer(private val channelId: String, private val topLevelOnly: Boolean) {
    private val rows = LinkedHashMap<String, CommunityEvent>()
    private val aux = LinkedHashMap<String, CommunityEvent>()
    private val summaries = HashMap<String, ThreadSummary>()
    /** Replies seen live, per thread head: id → message. */
    private val liveReplies = HashMap<String, LinkedHashMap<String, CommunityMessage>>()

    /** Adds stored or live events. Returns true if anything visible may have changed. */
    fun add(events: Collection<CommunityEvent>): Boolean {
        var changed = false
        for (event in events) {
            if (event.channelId != null && event.channelId != channelId && event.kind != CommunityKinds.REACTION) continue
            when {
                event.kind in CommunityKinds.AUX -> if (aux.put(event.id, event) == null) changed = true
                event.kind == CommunityKinds.THREAD_SUMMARY -> ThreadSummary.fromEvent(event)?.let { summaries[it.rootId] = it; changed = true }
                CommunityMessage.fromEvent(event) != null -> changed = addRow(event) || changed
            }
        }
        return changed
    }

    fun addSummaries(list: Collection<ThreadSummary>) {
        list.forEach { summaries[it.rootId] = it }
    }

    private fun addRow(event: CommunityEvent): Boolean {
        val message = CommunityMessage.fromEvent(event) ?: return false
        if (topLevelOnly && !message.isTopLevel) {
            // A reply seen live: count it on its thread head.
            val root = message.thread!!.rootId
            return liveReplies.getOrPut(root) { LinkedHashMap() }.put(message.id, message) == null
        }
        return rows.put(event.id, event) == null
    }

    /** Rows oldest first, with edits, deletions and reactions applied. */
    fun messages(): List<CommunityMessage> {
        val list = rows.values.mapNotNull { CommunityMessage.fromEvent(it) }
            .sortedWith(compareBy<CommunityMessage> { it.createdAt }.thenBy { it.id })
        return applyAux(list, aux.values)
    }

    /**
     * Reply stats per thread head: the relay's summary plus replies that arrived live
     * after it. A live reply no newer than the summary's last reply is already counted.
     */
    fun threadSummaries(): Map<String, ThreadSummary> {
        val keys = summaries.keys + liveReplies.keys
        return keys.associateWith { root ->
            val base = summaries[root]
            val newer = liveReplies[root]?.values.orEmpty()
                .filter { base?.lastReplyAt == null || it.createdAt > base.lastReplyAt }
                .sortedByDescending { it.createdAt }
            val directNew = newer.count { it.thread?.parentId == root }
            ThreadSummary(
                rootId = root,
                replyCount = (base?.replyCount ?: 0) + directNew,
                descendantCount = (base?.descendantCount ?: 0) + newer.size,
                lastReplyAt = listOfNotNull(base?.lastReplyAt, newer.firstOrNull()?.createdAt).maxOrNull(),
                participants = (newer.map { it.author } + base?.participants.orEmpty()).distinct().take(10),
            )
        }
    }

    val size: Int get() = rows.size

    /** Oldest row time, for a timestamp-only fallback page. */
    fun oldestCreatedAt(): Long? = rows.values.minOfOrNull { it.createdAt }
}

/** What a stream room screen shows. */
data class StreamTimeline(
    val messages: List<CommunityMessage> = emptyList(),
    val threads: Map<String, ThreadSummary> = emptyMap(),
    val loading: Boolean = true,
    val hasMore: Boolean = false,
    val error: String? = null,
)

/** What a thread screen shows: the head and its replies, oldest first. */
data class ThreadView(
    val root: CommunityMessage? = null,
    val replies: List<CommunityMessage> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

/** A forum room: topics newest first, with their comment counts. */
data class ForumView(
    val posts: List<CommunityMessage> = emptyList(),
    val commentCounts: Map<String, Int> = emptyMap(),
    val loading: Boolean = true,
    val error: String? = null,
)
