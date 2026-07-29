package com.cruxcoach.android.data

import com.cruxcoach.android.fakes.createTestUserPreferences
import com.cruxcoach.domain.board.MoonBoardHoldSets
import com.cruxcoach.domain.board.MoonBoardVariant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The stored side of the MoonBoard hold-set selection (FEAT-049 §3.5).
 *
 * The load-bearing property here is the direction of the default: every
 * unknown, absent or damaged value must mean "all sets mounted". The opposite
 * reading would hide a user's entire catalogue behind a preference they never
 * set, which is the one failure the filter must not be able to produce.
 */
class UserPreferencesMoonBoardHoldSetsTest {

    private val masters2019 = MoonBoardVariant.MASTERS_2019
    private val masters2017 = MoonBoardVariant.MASTERS_2017
    private val universe2019 = MoonBoardHoldSets.setIdsFor(masters2019)

    @Test
    fun `absent preference means every set`() = runTest {
        val prefs = createTestUserPreferences(backgroundScope)

        assertEquals(universe2019, prefs.getMoonBoardHoldSets(masters2019))
        assertEquals(universe2019, prefs.moonBoardHoldSets(masters2019).first())
    }

    @Test
    fun `selection round-trips and keeps the universe's ascending order`() = runTest {
        val prefs = createTestUserPreferences(backgroundScope)

        // Deliberately out of order: the bit order comes from the universe,
        // never from the order the user happened to tick boxes in.
        prefs.setMoonBoardHoldSets(masters2019, listOf(21L, 17L, 19L))

        assertEquals(listOf(17L, 19L, 21L), prefs.getMoonBoardHoldSets(masters2019))
    }

    @Test
    fun `each layout keeps its own selection`() = runTest {
        val prefs = createTestUserPreferences(backgroundScope)

        // A 2019 at home, a 2017 at the gym. One must never be read as the
        // other (edge case 2).
        prefs.setMoonBoardHoldSets(masters2019, listOf(17L, 18L))
        prefs.setMoonBoardHoldSets(masters2017, listOf(11L, 12L, 13L))

        assertEquals(listOf(17L, 18L), prefs.getMoonBoardHoldSets(masters2019))
        assertEquals(listOf(11L, 12L, 13L), prefs.getMoonBoardHoldSets(masters2017))
        // Untouched variants still report their complete setup.
        assertEquals(
            MoonBoardHoldSets.setIdsFor(MoonBoardVariant.MOONBOARD_2016),
            prefs.getMoonBoardHoldSets(MoonBoardVariant.MOONBOARD_2016),
        )
    }

    @Test
    fun `an empty selection is never stored`() = runTest {
        val prefs = createTestUserPreferences(backgroundScope)
        prefs.setMoonBoardHoldSets(masters2019, listOf(17L, 18L))

        prefs.setMoonBoardHoldSets(masters2019, emptyList())

        assertEquals(
            listOf(17L, 18L),
            prefs.getMoonBoardHoldSets(masters2019),
            "an empty write must not clear a real selection",
        )
    }

    @Test
    fun `ids from another board are ignored rather than believed`() = runTest {
        val prefs = createTestUserPreferences(backgroundScope)

        // 2016's ids (2, 3, 4) are not part of the 2019 universe. Storing them
        // leaves nothing valid, which reads as "all sets" — not as "no sets".
        prefs.setMoonBoardHoldSets(masters2019, listOf(2L, 3L, 4L))

        assertEquals(universe2019, prefs.getMoonBoardHoldSets(masters2019))
    }

    @Test
    fun `toggling starts from the stored value, so concurrent taps both land`() = runTest {
        // Edge case 11, at the level where the fix lives. The read-modify-write
        // this replaces was in the ViewModel: two taps read the same list and
        // each wrote a full replacement derived from it, so the second write put
        // back what the first removed. Launching both toggles without awaiting
        // the first is the closest a unit test gets to two fast taps; DataStore
        // serialises the edits, and because the flip happens INSIDE the edit the
        // second one starts from the first one's result.
        val prefs = createTestUserPreferences(backgroundScope)

        val first = launch { prefs.toggleMoonBoardHoldSet(masters2019, 21L) }
        val second = launch { prefs.toggleMoonBoardHoldSet(masters2019, 22L) }
        first.join()
        second.join()

        assertEquals(
            universe2019 - 21L - 22L,
            prefs.getMoonBoardHoldSets(masters2019),
            "both deselections survive, in either arrival order",
        )
    }

    @Test
    fun `toggling flips a set back on again`() = runTest {
        val prefs = createTestUserPreferences(backgroundScope)

        assertTrue(prefs.toggleMoonBoardHoldSet(masters2019, 21L))
        assertEquals(universe2019 - 21L, prefs.getMoonBoardHoldSets(masters2019))

        assertTrue(prefs.toggleMoonBoardHoldSet(masters2019, 21L))
        assertEquals(universe2019, prefs.getMoonBoardHoldSets(masters2019))
    }

    @Test
    fun `toggling off the last set is refused, not stored`() = runTest {
        // Edge case 1. The refusal has to be decided against the value the write
        // would use, not against a copy the caller is holding — otherwise the
        // check and the write can disagree about what "the last set" is.
        val prefs = createTestUserPreferences(backgroundScope)
        prefs.setMoonBoardHoldSets(masters2019, listOf(17L))

        assertFalse(
            prefs.toggleMoonBoardHoldSet(masters2019, 17L),
            "the caller is told the toggle was refused, so it can say why",
        )
        assertEquals(listOf(17L), prefs.getMoonBoardHoldSets(masters2019))
    }

    @Test
    fun `toggling ignores an id the variant does not have`() = runTest {
        // 2016's set 2 is not part of the 2019 universe. Adding it must not
        // change anything — and must certainly not be stored, since a stored
        // list of foreign ids resolves back to "all sets".
        val prefs = createTestUserPreferences(backgroundScope)
        prefs.setMoonBoardHoldSets(masters2019, listOf(17L, 18L))

        prefs.toggleMoonBoardHoldSet(masters2019, 2L)

        assertEquals(listOf(17L, 18L), prefs.getMoonBoardHoldSets(masters2019))
    }

    @Test
    fun `selecting everything is how the complete setup is stored`() = runTest {
        val prefs = createTestUserPreferences(backgroundScope)

        prefs.setMoonBoardHoldSets(masters2019, listOf(17L, 18L))
        prefs.setMoonBoardHoldSets(masters2019, universe2019)

        // Level 1 has no flag of its own — it IS the full list (AC 12).
        assertEquals(universe2019, prefs.getMoonBoardHoldSets(masters2019))
    }
}
