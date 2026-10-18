package com.cruxcoach.app.map

import com.cruxcoach.app.browse.testing.MemoryKeyValueStore
import com.cruxcoach.app.logbook.CI_WAIT_MS
import com.cruxcoach.app.ui.MapScreenModel
import com.cruxcoach.app.ui.MapScreenState
import com.cruxcoach.domain.board.BoardBrand
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * The 0.2.4 map behaviour behind the iOS facade: first view from the timezone,
 * camera memory, the in-view list, "My location", search picks and the venue
 * detail facts, on the real Pages parser output.
 */
class MapScreenViewTest {
    private val executor = Executors.newSingleThreadExecutor()
    private val serial = executor.asCoroutineDispatcher()
    private val db = MapTestDb()

    @AfterTest
    fun tearDown() {
        db.close()
        serial.close()
        executor.shutdownNow()
    }

    private val snapshot = PagesBoardMapParser.parse(
        """{"type":"FeatureCollection","features":[
            {"type":"Feature","geometry":{"type":"Point","coordinates":[16.37,48.20]},"properties":{
              "name":"Wien Boulder","city":"Vienna","country":"AT","website":"https://wien.example",
              "hours":["10:00-22:00","10:00-22:00","10:00-22:00","10:00-22:00","10:00-22:00","",""],
              "boards":[{"board":"kilter","address":"Ring 1","walls":[{"wall_name":"Big wall","layout":"Original","size_id":10,"size_label":"12x12","adjustable":true,"min_angle":0,"max_angle":70,"angle_increments":5}]},
                        {"board":"tension"}]}},
            {"type":"Feature","geometry":{"type":"Point","coordinates":[16.30,48.18]},"properties":{
              "name":"West Wall","city":"Vienna","country":"AT","website":"http://192.168.0.1/",
              "boards":[{"board":"moonboard","variant":"mb2016","led":true,"angle":40,"commercial":true}]}},
            {"type":"Feature","geometry":{"type":"Point","coordinates":[13.40,52.52]},"properties":{
              "name":"Berlin Gym","city":"Berlin","country":"DE","boards":[{"board":"kilter","walls":[]}]}}
        ]}""",
        """{"cities":[["Vienna","AT",48.2,16.37,"Vienna",["Wien"],"Wien"]]}""",
    )

    private fun model(store: MemoryKeyValueStore = MemoryKeyValueStore()): MapScreenModel = MapScreenModel(
        MapPresenter(
            repository = db.repository,
            pages = PagesBoardMapSource { snapshot },
            keyValues = store,
            scope = CoroutineScope(serial),
            ioDispatcher = serial,
            timeZoneId = { "Europe/Vienna" },
            localeCountry = { "DE" },
        ),
    )

    private suspend fun await(model: MapScreenModel, predicate: (MapScreenState) -> Boolean): MapScreenState =
        withTimeout(CI_WAIT_MS) {
            var state = withContext(serial) { model.currentState }
            while (!predicate(state)) {
                delay(5)
                state = withContext(serial) { model.currentState }
            }
            state
        }

    private suspend fun act(model: MapScreenModel, block: (MapScreenModel) -> Unit) = withContext(serial) { block(model) }

    @Test
    fun `a first visit opens on the timezone's country and a remembered camera wins later`() = runBlocking {
        val store = MemoryKeyValueStore()
        val first = await(model(store)) { !it.isLoading && it.hasStartCamera }
        // Both Austrian venues, not Berlin.
        assertTrue(first.startCamera.latitude in 48.0..48.4, "lat ${first.startCamera.latitude}")
        assertTrue(first.startCamera.longitude in 16.0..16.6, "lng ${first.startCamera.longitude}")

        val m = model(store)
        await(m) { !it.isLoading }
        act(m) { it.rememberCamera(52.52, 13.40, 0.5, 0.5) }
        val again = await(model(store)) { !it.isLoading && it.hasStartCamera }
        assertEquals(52.52, again.startCamera.latitude, 1e-9)
        assertEquals(0.5, again.startCamera.latitudeDelta, 1e-9)
    }

