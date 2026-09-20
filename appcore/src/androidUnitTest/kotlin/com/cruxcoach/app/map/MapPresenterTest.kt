package com.cruxcoach.app.map

import com.cruxcoach.app.browse.testing.MemoryKeyValueStore
import com.cruxcoach.app.logbook.CI_WAIT_MS
import com.cruxcoach.app.ui.MapScreenModel
import com.cruxcoach.app.ui.MapScreenState
import com.cruxcoach.data.repository.AccessType
import com.cruxcoach.data.repository.Adjustability
import com.cruxcoach.domain.board.BoardBrand
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * The presenter and its IO share ONE thread: a SQLDelight JDBC driver holds a
 * single connection, so a read from the test thread while the presenter reads
 * on another corrupts it.
 */
class MapPresenterTest {
    private val executor = Executors.newSingleThreadExecutor()
    private val serial = executor.asCoroutineDispatcher()
    private val db = MapTestDb()

    @AfterTest
    fun tearDown() {
        db.close()
        serial.close()
        executor.shutdownNow()
    }

    private fun model(
        store: MemoryKeyValueStore = MemoryKeyValueStore(),
        canonical: List<com.cruxcoach.data.repository.BoardLocation> = emptyList(),
    ): MapScreenModel = MapScreenModel(
        MapPresenter(
            repository = db.repository,
            pages = PagesBoardMapSource { PagesBoardMapSnapshot(canonical) },
            keyValues = store,
            scope = CoroutineScope(serial),
            ioDispatcher = serial,
        ),
    )

    /** Waits for the first state the presenter publishes after its initial load. */
    private suspend fun ready(model: MapScreenModel): MapScreenState = withTimeout(CI_WAIT_MS) {
        var state = withContext(serial) { model.currentState }
        while (state.isLoading) {
            delay(5)
            state = withContext(serial) { model.currentState }
        }
        state
    }

    private suspend fun act(model: MapScreenModel, block: (MapScreenModel) -> Unit): MapScreenState =
        withContext(serial) {
            block(model)
            model.currentState
        }

    /** Waits for work the presenter launched on its own scope (e.g. [MapScreenModel.refresh]). */
    private suspend fun await(model: MapScreenModel, predicate: (MapScreenState) -> Boolean): MapScreenState =
        withTimeout(CI_WAIT_MS) {
            var state = withContext(serial) { model.currentState }
            while (!predicate(state)) {
                delay(5)
                state = withContext(serial) { model.currentState }
            }
            state
        }

    @Test
    fun `pins collapse co-located boards and report the board count`() = runBlocking {
        db.insert(
            location("k", name = "Boulderwelt", lat = 52.5, lng = 13.4, layoutId = KILTER_ORIGINAL_LAYOUT),
            location("m", name = "Boulderwelt Moon", lat = 52.50002, lng = 13.4,
                brand = BoardBrand.MOONBOARD, layoutId = 2),
            location("far", name = "Other Gym", lat = 48.1, lng = 11.5, country = "DE", city = "Munich"),
        )
        val state = ready(model())

        assertEquals(2, state.pins.size)
        val shared = state.pins.single { it.boardCount > 1 }
        assertEquals("Boulderwelt", shared.title)
        assertEquals("Berlin · DE", shared.subtitle)
        assertEquals("multi", shared.brandWire)
        assertEquals(2, shared.boardCount)
        assertEquals(2, state.venueCount)
        assertEquals(2, state.totalVenueCount)
        // Three boards behind two pins.
        assertEquals(3, state.stats.total)
    }

