package com.cruxcoach.app.playlist

import com.cruxcoach.app.browse.BrowsePreferences
import com.cruxcoach.app.browse.BrowserFilterState
import com.cruxcoach.app.browse.ClimbStatusFilter
import com.cruxcoach.app.browse.OriginFilter
import com.cruxcoach.app.browse.testing.BrowseTestDb
import com.cruxcoach.app.logbook.CI_WAIT_MS
import com.cruxcoach.app.logbook.SerialDispatcher
import com.cruxcoach.app.ui.GeneratorScreenModel
import com.cruxcoach.app.ui.GeneratorScreenState
import com.cruxcoach.data.repository.ClimbTypeFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Android PlaylistGeneratorScreen's "Minimum ascents" and "More filters" sheet, through the facade. */
class GeneratorMoreFiltersTest {
    private val serialOwner = SerialDispatcher()
    private val serial = serialOwner.dispatcher
    private val db = BrowseTestDb()
    private val store = com.cruxcoach.app.browse.testing.MemoryKeyValueStore().apply {
        values[BrowsePreferences.BOARD_BRAND] = "kilter"
        values[BrowsePreferences.BOARD_LAYOUT_ID] = "1"
        values[BrowsePreferences.BOARD_PRODUCT_SIZE_ID] = "0"
        values[BrowsePreferences.BOARD_ANGLE] = "40"
    }

    @AfterTest
    fun tearDown() = runBlocking { withContext(serial) { db.close() } }

    private fun model() = GeneratorScreenModel(
        GeneratorPresenter(
            boardRepository = db.boardRepo,
            personalBoardRepo = db.personalRepo,
            preferences = BrowsePreferences(store),
            scope = CoroutineScope(serial),
            ioDispatcher = serial,
            randomSeed = { 7L },
        )
    )

    private suspend fun awaitUi(m: GeneratorScreenModel, predicate: (GeneratorScreenState) -> Boolean): GeneratorScreenState =
        withTimeout(CI_WAIT_MS) {
            var seen = withContext(serial) { m.currentState }
            while (!predicate(seen)) {
                delay(5)
                seen = withContext(serial) { m.currentState }
            }
            seen
        }

    @Test
    fun `the browser's filters arrive as codes and the browser's ascent threshold joins the options`() = runBlocking {
        BrowsePreferences(store).saveFilter(
            BrowserFilterState(
                minAscensionists = 12, benchmarkOnly = true, originFilter = OriginFilter.CRUXCOACH,
                statusFilter = setOf(ClimbStatusFilter.NEW, ClimbStatusFilter.ATTEMPTED),
                climbTypeFilter = ClimbTypeFilter.ROUTE,
            )
        )
        val m = model()
        val ui = awaitUi(m) { it.minAscensionists == 12 }
        assertEquals(listOf(0, 5, 12, 20, 100, 500), ui.minAscentOptions)
        assertTrue(ui.benchmarkOnly)
        assertEquals("unsent", ui.statusCode)
        assertEquals("cruxcoach", ui.originCode)
        assertEquals("route", ui.climbTypeCode)
        assertEquals(listOf("benchmark", "unsent", "cruxcoach", "route"), ui.activeFilterCodes)
        withContext(serial) { m.close() }
    }

    @Test
    fun `the sheet's choices reach the generator and unknown codes are ignored`() = runBlocking {
        val m = model()
        awaitUi(m) { it.boardWire == "kilter" }
        withContext(serial) {
            m.setMinAscensionists(500)
            m.setBenchmarkOnly(true)
            m.setStatusCode("sent")
            m.setOriginCode("boardsesh")
            m.setClimbTypeCode("all")
        }
        var ui = awaitUi(m) { it.climbTypeCode == "all" }
        assertEquals(500, ui.minAscensionists, "Android's 500+ choice is kept, not capped at 50")
        assertTrue(ui.benchmarkOnly)
        assertEquals("sent", ui.statusCode)
        assertEquals("boardsesh", ui.originCode)
        assertEquals(listOf("benchmark", "sent", "boardsesh", "all"), ui.activeFilterCodes)

        withContext(serial) { m.setMinAscensionists(99_999) }
        assertEquals(GeneratorPresenter.MAX_MIN_ASCENSIONISTS, awaitUi(m) { it.minAscensionists != 500 }.minAscensionists)

        withContext(serial) {
            m.setStatusCode("bogus")
            m.setOriginCode("bogus")
            m.setClimbTypeCode("bogus")
            m.setBenchmarkOnly(false)
            m.setStatusCode("all")
        }
        ui = awaitUi(m) { it.statusCode == "all" }
        assertFalse(ui.benchmarkOnly)
        assertEquals("boardsesh", ui.originCode)
        assertEquals("all", ui.climbTypeCode)
        assertEquals(listOf("boardsesh", "all"), ui.activeFilterCodes)
        assertEquals(listOf("all", "new", "unsent", "sent"), m.statusCodes)
        withContext(serial) { m.close() }
    }
}
