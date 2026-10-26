package com.cruxcoach.android.sharing

import com.cruxcoach.domain.sharing.ShareCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * What one direction covers on the wire: categories (sorted) and the cutoff of
 * the dated categories (ISO date; `null` = all history or none in scope).
 */
@Serializable data class ShareScope(
    val categories: List<ShareCategory>,
    val cutoff: String? = null,
) {
    fun asSourceScope() = SharingScope(categories.toSet(), cutoff ?: ALL_HISTORY)

    companion object {
        const val ALL_HISTORY = "1970-01-01"
        val EMPTY = ShareScope(emptyList(), null)
    }
}

/**
 * The application message of sharing v3 (one Olm message on the direct
 * channel and, later, in a mailbox). One shape for every type keeps the
 * canonical encoding simple; [ShareProtocol.decode] rejects every field a
 * type does not use.
 *
 * The owner syncs per stream: [stream] names it (random, 32
 * hex) and [assign] says who a message is for. `0` goes to every member of the
 * stream; `n > 0` is the n-th snapshot handed to this one friend, and a higher
 * one replaces everything the friend holds from the owner.
 *
 * - `manifest` starts a generation, a snapshot at (gen, seq), or is a
 *   heartbeat: scope, count, digest.
 *
 * `manifest` and `delta` carry [at], the owner's clock to the minute when
 * that state was current: the friend shows "complete as of" from it.
 * - `full` carries one page of that complete state.
 * - `delta` carries upserts/deletes from sequence `seq - 1` to `seq`.
 * - `ack` confirms the receiver's state (stream, assign, generation, sequence, digest).
 * - `resync` asks the owner for a fresh snapshot.
 * - `end` ends the friendship; both sides delete what they received.
 */
@Serializable data class ShareMessage(
    val v: Int = ShareProtocol.VERSION,
    val type: String,
    val stream: String = "",
    val assign: Long = 0,
    val gen: Long = 0,
    val seq: Long = 0,
    val at: Long = 0,
    val part: Int = 0,
    val parts: Int = 0,
    val scope: ShareScope? = null,
    val count: Int = 0,
    val digest: String = "",
    val records: List<SharingRecord> = emptyList(),
    val deletes: List<String> = emptyList(),
)

object ShareProtocol {
    /** 3: categories of FEAT-073 (sends and attempts apart), per-record expiry and streams. */
    const val VERSION = 3
    const val MAX_BYTES = 49_152
    const val MAX_PAGE_RECORDS = 16
    const val MAX_PARTS = 128
    const val MAX_DELTA_UPSERTS = 64
    const val MAX_DELTA_DELETES = 256
    private const val MAX_PENDING_REFERENCE = SharingRecords.MAX_RECORDS

