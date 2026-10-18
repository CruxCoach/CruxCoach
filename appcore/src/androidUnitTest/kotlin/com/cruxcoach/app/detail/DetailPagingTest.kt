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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Android `BoardClimbDetailScreen`'s swipe pager over the list the detail was opened from. */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailPagingTest {
    private lateinit var db: BrowseTestDb
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    @BeforeTest
    fun setUp() {
        db = BrowseTestDb()
        db.boardRepo.upsertPlacement(100, 1, 1, 24, 78)
        db.boardRepo.upsertProductSize(10, 1, "12x12", 0, 144, 0, 156, null)
        for (uuid in listOf("k1", "k2", "k3")) db.climb(uuid, name = "Climb $uuid", frames = "p100r12", angle = 40)
    }

    @AfterTest
    fun tearDown() = db.close()

    @Test
    fun `pages are de-duplicated and a climb outside the list gets a page of its own`() {
        val pages = DetailPages.of(listOf("k2", "k1", "k2", "", "k3"), "k1")
        assertEquals(listOf("k2", "k1", "k3"), pages.uuids)
        assertEquals(1, pages.index)
        assertTrue(pages.hasPrevious)
        assertTrue(pages.hasNext)

        // A stale list must not open the detail on another climb than the one tapped.
        val stale = DetailPages.of(listOf("a", "b"), "k9")
        assertEquals(listOf("k9"), stale.uuids)
        assertEquals("k9", stale.current)
        assertFalse(stale.hasNext)
        assertFalse(stale.hasPrevious)

        assertNull(pages.moveTo(1), "the page already shown is no move")
        assertNull(pages.moveTo(3))
        assertNull(pages.moveTo(-1))
        val last = pages.moveTo(2)!!
        assertEquals("k3", last.current)
        assertFalse(last.hasNext)
    }

    @Test
    fun `switching climbs keeps the browser refresh flag and loads the new climb`() = scope.runTest {
        val p = ClimbDetailPresenter(db.boardRepo, db.personalRepo, MemoryKeyValueStore(), main = dispatcher, io = dispatcher)
        p.open("k1", 40); advanceUntilIdle()
        p.setIgnored(true); advanceUntilIdle()
        assertTrue(p.state.value.browserDirty)

        p.switchClimb("k2", 40); advanceUntilIdle()
        val s = p.state.value
        assertEquals(ClimbDetailStatus.READY, s.status)
        assertEquals("k2", s.data!!.climb.uuid)
        assertFalse(s.isIgnored)
        assertTrue(s.browserDirty, "the ignored climb must still leave the browser list")

        p.open("k3", 40); advanceUntilIdle()
        assertFalse(p.state.value.browserDirty, "a fresh open starts clean")
        p.close()
    }

    @Test
    fun `the neighbouring pages are loaded ahead and a swipe shows them at once then reloads them`() = scope.runTest {
        val p = ClimbDetailPresenter(db.boardRepo, db.personalRepo, MemoryKeyValueStore(), main = dispatcher, io = dispatcher)
        p.open("k2", 40)
        p.preload(listOf("k1", "k3"), 40)
        advanceUntilIdle()

        // Favoured elsewhere after k3 was loaded ahead: the swipe shows the cached page, the reload the truth.
        val other = ClimbDetailPresenter(db.boardRepo, db.personalRepo, MemoryKeyValueStore(), main = dispatcher, io = dispatcher)
        other.open("k3", 40); advanceUntilIdle()
        other.toggleFavourite(); advanceUntilIdle()
        assertTrue(other.state.value.isFavorited)

        p.switchClimb("k3", 40)
        var s = p.state.value
        assertEquals(ClimbDetailStatus.READY, s.status, "no loading flash for a preloaded neighbour")
        assertEquals("Climb k3", s.data!!.climb.name)
        assertFalse(s.isFavorited)
        advanceUntilIdle()
        assertTrue(p.state.value.isFavorited, "the switch still reloads the page")

        // Only the pages next to the one shown stay cached.
        p.preload(listOf("k2"), 40); advanceUntilIdle()
        p.switchClimb("k1", 40)
        assertEquals(ClimbDetailStatus.LOADING, p.state.value.status)
        advanceUntilIdle()
        assertEquals("Climb k1", p.state.value.data!!.climb.name)

        // Another angle is another page; a fresh open forgets the old list's pages.
        p.preload(listOf("k2"), 40); advanceUntilIdle()
        p.switchClimb("k2", 25)
        assertEquals(ClimbDetailStatus.LOADING, p.state.value.status)
        advanceUntilIdle()
        p.open("k3", 40)
        p.switchClimb("k2", 40)
        assertEquals(ClimbDetailStatus.LOADING, p.state.value.status)
        advanceUntilIdle()
        p.close(); other.close()
    }

    @Test
    fun `the detail model loads the pages next to the one shown`() = scope.runTest {
        val sender = BoardSender(
            db.boardRepo,
            BoardConnectionPresenter(FakeBleCentral { 0L }, MemoryKeyValueStore(), FixedClock(1), dispatcher),
            MemoryKeyValueStore(),
            main = dispatcher, io = dispatcher,
        )
        val loggerScope = CoroutineScope(SupervisorJob() + dispatcher)
        val model = DetailScreenModel(
            ClimbDetailPresenter(db.boardRepo, db.personalRepo, MemoryKeyValueStore(), main = dispatcher, io = dispatcher),
            LogAttemptPresenter(db.personalRepo, NoSessionRecorder, null, loggerScope, dispatcher),
            sender,
            GradeFormatter(french = false),
            dispatcher,
        )
        val watch = model.watch { }
        model.openPaged("k1", 40, listOf("k1", "k2", "k3"))
        advanceUntilIdle()
        model.nextPage()
        assertEquals("ready", model.currentState.status)
        assertEquals("Climb k2", model.currentState.name)
        advanceUntilIdle()
        // k2's own neighbour k3 was loaded once k2 was shown.
        model.nextPage()
        assertEquals("Climb k3", model.currentState.name)
        watch.cancel()
        model.close()
        loggerScope.cancel()
    }

    @Test
    fun `the detail pages through the browser list at the browsed angle`() = scope.runTest {
        val sender = BoardSender(
            db.boardRepo,
            BoardConnectionPresenter(FakeBleCentral { 0L }, MemoryKeyValueStore(), FixedClock(1), dispatcher),
            MemoryKeyValueStore(),
            main = dispatcher, io = dispatcher,
        )
        val loggerScope = CoroutineScope(SupervisorJob() + dispatcher)
        val model = DetailScreenModel(
            ClimbDetailPresenter(db.boardRepo, db.personalRepo, MemoryKeyValueStore(), main = dispatcher, io = dispatcher),
            LogAttemptPresenter(db.personalRepo, NoSessionRecorder, null, loggerScope, dispatcher),
            sender,
            GradeFormatter(french = false),
            dispatcher,
        )
        db.send("k1")
        val watch = model.watch { }
        model.openPaged("k2", 40, listOf("k1", "k2", "k3", "k2"))
        advanceUntilIdle()
        var s = model.currentState
        assertEquals("ready", s.status)
        assertEquals("Climb k2", s.name)
        assertEquals(1, s.pageIndex)
        assertEquals(3, s.pageCount)

        // An angle picked on one page does not carry over: Android pages at the list's angle.
        model.selectAngle(25); advanceUntilIdle()
        model.nextPage(); advanceUntilIdle()
        s = model.currentState
        assertEquals("k3", s.uuid)
        assertEquals("Climb k3", s.name)
        assertEquals(40, s.angle)
        assertEquals(2, s.pageIndex)

        model.nextPage(); advanceUntilIdle()
        assertEquals("k3", model.currentState.uuid, "no page after the last one")
        model.showPage(0); advanceUntilIdle()
        assertEquals("k1", model.currentState.uuid)
        model.previousPage(); advanceUntilIdle()
        assertEquals(0, model.currentState.pageIndex)

        // The logger follows the page on screen: k1's own history is shown on k1 only.
        assertEquals(1, model.currentState.ascents.size)
        model.nextPage(); advanceUntilIdle()
        assertEquals("k2", model.currentState.uuid)
        assertTrue(model.currentState.ascents.isEmpty())

        model.open("k2", 40); advanceUntilIdle()
        assertEquals(1, model.currentState.pageCount, "a plain open has no pages")
        watch.cancel()
        model.close()
        loggerScope.cancel()
    }
}