    @Test
    fun `statistics describe every recorded board, not the filtered pins`() = runBlocking {
        db.insert(
            location("k1", lat = 52.5, lng = 13.4, layoutId = KILTER_ORIGINAL_LAYOUT,
                access = AccessType.PUBLIC, adjustability = Adjustability.ADJUSTABLE),
            location("k2", lat = 48.1, lng = 11.5, layoutId = KILTER_HOMEWALL_LAYOUT,
                access = AccessType.PRIVATE, adjustability = Adjustability.FIXED),
            location("m1", lat = 40.0, lng = 10.0, brand = BoardBrand.MOONBOARD, layoutId = 2),
        )
        val model = model()
        ready(model)

        val filtered = act(model) { it.toggleBrand("moonboard") }
        assertEquals(listOf("moonboard"), filtered.filters.brands)
        assertEquals(1, filtered.venueCount)
        assertEquals(3, filtered.totalVenueCount)
        // Unchanged by the brand filter: the stats scope is the whole dataset.
        assertEquals(3, filtered.stats.total)
        assertEquals(1, filtered.stats.originalCount)
        assertEquals(1, filtered.stats.homewallCount)
        assertEquals(1, filtered.stats.publicCount)
        assertEquals(1, filtered.stats.privateCount)
        assertEquals(1, filtered.stats.adjustableCount)
        assertEquals(1, filtered.stats.fixedCount)
        assertEquals(3, filtered.stats.byCountry.sumOf { it.count })
    }

    @Test
    fun `filters persist under the Android preference keys and reset clears them`() = runBlocking {
        db.insert(location("k1", layoutId = KILTER_ORIGINAL_LAYOUT, productSizeId = 10, sizeLabel = "12x12"))
        val store = MemoryKeyValueStore()
        val model = model(store)
        ready(model)

        act(model) { it.toggleShowHomewalls() }
        act(model) { it.toggleCountry("DE") }
        act(model) { it.toggleSizeId(10) }
        act(model) { it.toggleWellpassOnly() }
        act(model) { it.toggleAccessType("public") }
        act(model) { it.toggleMoonLedState("noLed") }

        assertEquals("false", store.values[MapFilterStore.SHOW_HOMEWALLS])
        assertEquals("DE", store.values[MapFilterStore.COUNTRIES])
        assertEquals("10", store.values[MapFilterStore.SIZE_IDS])
        assertEquals("true", store.values[MapFilterStore.WELLPASS_ONLY])
        assertEquals("PUBLIC", store.values[MapFilterStore.ACCESS_TYPES])
        assertEquals("NO_LED", store.values[MapFilterStore.MOON_LED_STATES])

        val afterReset = act(model) { it.resetFilters() }
        assertTrue(afterReset.filters.isAtDefault)
        assertFalse(store.values.containsKey(MapFilterStore.COUNTRIES))
        assertFalse(store.values.containsKey(MapFilterStore.SHOW_HOMEWALLS))
    }

    @Test
    fun `stored filters are read back on the next load`() = runBlocking {
        db.insert(
            location("k-orig", lat = 1.0, lng = 1.0, layoutId = KILTER_ORIGINAL_LAYOUT),
            location("k-home", lat = 2.0, lng = 2.0, layoutId = KILTER_HOMEWALL_LAYOUT),
        )
        val store = MemoryKeyValueStore()
        store.values[MapFilterStore.SHOW_ORIGINAL] = "false"
        // A brand that no longer exists must not collapse the map onto Kilter.
        store.values[MapFilterStore.BRANDS] = "kilter,not-a-brand"

        val state = ready(model(store))
        assertFalse(state.filters.showOriginal)
        assertEquals(listOf("kilter"), state.filters.brands)
        assertEquals(listOf("k-home"), state.pins.map { it.title })
    }

    @Test
    fun `matches my board is refused until a board size has been chosen`() = runBlocking {
        db.insert(
            location("mine", lat = 1.0, lng = 1.0, layoutId = KILTER_ORIGINAL_LAYOUT, productSizeId = 10),
            location("theirs", lat = 2.0, lng = 2.0, layoutId = KILTER_HOMEWALL_LAYOUT, productSizeId = 21),
        )
        val store = MemoryKeyValueStore()
        val model = model(store)
        val initial = ready(model)
        assertFalse(initial.canFilterByMyBoard)

        val ignored = act(model) { it.toggleMatchesMyBoard() }
        assertFalse(ignored.filters.matchesMyBoard)
        assertEquals(2, ignored.pins.size)

        store.values["board_brand"] = "kilter"
        store.values["board_layout_id"] = KILTER_ORIGINAL_LAYOUT.toString()
        store.values["board_product_size_id"] = "10"
        act(model) { it.refresh() }
        val configured = await(model) { it.canFilterByMyBoard }
        assertTrue(configured.canFilterByMyBoard)

        val matched = act(model) { it.toggleMatchesMyBoard() }
        assertEquals(listOf("mine"), matched.pins.map { it.title })
    }

