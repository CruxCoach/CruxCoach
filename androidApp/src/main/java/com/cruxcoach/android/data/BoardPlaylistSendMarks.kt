package com.cruxcoach.android.data

import com.cruxcoach.data.repository.AscentWithClimb

/** This member's personal result for a climb at one angle. Never shared over FIPS. */
enum class BoardPlaylistLogMark { UNATTEMPTED, ATTEMPTED, SENT }

internal fun boardPlaylistLogKey(climbUuid: String, angle: Int): String =
    "${climbUuid.lowercase()}@$angle"

/**
 * Builds the local colour overlay from the user's own logbook.
 *
 * A later send wins over earlier failed attempts. The map is deliberately
 * keyed by climb and angle rather than playlist occurrence: repeated entries
 * represent separate turns in the shared list, but the personal logbook fact
 * shown beside them is the same. Nothing from this map enters canonical state.
 */
internal fun personalBoardPlaylistLogMarks(
    logs: List<AscentWithClimb>,
): Map<String, BoardPlaylistLogMark> {
    val marks = HashMap<String, BoardPlaylistLogMark>()
    logs.forEach { log ->
        val key = boardPlaylistLogKey(log.climbUuid, log.angle.toInt())
        val candidate = if (log.isSend) BoardPlaylistLogMark.SENT
        else BoardPlaylistLogMark.ATTEMPTED
        if (candidate == BoardPlaylistLogMark.SENT || key !in marks) marks[key] = candidate
    }
    return marks
}
