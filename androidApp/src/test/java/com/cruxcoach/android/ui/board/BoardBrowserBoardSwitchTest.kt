package com.cruxcoach.android.ui.board

import com.cruxcoach.domain.board.HoldSetMask
import com.cruxcoach.domain.board.MoonBoardHoldSets
import com.cruxcoach.domain.board.MoonBoardVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The board switch as the browser publishes it (FEAT-049 edge case 3).
 *
 * The bug this pins is not a wrong mask — the mask that eventually arrives was
 * always right. It is the *pairing*: the new layout became visible to every
 * reader of the filter state while the previous board's mask was still in
 * place, and the mask bits are positional per layout. Bit 3 is Wooden Holds on
 * a Masters 2019 and Original School Holds on a 2017, so a search landing in
 * that window hid the wrong set rather than merely too much — and if the
 * refresh that would have corrected it was cancelled (a second board switch
 * cancels it) or threw, nothing corrected it at all.
 *
 * So the assertions here are about what a single state transition contains, not
 * about the end state after a happy-path refresh: the end state was already
 * correct before this fix.
 */
class BoardBrowserBoardSwitchTest {

    private val masters2019 = MoonBoardVariant.MASTERS_2019
    private val masters2017 = MoonBoardVariant.MASTERS_2017

    /** Wooden Holds (set 21) deselected on a 2019 → bit 3. */
    private val woodenOff2019 = HoldSetMask.excludedMask(
        layoutSetIds = MoonBoardHoldSets.setIdsFor(masters2019),
        sizeSetIds = MoonBoardHoldSets.setIdsFor(masters2019) - 21L,
    )

    private fun on2019(mask: Long) = BoardBrowserState(
        hsmExcludedMask = mask,
        filter = BrowserFilterState(
            angle = 40,
            layoutId = masters2019.layoutId.toInt(),
            boardBrand = "moonboard",
            angleChips = listOf(40),
        ),
    )

    @Test
    fun `the state that publishes the new layout already carries no mask`() {
        val before = on2019(woodenOff2019)
        assertNotEquals(0L, before.hsmExcludedMask, "fixture must start with a real mask")

        val after = before.onBoardSwitch(
            angle = 40,
            layoutId = masters2017.layoutId.toInt(),
            boardBrand = "moonboard",
            angleChips = listOf(40),
        )

        // One transition, both facts. There is no reachable state in between
        // that a search, a count or the queue could read.
        assertEquals(masters2017.layoutId.toInt(), after.filter.layoutId)
        assertEquals(
            0L, after.hsmExcludedMask,
            "bit 3 means a different hold set on the new board; carrying it over hides the wrong one",
        )
    }

    @Test
    fun `a refresh that never finishes leaves the filter off, not wrong`() {
        // The failure path, as a sequence: switch published, then the board
        // refresh is cancelled by the next switch or throws in the repository,
        // so the recomputed mask never arrives. What the user is left with must
        // be the inert value.
        val switched = on2019(woodenOff2019).onBoardSwitch(
            angle = 40,
            layoutId = masters2017.layoutId.toInt(),
            boardBrand = "moonboard",
            angleChips = listOf(40),
        )

        // …nothing else happens…

        assertEquals(
            0L, switched.hsmExcludedMask,
            "0 is the documented filter-off value: a climb wrongly shown beats one wrongly hidden",
        )
    }

    @Test
    fun `leaving MoonBoard for a Kilter board drops the MoonBoard mask`() {
        // The same window in the other direction. Kilter derives its mask from
        // the configured product size, several suspending calls later; until
        // then a MoonBoard selection must not be filtering Kilter rows, whose
        // hsm has been populated since 0.2.0 and whose bits mean something
        // entirely different (see MoonBoardHsmFilterTest's brand-scope test).
        val after = on2019(woodenOff2019).onBoardSwitch(
            angle = 40,
            layoutId = 1,
            boardBrand = "kilter",
            angleChips = emptyList(),
        )

        assertEquals("kilter", after.filter.boardBrand)
        assertEquals(0L, after.hsmExcludedMask)
    }

    @Test
    fun `the switch touches nothing else`() {
        // Guard on scope: this runs on every board change, so it must not
        // quietly reset unrelated filter state the user set (grades, sort,
        // status) — that would be a different bug wearing this fix's clothes.
        val before = on2019(woodenOff2019).let {
            it.copy(filter = it.filter.copy(minAscensionists = 25, searchQuery = "crimp"))
        }

        val after = before.onBoardSwitch(
            angle = 40,
            layoutId = masters2017.layoutId.toInt(),
            boardBrand = "moonboard",
            angleChips = listOf(40),
        )

        assertEquals(25, after.filter.minAscensionists)
        assertEquals("crimp", after.filter.searchQuery)
    }
}
