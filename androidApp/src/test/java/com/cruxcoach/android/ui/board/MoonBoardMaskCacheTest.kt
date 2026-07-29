package com.cruxcoach.android.ui.board

import com.cruxcoach.domain.board.MoonBoardHoldSets
import com.cruxcoach.domain.board.MoonBoardVariant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The browse filter's MoonBoard mask, and the invalidation rule around it
 * (FEAT-049 §3.6, edge cases 2/3/4).
 *
 * Two things could go wrong here and neither would be visible from the mask
 * value alone: a stale key letting one variant's selection apply to another,
 * and the presence probe running on a path that repeats several times a
 * minute. Both are asserted directly.
 */
class MoonBoardMaskCacheTest {

    private val masters2019 = MoonBoardVariant.MASTERS_2019
    private val masters2017 = MoonBoardVariant.MASTERS_2017
    private val universe2019 = MoonBoardHoldSets.setIdsFor(masters2019)
    private val universe2017 = MoonBoardHoldSets.setIdsFor(masters2017)

    private class CountingGate(var present: Boolean) {
        var calls = 0
            private set

        suspend fun probe(): Boolean {
            calls++
            return present
        }
    }

    @Test
    fun `complete setup masks nothing and never probes the catalogue`() = runTest {
        val cache = MoonBoardMaskCache()
        val gate = CountingGate(present = true)

        val mask = cache.maskFor(masters2019, universe2019, syncGeneration = 1, gate::probe)

        assertEquals(0L, mask)
        assertEquals(
            0, gate.calls,
            "with everything selected the probe cannot change the answer",
        )
    }

    @Test
    fun `a deselected set produces its bit once the catalogue carries data`() = runTest {
        val cache = MoonBoardMaskCache()
        val gate = CountingGate(present = true)

        val mask = cache.maskFor(masters2019, universe2019 - 21L, syncGeneration = 1, gate::probe)

        assertEquals(0b001000L, mask, "Wooden Holds is bit 3 on layout 5")
        assertEquals(1, gate.calls)
    }

    @Test
    fun `without catalogue data the filter stays off`() = runTest {
        // Edge case 4: an old chunk with a new app. Every MoonBoard row still
        // carries hsm 0, so the mask would be inert anyway — reporting 0 keeps
        // the browse path and the disabled picker telling the same story.
        val cache = MoonBoardMaskCache()
        val gate = CountingGate(present = false)

        val mask = cache.maskFor(masters2019, universe2019 - 21L, syncGeneration = 1, gate::probe)

        assertEquals(0L, mask)
    }

    @Test
    fun `repeated refreshes reuse the answer`() = runTest {
        val cache = MoonBoardMaskCache()
        val gate = CountingGate(present = true)
        val owned = universe2019 - 21L

        repeat(10) { cache.maskFor(masters2019, owned, syncGeneration = 4, gate::probe) }

        assertEquals(
            1, gate.calls,
            "the browser's board block runs on every refresh; the probe must not",
        )
    }

    @Test
    fun `a completed sync re-asks the catalogue`() = runTest {
        val cache = MoonBoardMaskCache()
        val gate = CountingGate(present = false)
        val owned = universe2019 - 21L

        assertEquals(0L, cache.maskFor(masters2019, owned, 1, gate::probe))
        // The chunk with populated hsm lands; syncGeneration moves.
        gate.present = true
        assertEquals(0b001000L, cache.maskFor(masters2019, owned, 2, gate::probe))
        assertEquals(2, gate.calls)
    }

    @Test
    fun `changing the selection recomputes`() = runTest {
        val cache = MoonBoardMaskCache()
        val gate = CountingGate(present = true)

        assertEquals(0b001000L, cache.maskFor(masters2019, universe2019 - 21L, 1, gate::probe))
        assertEquals(0b010000L, cache.maskFor(masters2019, universe2019 - 22L, 1, gate::probe))
    }

    @Test
    fun `a 2019 selection is never applied to a 2017`() = runTest {
        // Edge case 2. The set-id spaces are disjoint per layout, so a stale
        // answer would not merely be imprecise — it would exclude every set
        // the new board has.
        val cache = MoonBoardMaskCache()
        val gate = CountingGate(present = true)

        assertEquals(0b001000L, cache.maskFor(masters2019, universe2019 - 21L, 1, gate::probe))
        assertEquals(
            0L,
            cache.maskFor(masters2017, universe2017, 1, gate::probe),
            "the 2017 board starts on its own complete setup, not the 2019 mask",
        )
    }

    @Test
    fun `switching away from MoonBoard clears the mask and the memo`() = runTest {
        // Edge case 3: the stale non-zero mask must not survive a move to a
        // Kilter board and back.
        val cache = MoonBoardMaskCache()
        val gate = CountingGate(present = true)
        val owned = universe2019 - 21L

        assertEquals(0b001000L, cache.maskFor(masters2019, owned, 1, gate::probe))
        assertEquals(0L, cache.maskFor(null, emptyList(), 1, gate::probe))
        // Back on the MoonBoard: recomputed, not served from the memo.
        assertEquals(0b001000L, cache.maskFor(masters2019, owned, 1, gate::probe))
        assertEquals(2, gate.calls)
    }
}
