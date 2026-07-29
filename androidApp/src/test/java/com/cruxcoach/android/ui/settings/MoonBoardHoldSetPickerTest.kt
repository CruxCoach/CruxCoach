package com.cruxcoach.android.ui.settings

import app.cash.turbine.test
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.fakes.FakeBoardRepository
import com.cruxcoach.android.fakes.createTestUserPreferences
import com.cruxcoach.data.repository.ClimbTypeFilter
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.domain.board.HoldSetMask
import com.cruxcoach.domain.board.MoonBoardHoldSets
import com.cruxcoach.domain.board.MoonBoardVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The two levels of the hold-set picker (FEAT-049 §3.5, AC 11/12).
 *
 * The VM's repository reads run through `withContext(Dispatchers.IO)`, so the
 * assertions await StateFlow emissions with Turbine rather than reading
 * eagerly — same shape as [GymBoardPickerViewModelTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MoonBoardHoldSetPickerTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val repo = FakeBoardRepository()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val masters2019 = MoonBoardVariant.MASTERS_2019
    private val universe = MoonBoardHoldSets.setIdsFor(masters2019)

    private suspend fun onMasters2019(prefs: UserPreferences) {
        prefs.setMoonBoardSelection(masters2019.layoutId.toInt())
    }

    private fun vm(prefs: UserPreferences) = MoonBoardHoldSetViewModel(prefs, repo)

    @Test
    fun `level 1 is the default and the per-set list starts collapsed`() = runTest {
        val prefs = createTestUserPreferences(backgroundScope)
        onMasters2019(prefs)

        vm(prefs).state.test {
            val state = awaitItem { it.variant != null }
            assertEquals(masters2019, state.variant)
            assertTrue("a fresh install owns the complete setup", state.isCompleteSetup)
            assertEquals(universe.toSet(), state.selectedSetIds)
            assertFalse("the per-set list must stay closed until asked", state.expanded)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deselecting one set moves the summary off complete`() = runTest {
        val prefs = createTestUserPreferences(backgroundScope)
        onMasters2019(prefs)
        val viewModel = vm(prefs)

        viewModel.state.test {
            awaitItem { it.variant != null }
            viewModel.setExpanded(true)
            viewModel.toggleSet(21L) // Wooden Holds — the board from issue #9

            val state = awaitItem { !it.isCompleteSetup }
            assertEquals(universe.toSet() - 21L, state.selectedSetIds)
            assertTrue(state.expanded)
            cancelAndIgnoreRemainingEvents()
        }
        // No separate "is complete" flag exists — the stored list is the state.
        assertEquals(universe - 21L, prefs.getMoonBoardHoldSets(masters2019))
    }

    @Test
    fun `selecting the complete setup stores the full list again`() = runTest {
        val prefs = createTestUserPreferences(backgroundScope)
        onMasters2019(prefs)
        prefs.setMoonBoardHoldSets(masters2019, listOf(17L, 18L))
        val viewModel = vm(prefs)

        viewModel.state.test {
            awaitItem { it.variant != null && !it.isCompleteSetup }
            viewModel.setExpanded(true)
            viewModel.selectCompleteSetup()

            val state = awaitItem { it.isCompleteSetup }
            assertEquals(universe.toSet(), state.selectedSetIds)
            assertFalse("picking the bundle closes the per-set list", state.expanded)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(universe, prefs.getMoonBoardHoldSets(masters2019))
    }

    @Test
    fun `the last remaining set cannot be unticked`() = runTest {
        val prefs = createTestUserPreferences(backgroundScope)
        onMasters2019(prefs)
        prefs.setMoonBoardHoldSets(masters2019, listOf(17L))
        val viewModel = vm(prefs)

        viewModel.state.test {
            awaitItem { it.variant != null && it.selectedSetIds == setOf(17L) }
            viewModel.toggleSet(17L)

            val state = awaitItem { it.showMinimumOneWarning }
            assertEquals(
                "the selection must not change behind the warning",
                setOf(17L), state.selectedSetIds,
            )
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf(17L), prefs.getMoonBoardHoldSets(masters2019))
    }

    @Test
    fun `moonBoard 2010 offers no picker at all`() = runTest {
        val prefs = createTestUserPreferences(backgroundScope)
        prefs.setMoonBoardSelection(MoonBoardVariant.MOONBOARD_2010.layoutId.toInt())

        vm(prefs).state.test {
            val state = awaitItem { it.loaded }
            // One set is not a choice; a lone checkbox that cannot be unticked
            // would be worse than nothing (edge case 5).
            assertNull(state.variant)
            assertFalse(state.isVisible)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a kilter board offers no picker either`() = runTest {
        val prefs = createTestUserPreferences(backgroundScope)
        prefs.setBoardSelection("kilter", layoutId = 1, productSizeId = 10)

        vm(prefs).state.test {
            val state = awaitItem { it.loaded }
            assertNull(state.variant)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the picker reports the catalogue gate`() = runTest {
        val prefs = createTestUserPreferences(backgroundScope)
        onMasters2019(prefs)

        repo.moonBoardHoldSetMaskPresent = false
        vm(prefs).state.test {
            val blocked = awaitItem { it.variant != null }
            assertFalse(
                "without hold-set data in the catalogue the picker is disabled",
                blocked.catalogueHasHoldSetData,
            )
            cancelAndIgnoreRemainingEvents()
        }

        repo.moonBoardHoldSetMaskPresent = true
        vm(prefs).state.test {
            val open = awaitItem { it.variant != null && it.catalogueHasHoldSetData }
            assertTrue(open.catalogueHasHoldSetData)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the climbable count is countFilteredClimbs under the same mask`() = runTest {
        val prefs = createTestUserPreferences(backgroundScope)
        onMasters2019(prefs)
        repo.moonBoardHoldSetMaskPresent = true
        // Three problems: one needing Wooden Holds (bit 3), one not, one whose
        // sets were never derived.
        repo.addClimbs(
            climb("needs-wooden", hsm = 0b001001L),
            climb("hands-only", hsm = 0b000011L),
            climb("unknown", hsm = 0L),
        )
        val viewModel = vm(prefs)

        // AC 15: what the browse list will return for the same mask. Asserted
        // as a literal too, so a drifting fake cannot make this vacuous.
        val woodenOff = countWithMask(HoldSetMask.excludedMask(universe, universe - 21L))
        assertEquals("the Wooden climb drops, the unknown one stays", 2L, woodenOff)

        viewModel.state.test {
            val complete = awaitItem { it.counts != null && it.isCompleteSetup }
            assertEquals(3L, complete.counts!!.total)
            assertEquals(
                "the complete setup hides nothing",
                3L, complete.counts!!.climbable,
            )

            viewModel.toggleSet(21L)
            // Awaiting the converged value rather than the first post-toggle
            // emission: selection and counts settle through separate flows.
            val partial = awaitItem { !it.isCompleteSetup && it.counts?.climbable == woodenOff }
            assertEquals("the total is the unfiltered board", 3L, partial.counts!!.total)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun countWithMask(mask: Long): Long = repo.countFilteredClimbs(
        angle = 40,
        layoutId = masters2019.layoutId.toInt(),
        boardBrand = "moonboard",
        minDifficulty = 0.0,
        maxDifficulty = 100.0,
        minAscensionists = 0,
        climbType = ClimbTypeFilter.ALL,
        selProductSizeId = 0,
        hsmExcludedMask = mask,
        showUngraded = true,
    )

    private fun climb(uuid: String, hsm: Long) = ClimbWithStats(
        uuid = uuid,
        layoutId = masters2019.layoutId,
        setterUsername = "s",
        name = uuid,
        frames = "p100r42p101r43",
        framesCount = 1L,
        difficultyAverage = 15.0,
        qualityAverage = 2.5,
        ascensionistCount = 10L,
        hsm = hsm,
    )

    /** Awaits the first emission satisfying [predicate]; the VM emits an
     *  initial placeholder plus one item per async input settling. */
    private suspend fun app.cash.turbine.ReceiveTurbine<MoonBoardHoldSetState>.awaitItem(
        predicate: (MoonBoardHoldSetState) -> Boolean,
    ): MoonBoardHoldSetState {
        repeat(24) {
            val item = awaitItem()
            if (predicate(item)) return item
        }
        error("no matching state emitted")
    }
}
