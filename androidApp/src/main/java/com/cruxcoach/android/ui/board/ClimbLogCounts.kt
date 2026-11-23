package com.cruxcoach.android.ui.board

import com.cruxcoach.data.repository.AscentWithClimb

/**
 * What the two logging buttons of a climb page have recorded for the climb so far, over every angle and day.
 *
 * Each number is what its own button adds to: a tap on "Try" raises [tries] by one, a tap on "Top" raises [tops] by
 * one. So the try that ended in the top is the top and not also a try – a send logged with three tries counts two
 * tries and one top.
 */
data class ClimbLogCounts(val tries: Int, val tops: Int)

/** [history] is the climber's log of one climb: sends, and attempt rows that hold several tries. */
fun climbLogCounts(history: List<AscentWithClimb>): ClimbLogCounts {
    val tops = history.count { it.isSend }
    val tries = history.sumOf { if (it.isSend) (it.bidCount - 1).coerceAtLeast(0L) else it.bidCount.coerceAtLeast(1L) }
    return ClimbLogCounts(tries = tries.toInt(), tops = tops)
}
