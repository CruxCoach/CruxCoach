package com.cruxcoach.android.data.kilter

import com.cruxcoach.domain.board.ClimbUuid
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * One local logbook row on its way to Kilter, with the row-version snapshot
 * needed to mark it synced without clobbering a concurrent local edit.
 */
internal data class KilterUploadItem(
    val uuid: String,
    val rowVersion: Long,
    val isAscent: Boolean,
    /** Entry from an Aurora export (FEAT-005): uploaded only on opt-in. */
    val imported: Boolean,
    val log: KilterLog,
    /** [ClimbUuid.normKey] of the local climb: rows of one climb share their fate on Kilter. */
    val climbKey: String = ClimbUuid.normKey(log.climbUuid),
    /** Ids to try for the climb, in order ([KilterClimbWireIds.candidates]); [log] carries [candidates]`[tried]`. */
    val candidates: List<String> = listOf(log.climbUuid),
    val tried: Int = 0,
    /**
     * What a held-back decision is tied to: everything this row would send,
     * taken before any retry swaps the id (copy() keeps it). Any change — an
     * edit, a quick-log bid promoted to a send, a new wall context — makes it
     * a different request and retries it.
     */
    val fingerprint: Int = log.hashCode(),
    /** Kilter held a copy under this uuid, deleted to send the row again ([KilterSyncEngine]); only once. */
    val replaced: Boolean = false,
) {
    /** The same row with the next candidate id, or null once Kilter refused every one. */
    fun nextCandidate(): KilterUploadItem? = candidates.getOrNull(tried + 1)?.let {
        copy(log = log.copy(climbUuid = it), tried = tried + 1)
    }
}

internal object KilterLogUploadPlan {
    /**
     * The instant Kilter should record, or null when [raw] is not a
     * timestamp. Kilter answers an unparseable one with HTTP 400; local
     * timestamps without a zone are device-local time (DateTimeUtil.nowIso).
     */
    fun kilterTimestamp(raw: String): String? {
        val s = raw.trim().replace(' ', 'T')
        if (s.isEmpty()) return null
        runCatching { return Instant.parse(s).toString() }
        runCatching { return OffsetDateTime.parse(s).toInstant().toString() }
        runCatching { return LocalDateTime.parse(s).toInstant(TimeZone.currentSystemDefault()).toString() }
        return null
    }

    fun epochMillis(timestamp: String): Long? =
        runCatching { Instant.parse(timestamp).toEpochMilli() }.getOrNull()
            ?: kilterTimestamp(timestamp)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    /**
     * Whether Kilter's copy of a log uuid says what the local row says. Only
     * what the user can edit or see counts; the wall context and the id
     * spelling are not edits (Kilter files one climb under one id whatever
     * spelling a client used).
     */
    fun sameContent(local: KilterLog, remote: KilterLog): Boolean =
        ClimbUuid.normKey(local.climbUuid) == ClimbUuid.normKey(remote.climbUuid) &&
            local.angle == remote.angle && local.topped == remote.topped &&
            local.flashed == remote.flashed && local.attempts == remote.attempts &&
            sameMillis(local.createdAt, remote.createdAt)

    private fun sameMillis(a: String, b: String): Boolean {
        val x = epochMillis(a) ?: return a == b
        val y = epochMillis(b) ?: return a == b
        return x == y
    }
}

/**
 * The logs Kilter already holds under a different uuid, to keep the same
 * ascent from being uploaded twice — the account's own logs, each backing at
 * most one local entry. Every entry may match an exact twin (same climb,
 * angle, send state and second). Aurora-imported entries may afterwards match
 * a log of the same climb, angle and send state up to a day away, nearest in
 * time first ([claimNearest]): the export and Kilter's migrated copy need not
 * agree on the zone of the time. Callers claim exact twins for all entries
 * first, so an exact twin is never taken by a looser match.
 */
internal class KilterTwinIndex(remote: Collection<KilterLog>) {
    private class Remote(val millis: Long, val second: Long, val day: Long) { var claimed = false }

    private val buckets = HashMap<String, MutableList<Remote>>()

    init {
        for (log in remote) {
            val millis = KilterLogUploadPlan.epochMillis(log.createdAt) ?: continue
            buckets.getOrPut(bucket(log)) { ArrayList(1) } += Remote(millis, Math.floorDiv(millis, 1000L), day(millis))
        }
    }

    /** Claims an unclaimed remote log of the same second; true when one was left. */
    fun claimExact(log: KilterLog): Boolean {
        val millis = KilterLogUploadPlan.epochMillis(log.createdAt) ?: return false
        val second = Math.floorDiv(millis, 1000L)
        return claim(log) { it.second == second }
    }

    /** Claims an unclaimed remote log [dayOffset] days from the entry's UTC day. */
    fun claimDay(log: KilterLog, dayOffset: Long): Boolean {
        val millis = KilterLogUploadPlan.epochMillis(log.createdAt) ?: return false
        val day = day(millis) + dayOffset
        return claim(log) { it.day == day }
    }

    /**
     * Pairs each of [entries] with an unclaimed remote log of the same climb,
     * angle and send state no more than [maxDays] UTC days away, nearest in
     * time first across all entries: an export whose times are off by the
     * user's zone must not hand one ascent's log to the entry of the day
     * before. Returns the keys of the entries that found one.
     */
    fun <K> claimNearest(entries: List<Pair<K, KilterLog>>, maxDays: Long = 1): Set<K> {
        class Pairing(val key: K, val remote: Remote, val distance: Long)
        val pairings = ArrayList<Pairing>()
        for ((key, log) in entries) {
            val millis = KilterLogUploadPlan.epochMillis(log.createdAt) ?: continue
            val day = day(millis)
            for (remote in buckets[bucket(log)].orEmpty()) {
                if (!remote.claimed && kotlin.math.abs(remote.day - day) <= maxDays) {
                    pairings += Pairing(key, remote, kotlin.math.abs(remote.millis - millis))
                }
            }
        }
        pairings.sortBy { it.distance }
        val matched = LinkedHashSet<K>()
        for (p in pairings) {
            if (p.key in matched || p.remote.claimed) continue
            p.remote.claimed = true
            matched += p.key
        }
        return matched
    }

    private inline fun claim(log: KilterLog, match: (Remote) -> Boolean): Boolean {
        val hit = buckets[bucket(log)]?.firstOrNull { !it.claimed && match(it) } ?: return false
        hit.claimed = true
        return true
    }

    private fun day(millis: Long): Long =
        Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC).toLocalDate().toEpochDay()

    private fun bucket(log: KilterLog) = "${ClimbUuid.normKey(log.climbUuid)}|${log.angle}|${log.topped}"
}
