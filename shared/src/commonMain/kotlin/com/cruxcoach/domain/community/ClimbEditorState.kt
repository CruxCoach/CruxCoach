package com.cruxcoach.domain.community

import com.cruxcoach.domain.board.BoardClimbParser

/**
 * Pure-data state for the climb editor. Lives in commonMain so it can be
 * unit-tested without Android dependencies.
 *
 * Hold map: `placement_id` → `HoldRole`. Boulder-only for v0.1.4 (per
 * FEAT-003 Non-Goals); route-specific roles 42-45 are not produced.
 */
data class ClimbEditorState(
    val selectedHolds: Map<Int, Int> = emptyMap(),  // placementId → roleId (12/13/14/15)
    val name: String = "",
    val description: String = "",
    val setterGradeId: Int? = null,                  // 10..34, see KilterGradeMapper
    val angle: Int? = null,                          // 20..70 in 5° steps
    /**
     * Active brush role for paint-mode taps. Non-null → taps paint this
     * role (or toggle off if the hold already has it). Null (no chip
     * selected) → taps remove the hold's role; empty holds are no-ops.
     * Long-press + drag continues to MOVE existing holds regardless.
     */
    val activeBrush: Int? = null,
)

/**
 * Apply the active brush to a hold:
 * - Hold not selected → assign brush role
 * - Hold has the brush role already → remove it (toggle)
 * - Hold has a different role → replace with brush role
 *
 * Returns the new role for the hold (null = remove).
 */
fun paintWithBrush(currentRole: Int?, brush: Int): Int? =
    if (currentRole == brush) null else brush

/**
 * Encode the editor's hold map back to the on-wire delta-format frames
 * string used everywhere in the climbs table (`p{id}r{role}…`).
 * Order is by placementId ascending — deterministic so frames_hash stays
 * stable across re-encodes.
 */
fun ClimbEditorState.encodeFrames(): String {
    val holds = selectedHolds.entries
        .sortedBy { it.key }
        .map { com.cruxcoach.domain.board.BoardHold(it.key, it.value) }
    return BoardClimbParser.encodeFrames(holds)
}
