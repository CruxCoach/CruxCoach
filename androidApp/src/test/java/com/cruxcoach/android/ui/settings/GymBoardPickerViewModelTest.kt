package com.cruxcoach.android.ui.settings

import app.cash.turbine.test
import com.cruxcoach.android.fakes.FakeBoardLocationRepository
import com.cruxcoach.data.repository.AccessType
import com.cruxcoach.data.repository.Adjustability
import com.cruxcoach.data.repository.BoardLocation
import com.cruxcoach.data.repository.BoardWall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [GymBoardPickerViewModel].
 *
 * The VM body uses `withContext(Dispatchers.IO) { ... }` for repository
 * reads, which means the work executes on a real IO thread regardless of
 * the test scheduler. Tests therefore use Turbine's `.test {}` to await
 * the eventual StateFlow emission rather than asserting eagerly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GymBoardPickerViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val repo = FakeBoardLocationRepository()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun loc(
        id: String,
        name: String = "Gym $id",
        layoutId: Int? = 1,
        boardBrand: com.cruxcoach.domain.board.BoardBrand =
            com.cruxcoach.domain.board.BoardBrand.KILTER,
    ) = BoardLocation(
        id = id, name = name, lat = 0.0, lng = 0.0,
        address = null, city = null, countryCode = "DE",
        phone = null, email = null, url = null, instagram = null,
        layoutName = null, layoutId = layoutId,
        sizeLabel = null, productSizeId = null,
        accessType = AccessType.PUBLIC, adjustability = Adjustability.ADJUSTABLE,
        fixedAngle = null, frameMaker = null,
        boardBrand = boardBrand,
    )

    private fun wall(
        gymUuid: String,
        productSizeId: Int? = 10,
        layoutId: Int? = 1,
    ) = BoardWall(
        wallUuid = java.util.UUID.randomUUID().toString(),
        gymUuid = gymUuid,
        name = null, productName = "Original 12x12", layoutId = layoutId,
        productLayoutUuid = null, productSizeId = productSizeId,
        sizeLabel = "12x12",
        isAdjustable = true, minAngle = 0, maxAngle = 70, angleIncrements = 5,
        fixedAngle = null,
        accumulatedHoldSetValue = null, serialNumber = null, isListed = true,
    )

    @Test
    fun `init disables picker when no locations`() = runTest {
        // No locations synced at all → nothing to search, picker stays off.
        val vm = GymBoardPickerViewModel(repo)
        vm.state.test {
            val first = awaitItem()
            assertEquals(false, first.enabled)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `init enables picker when locations present`() = runTest {
        // Gated on locations, not walls — a MoonBoard-only dataset (no
        // walls) must still enable the "find my gym" path.
        repo.locations += loc("g1")
        val vm = GymBoardPickerViewModel(repo)
        vm.state.test {
            // Drain emissions until enabled flips.
            var seen = awaitItem()
            while (!seen.enabled) seen = awaitItem()
            assertEquals(true, seen.enabled)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `selectGym on MoonBoard gym with resolved variant offers that variant`() = runTest {
        // Masters 2019 = layout 5. A resolved-variant MoonBoard gym offers
        // exactly that board, keyed by its layout id (no product size).
        repo.locations += loc(
            "mb1", name = "Boulderwelt", layoutId = 5,
            boardBrand = com.cruxcoach.domain.board.BoardBrand.MOONBOARD,
        )
        val vm = GymBoardPickerViewModel(repo)
        vm.state.test {
            var seen = awaitItem()
            while (!seen.enabled) seen = awaitItem()
            vm.selectGym(repo.locations.first { it.id == "mb1" })
            while (awaitItem().wallOptions.isEmpty()) { /* await resolution */ }
            val opts = vm.state.value.wallOptions
            assertEquals(1, opts.size)
            assertEquals(5, opts.first().layoutId)
            assertEquals(MOONBOARD_NO_SIZE, opts.first().productSizeId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `selectGym on MoonBoard gym with unknown variant offers all variants`() = runTest {
        // Variant-unknown MoonBoard gym (layout null) lets the climber pick
        // from every supported variant.
        repo.locations += loc(
            "mb2", name = "Home Board", layoutId = null,
            boardBrand = com.cruxcoach.domain.board.BoardBrand.MOONBOARD,
        )
        val vm = GymBoardPickerViewModel(repo)
        vm.state.test {
            var seen = awaitItem()
            while (!seen.enabled) seen = awaitItem()
            vm.selectGym(repo.locations.first { it.id == "mb2" })
            while (awaitItem().wallOptions.isEmpty()) { /* await resolution */ }
            val opts = vm.state.value.wallOptions
            assertEquals(
                com.cruxcoach.domain.board.MoonBoardVariant.entries.size,
                opts.size,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onQueryChange under 2 chars clears results`() = runTest {
        repo.locations += loc("g1", "Boulderwelt München")
        val vm = GymBoardPickerViewModel(repo)
        vm.state.test {
            awaitItem() // initial / init-update
            vm.onQueryChange("B")
            // The state update is synchronous for the <2-chars path
            // (no IO involved), so we expect at least one emission
            // where results stay empty and searching is false.
            var seen = awaitItem()
            while (seen.results.isNotEmpty() || seen.searching) seen = awaitItem()
            assertTrue(seen.results.isEmpty())
            assertEquals(false, seen.searching)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onQueryChange triggers search at 2 chars and surfaces results`() = runTest {
        repo.locations += loc("g1", "Boulderwelt München")
        repo.locations += loc("g2", "Klettercentrum Stuttgart")
        val vm = GymBoardPickerViewModel(repo)
        vm.state.test {
            awaitItem()
            vm.onQueryChange("Bo")
            var seen = awaitItem()
            while (seen.results.isEmpty()) seen = awaitItem()
            assertEquals(1, seen.results.size)
            assertEquals("g1", seen.results.first().id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `selectGym filters walls with null layout or null productSize`() = runTest {
        repo.locations += loc("g1")
        repo.walls += wall(gymUuid = "g1", productSizeId = 10)
        repo.walls += wall(gymUuid = "g1", productSizeId = null)
        repo.walls += wall(gymUuid = "g1", layoutId = null)
        val vm = GymBoardPickerViewModel(repo)
        vm.state.test {
            awaitItem()
            vm.selectGym(repo.locations.first())
            var seen = awaitItem()
            while (seen.selectedGym == null) seen = awaitItem()
            assertEquals(1, seen.wallOptions.size)
            assertEquals(10, seen.wallOptions.first().productSizeId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `selectGym sorts wall options by frequency descending`() = runTest {
        repo.locations += loc("g1")
        // Frequency seed: psid=10 appears thrice globally, psid=20 once.
        repo.walls += wall(gymUuid = "g0", productSizeId = 10)
        repo.walls += wall(gymUuid = "g0", productSizeId = 10)
        repo.walls += wall(gymUuid = "g0", productSizeId = 10)
        repo.walls += wall(gymUuid = "g0", productSizeId = 20)
        // Selected gym has one of each.
        repo.walls += wall(gymUuid = "g1", productSizeId = 20)
        repo.walls += wall(gymUuid = "g1", productSizeId = 10)
        val vm = GymBoardPickerViewModel(repo)
        vm.state.test {
            // Wait for init to settle (enabled=true) so frequency is populated.
            var seen = awaitItem()
            while (!seen.enabled) seen = awaitItem()
            vm.selectGym(repo.locations.first { it.id == "g1" })
            while (seen.wallOptions.isEmpty()) seen = awaitItem()
            val ids = seen.wallOptions.map { it.productSizeId }
            assertEquals(listOf(10, 20), ids)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `clearGymSelection resets selectedGym and wallOptions`() = runTest {
        repo.locations += loc("g1")
        repo.walls += wall(gymUuid = "g1")
        val vm = GymBoardPickerViewModel(repo)
        vm.state.test {
            awaitItem()
            vm.selectGym(repo.locations.first())
            var seen = awaitItem()
            while (seen.selectedGym == null) seen = awaitItem()
            vm.clearGymSelection()
            seen = awaitItem()
            while (seen.selectedGym != null) seen = awaitItem()
            assertNull(seen.selectedGym)
            assertTrue(seen.wallOptions.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