    @Test
    fun `the panel lists venues in view nearest first and my location reorders and frames`() = runBlocking {
        val m = model()
        await(m) { !it.isLoading }
        act(m) { it.setViewport(47.0, 15.0, 49.0, 18.0, 48.20, 16.37) }
        var s = await(m) { it.hasViewport }
        assertEquals(2, s.inViewTotal)
        assertEquals(listOf("Wien Boulder", "West Wall"), s.inView.map { it.name })
        assertFalse(s.inViewFromUser)
        assertEquals(listOf("kilter", "tension"), s.inView.first().markerBrandWires)

        val before = s.cameraRequestId
        act(m) { it.showMyLocation(48.18, 16.29) }
        s = await(m) { it.cameraRequestId > before }
        assertTrue(s.hasUserPosition)
        assertTrue(s.inViewFromUser)
        assertEquals(listOf("West Wall", "Wien Boulder"), s.inView.map { it.name })
        assertTrue(s.inView.first().hasDistance)
        assertEquals("m", s.inView.first().distance.unitCode)
        // The frame holds the user and the nearest boards.
        assertTrue(s.cameraRequest.latitude - s.cameraRequest.latitudeDelta / 2 <= 48.18)
        assertTrue(s.cameraRequest.latitude + s.cameraRequest.latitudeDelta / 2 >= 48.20)

        // Out-of-range positions are ignored.
        val id = s.cameraRequestId
        act(m) { it.showMyLocation(Double.NaN, 16.0) }
        assertEquals(id, withContext(serial) { m.currentState.cameraRequestId })
    }

    @Test
    fun `search finds a venue and a place and a venue pick opens its details`() = runBlocking {
        val m = model()
        await(m) { !it.isLoading }
        act(m) { it.search("wien") }
        var s = await(m) { it.searchResults.isNotEmpty() }
        assertEquals("venue", s.searchResults.first().kind)
        assertTrue(s.searchResults.any { it.kind == "place" && it.title == "Vienna" })

        val before = s.cameraRequestId
        act(m) { it.pickSearchResult(0) }
        s = await(m) { it.cameraRequestId > before }
        assertEquals("", s.searchQuery)
        val venue = assertNotNull(s.selectedVenue)
        assertEquals("Wien Boulder", venue.name)
        assertEquals("https://wien.example", venue.websiteUrl)
        assertEquals("Ring 1", venue.address)
        assertTrue(venue.hasHours)

        val kilter = venue.boards.first { it.brandWire == "kilter" }
        assertEquals("Kilter · Original", kilter.titleText)
        assertEquals("12x12, with Kickboard", kilter.sizeText)
        assertEquals("range", kilter.angleCode)
        assertEquals(70, kilter.angleMax)
        assertEquals(5, kilter.angleStep)
        assertEquals("Big wall", kilter.wallName)
        assertEquals("browse", kilter.actionCode)
        assertEquals(10, kilter.browseBoard?.productSizeId)
        // Tension has no layout in the data: the gym search picks the board.
        assertEquals("choose", venue.boards.first { it.brandWire == "tension" }.actionCode)

        val rows = withContext(serial) {
            m.hoursRows(venue.id, listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"), "zu", "24 h", "Folgetag")
        }
        assertEquals(listOf("Mo–Fr" to "10:00–22:00", "Sa–So" to "zu"), rows.map { it.days to it.ranges })
        assertEquals(BoardBrand.entries.size, s.markerStyles.size)
    }

    @Test
    fun `a private website is never offered and the MoonBoard facts read like Android`() = runBlocking {
        val m = model()
        val loaded = await(m) { !it.isLoading }
        val westId = loaded.pins.first { it.title == "West Wall" }.id
        act(m) { it.selectVenue(westId) }
        val venue = assertNotNull(await(m) { it.selectedVenue != null }.selectedVenue)
        assertEquals("", venue.websiteUrl)
        val moon = venue.boards.single()
        assertTrue(moon.isMoonBoard)
        assertEquals("MoonBoard 2016", moon.titleText)
        assertEquals("led", moon.ledCode)
        assertEquals(40, moon.moonAngle)
        assertEquals("public", moon.accessCode)
        assertEquals(1, withContext(serial) { m.venueRowsForTest(westId) })
    }

    @Test
    fun `the filter chip counts filters besides the brand choice`() = runBlocking {
        val m = model()
        await(m) { !it.isLoading }
        act(m) { it.toggleBrand("kilter"); it.toggleWellpassOnly(); it.toggleCountry("AT") }
        assertEquals(2, await(m) { it.filters.wellpassOnly }.filterCount)
    }
}

/** The rows "Choose this board" hands to the gym search. */
private fun MapScreenModel.venueRowsForTest(venueId: String): Int = venueRows(venueId).size
