package com.cruxcoach.app.kilter

import com.cruxcoach.domain.board.ClimbUuid
import kotlin.math.abs
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * One local logbook row on its way to Kilter, with the row-version snapshot
 * needed to mark it synced without clobbering a concurrent local edit.
 * Port of Android's `KilterUploadItem`.
 */
internal data class KilterUploadItem(
    val uuid: String,
    val rowVersion: Long,
    val isAscent: Boolean,
    /** Entry from an Aurora export: uploaded only on opt-in. */
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
    val fingerprint: Int = KilterLogUploadPlan.fingerprint(log),
    /** Kilter held a copy under this uuid, deleted to send the row again; only once. */
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
     * timestamps without a zone are device-local time.
     */
    fun kilterTimestamp(raw: String, zone: TimeZone = TimeZone.currentSystemDefault()): String? {
        val s = raw.trim().replace(' ', 'T')
        if (s.isEmpty()) return null
        // kotlin.time.Instant takes both "Z" and numeric offsets, which Android
        // needs Instant and OffsetDateTime for.
        runCatching { return Instant.parse(s).toString() }
        runCatching { return LocalDateTime.parse(s).toInstant(zone).toString() }
        return null
    }

    fun epochMillis(timestamp: String): Long? =
        runCatching { Instant.parse(timestamp).toEpochMilliseconds() }.getOrNull()
            ?: kilterTimestamp(timestamp)?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }

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

    /**
     * Android uses the data class hash. Spelled out here so the stored value
     * does not depend on how a Kotlin target hashes a data class: a held-back
     * decision must survive an app restart unchanged.
     */
    fun fingerprint(log: KilterLog): Int {
        var h = 17
        fun mix(value: String?) { h = 31 * h + (value?.let(::stableHash) ?: 0) }
        mix(log.logUuid); mix(log.userUuid); mix(log.climbUuid); mix(log.gymUuid); mix(log.wallUuid)
        mix(log.productLayoutUuid)
        h = 31 * h + log.angle
        h = 31 * h + if (log.flashed) 1231 else 1237
        h = 31 * h + if (log.topped) 1231 else 1237
        h = 31 * h + log.attempts
        mix(log.createdAt); mix(log.comment)
        return h
    }

    private fun stableHash(value: String): Int {
        var h = 0
        for (c in value) h = 31 * h + c.code
        return h
    }

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
            buckets.getOrPut(bucket(log)) { ArrayList(1) } += Remote(millis, millis.floorDiv(1000L), day(millis))
        }
    }

    /** Claims an unclaimed remote log of the same second; true when one was left. */
    fun claimExact(log: KilterLog): Boolean {
        val millis = KilterLogUploadPlan.epochMillis(log.createdAt) ?: return false
        val second = millis.floorDiv(1000L)
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
                if (!remote.claimed && abs(remote.day - day) <= maxDays) {
                    pairings += Pairing(key, remote, abs(remote.millis - millis))
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

    /** UTC epoch day, as Android's `atOffset(UTC).toLocalDate().toEpochDay()`. */
    private fun day(millis: Long): Long = millis.floorDiv(MILLIS_PER_DAY)

    private fun bucket(log: KilterLog) = "${ClimbUuid.normKey(log.climbUuid)}|${log.angle}|${log.topped}"

    private companion object {
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
    }
}
