package com.cruxcoach.app.gym

import com.cruxcoach.app.logbook.CI_WAIT_MS
import com.cruxcoach.app.logbook.SerialDispatcher
import com.cruxcoach.app.ui.GymSearchScreenModel
import com.cruxcoach.data.repository.AccessType
import com.cruxcoach.data.repository.Adjustability
import com.cruxcoach.data.repository.BoardLocation
import com.cruxcoach.data.repository.BoardWall
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant
import com.cruxcoach.domain.board.QuantumBoardModel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Port of Android `GymBoardPickerViewModelTest`, assertions unchanged. Turbine's
 * "await the next item until X" becomes "poll the state until X"; presenter scope
 * and IO share one thread so the fake repository is never read concurrently.
 */
class GymBoardPickerPresenterTest {
    private val serial = SerialDispatcher()
    private val repo = FakeBoardLocationRepository()
    private val bundled = mutableListOf<BoardLocation>()
    private var vm: GymBoardPickerPresenter? = null

    @AfterTest
    fun tearDown() {
        vm?.close()
    }

    private fun createViewModel(): GymBoardPickerPresenter = GymBoardPickerPresenter(
        repository = repo,
        loadBundled = { bundled.toList() },
        scope = CoroutineScope(SupervisorJob() + serial.dispatcher),
        ioDispatcher = serial.dispatcher,
    ).also { vm = it }

    private suspend fun GymBoardPickerPresenter.await(until: (GymBoardPickerState) -> Boolean): GymBoardPickerState =
        withTimeout(CI_WAIT_MS) {
            var seen = withContext(serial.dispatcher) { state.value }
            while (!until(seen)) {
                delay(5)
                seen = withContext(serial.dispatcher) { state.value }
            }
            seen
        }

    /** Lets the presenter's queued init work finish (one hop through its thread). */
    private suspend fun settle() = withContext(serial.dispatcher) { }