    val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; explicitNulls = false }
    private val hex64 = Regex("[a-f0-9]{64}")
    private val hex32 = Regex("[a-f0-9]{32}")
    private val date = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")

    fun encode(message: ShareMessage): String = json.encodeToString(message)

    fun valid(scope: ShareScope): Boolean =
        scope.categories == scope.categories.distinct().sorted() &&
            SharingRecords.supported.containsAll(scope.categories) &&
            (scope.cutoff == null || (date.matches(scope.cutoff) &&
                runCatching { java.time.LocalDate.parse(scope.cutoff).toString() == scope.cutoff }.getOrDefault(false))) &&
            (scope.cutoff == null || scope.categories.any { it.dated })

    private fun validShape(m: ShareMessage): Boolean = m.v == VERSION && when (m.type) {
        "manifest" -> hex32.matches(m.stream) && m.assign >= 0 && m.gen >= 1 && m.seq >= 0 && minute(m.at) &&
            m.scope != null && valid(m.scope) &&
            m.count in 0..SharingRecords.MAX_RECORDS && hex64.matches(m.digest) &&
            m.records.isEmpty() && m.deletes.isEmpty() && m.part == 0 && m.parts == 0
        // A generation for the whole stream starts at sequence 0; a snapshot stands anywhere.
        "full" -> hex32.matches(m.stream) && m.assign >= 0 && m.gen >= 1 && m.seq >= 0 && (m.assign > 0 || m.seq == 0L) &&
            m.parts in 1..MAX_PARTS && m.part in 0 until m.parts &&
            m.records.size <= MAX_PAGE_RECORDS && m.records.map { it.id }.distinct().size == m.records.size &&
            m.scope == null && m.deletes.isEmpty() && m.count == 0 && m.digest.isEmpty() && m.at == 0L
        "delta" -> hex32.matches(m.stream) && m.assign == 0L && m.gen >= 1 && m.seq >= 1 && minute(m.at) &&
            m.scope != null && valid(m.scope) &&
            m.records.size <= MAX_DELTA_UPSERTS && m.deletes.size <= MAX_DELTA_DELETES &&
            (m.records.map { it.id } + m.deletes).let { it.distinct().size == it.size } &&
            m.deletes.all { it.length in 1..256 } &&
            m.count in 0..MAX_PENDING_REFERENCE && hex64.matches(m.digest) && m.part == 0 && m.parts == 0
        "ack" -> hex32.matches(m.stream) && m.assign >= 1 && m.gen >= 1 && m.seq >= 0 && m.at == 0L && hex64.matches(m.digest) && m.scope == null &&
            m.records.isEmpty() && m.deletes.isEmpty() && m.count == 0 && m.part == 0 && m.parts == 0
        // Sent by a friend who may hold no stream yet.
        "resync" -> (m.stream.isEmpty() || hex32.matches(m.stream)) && m.assign >= 0 && m.gen >= 0 && m.seq >= -1 && m.at == 0L &&
            m.scope == null && m.records.isEmpty() && m.deletes.isEmpty() &&
            m.digest.isEmpty() && m.count == 0 && m.part == 0 && m.parts == 0
        "end" -> m == ShareMessage(type = "end")
        else -> false
    }

    /** A time on the wire: whole minutes, never seconds (what a friend learns about our activity). */
    private fun minute(at: Long) = at >= 0 && at % 60_000 == 0L

    /** [now] cut to the minute, as [ShareMessage.at] carries it. */
    fun minuteOf(now: Long): Long = now - now % 60_000

    /** Strict and canonical: re-encoding must reproduce the exact input. */
    fun decode(raw: String): ShareMessage? {
        if (raw.length > MAX_BYTES) return null
        val message = runCatching { json.decodeFromString<ShareMessage>(raw) }.getOrNull() ?: return null
        return message.takeIf { validShape(it) && encode(it) == raw }
    }

    /** Start of a generation (`seq` 0) or of a snapshot (`assign` > 0), or a heartbeat. */
    fun manifest(stream: String, assign: Long, gen: Long, seq: Long, scope: ShareScope, records: Collection<SharingRecord>, at: Long = 0) =
        ShareMessage(type = "manifest", stream = stream, assign = assign, gen = gen, seq = seq, at = minuteOf(at), scope = scope,
            count = records.size, digest = SharingRecords.digest(records))

    /** Pages of at most [MAX_PAGE_RECORDS] records and [MAX_BYTES]; at least one page. */
    fun full(stream: String, assign: Long, gen: Long, seq: Long, records: List<SharingRecord>): List<ShareMessage> {
        val pages = mutableListOf<List<SharingRecord>>()
        var current = mutableListOf<SharingRecord>()
        fun size(page: List<SharingRecord>) = encode(ShareMessage(type = "full", stream = stream, assign = assign, gen = gen,
            seq = seq, part = MAX_PARTS - 1, parts = MAX_PARTS, records = page)).length
        for (record in records.sortedBy { it.id }) {
            if (current.size == MAX_PAGE_RECORDS || current.isNotEmpty() && size(current + record) > MAX_BYTES - 256) {
                pages += current
                current = mutableListOf()
            }
            current += record
        }
        if (current.isNotEmpty() || pages.isEmpty()) pages += current
        require(pages.size <= MAX_PARTS) { "share_scope_limit" }
        return pages.mapIndexed { index, page ->
            ShareMessage(type = "full", stream = stream, assign = assign, gen = gen, seq = seq, part = index, parts = pages.size, records = page)
        }
    }

    fun delta(stream: String, gen: Long, seq: Long, scope: ShareScope, upserts: List<SharingRecord>, deletes: List<String>,
        target: Collection<SharingRecord>, at: Long = 0) =
        ShareMessage(type = "delta", stream = stream, gen = gen, seq = seq, at = minuteOf(at), scope = scope, records = upserts.sortedBy { it.id },
            deletes = deletes.sorted(), count = target.size, digest = SharingRecords.digest(target))

    fun fits(message: ShareMessage) = encode(message).length <= MAX_BYTES
}
