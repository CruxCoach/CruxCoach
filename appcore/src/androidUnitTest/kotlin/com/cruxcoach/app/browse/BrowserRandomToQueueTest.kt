package com.cruxcoach.app.browse

import com.cruxcoach.app.browse.testing.BrowseTestDb
import com.cruxcoach.app.browse.testing.MemoryKeyValueStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Android `addRandomClimbToQueue`: the joined playlist's dice queues a random climb of the browser at its angle. */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserRandomToQueueTest {
    private lateinit var db: BrowseTestDb
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    @BeforeTest
    fun setUp() {
        db = BrowseTestDb()
        db.boardRepo.upsertProductSize(10, 1, "12x12", -1, 145, -1, 157, null)
        db.boardRepo.upsertPlacement(100, 1, 1, 24, 78)
        db.climb("wide", name = "Wide", frames = "p100r12")
        db.climb("crimp", name = "Crimp", frames = "p100r12")
    }

    @AfterTest
    fun tearDown() = db.close()

    @Test
    fun `the dice hands over a climb of the browser at the browsed angle without opening it`() = scope.runTest {
        val presenter = BoardBrowserPresenter(db.boardRepo, db.personalRepo, MemoryKeyValueStore(), { null }, main = dispatcher, io = dispatcher)
        presenter.start(); advanceUntilIdle()
        presenter.setSearch("Crimp"); advanceUntilIdle()
        val angle = presenter.state.value.filter.angle

        val picked = mutableListOf<Pair<String, Int>>()
        presenter.pickRandomThen { uuid, at -> picked += uuid to at }
        advanceUntilIdle()

        assertEquals(listOf("crimp" to angle), picked, "only climbs the browser shows")
        assertTrue(presenter.state.value.randomClimb == null, "nothing is opened")
        presenter.close()
    }
}
