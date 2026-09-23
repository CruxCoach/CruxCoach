package com.cruxcoach.android.sharing

import com.cruxcoach.domain.sharing.SharingCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * What one direction covers on the wire: categories (sorted) and the training
 * cutoff (ISO date; `null` = all history or no training in scope).
 */
@Serializable data class ShareScope(
    val categories: List<SharingCategory>,
    val cutoff: String? = null,
) {
    fun asSourceScope() = SharingScope(categories.toSet(), cutoff ?: ALL_HISTORY)

    companion object {
        const val ALL_HISTORY = "1970-01-01"
        val EMPTY = ShareScope(emptyList(), null)
    }
}

/**
 * The inner application message (MLS kind 1230, label `cc.share.v2`). One shape
 * for every type keeps the canonical encoding simple; [ShareProtocol.decode]
 * rejects every field a type does not use.
 *
 * - `manifest` starts a generation (or is a heartbeat): scope, count, digest.
 * - `full` carries one page of the generation's complete state.
 * - `delta` carries upserts/deletes from sequence `seq - 1` to `seq`.
 * - `ack` confirms the receiver's state (generation, sequence, digest).
 * - `resync` asks the owner for a new generation.
 * - `end` ends the friendship; both sides delete what they received.
 */
@Serializable data class ShareMessage(
    val v: Int = ShareProtocol.VERSION,
    val type: String,
    val gen: Long = 0,
    val seq: Long = 0,
    val part: Int = 0,
    val parts: Int = 0,
    val scope: ShareScope? = null,
    val count: Int = 0,
    val digest: String = "",
    val records: List<SharingRecord> = emptyList(),
    val deletes: List<String> = emptyList(),
)

object ShareProtocol {
    const val VERSION = 2
    const val MAX_BYTES = 49_152
    const val MAX_PAGE_RECORDS = 16
    const val MAX_PARTS = 128
    const val MAX_DELTA_UPSERTS = 64
    const val MAX_DELTA_DELETES = 256
    private const val MAX_PENDING_REFERENCE = SharingRecords.MAX_RECORDS

    val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; explicitNulls = false }
    private val hex64 = Regex("[a-f0-9]{64}")
    private val date = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")

    fun encode(message: ShareMessage): String = json.encodeToString(message)

    fun valid(scope: ShareScope): Boolean =
        scope.categories == scope.categories.distinct().sorted() &&
            SharingRecords.supported.containsAll(scope.categories) &&
            (scope.cutoff == null || (date.matches(scope.cutoff) &&
                runCatching { java.time.LocalDate.parse(scope.cutoff).toString() == scope.cutoff }.getOrDefault(false))) &&
            (scope.cutoff == null || SharingCategory.TRAINING_HISTORY in scope.categories)

    private fun validShape(m: ShareMessage): Boolean = m.v == VERSION && when (m.type) {
        "manifest" -> m.gen >= 1 && m.seq >= 0 && m.scope != null && valid(m.scope) &&
            m.count in 0..SharingRecords.MAX_RECORDS && hex64.matches(m.digest) &&
            m.records.isEmpty() && m.deletes.isEmpty() && m.part == 0 && m.parts == 0
        "full" -> m.gen >= 1 && m.seq == 0L && m.parts in 1..MAX_PARTS && m.part in 0 until m.parts &&
            m.records.size <= MAX_PAGE_RECORDS && m.records.map { it.id }.distinct().size == m.records.size &&
            m.scope == null && m.deletes.isEmpty() && m.count == 0 && m.digest.isEmpty()
        "delta" -> m.gen >= 1 && m.seq >= 1 && m.scope != null && valid(m.scope) &&
            m.records.size <= MAX_DELTA_UPSERTS && m.deletes.size <= MAX_DELTA_DELETES &&
            (m.records.map { it.id } + m.deletes).let { it.distinct().size == it.size } &&
            m.deletes.all { it.length in 1..256 } &&
            m.count in 0..MAX_PENDING_REFERENCE && hex64.matches(m.digest) && m.part == 0 && m.parts == 0
        "ack" -> m.gen >= 1 && m.seq >= 0 && hex64.matches(m.digest) && m.scope == null &&
            m.records.isEmpty() && m.deletes.isEmpty() && m.count == 0 && m.part == 0 && m.parts == 0
        "resync" -> m.gen >= 0 && m.seq >= -1 && m.scope == null && m.records.isEmpty() && m.deletes.isEmpty() &&
            m.digest.isEmpty() && m.count == 0 && m.part == 0 && m.parts == 0
        "end" -> m == ShareMessage(type = "end")
        else -> false
    }

    /** Strict and canonical: re-encoding must reproduce the exact input. */
    fun decode(raw: String): ShareMessage? {
        if (raw.length > MAX_BYTES) return null
        val message = runCatching { json.decodeFromString<ShareMessage>(raw) }.getOrNull() ?: return null
        return message.takeIf { validShape(it) && encode(it) == raw }
    }

    fun manifest(gen: Long, seq: Long, scope: ShareScope, records: Collection<SharingRecord>) =
        ShareMessage(type = "manifest", gen = gen, seq = seq, scope = scope, count = records.size, digest = SharingRecords.digest(records))

    /** Pages of at most [MAX_PAGE_RECORDS] records and [MAX_BYTES]; at least one page. */
    fun full(gen: Long, records: List<SharingRecord>): List<ShareMessage> {
        val pages = mutableListOf<List<SharingRecord>>()
        var current = mutableListOf<SharingRecord>()
        fun size(page: List<SharingRecord>) = encode(ShareMessage(type = "full", gen = gen, part = MAX_PARTS - 1, parts = MAX_PARTS, records = page)).length
        for (record in records.sortedBy { it.id }) {
            if (current.size == MAX_PAGE_RECORDS || current.isNotEmpty() && size(current + record) > MAX_BYTES - 256) {
                pages += current
                current = mutableListOf()
            }
            current += record
        }
        if (current.isNotEmpty() || pages.isEmpty()) pages += current
        require(pages.size <= MAX_PARTS) { "share_scope_limit" }
        return pages.mapIndexed { index, page -> ShareMessage(type = "full", gen = gen, part = index, parts = pages.size, records = page) }
    }

    fun delta(gen: Long, seq: Long, scope: ShareScope, upserts: List<SharingRecord>, deletes: List<String>, target: Collection<SharingRecord>) =
        ShareMessage(type = "delta", gen = gen, seq = seq, scope = scope, records = upserts.sortedBy { it.id },
            deletes = deletes.sorted(), count = target.size, digest = SharingRecords.digest(target))

    fun fits(message: ShareMessage) = encode(message).length <= MAX_BYTES
}
