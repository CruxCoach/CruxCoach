package com.cruxcoach.app.send

import com.cruxcoach.app.ble.BoardConnectionPresenter
import com.cruxcoach.app.ble.BoardSendResult
import com.cruxcoach.app.ble.FakeBleCentral
import com.cruxcoach.app.browse.BrowsePreferences
import com.cruxcoach.app.browse.testing.BrowseTestDb
import com.cruxcoach.app.browse.testing.MemoryKeyValueStore
import com.cruxcoach.app.detail.ClimbDetailPresenter
import com.cruxcoach.app.logbook.LogAttemptPresenter
import com.cruxcoach.app.logbook.NoSessionRecorder
import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.app.ui.DetailScreenModel
import com.cruxcoach.app.ui.GradeFormatter
import com.cruxcoach.domain.board.BoardHold
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Android BoardSendController's LED-map checks before a direct send: no LED data, a climb none of
 * whose holds is on the configured size (refused, with the guided fix), and a partial fit (sent,
 * with a warning). The configured size decides, not the larger board the detail may draw.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoardSendFitTest {
    private lateinit var db: BrowseTestDb
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val store = MemoryKeyValueStore()

    @BeforeTest
    fun setUp() {
        db = BrowseTestDb()
        // Holes 1/2 exist on both sizes, holes 3/4 only on the larger size 11.
        db.boardRepo.upsertPlacement(100, 1, 1, 24, 78)
        db.boardRepo.upsertPlacement(101, 2, 1, 32, 78)
        db.boardRepo.upsertPlacement(200, 3, 1, 24, 150)
        db.boardRepo.upsertPlacement(201, 4, 1, 32, 150)
        for ((hole, led) in listOf(1L to 10L, 2L to 11L)) db.boardRepo.upsertLed(hole, 10, led)
        for ((hole, led) in listOf(1L to 20L, 2L to 21L, 3L to 22L, 4L to 23L)) db.boardRepo.upsertLed(hole, 11, led)
        db.climb("fits", frames = "p100r12p101r14")
        db.climb("partial", frames = "p100r12p200r14")
        db.climb("off", frames = "p200r12p201r14")
        BrowsePreferences(store).setBoardSelection("kilter", 1, 10, 40)
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun holds(vararg ids: Int) = ids.map { BoardHold(it, 12) }

    @Test
    fun `the fit follows the configured size's LED map`() {
        val map = mapOf(100 to 10, 101 to 11)
        assertEquals(BoardSendFit.FITS, BoardSendFit.of(holds(100, 101), map))
        assertEquals(BoardSendFit.PARTIAL, BoardSendFit.of(holds(100, 200), map))
        assertEquals(BoardSendFit.OFF_BOARD, BoardSendFit.of(holds(200, 201), map))
        assertEquals(BoardSendFit.NO_LED_DATA, BoardSendFit.of(holds(100), emptyMap()))
    }

    /** Opens [uuid], sends it and returns the late outcome and what reached the link. */
    private fun TestScope.sendOutcome(uuid: String): Pair<String, BoardSendResult?> {
        val link = BoardConnectionPresenter(FakeBleCentral { 0L }, MemoryKeyValueStore(), FixedClock(1), dispatcher)
        var model: DetailScreenModel? = null
        val sender = BoardSender(db.boardRepo, link, store, { code -> model?.reportSendFailure(code) },
            main = dispatcher, io = dispatcher)
        val loggerScope = CoroutineScope(SupervisorJob() + dispatcher)
        val detail = DetailScreenModel(
            ClimbDetailPresenter(db.boardRepo, db.personalRepo, store, main = dispatcher, io = dispatcher),
            LogAttemptPresenter(db.personalRepo, NoSessionRecorder, null, loggerScope, dispatcher),
            sender,
            GradeFormatter(french = false),
            dispatcher,
        )
        model = detail
        val watch = detail.watch { }
        detail.open(uuid, 40); advanceUntilIdle()
        detail.sendToBoard(); advanceUntilIdle()
        val outcome = detail.currentState.sendRequestFailure
        watch.cancel()
        detail.close()
        loggerScope.cancel()
        return outcome to link.state.value.lastSendResult
    }

    @Test
    fun `a climb with none of its holds on the configured size is refused before anything is written`() = scope.runTest {
        val (outcome, written) = sendOutcome("off")
        assertEquals("climbOffBoard", outcome)
        assertNull(written, "nothing may reach the board")
    }

    @Test
    fun `a partial fit is sent with the warning and a full fit without one`() = scope.runTest {
        val (partial, partialWrite) = sendOutcome("partial")
        assertEquals("holdsNotLit", partial)
        // The fake link is down: the request reached the connection, which refused it there.
        assertEquals(BoardSendResult.NOT_CONNECTED, partialWrite)
        val (fits, fitsWrite) = sendOutcome("fits")
        assertEquals("none", fits)
        assertEquals(BoardSendResult.NOT_CONNECTED, fitsWrite)
    }

    @Test
    fun `a configured size without LED data says so instead of sending`() = scope.runTest {
        BrowsePreferences(store).setBoardSelection("kilter", 1, 12, 40)
        val (outcome, written) = sendOutcome("fits")
        assertEquals("noLedData", outcome)
        assertNull(written)
    }
}
