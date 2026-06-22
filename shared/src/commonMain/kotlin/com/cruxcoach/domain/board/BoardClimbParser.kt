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

    /**
     * Canonical role *class* for a raw frame role code, brand-agnostic.
     *
     * Aurora-family boards (Tension/Grasshopper/Decoy/So iLL/Touchstone) number
     * roles 1-4 (start/middle/finish/foot) plus a mirrored set 5-8; Kilter uses
     * 12-15 (and the 42-45 route variants); MoonBoard saved climbs use 42-44.
     * They all collapse to START/HAND/FINISH/FOOT here. Aurora "middle" maps to
     * HAND — there is no distinct hand id. Unknown codes return themselves so
     * exact-match callers keep working. Codes 1-8 are exclusive to the Aurora
     * family (Kilter only ever uses 12-15/42-45), so the fold is collision-free.
     *
     * Comparison / colour-resolution ONLY — never rewrite a *stored* climb
     * with this. Catalogue role ids must stay brand-native: AuroraImporter
     * round-trips frames verbatim (parse→encode), and the per-board
     * placement_roles colour map is keyed by the raw 1-4 ids. Mutating the
     * codes in place (e.g. via [normalize]) would corrupt Aurora frames and
     * mis-key that map. The one sanctioned re-encode is the climb editor's
     * seeding (`parseHoldsForEditor`), which folds a forked catalogue frame
     * into the 12-15 palette to author a NEW climb — the source row itself
     * is never touched.
     */
    fun roleClass(roleId: Int): Int = when (roleId) {
        1, 5 -> START
        2, 6 -> HAND
        3, 7 -> FINISH
        4, 8 -> FOOT
        else -> normalize(roleId)
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
     * Encode holds to Kilter climbConcat string (`h{hole_id}p{role}`).
     *
     * **Important: `h{...}` is hole_id, NOT placement_id.** The Kilter
     * API treats the h-prefixed value as a hole identifier and looks it
     * up in its own placement set; sending placement_ids straight from
     * our local Aurora-derived schema produces a JSON that the API
     * accepts (because most numeric values happen to also be valid
     * hole_ids on the same board) but at completely wrong spatial
     * positions — the published climb shows different holds than the
     * user drew. The cron-side ingest (update_board_db.convert_climb_concat)
     * already understands this correctly when going Kilter→Aurora; the
     * publish path is the inverse and was previously broken.
     *
     * Caller passes a placements map (placementId → BoardPlacement)
     * keyed on the same placementId values that appear inside [BoardHold].
     * Holds whose placement isn't present in the map are skipped — that
     * shouldn't normally happen because the editor only emits holds it
     * could resolve, but defensive in case of map-staleness across
     * board-data sync boundaries.
     */
    fun encodeClimbConcat(holds: List<BoardHold>, placementToHoleId: Map<Int, Long>): String {
        return holds.joinToString("") { hold ->
            val hid = placementToHoleId[hold.placementId]
                ?: return@joinToString ""
            "h${hid}p${hold.roleId}"
        }
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
