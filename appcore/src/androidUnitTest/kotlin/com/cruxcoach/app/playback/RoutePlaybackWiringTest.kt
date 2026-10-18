package com.cruxcoach.app.playback

import com.cruxcoach.app.browse.testing.BrowseTestDb
import com.cruxcoach.app.browse.testing.MemoryKeyValueStore
import com.cruxcoach.app.detail.ClimbDetailPresenter
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/** Route playback beside a real climb detail presenter over a real board database. */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutePlaybackWiringTest {
    private lateinit var db: BrowseTestDb
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    @BeforeTest
    fun setUp() {
        db = BrowseTestDb()
        db.boardRepo.upsertPlacement(100, 1, 1, 24, 78)
        db.boardRepo.upsertPlacement(101, 2, 1, 120, 78)
        db.boardRepo.upsertPlacement(102, 3, 1, 72, 120)
        db.boardRepo.upsertProductSize(10, 1, "12x12", 0, 144, 0, 156, null)
        // Three route sections: start, then 101 added, then 100 removed and 102 added.
        db.climb("route", frames = "p100r12,p101r13,x100p102r14", angle = 40)
        db.climb("plain", frames = "p100r12p101r14", angle = 40)
    }

    @AfterTest
    fun tearDown() = db.close()

    @Test
    fun `each frame is shown by the detail and sent while the board can be reached`() = scope.runTest {
        val presenter = ClimbDetailPresenter(db.boardRepo, db.personalRepo, MemoryKeyValueStore(), main = dispatcher, io = dispatcher)
        val sent = mutableListOf<List<Int>>()
        val linked = MutableStateFlow(true)
        val playlist = MutableStateFlow(false)
        val model = routePlaybackFor(
            presenter = presenter,
            send = { state -> sent += state.holds.map { it.placementId }.sorted(); "started" },
            reportSendFailure = {},
            boardLinked = linked,
            playlistRunning = playlist,
            prefs = { RoutePlaybackPrefs(frameSpeedSec = 2f, countdown = false) },
            saveAutoLoop = {},
            // A foreground scope: advanceUntilIdle does not wait for backgroundScope work.
            scope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        presenter.open("route", 40); advanceUntilIdle()
        var ui = model.currentState
        assertTrue(ui.isRoute)
        assertEquals(3, ui.totalFrames)
        assertTrue(ui.canReachBoard)
        assertEquals(listOf(100), presenter.state.value.holds.map { it.placementId })

        // Android's preview: every section at once, on screen only.
        model.togglePreview(); advanceUntilIdle()
        assertTrue(model.currentState.showPreview)
        assertEquals(listOf(100, 101, 102), model.currentState.previewHolds.map { it.placementId }.sorted())
        assertTrue(sent.isEmpty(), "the preview is not sent")
        val startArgb = model.currentState.previewHolds.first { it.placementId == 100 }.argb
        val handArgb = model.currentState.previewHolds.first { it.placementId == 101 }.argb
        assertTrue(startArgb != handArgb)
        presenter.toggleMirror(); advanceUntilIdle()
        assertEquals(listOf(100, 101, 102), model.currentState.previewHolds.map { it.placementId }.sorted())
        assertEquals(startArgb, model.currentState.previewHolds.first { it.placementId == 101 }.argb,
            "mirrored, the start hold moves to its partner")
        presenter.toggleMirror(); advanceUntilIdle()

        model.nextFrame(); advanceUntilIdle()
        assertFalse(model.currentState.showPreview, "stepping to a section ends the preview")
        assertTrue(model.currentState.previewHolds.isEmpty())
        assertEquals(listOf(100, 101), presenter.state.value.holds.map { it.placementId }.sorted())
        assertEquals(listOf(listOf(100, 101)), sent, "the frame just selected is the one sent")

        // A playlist owns the wall: the frames stay on screen only (Android route_playback_board_busy).
        playlist.value = true; advanceUntilIdle()
        assertFalse(model.currentState.canReachBoard)
        assertFalse(model.currentState.canTogglePlay)
        model.nextFrame(); advanceUntilIdle()
        assertEquals(listOf(101, 102), presenter.state.value.holds.map { it.placementId }.sorted())
        assertEquals(1, sent.size)

        playlist.value = false; advanceUntilIdle()
        model.firstFrame(); advanceUntilIdle()
        model.togglePlay()
        advanceTimeBy(4_100)
        assertEquals(listOf(listOf(100, 101), listOf(100), listOf(100, 101), listOf(101, 102)), sent)
        advanceTimeBy(2_000)
        assertFalse(model.currentState.isPlaying)

        // Paging to a single-section climb hides the controls; back on the route it starts on frame 1.
        presenter.switchClimb("plain", 40); advanceUntilIdle()
        assertFalse(model.currentState.isRoute)
        presenter.switchClimb("route", 40); advanceUntilIdle()
        ui = model.currentState
        assertTrue(ui.isRoute)
        assertEquals(1, ui.frameNumber)
        assertEquals(listOf(100), presenter.state.value.holds.map { it.placementId })
        model.close()
        presenter.close()
    }

    @Test
    fun `another angle stops playback and returns the detail to the first frame`() = scope.runTest {
        db.board.boardQueries.upsertClimbStat("route", 25, 12.0, 12.0, 3.0, 4, null, null, null, null)
        val presenter = ClimbDetailPresenter(db.boardRepo, db.personalRepo, MemoryKeyValueStore(), main = dispatcher, io = dispatcher)
        val failures = mutableListOf<String>()
        val model = routePlaybackFor(
            presenter = presenter,
            send = { "noBoardGeometry" },
            reportSendFailure = { failures += it },
            boardLinked = MutableStateFlow(true),
            playlistRunning = MutableStateFlow(false),
            prefs = { RoutePlaybackPrefs(countdown = false) },
            saveAutoLoop = {},
            // A foreground scope: advanceUntilIdle does not wait for backgroundScope work.
            scope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        presenter.open("route", 40); advanceUntilIdle()
        model.lastFrame(); advanceUntilIdle()
        assertEquals(listOf("noBoardGeometry"), failures, "a send that cannot be built is reported, not swallowed")
        assertEquals(2, presenter.state.value.frameIndex)
        model.togglePlay()
        presenter.selectAngle(25); advanceUntilIdle()
        assertFalse(model.currentState.isPlaying)
        assertEquals(1, model.currentState.frameNumber)
        assertEquals(0, presenter.state.value.frameIndex)
        model.close()
        presenter.close()
    }
}
