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
    /** Set once the other case of a compact id has been tried for this row. */
    val caseRetried: Boolean = false,
)

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

    fun epochMillis(timestamp: String): Long? = runCatching { Instant.parse(timestamp).toEpochMilli() }.getOrNull()

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
 * ascent from being uploaded twice — the account's own logs, matched one to
 * one. A regular entry matches only an exact twin (same climb, angle, send
 * state and second). An Aurora-imported entry also matches a log of the same
 * climb, angle and send state within a day either side: the export and
 * Kilter's migrated copy need not agree on the zone of the time.
 */
internal class KilterTwinIndex(remote: Collection<KilterLog>) {
    private val exact = HashMap<String, Int>()
    private val byDay = HashMap<String, Int>()

    init {
        for (log in remote) {
            val millis = KilterLogUploadPlan.epochMillis(log.createdAt) ?: continue
            exact.merge(exactKey(log, millis), 1, Int::plus)
            byDay.merge(dayKey(log, day(millis)), 1, Int::plus)
        }
    }

    /** Claims one matching remote log for [log]; true when one was left. */
    fun claim(log: KilterLog, imported: Boolean): Boolean {
        val millis = KilterLogUploadPlan.epochMillis(log.createdAt) ?: return false
        if (take(exact, exactKey(log, millis))) {
            take(byDay, dayKey(log, day(millis)))
            return true
        }
        if (!imported) return false
        val d = day(millis)
        for (candidate in longArrayOf(d, d - 1, d + 1)) {
            if (take(byDay, dayKey(log, candidate))) {
                // The exact key of the claimed log is unknown here, so it stays
                // claimable by an exact twin. Callers claim regular entries
                // first; the remaining overlap can only keep an imported entry
                // local, never upload one twice.
                return true
            }
        }
        return false
    }

    private fun take(map: HashMap<String, Int>, key: String): Boolean {
        val n = map[key] ?: return false
        if (n <= 1) map.remove(key) else map[key] = n - 1
        return true
    }

    private fun day(millis: Long): Long =
        Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC).toLocalDate().toEpochDay()

    private fun exactKey(log: KilterLog, millis: Long) =
        "${ClimbUuid.normKey(log.climbUuid)}|${log.angle}|${log.topped}|${millis / 1000}"

    private fun dayKey(log: KilterLog, day: Long) =
        "${ClimbUuid.normKey(log.climbUuid)}|${log.angle}|${log.topped}|$day"
}
