package com.cruxcoach.app.detail

import com.cruxcoach.app.ble.BoardConnectionPresenter
import com.cruxcoach.app.ble.FakeBleCentral
import com.cruxcoach.app.browse.testing.BrowseTestDb
import com.cruxcoach.app.browse.testing.MemoryKeyValueStore
import com.cruxcoach.app.logbook.LogAttemptPresenter
import com.cruxcoach.app.logbook.NoSessionRecorder
import com.cruxcoach.app.send.BoardSender
import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.app.ui.DetailScreenModel
import com.cruxcoach.app.ui.GradeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Android `UserAscentHistory` in the climb detail: each own entry can be edited or deleted there. */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailAscentEditTest {
    private lateinit var db: BrowseTestDb
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    @BeforeTest
    fun setUp() {
        db = BrowseTestDb()
        db.boardRepo.upsertPlacement(100, 1, 1, 24, 78)
        db.boardRepo.upsertProductSize(10, 1, "12x12", 0, 144, 0, 156, null)
        db.climb("k1", name = "Climb k1", frames = "p100r12", angle = 40)
        db.send("k1")
        db.attempt("k1")
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun model(loggerScope: CoroutineScope) = DetailScreenModel(
        ClimbDetailPresenter(db.boardRepo, db.personalRepo, MemoryKeyValueStore(), main = dispatcher, io = dispatcher),
        LogAttemptPresenter(db.personalRepo, NoSessionRecorder, null, loggerScope, dispatcher),
        BoardSender(
            db.boardRepo,
            BoardConnectionPresenter(FakeBleCentral { 0L }, MemoryKeyValueStore(), FixedClock(1), dispatcher),
            MemoryKeyValueStore(),
            main = dispatcher, io = dispatcher,
        ),
        GradeFormatter(french = false),
        dispatcher,
    )

    @Test
    fun `the ascent list carries Android's row details`() = scope.runTest {
        val loggerScope = CoroutineScope(SupervisorJob() + dispatcher)
        val m = model(loggerScope)
        val watch = m.watch { }
        m.open("k1", 40); advanceUntilIdle()
        val rows = m.currentState.ascents.associateBy { it.uuid }
        assertEquals(setOf("asc-k1", "bid-k1"), rows.keys)
        val send = rows.getValue("asc-k1")
        assertTrue(send.isSend)
        assertEquals("21.08.2026", send.dateLabel)
        assertEquals(3, send.quality)
        assertFalse(send.isMirror)
        assertFalse(send.isFlash, "the attempt an hour earlier was the first contact")
        assertFalse(rows.getValue("bid-k1").isSend)
        watch.cancel(); m.close(); loggerScope.cancel()
    }

    @Test
    fun `editing an entry opens the sheet on it and saving replaces it`() = scope.runTest {
        val loggerScope = CoroutineScope(SupervisorJob() + dispatcher)
        val m = model(loggerScope)
        val watch = m.watch { }
        m.open("k1", 40); advanceUntilIdle()

        m.editAscent("no-such-entry"); advanceUntilIdle()
        assertFalse(m.currentState.showLogDialog, "an unknown entry opens nothing")

        m.editAscent("asc-k1"); advanceUntilIdle()
        var s = m.currentState
        assertTrue(s.showLogDialog)
        assertTrue(s.logIsEditing)
        assertTrue(s.logIsSend)
        assertEquals(1, s.logTries)
        assertEquals(3, s.logQuality)

        m.setLogTries(4)
        m.setLogComment("crimpy")
        m.saveLog(); advanceUntilIdle()
        s = m.currentState
        assertFalse(s.showLogDialog)
        assertFalse(s.logIsEditing)
        assertEquals(2, s.ascents.size, "an edit replaces the entry, it adds none")
        val edited = s.ascents.first { it.uuid == "asc-k1" }
        assertEquals(4L, edited.tries)
        assertEquals("crimpy", edited.comment)
        watch.cancel(); m.close(); loggerScope.cancel()
    }

    @Test
    fun `deleting asks first and a dismissed confirmation keeps the entry`() = scope.runTest {
        val loggerScope = CoroutineScope(SupervisorJob() + dispatcher)
        val m = model(loggerScope)
        val watch = m.watch { }
        m.open("k1", 40); advanceUntilIdle()

        m.requestDeleteAscent("no-such-entry"); advanceUntilIdle()
        assertFalse(m.currentState.deleteAscentPending)

        m.requestDeleteAscent("bid-k1"); advanceUntilIdle()
        assertTrue(m.currentState.deleteAscentPending)
        m.dismissDeleteAscent(); advanceUntilIdle()
        assertFalse(m.currentState.deleteAscentPending)
        assertEquals(2, m.currentState.ascents.size)

        m.requestDeleteAscent("bid-k1")
        m.confirmDeleteAscent(); advanceUntilIdle()
        val s = m.currentState
        assertFalse(s.deleteAscentPending)
        assertEquals(listOf("asc-k1"), s.ascents.map { it.uuid })
        assertTrue(s.ascents.single().isFlash, "with the attempt gone the send is the first contact")

        m.requestDeleteAscent("asc-k1")
        m.confirmDeleteAscent(); advanceUntilIdle()
        assertTrue(m.currentState.ascents.isEmpty())
        watch.cancel(); m.close(); loggerScope.cancel()
    }
}
