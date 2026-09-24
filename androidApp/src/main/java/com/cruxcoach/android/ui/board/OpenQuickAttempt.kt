package com.cruxcoach.android.ui.board

import com.cruxcoach.data.repository.AscentWithClimb

/**
 * The attempt row a one-tap log continues: the newest entry on this climb,
 * angle and side is an unsent attempt logged since [since] (an ISO date or
 * date-time — the running session's start, or today). A send after it
 * closes that sequence, so a later attempt starts a fresh row.
 *
 * Without this, "Attempt" then "Sent" in the playlist player, or across two
 * visits of a climb, left an open attempt entry beside a one-try send that
 * counted as a flash.
 */
internal fun openQuickAttempt(
    history: List<AscentWithClimb>,
    angle: Long,
    isMirror: Boolean,
    since: String?,
): AscentWithClimb? {
    val start = since ?: return null
    val newest = history
        .filter { it.angle == angle && it.isMirror == isMirror }
        .maxByOrNull { it.climbedAt }
        ?: return null
    return newest.takeIf { !it.isSend && it.climbedAt >= start }
}
