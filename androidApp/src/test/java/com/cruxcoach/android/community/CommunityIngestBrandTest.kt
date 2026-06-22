package com.cruxcoach.android.community

import com.cruxcoach.domain.board.BoardBrand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the community-ingest brand-forgery fix (C1) via the extracted pure
 * function [resolveIngestBoardBrand], plus the strict [BoardBrand.fromWireOrNull]
 * it builds on.
 *
 * CROSS-BOARD INVARIANT (Req-2), measured against the aurora-re extracts +
 * published chunks and recorded here so the absence of a cross-board climb
 * matcher is DELIBERATE, not an oversight:
 *  - A climb's grip identity = (hole position x/y) + (mounted hold-set) + role.
 *  - Aurora boards share t-nut GRIDS (a mounting standard) but NOT grips:
 *    grasshopper×tension = 449 shared (x,y) positions but 0 shared (x,y)+set;
 *    decoy×grasshopper = 466 / 0; soill×tension = 303 / 0. Set names are
 *    disjoint (Dimension/Engage/… vs Set A/B/C/Wood…).
 *  - Therefore a CORRECT grip-identity cross-board feature yields ZERO climbs on
 *    the current catalogue, so cross-board inclusion is intentionally absent.
 *    Within ONE board, sizes share a single holes table → a smaller size is a
 *    strict subset of a larger, so the always-on bbox size-fit IS grip-identity
 *    there (e.g. Kilter 7x10 ⊂ 12x12). Brand+layout scoping (asserted indirectly
 *    by C1/C4) keeps the two regimes from mixing. If a future board pair ever
 *    mounts the same set on the same grid, build a board-invariant grip key —
 *    do NOT widen the position-only bbox filter across brands.
 */
class CommunityIngestBrandTest {

    private fun rejected(why: String, block: () -> Unit) {
        assertTrue("expected rejection: $why", runCatching { block() }.isFailure)
    }

    @Test
    fun `fromWireOrNull is strict where fromWire is lenient`() {
        assertEquals(BoardBrand.TENSION, BoardBrand.fromWireOrNull("tension"))
        assertEquals(BoardBrand.KILTER, BoardBrand.fromWireOrNull("kilter"))
        // Unknown / null → null (strict), unlike fromWire which defaults to KILTER.
        assertNull(BoardBrand.fromWireOrNull("evilboard"))
        assertNull(BoardBrand.fromWireOrNull(null))
        assertEquals(BoardBrand.KILTER, BoardBrand.fromWire("evilboard"))
    }

    @Test
    fun `legitimate events resolve to their real brand`() {
        // Legacy Kilter: v1 namespace, board_brand tag absent (pre-FEAT-031) or "kilter".
        assertEquals(BoardBrand.KILTER, resolveIngestBoardBrand(null, foundV1 = true, foundV2 = false, deleted = false))
        assertEquals(BoardBrand.KILTER, resolveIngestBoardBrand("kilter", foundV1 = true, foundV2 = false, deleted = false))
        // New boards: v2 namespace + explicit brand tag (what the publisher emits).
        assertEquals(BoardBrand.TENSION, resolveIngestBoardBrand("tension", foundV1 = false, foundV2 = true, deleted = false))
        assertEquals(BoardBrand.MOONBOARD, resolveIngestBoardBrand("moonboard", foundV1 = false, foundV2 = true, deleted = false))
        assertEquals(BoardBrand.DECOY, resolveIngestBoardBrand("decoy", foundV1 = false, foundV2 = true, deleted = false))
    }

    @Test
    fun `forged or malformed brand-namespace pairs are rejected`() {
        // Unknown brand — must NOT silently become KILTER.
        rejected("unknown brand") { resolveIngestBoardBrand("evilboard", foundV1 = false, foundV2 = true, deleted = false) }
        // Map-only / non-interactive families can't carry climbs.
        rejected("aurora info-layer") { resolveIngestBoardBrand("aurora", foundV1 = false, foundV2 = true, deleted = false) }
        rejected("12climb info-layer") { resolveIngestBoardBrand("12climb", foundV1 = false, foundV2 = true, deleted = false) }
        // Namespace/brand mismatch: a foreign brand forged onto the legacy v1
        // namespace, or Kilter claimed on v2 — neither is something the publisher
        // ever emits, so both are dropped.
        rejected("tension on v1") { resolveIngestBoardBrand("tension", foundV1 = true, foundV2 = false, deleted = false) }
        rejected("kilter on v2") { resolveIngestBoardBrand("kilter", foundV1 = false, foundV2 = true, deleted = false) }
        // A non-Kilter brand with NO namespace label at all.
        rejected("tension, no namespace") { resolveIngestBoardBrand("tension", foundV1 = false, foundV2 = false, deleted = false) }
    }

    @Test
    fun `tombstones skip strict validation so legitimate deletions are never dropped`() {
        // A non-Kilter deletion: the tombstone carries NO board_brand tag yet
        // rides the v2 namespace. This must NOT be rejected (it keys off the
        // uuid). Resolves leniently — same as pre-C1 behaviour.
        assertEquals(BoardBrand.KILTER, resolveIngestBoardBrand(null, foundV1 = false, foundV2 = true, deleted = true))
        // Even an unknown brand on a tombstone resolves leniently (no throw).
        assertEquals(BoardBrand.KILTER, resolveIngestBoardBrand("evilboard", foundV1 = false, foundV2 = true, deleted = true))
        assertEquals(BoardBrand.TENSION, resolveIngestBoardBrand("tension", foundV1 = false, foundV2 = true, deleted = true))
    }
}
