package com.cruxcoach.domain.community

import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.board.BoardHold

/**
 * Canonical hash of a climb's hold layout for duplicate detection.
 *
 * Algorithm (FEAT-003 §4.3):
 *   1. Parse `frames` into (placementId, roleId) pairs
 *   2. Sort by placementId ascending
 *   3. Concatenate as `p{pid}r{role}…`
 *   4. Prefix with `layout:{layoutId}:` and SHA-256
 *
 * **layoutId IS in the hash** — same placement_ids on different layouts
 * are physically different climbs.
 *
 * **angle is NOT in the hash** — same holds at different angles is the
 * same climb (mirrors Aurora's climb + climb_stat split).
 */
expect object FramesHash {
    fun of(frames: String, layoutId: Long): String
}

/** Build the canonical pre-hash input. Pure function, shared across platforms. */
internal fun framesHashInput(frames: String, layoutId: Long): String {
    val holds = BoardClimbParser.parseFrames(frames)
        .sortedBy { it.placementId }
        .joinToString("") { h: BoardHold -> "p${h.placementId}r${h.roleId}" }
    return "layout:$layoutId:$holds"
}
