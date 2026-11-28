package com.cruxcoach.android.ui.board

import com.cruxcoach.data.repository.AscentWithClimb

/**
 * What the two logging buttons of a climb page have recorded for the climb so far: at the angle on screen
 * ([triesAtAngle], [topsAtAngle]) and over every angle and day ([tries], [tops]). Like the board makers' apps, the
 * page leads with the angle on screen; a top at 40° is no top at 25°.
 *
 * Each number is what its own button adds to: a tap on "Try" raises the tries by one, a tap on "Top" raises the
 * tops by one. So the try that ended in the top is the top and not also a try – a send logged with three tries
 * counts two tries and one top.
 */
data class ClimbLogCounts(val triesAtAngle: Int, val topsAtAngle: Int, val tries: Int, val tops: Int)

/** [history] is the climber's log of one climb: sends, and attempt rows that hold several tries. [angle] is the
 *  wall angle on screen. */
fun climbLogCounts(history: List<AscentWithClimb>, angle: Int): ClimbLogCounts {
    val atAngle = history.filter { it.angle == angle.toLong() }
    return ClimbLogCounts(
        triesAtAngle = logTries(atAngle),
        topsAtAngle = atAngle.count { it.isSend },
        tries = logTries(history),
        tops = history.count { it.isSend },
    )
}

private fun logTries(rows: List<AscentWithClimb>): Int =
    rows.sumOf { if (it.isSend) (it.bidCount - 1).coerceAtLeast(0L) else it.bidCount.coerceAtLeast(1L) }.toInt()