    private fun loc(
        id: String,
        name: String = "Gym $id",
        layoutId: Int? = 1,
        boardBrand: BoardBrand = BoardBrand.KILTER,
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

    private fun wall(gymUuid: String, productSizeId: Int? = 10, layoutId: Int? = 1) = BoardWall(
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
    fun `init disables picker when no locations`() = runBlocking {
        val vm = createViewModel()
        settle()
        val first = vm.await { true }
        assertEquals(false, first.enabled)
    }

    @Test
    fun `init enables picker when locations present`() = runBlocking {
        repo.locations += loc("g1")
        val vm = createViewModel()
        val seen = vm.await { it.enabled }
        assertEquals(true, seen.enabled)
    }

    @Test
    fun `selectGym on MoonBoard gym with resolved variant offers that variant`() = runBlocking {
        repo.locations += loc("mb1", name = "Boulderwelt", layoutId = 5, boardBrand = BoardBrand.MOONBOARD)
        val vm = createViewModel()
        vm.await { it.enabled }
        withContext(serial.dispatcher) { vm.selectGym(repo.locations.first { it.id == "mb1" }) }
        val opts = vm.await { it.wallOptions.isNotEmpty() }.wallOptions
        assertEquals(1, opts.size)
        assertEquals(5, opts.first().layoutId)
        assertEquals(MOONBOARD_NO_SIZE, opts.first().productSizeId)
    }

    @Test
    fun `selectGym on MoonBoard gym with unknown variant offers all variants`() = runBlocking {
        repo.locations += loc("mb2", name = "Home Board", layoutId = null, boardBrand = BoardBrand.MOONBOARD)
        val vm = createViewModel()
        vm.await { it.enabled }
        withContext(serial.dispatcher) { vm.selectGym(repo.locations.first { it.id == "mb2" }) }
        val opts = vm.await { it.wallOptions.isNotEmpty() }.wallOptions
        assertEquals(MoonBoardVariant.entries.size, opts.size)
    }

    @Test
    fun `onQueryChange under 2 chars clears results`() = runBlocking {
        repo.locations += loc("g1", "Boulderwelt München")
        val vm = createViewModel()
        vm.await { it.enabled }
        withContext(serial.dispatcher) { vm.onQueryChange("B") }
        val seen = vm.await { it.results.isEmpty() && !it.searching }
        assertTrue(seen.results.isEmpty())
        assertEquals(false, seen.searching)
        assertEquals("B", seen.query)
    }

    @Test
    fun `onQueryChange triggers search at 2 chars and surfaces results`() = runBlocking {
        repo.locations += loc("g1", "Boulderwelt München")
        repo.locations += loc("g2", "Klettercentrum Stuttgart")
        val vm = createViewModel()
        vm.await { it.enabled }
        withContext(serial.dispatcher) { vm.onQueryChange("Bo") }
        val seen = vm.await { it.results.isNotEmpty() }
        assertEquals(1, seen.results.size)
        assertEquals("g1", seen.results.first().id)
    }

    @Test
    fun `selectGym filters walls with null layout or null productSize`() = runBlocking {
        repo.locations += loc("g1")
        repo.walls += wall(gymUuid = "g1", productSizeId = 10)
        repo.walls += wall(gymUuid = "g1", productSizeId = null)
        repo.walls += wall(gymUuid = "g1", layoutId = null)
        val vm = createViewModel()
        vm.await { it.enabled }
        withContext(serial.dispatcher) { vm.selectGym(repo.locations.first()) }
        val seen = vm.await { it.selectedGym != null }
        assertEquals(1, seen.wallOptions.size)
        assertEquals(10, seen.wallOptions.first().productSizeId)
    }

    @Test
    fun `selectGym sorts wall options by frequency descending`() = runBlocking {
        repo.locations += loc("g1")
        // Frequency seed: psid=10 appears thrice globally, psid=20 once.
        repo.walls += wall(gymUuid = "g0", productSizeId = 10)
        repo.walls += wall(gymUuid = "g0", productSizeId = 10)
        repo.walls += wall(gymUuid = "g0", productSizeId = 10)
        repo.walls += wall(gymUuid = "g0", productSizeId = 20)
        // Selected gym has one of each.
        repo.walls += wall(gymUuid = "g1", productSizeId = 20)
        repo.walls += wall(gymUuid = "g1", productSizeId = 10)
        val vm = createViewModel()
        vm.await { it.enabled }
        withContext(serial.dispatcher) { vm.selectGym(repo.locations.first { it.id == "g1" }) }
        val seen = vm.await { it.wallOptions.isNotEmpty() }
        val ids = seen.wallOptions.map { it.productSizeId }
        assertEquals(listOf(10, 20), ids)
    }

    @Test
    fun `near me falls back to the bundled snapshot and offers its Kilter walls`() = runBlocking {
        // Nothing synced yet (first start): the bundled map rows stand in.
        bundled += mapBoard("pages-k0", 48.14, 11.58, name = "City Boulder", productSizeId = 10, sizeLabel = "12x12")
        bundled += mapBoard("pages-k1", 48.14, 11.58, name = "City Boulder", productSizeId = 26, sizeLabel = "10x12")
        bundled += mapBoard("pages-far", 52.52, 13.40, name = "Berlin Gym")
        val vm = createViewModel()
        vm.await { it.enabled }
        withContext(serial.dispatcher) { vm.showNearby(48.137, 11.575) }
        var seen = vm.await { !it.searching && it.results.isNotEmpty() }
        assertEquals(listOf("City Boulder"), seen.results.map { it.name })
        assertTrue(seen.nearby)
        assertTrue(seen.distanceById.getValue(seen.results.single().id) < 1_000.0)
        assertEquals(listOf(BoardBrand.KILTER), seen.brandsById[seen.results.single().id])

        withContext(serial.dispatcher) { vm.selectGym(seen.results.single()) }
        seen = vm.await { it.wallOptions.isNotEmpty() }
        assertEquals(setOf(10, 26), seen.wallOptions.map { it.productSizeId }.toSet())
    }

    @Test
    fun `a place from the map opens with its boards and Tension variants and Quantum models included`() = runBlocking {
        val rows = listOf(
            mapBoard("pages-t", 48.14, 11.58, name = "Mixed Gym", brand = BoardBrand.TENSION),
            mapBoard("pages-q", 48.14, 11.58, name = "Mixed Gym", brand = BoardBrand.QUANTUM),
        )
        val vm = createViewModel()
        settle()
        withContext(serial.dispatcher) { vm.openGym(rows) }
        val seen = vm.await { it.wallOptions.isNotEmpty() }
        assertEquals("Mixed Gym", seen.selectedGym?.name)
        val byBrand = seen.wallOptions.groupBy { it.boardBrand }
        assertEquals(
            GymBoardCatalog.auroraVariants(BoardBrand.TENSION).size,
            byBrand.getValue(BoardBrand.TENSION).size,
        )
        assertEquals(
            QuantumBoardModel.entries.map { it.layoutId.toInt() },
            byBrand.getValue(BoardBrand.QUANTUM).map { it.layoutId },
        )
    }

    @Test
    fun `typing leaves near-me mode`() = runBlocking {
        bundled += mapBoard("pages-k0", 48.14, 11.58, name = "City Boulder")
        val vm = createViewModel()
        vm.await { it.enabled }
        withContext(serial.dispatcher) { vm.showNearby(48.137, 11.575) }
        vm.await { !it.searching && it.results.isNotEmpty() }
        withContext(serial.dispatcher) { vm.onQueryChange("Ci") }
        val seen = vm.await { !it.nearby && it.distanceById.isEmpty() }
        assertEquals("Ci", seen.query)
    }

    @Test
    fun `clearGymSelection resets selectedGym and wallOptions`() = runBlocking {
        repo.locations += loc("g1")
        repo.walls += wall(gymUuid = "g1")
        val vm = createViewModel()
        vm.await { it.enabled }
        withContext(serial.dispatcher) { vm.selectGym(repo.locations.first()) }
        vm.await { it.selectedGym != null }
        withContext(serial.dispatcher) { vm.clearGymSelection() }
        val seen = vm.await { it.selectedGym == null }
        assertNull(seen.selectedGym)
        assertTrue(seen.wallOptions.isEmpty())
    }

    // ── iOS facade on top (not on Android: the Swift side of the same sheet) ──

    @Test
    fun `the facade hands a picked option over as the manual picker's board`() = runBlocking {
        val rows = listOf(
            mapBoard("pages-g", 48.14, 11.58, name = "Hopper Gym", brand = BoardBrand.GRASSHOPPER),
            mapBoard("pages-m", 48.14, 11.58, name = "Hopper Gym", brand = BoardBrand.MOONBOARD, layoutId = 5),
        )
        val model = GymSearchScreenModel(createViewModel())
        settle()
        withContext(serial.dispatcher) { model.openGym(rows) }
        val state = withTimeout(CI_WAIT_MS) {
            var s = withContext(serial.dispatcher) { model.currentState }
            while (s.options.isEmpty()) { delay(5); s = withContext(serial.dispatcher) { model.currentState } }
            s
        }
        assertEquals("categories", state.optionsPromptCode)
        val grasshopper = state.options.filter { it.brandWire == BoardBrand.GRASSHOPPER.wireValue }
        // Layout 0 / explicit size on Android; iOS stores the single layout directly.
        assertEquals(listOf(4, 5, 6), grasshopper.map { it.board.productSizeId })
        assertTrue(grasshopper.all { it.board.layoutId == 1 })
        assertEquals("Grasshopper GrandMaster (12 x 12)", grasshopper.first().label)
        val moon = state.options.single { it.brandWire == BoardBrand.MOONBOARD.wireValue }
        assertEquals(5, moon.board.layoutId)
        assertEquals(0, moon.board.productSizeId)
        assertTrue(state.selectionKey.isNotEmpty())
    }

    @Test
    fun `distances round like Android`() {
        assertEquals(350.0, GymSearchScreenModel.distance(347.0).value)
        assertEquals("m", GymSearchScreenModel.distance(994.0).unitCode)
        assertEquals(1.0, GymSearchScreenModel.distance(996.0).value)
        assertEquals(1.2, GymSearchScreenModel.distance(1_234.0).value)
        assertEquals(1, GymSearchScreenModel.distance(1_234.0).fractionDigits)
        assertEquals(12.0, GymSearchScreenModel.distance(12_345.0).value)
        assertEquals(0, GymSearchScreenModel.distance(12_345.0).fractionDigits)
    }
}
