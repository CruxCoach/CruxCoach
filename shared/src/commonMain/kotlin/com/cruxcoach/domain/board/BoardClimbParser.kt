package com.cruxcoach.domain.board

import kotlinx.serialization.Serializable

@Serializable
data class BoardHold(val placementId: Int, val roleId: Int)

/**
 * Hold roles in the climb-frame protocol (Aurora-derived):
 * - 12 = Start hold (green)    — boulder
 * - 13 = Hand hold (blue/cyan) — boulder
 * - 14 = Finish hold (magenta) — boulder
 * - 15 = Foot hold (yellow/orange) — boulder
 * - 42 = Start hold (route) — maps to 12
 * - 43 = Hand hold (route)  — maps to 13
 * - 44 = Finish hold (route) — maps to 14
 * - 45 = Foot hold (route)  — maps to 15
 */
object HoldRole {
    const val START = 12
    const val HAND = 13
    const val FINISH = 14
    const val FOOT = 15

    const val ROUTE_START = 42
    const val ROUTE_HAND = 43
    const val ROUTE_FINISH = 44
    const val ROUTE_FOOT = 45

    /** Normalize route-specific roles (42-45) to standard roles (12-15). */
    fun normalize(roleId: Int): Int = when (roleId) {
        ROUTE_START -> START
        ROUTE_HAND -> HAND
        ROUTE_FINISH -> FINISH
        ROUTE_FOOT -> FOOT
        else -> roleId
    }
}

object BoardClimbParser {

    /**
     * Parse frames string into hold list. Supports both formats:
     * - Delta:  "p{placementId}r{roleId}..."  (e.g. "p1091r15p1096r15p1163r12") — Aurora-era / Blossom DB
     * - Range:  "h{holdPlacementId}p{roleId}..." (e.g. "h1461p12h1575p13h1636p14") — Kilter REST API
     *
     * Format is auto-detected by checking the first character.
     * Route-specific role IDs (42-45) are normalized to standard roles (12-15).
     */
    fun parseFrames(frames: String): List<BoardHold> {
        if (frames.isBlank()) return emptyList()
        return parseHoldEntries(frames)
    }

    /** Detect whether a frames string uses Kilter climbConcat format. */
    fun isClimbConcat(frames: String): Boolean = frames.trimStart().startsWith("h")

    /**
     * Parse a single frame section, extracting hold entries and
     * ignoring x{id} removal entries (only relevant in multi-frame context).
     * Auto-detects delta-format (p…r…) vs. range-format (h…p…).
     */
    private fun parseHoldEntries(section: String): List<BoardHold> {
        if (section.isBlank()) return emptyList()
        val holds = mutableListOf<BoardHold>()
        val pattern = if (isClimbConcat(section)) RANGE_PATTERN else DELTA_PATTERN
        for (match in pattern.findAll(section)) {
            val placement = match.groupValues[1].toIntOrNull() ?: continue
            val rawRole = match.groupValues[2].toIntOrNull() ?: continue
            holds.add(BoardHold(placement, HoldRole.normalize(rawRole)))
        }
        return holds
    }

    private val DELTA_PATTERN = Regex("""p(\d+)r(\d+)""")
    private val RANGE_PATTERN = Regex("""h(\d+)p(\d+)""")

    /**
     * Parse x{id} removal entries from a frame section.
     * Returns set of placement IDs to remove from previous frame.
     */
    private fun parseRemovals(section: String): Set<Int> {
        val removals = mutableSetOf<Int>()
        val pattern = Regex("""x(\d+)""")
        for (match in pattern.findAll(section)) {
            match.groupValues[1].toIntOrNull()?.let { removals.add(it) }
        }
        return removals
    }

    /**
     * Encode holds back to delta-format frames string (p{id}r{role}).
     */
    fun encodeFrames(holds: List<BoardHold>): String {
        return holds.joinToString("") { "p${it.placementId}r${it.roleId}" }
    }

    /**
     * Encode holds to Kilter climbConcat string (h{id}p{role}).
     */
    fun encodeClimbConcat(holds: List<BoardHold>): String {
        return holds.joinToString("") { "h${it.placementId}p${it.roleId}" }
    }

    /**
     * Count holds by role.
     */
    fun countByRole(holds: List<BoardHold>): Map<Int, Int> {
        return holds.groupBy { it.roleId }.mapValues { it.value.size }
    }

    /**
     * Get only hand/start/finish holds (no foot-only).
     */
    fun getHandHolds(holds: List<BoardHold>): List<BoardHold> {
        return holds.filter { it.roleId in listOf(HoldRole.START, HoldRole.HAND, HoldRole.FINISH) }
    }

    /**
     * Total moves = hand holds - start holds (starts are already on the wall).
     */
    fun estimateMoveCount(holds: List<BoardHold>): Int {
        val handHolds = getHandHolds(holds)
        val startCount = holds.count { it.roleId == HoldRole.START }
        return (handHolds.size - startCount).coerceAtLeast(0)
    }

    /**
     * Parse multi-frame route string into list of resolved frames.
     *
     * Delta route format uses incremental diffs:
     * - Frame 1: base set of holds (p{id}r{role} entries)
     * - Frame 2+: x{id} = remove hold, p{id}r{role} = add hold
     * - Comma = frame delimiter
     *
     * Each returned frame is the FULL set of holds visible at that point,
     * not just the diff.
     */
    fun parseMultiFrames(frames: String): List<List<BoardHold>> {
        if (frames.isBlank()) return listOf(emptyList())
        if (!frames.contains(",")) return listOf(parseFrames(frames))

        val sections = frames.split(",")
        val result = mutableListOf<List<BoardHold>>()

        // Frame 1: base set of holds
        var currentHolds = parseHoldEntries(sections[0]).associateBy { it.placementId }.toMutableMap()
        result.add(currentHolds.values.toList())

        // Frame 2+: apply incremental diffs
        for (i in 1 until sections.size) {
            val section = sections[i]
            if (section.isBlank()) continue

            // Remove holds marked with x{id}
            val removals = parseRemovals(section)
            removals.forEach { currentHolds.remove(it) }

            // Add new holds from p{id}r{role}
            val additions = parseHoldEntries(section)
            additions.forEach { currentHolds[it.placementId] = it }

            result.add(currentHolds.values.toList())
        }

        return result
    }

    /**
     * Encode multiple frames back to string.
     */
    fun encodeMultiFrames(frames: List<List<BoardHold>>): String {
        return frames.joinToString(",") { encodeFrames(it) }
    }

    /**
     * Check if a frames string represents a multi-frame route.
     */
    fun isMultiFrame(frames: String): Boolean {
        return frames.contains(",")
    }
}
