package com.cruxcoach.domain.community

import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.board.HoldRole

/**
 * Pure-data state for the climb editor. Lives in commonMain so it can be
 * unit-tested without Android dependencies.
 *
 * Hold map: `placement_id` → `HoldRole`. Boulder-only for v0.1.4 (per
 * FEAT-003 Non-Goals); route-specific roles 42-45 are not produced.
 */
data class ClimbEditorState(
    /**
     * Hold map: id → brand-native roleId. Kilter boulder roles are 12/13/14/15
     * (start/hand/finish/foot); MoonBoard uses route roles 42/43/44
     * (start/hand/finish, no foot). [encodeFrames] emits these verbatim, so
     * what is stored is exactly what goes on the wire — Kilter `p{id}r12…`,
     * MoonBoard `p{holdId}r42…`. Validation normalizes when counting so both
     * palettes share one set of rules.
     */
    val selectedHolds: Map<Int, Int> = emptyMap(),
    /**
     * Board the draft targets: `"kilter"` (default — every pre-MoonBoard
     * caller stays unchanged) or `"moonboard"`. Drives the brush palette,
     * board renderer, BLE-preview transport, and publish destinations
     * (MoonBoard publishes to the CruxCoach Nostr community only — no push
     * to the official MoonBoard app, unlike Kilter).
     */
    val boardBrand: String = "kilter",
    val name: String = "",
    val description: String = "",
    /**
     *  10..34 (see [com.cruxcoach.domain.board.KilterGradeMapper]).
     *  Defaults to [com.cruxcoach.domain.board.KilterGradeMapper.DEFAULT_SETTER_GRADE_ID]
     *  (V4 / 6B+) so a fresh editor always has a publishable grade
     *  pre-selected. Pre-fix this defaulted to null and the slider
     *  *displayed* the default visually but kept the state field at
     *  null — users who didn't touch the slider published events
     *  without a `setter_grade` tag, which every subscriber silently
     *  dropped (no log, climb invisible to every other device). The
     *  composable now renders the seeded value, validation passes
     *  on the seed, and the publisher's `require(...)` becomes
     *  defense-in-depth.
     */
    val setterGradeId: Int? = com.cruxcoach.domain.board.KilterGradeMapper.DEFAULT_SETTER_GRADE_ID,
    val angle: Int? = null,                          // 20..70 in 5° steps
    /**
     * Active brush role for paint-mode taps. Non-null → taps paint this
     * role (or toggle off if the hold already has it). Null (no chip
     * selected) → taps remove the hold's role; empty holds are no-ops.
     * Long-press + drag continues to MOVE existing holds regardless.
     *
     * Default = `HoldRole.START` so a freshly-opened editor pre-selects
     * the green Start chip. Teaches first-time users that the chip row
     * controls which role taps paint, instead of leaving them in a
     * silent delete mode where their first tap simply does nothing.
     */
    val activeBrush: Int? = HoldRole.START,
)

/** Typed board family this draft targets — the bridge from the persisted
 *  `boardBrand` String to the [BoardBrand] capability model (brush palette,
 *  renderer, BLE transport, publish destinations). */
val ClimbEditorState.brand: BoardBrand get() = BoardBrand.fromWire(boardBrand)

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