    @Test
    fun `the bundled snapshot is merged with the synced rows`() = runBlocking {
        db.insert(
            // Same venue and family as the snapshot row: contributes contact data only.
            location("db-same", lat = 10.0, lng = 10.0, url = "https://synced.example"),
            location("db-new", lat = 30.0, lng = 30.0, name = "Synced only"),
        )
        val canonical = listOf(location("pages-1", name = "Canonical", lat = 10.0, lng = 10.0, url = null))
        val model = model(canonical = canonical)
        val state = ready(model)

        assertEquals(2, state.pins.size)
        assertEquals(listOf("Canonical", "Synced only"), state.pins.map { it.title })
        val canonicalPin = state.pins.first { it.title == "Canonical" }
        assertEquals(1, canonicalPin.boardCount)

        // The snapshot row keeps venue identity but takes the synced contact data.
        val venue = requireNotNull(act(model) { it.selectVenue(canonicalPin.id) }.selectedVenue)
        assertEquals("https://synced.example", venue.url)
    }

    @Test
    fun `selecting a venue exposes its boards and a cleared filter drops the selection`() = runBlocking {
        db.insert(
            location("k", name = "Mixed Gym", lat = 5.0, lng = 5.0, layoutId = KILTER_ORIGINAL_LAYOUT,
                sizeLabel = "12x12", productSizeId = 10, adjustability = Adjustability.ADJUSTABLE,
                address = "Street 1", url = "https://mixed.example"),
            location("m", name = "Mixed Gym Moon", lat = 5.0, lng = 5.0, brand = BoardBrand.MOONBOARD,
                layoutId = 2, layoutName = "MoonBoard 2016", access = AccessType.PUBLIC,
                adjustability = Adjustability.FIXED, fixedAngle = 40),
        )
        val model = model()
        val initial = ready(model)
        val pin = initial.pins.single()

        val selected = act(model) { it.selectVenue(pin.id) }
        val venue = requireNotNull(selected.selectedVenue)
        assertEquals("Mixed Gym", venue.name)
        assertEquals(2, venue.boardCount)
        assertEquals(listOf("kilter", "moonboard"), venue.boards.map { it.brandWire })
        assertEquals("Street 1", venue.address)
        assertEquals("https://mixed.example", venue.url)
        assertEquals(listOf("adjustable", "fixed"), venue.boards.map { it.adjustabilityCode })
        assertEquals(listOf(0, 40), venue.boards.map { it.fixedAngle })

        // Filtering the venue away must close the sheet instead of stranding it.
        val afterFilter = act(model) { it.toggleBrand("tension") }
        assertTrue(afterFilter.pins.isEmpty())
        assertNull(afterFilter.selectedVenue)
    }

    @Test
    fun `filter chips are derived from the whole dataset`() = runBlocking {
        db.insert(
            location("k1", lat = 1.0, lng = 1.0, country = "DE", sizeLabel = "12x12", productSizeId = 10),
            location("k2", lat = 2.0, lng = 2.0, country = "DE", sizeLabel = "12x12", productSizeId = 10),
            location("m1", lat = 1.0, lng = 1.0, country = "DE", brand = BoardBrand.MOONBOARD,
                layoutId = 2, layoutName = "MoonBoard 2016"),
            location("m2", lat = 3.0, lng = 3.0, country = "US", brand = BoardBrand.MOONBOARD, layoutId = 3),
        )
        val state = ready(model())

        assertEquals(listOf("kilter", "moonboard"), state.brandOptions.map { it.code })
        // Brand chips count venues holding that family: Kilter at two, MoonBoard at two.
        assertEquals(listOf(2, 2), state.brandOptions.map { it.count })
        assertEquals(listOf("MoonBoard 2016", "3"), state.moonVariantOptions.map { it.label })
        assertEquals(listOf("10" to 2), state.sizeOptions.map { it.code to it.count })
        assertEquals(listOf("DE" to 3, "US" to 1), state.countryOptions.map { it.code to it.count })
    }

    @Test
    fun `an empty dataset reports no data instead of an error`() = runBlocking {
        val state = ready(model())
        assertFalse(state.hasData)
        assertEquals("none", state.errorCode)
        assertTrue(state.pins.isEmpty())
        assertEquals(0, state.stats.total)
    }
}
