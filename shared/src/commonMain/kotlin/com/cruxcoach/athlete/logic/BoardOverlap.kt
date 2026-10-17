package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.model.ClimbingDayKind
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * Climbing from Health Connect that the board logbook already counts (holistic audit W3, P0-3).
 *
 * A watch that records the board session as climbing would otherwise add its minutes, finger units and
 * energy on top of the logbook's. A synced session counts as covered where board session time or logged
 * attempts fall into its time window. A session titled as another board on a day with board rows is the
 * board session as a whole: the logbook's clock times may come from another device or time zone. Only the
 * minutes outside count; a remainder under [MIN_EXTRA_MINUTES] is clock noise and counts as covered too.
 *
 * The athlete can count a session anyway (extra climbing the logbook does not know). Both live per entry
 * in one setting ([key]), so the climbing-day table keeps its schema.
 */
object BoardOverlap {

    /** A logged attempt stands for a few minutes of climbing around its timestamp. */
    const val ATTEMPT_PAD_MINUTES = 5
    /** Attempts closer together than this belong to one stretch of board climbing. */
    const val ATTEMPT_GAP_MINUTES = 30
    /** A rest of a covered session shorter than this is clock noise, not extra climbing. */
    const val MIN_EXTRA_MINUTES = 10

    const val SETTING_PREFIX = "hc.overlap."

    fun key(entryId: String): String = SETTING_PREFIX + entryId

    data class Window(val startMs: Long, val endMs: Long)

    /** A board session as the board database keeps it (local or zoned timestamps). */
    data class SessionTimes(val startedAt: String, val endedAt: String?, val totalSeconds: Long = 0)

    /** What the board logbook holds around the synced sessions. */
    data class Evidence(
        val sessions: List<Window> = emptyList(),
        /** Logged attempts that carry a clock time. */
        val attemptsMs: List<Long> = emptyList(),
        /** Days (ISO) with board logbook rows or board sessions, with or without a clock time. */
        val boardDays: Set<String> = emptySet(),
    )

    /** One synced entry: minutes the board logbook already counts, and the athlete's "count anyway". */
    data class Stored(val coveredMinutes: Int, val countAnyway: Boolean = false) {
        /** The entry's minutes as they count for load, energy and the day's climbing time. */
        fun counted(minutes: Int): Int = if (countAnyway) minutes else (minutes - coveredMinutes).coerceAtLeast(0)

        fun encode(): String = coveredMinutes.toString() + if (countAnyway) COUNT_FLAG else ""

        companion object {
            private const val COUNT_FLAG = ",count"

            fun parse(value: String?): Stored? {
                val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                val covered = v.substringBefore(',').toIntOrNull()?.coerceAtLeast(0) ?: return null
                return Stored(covered, v.endsWith(COUNT_FLAG))
            }
        }
    }

    /**
     * Minutes of a synced session ([session], [recordedMinutes] long, on [day]) that the board logbook
     * already counts: 0 … [recordedMinutes].
     */
    fun coveredMinutes(session: Window, recordedMinutes: Int, day: String, kind: ClimbingDayKind, evidence: Evidence): Int {
        if (recordedMinutes <= 0 || session.endMs <= session.startMs) return 0
        if (kind == ClimbingDayKind.OTHER_BOARD && day in evidence.boardDays) return recordedMinutes
        val spans = merge(evidence.sessions.filter { it.endMs > it.startMs } + attemptSpans(evidence.attemptsMs))
        val ms = spans.sumOf { (maxOf(0L, minOf(it.endMs, session.endMs) - maxOf(it.startMs, session.startMs))) }
        val covered = ((ms + 30_000) / 60_000).toInt().coerceIn(0, recordedMinutes)
        return if (covered > 0 && recordedMinutes - covered < MIN_EXTRA_MINUTES) recordedMinutes else covered
    }

    /** [Evidence] from logbook rows and board sessions; an open session runs until [nowMs]. */
    fun evidence(rows: List<ClimbRow>, sessions: List<SessionTimes>, zone: TimeZone, nowMs: Long): Evidence {
        val windows = sessions.mapNotNull { s ->
            val start = epochMillis(s.startedAt, zone) ?: return@mapNotNull null
            val end = s.endedAt?.takeIf { it.isNotBlank() }?.let { epochMillis(it, zone) }
                ?: if (s.endedAt.isNullOrBlank()) nowMs else start + s.totalSeconds * 1000
            Window(start, end)
        }
        return Evidence(
            sessions = windows,
            attemptsMs = rows.mapNotNull { epochMillis(it.climbedAt, zone) },
            boardDays = (rows.map { it.day } + sessions.map { it.startedAt.take(10) }).filter { it.length == 10 }.toSet(),
        )
    }

    /**
     * A logbook timestamp in epoch milliseconds: zoned ("…Z", "…+02:00") or device-local ("2026-10-17T18:05:11.123",
     * "2026-10-17 18:05:11"). Null for a date without a clock time – a bare date, exactly midnight, or the noon-UTC
     * marker of the MoonBoard CSV import – so it never makes a false overlap.
     */
    fun epochMillis(raw: String, zone: TimeZone): Long? {
        val s = raw.trim().replace(' ', 'T')
        if (s.length < 16 || s[10] != 'T') return null
        val time = s.substring(11)
        if (time == "12:00:00Z" || time.trimEnd('0', '.', ':').isEmpty()) return null
        runCatching { return kotlin.time.Instant.parse(s).toEpochMilliseconds() }
        runCatching { return LocalDateTime.parse(s).toInstant(zone).toEpochMilliseconds() }
        return null
    }

    private fun attemptSpans(attemptsMs: List<Long>): List<Window> {
        val pad = ATTEMPT_PAD_MINUTES * 60_000L
        val gap = ATTEMPT_GAP_MINUTES * 60_000L
        val out = mutableListOf<Window>()
        var first: Long? = null
        var last = 0L
        for (t in attemptsMs.sorted()) {
            if (first != null && t - last > gap) { out += Window(first - pad, last + pad); first = null }
            if (first == null) first = t
            last = t
        }
        first?.let { out += Window(it - pad, last + pad) }
        return out
    }

    private fun merge(windows: List<Window>): List<Window> {
        val out = mutableListOf<Window>()
        for (w in windows.sortedBy { it.startMs }) {
            val prev = out.lastOrNull()
            if (prev != null && w.startMs <= prev.endMs) out[out.size - 1] = Window(prev.startMs, maxOf(prev.endMs, w.endMs))
            else out += w
        }
        return out
    }
}
