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

/** Android `ClimbDetailInfoSheet`'s stat row, badges and FA line, as the iOS detail receives them. */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailInfoBlockTest {
    private lateinit var db: BrowseTestDb
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    @BeforeTest
    fun setUp() {
        db = BrowseTestDb()
        db.boardRepo.upsertPlacement(100, 1, 1, 24, 78)
        db.boardRepo.upsertProductSize(10, 1, "12x12", 0, 144, 0, 156, null)
        db.climb("bench", name = "Bench", frames = "p100r12", angle = 40, ascensionists = 321L, benchmark = 18.0)
        // Re-stat with a first ascent, as the catalogue import writes it.
        db.board.boardQueries.upsertClimbStat(
            climb_uuid = "bench", angle = 40L, display_difficulty = 15.0, difficulty_average = 15.0,
            quality_average = 2.46, ascensionist_count = 321L, benchmark_difficulty = 18.0,
            fa_username = "alex", fa_at = "2021-03-07 18:22:01", official_kilter_difficulty = null,
        )
        db.climb("plain", name = "Plain", frames = "p100r12", angle = 40, provenance = "cruxcoach")
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun model(): Pair<DetailScreenModel, CoroutineScope> {
        val sender = BoardSender(
            db.boardRepo,
            BoardConnectionPresenter(FakeBleCentral { 0L }, MemoryKeyValueStore(), FixedClock(1), dispatcher),
            MemoryKeyValueStore(),
            main = dispatcher, io = dispatcher,
        )
        val loggerScope = CoroutineScope(SupervisorJob() + dispatcher)
        return DetailScreenModel(
            ClimbDetailPresenter(db.boardRepo, db.personalRepo, MemoryKeyValueStore(), main = dispatcher, io = dispatcher),
            LogAttemptPresenter(db.personalRepo, NoSessionRecorder, null, loggerScope, dispatcher),
            sender,
            GradeFormatter(french = false),
            dispatcher,
        ) to loggerScope
    }

    @Test
    fun `a kilter benchmark carries quality sends moves FA and a known match rule`() = scope.runTest {
        val (m, loggerScope) = model()
        val watch = m.watch { }
        m.open("bench", 40); advanceUntilIdle()
        val s = m.currentState
        assertEquals("ready", s.status)
        assertFalse(s.infoIsRoute)
        assertEquals(1, s.infoMoveCount, "the fixture's stored move count")
        assertEquals("2.5", s.infoQuality)
        assertEquals(321L, s.infoSends)
        assertEquals("alex", s.faName)
        assertEquals("07.03.2021", s.faDate)
        assertTrue(s.isBenchmark)
        assertTrue(s.matchKnown, "Kilter catalogue climbs know their match rule")
        assertFalse(s.noMatch)
        assertEquals("none", s.methodCode)
        watch.cancel(); m.close(); loggerScope.cancel()
    }

    @Test
    fun `a community climb shows no match badge no FA and no benchmark`() = scope.runTest {
        val (m, loggerScope) = model()
        val watch = m.watch { }
        m.open("plain", 40); advanceUntilIdle()
        val s = m.currentState
        assertEquals("ready", s.status)
        assertFalse(s.matchKnown, "a CruxCoach setter was never asked about matching")
        assertEquals("", s.faName)
        assertEquals("", s.faDate)
        assertFalse(s.isBenchmark)
        assertEquals("2.5", s.infoQuality)
        watch.cancel(); m.close(); loggerScope.cancel()
    }

    @Test
    fun `MoonBoard method tokens map and unknown tokens say nothing`() {
        assertEquals("footless", DetailScreenModel.methodCode("method_footless"))
        assertEquals("footlessKickboard", DetailScreenModel.methodCode("method_footless_kickboard"))
        assertEquals("noKickboard", DetailScreenModel.methodCode("method_no_kickboard"))
        assertEquals("none", DetailScreenModel.methodCode("method_from_the_future"))
        assertEquals("none", DetailScreenModel.methodCode(null))
    }

    @Test
    fun `ascent dates use Android's day month year order`() {
        assertEquals("07.03.2021", DetailScreenModel.ascentDate("2021-03-07T18:22:01Z"))
        assertEquals("2021", DetailScreenModel.ascentDate("2021"))
    }
}
