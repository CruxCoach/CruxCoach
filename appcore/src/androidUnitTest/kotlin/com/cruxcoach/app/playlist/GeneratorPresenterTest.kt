package com.cruxcoach.app.playlist

import com.cruxcoach.app.browse.BrowsePreferences
import com.cruxcoach.app.browse.testing.BrowseTestDb
import com.cruxcoach.app.logbook.CI_WAIT_MS
import com.cruxcoach.app.logbook.SerialDispatcher
import com.cruxcoach.app.ui.GeneratorScreenModel
import com.cruxcoach.domain.playlist.GeneratorType
import com.cruxcoach.domain.playlist.PlaylistGeneratorParams
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * The planner and the filler are `:shared` and tested there; these cases cover
 * what the iOS presenter adds — reading the board and the logbook, keeping the
 * climber's own grade range, and persisting a generated session as a list with
 * its playback steps.
 */
class GeneratorPresenterTest {
    // One thread for the presenter scope and its IO: two threads on one
    // SQLDelight JDBC driver corrupt its single connection. Never shut down.
    private val serialOwner = SerialDispatcher()
    private val serial = serialOwner.dispatcher

    private val db = BrowseTestDb()
    // The board picker's own keys. Product size 0 = no fit filter: the
    // fixture inserts climbs, not a product-size catalogue.
    private val store = com.cruxcoach.app.browse.testing.MemoryKeyValueStore().apply {
        values[BrowsePreferences.BOARD_BRAND] = "kilter"
        values[BrowsePreferences.BOARD_LAYOUT_ID] = "1"
        values[BrowsePreferences.BOARD_PRODUCT_SIZE_ID] = "0"
        values[BrowsePreferences.BOARD_ANGLE] = "40"
    }

    @AfterTest
    fun tearDown() = runBlocking { withContext(serial) { db.close() } }

    /** Every repository call runs on the presenters' own thread. */
    private suspend fun <T> onDb(block: suspend () -> T): T = withContext(serial) { block() }

    private fun presenter() = GeneratorPresenter(
        boardRepository = db.boardRepo,
        personalBoardRepo = db.personalRepo,
        preferences = BrowsePreferences(store),
        scope = CoroutineScope(serial),
        ioDispatcher = serial,
        randomSeed = { 7L },
    )

    /** A board wide enough that every planned tier finds something. */
    private fun fillBoard() {
        var n = 0
        for (difficulty in 10..28) {
            repeat(6) {
                db.climb(
                    uuid = "c$difficulty-${n++}",
                    difficulty = difficulty.toDouble(),
                    ascensionists = 25L,
                )
            }
        }
    }

    private suspend fun awaitState(
        model: GeneratorPresenter,
        predicate: (GeneratorState) -> Boolean,
    ): GeneratorState = withTimeout(CI_WAIT_MS) {
        var seen = model.state.value
        while (!predicate(seen)) {
            delay(5)
            seen = model.state.value
        }
        seen
    }

    @Test
    fun `a generated session is persisted as a list of climb and rest steps`() = runBlocking {
        onDb { fillBoard() }
        val presenter = presenter()
        val state = awaitState(presenter) { it.plan != null }
        assertTrue(state.plan!!.slots.isNotEmpty(), "the preview plan must not be empty")

        presenter.setType(GeneratorType.VOLUME)
        awaitState(presenter) { it.type == GeneratorType.VOLUME && it.plan != null }
        presenter.generate("Volume session")

        val done = awaitState(presenter) { it.createdListId != null || it.error }
        assertFalse(done.error, "the board holds candidates in every planned band")
        val listId = assertNotNull(done.createdListId)

        val steps = onDb { db.personalRepo.getPlaybackSteps(listId) }
        assertTrue(steps.any { !it.isRest }, "a session needs climbs")
        assertTrue(steps.any { it.isRest }, "a volume session rests between problems")
        assertTrue(
            steps.filter { !it.isRest }.all { it.climbUuid != null && it.angle == 40L },
            "every climb step pins the board angle it was planned for",
        )

        // The recipe is stored so "generate again" can re-run it later.
        val list = onDb { db.personalRepo.getAllClimbLists().first { it.id == listId } }
        val params = assertNotNull(PlaylistGeneratorParams.fromJson(list.generatorParams))
        assertEquals(GeneratorType.VOLUME, params.type)
        presenter.dispose()
    }

    @Test
    fun `an empty board fails honestly instead of creating an empty list`() = runBlocking {
        val presenter = presenter()
        awaitState(presenter) { it.plan != null }
        presenter.generate("Nothing here")

        val done = awaitState(presenter) { it.error || it.createdListId != null }
        assertTrue(done.error, "nothing could be filled")
        assertEquals(null, done.createdListId)
        assertTrue(onDb { db.personalRepo.getAllClimbLists() }.none { it.name == "Nothing here" })
        presenter.dispose()
    }

    @Test
    fun `an edited grade range survives other changes until it is reset`() = runBlocking {
        onDb { fillBoard() }
        val presenter = presenter()
        awaitState(presenter) { it.plan != null }

        presenter.setTargetRange(17.0, 19.0)
        presenter.setDuration(90)
        val edited = awaitState(presenter) { it.durationMinutes == 90 }
        assertEquals(17.0, edited.targetMinDifficulty)
        assertEquals(19.0, edited.targetMaxDifficulty)

        presenter.useRecommendedRange()
        val recommended = awaitState(presenter) { !it.gradeRangeCustomized }
        // The recommendation comes from the plan, so it must differ from the
        // hand-set band rather than merely being cleared.
        assertNotNull(recommended.targetMinDifficulty)
        presenter.dispose()
    }

    @Test
    fun `the size control is re-seated on the new type's own range`() = runBlocking {
        onDb { fillBoard() }
        val presenter = presenter()
        val model = GeneratorScreenModel(presenter)
        awaitState(presenter) { it.plan != null }

        model.setTypeCode("powerEndurance")
        awaitState(presenter) { it.type == GeneratorType.POWER_ENDURANCE }
        val ui = model.currentState
        assertEquals("sets", ui.structureUnitCode)
        assertTrue(
            ui.structureSize in ui.structureMin..ui.structureMax,
            "size ${ui.structureSize} outside ${ui.structureMin}..${ui.structureMax}",
        )
        assertTrue(ui.plan.isNotEmpty(), "the preview follows the type")
        assertTrue(ui.gradeLabels.isNotEmpty(), "the grade control needs labels")
        model.close()
    }
}
