package com.cruxcoach.android.ui.board

import android.content.Context
import com.cruxcoach.android.community.OwnKilterClimbPublisher
import com.cruxcoach.android.data.ClimbNameResolver
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.IntensityZoneManager
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.AscentWithClimb
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.domain.board.IntensityZones
import io.mockk.every
import io.mockk.verify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The overview at the top of the logbook is computed on its own, before the
 * statistics: it must not wait for them, and the sheet knows when its numbers
 * are not there yet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoardLogbookOverviewLoadTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val personalBoardRepo = mockk<PersonalBoardRepository>(relaxed = true)
    private val userPreferences = mockk<UserPreferences>(relaxed = true)
    private val zoneManager = mockk<IntensityZoneManager>(relaxed = true)
    private val ownClimbPublisher = mockk<OwnKilterClimbPublisher>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)

    private val today = LocalDate.now()

    private val logbook = listOf(
        entry("s1", "c1", today, isSend = true, tries = 1),
        entry("s2", "c2", today.minusDays(3), isSend = true, tries = 4),
        entry("b1", "c3", today.minusDays(3), isSend = false, tries = 2),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { userPreferences.gradeScale } returns flowOf(GradeScale.FRENCH)
        every { userPreferences.boardBrand } returns flowOf("kilter")
        every { userPreferences.boardLayoutId } returns flowOf(1)
        every { userPreferences.boardProductSizeId } returns flowOf(1)
        every { zoneManager.zones } returns
            MutableStateFlow(IntensityZones(warmUpCeiling = 10.0, optimalCeiling = 20.0, isPersonalized = false))
        every { personalBoardRepo.getUserLogbookPage(any(), any()) } returns logbook
        every { personalBoardRepo.countUserLogbook() } returns logbook.size.toLong()
        every { personalBoardRepo.getUserLogbookAllLight() } returns logbook
        every { ownClimbPublisher.getOwnAuthoredClimbs() } returns emptyList()
    }

    @After
    fun tearDown() {
        // Let IO-tail resumptions land while Main is still installed (see
        // BoardClimbDetailOwnPublishTest.tearDown for the rationale).
        Thread.sleep(200)
        Dispatchers.resetMain()
    }

    private fun entry(uuid: String, climb: String, day: LocalDate, isSend: Boolean, tries: Int) = AscentWithClimb(
        uuid = uuid, climbUuid = climb, angle = 40, isMirror = false, bidCount = tries.toLong(),
        quality = null, difficulty = null, comment = null, climbedAt = "$day 18:00:00",
        climbName = "Problem $climb", climbFrames = "", difficultyAverage = 20.0, isSend = isSend,
    )

    private fun buildViewModel(climbNameResolver: ClimbNameResolver = mockk(relaxed = true)) = BoardLogbookViewModel(
        personalBoardRepo = personalBoardRepo,
        boardRepository = mockk<BoardRepository>(relaxed = true),
        userPreferences = userPreferences,
        zoneManager = zoneManager,
        climbNavState = mockk(relaxed = true),
        climbNameResolver = climbNameResolver,
        ownClimbPublisher = ownClimbPublisher,
        context = context,
    )

    @Test
    fun overviewAndStatisticsArrive() = runTest {
        val vm = buildViewModel()
        val s = vm.state.first { it.statsReady && it.atAGlance.hardestSend != null }
        assertEquals(setOf("s1"), s.flashUuids)
        assertEquals(2, s.stats.totalSends)
        assertNotNull(s.atAGlance.recent)
    }

    @Test
    fun overviewDoesNotWaitForTheStatistics() = runTest {
        // The statistics label their months and weeks; when that fails they never land.
        every { context.getString(any()) } throws IllegalStateException("no resources")
        every { context.getString(any(), *anyVararg()) } throws IllegalStateException("no resources")
        val vm = buildViewModel()
        val s = vm.state.first { it.atAGlance.hardestSend != null }
        assertEquals(setOf("s1"), s.flashUuids)
        // The sheet then shows what there is instead of loading forever.
        val after = vm.state.first { it.statsReady }
        assertEquals(0, after.stats.totalSends)
        assertNotNull(after.atAGlance.hardestSend)
    }

    @Test
    fun listAndOverviewDoNotWaitForTheCatalogueLookup() = runTest {
        // An entry without a name or grade is looked up in the catalogue; a climb the
        // catalogue lacks takes seconds there. Here the lookup holds until released.
        val unnamed = entry("u1", "missing", today.minusDays(1), isSend = true, tries = 2)
            .copy(climbName = "", difficultyAverage = null)
        val withUnnamed = logbook + unnamed
        every { personalBoardRepo.getUserLogbookPage(any(), any()) } returns withUnnamed
        every { personalBoardRepo.getUserLogbookAllLight() } returns withUnnamed
        val release = CountDownLatch(1)
        val lookupDone = AtomicBoolean(false)
        val resolver = mockk<ClimbNameResolver>()
        every { resolver.resolveClimb(any(), any()) } answers {
            release.await(10, TimeUnit.SECONDS)
            lookupDone.set(true)
            null
        }
        val vm = buildViewModel(resolver)
        try {
            val s = vm.state.first { !it.isLoading && it.ascents.isNotEmpty() && it.atAGlance.hardestSend != null }
            assertFalse(lookupDone.get())
            assertEquals(4, s.ascents.size)
        } finally {
            release.countDown()
        }
        verify(timeout = 5_000) { resolver.resolveClimb("missing", 40) }
    }

    @Test
    fun aPeriodTappedWhileTheLogbookLoadsKeepsTheSheetLoading() = runTest {
        val release = CountDownLatch(1)
        every { personalBoardRepo.getUserLogbookAllLight() } answers { release.await(10, TimeUnit.SECONDS); logbook }
        val vm = buildViewModel()
        try {
            vm.state.first { !it.isLoading && it.ascents.isNotEmpty() }
            vm.setStatsInterval(StatsTimeInterval.DAYS_30)
            Thread.sleep(300)
            // No statistics of an empty logbook: the sheet keeps loading.
            assertFalse(vm.state.value.statsReady)
        } finally {
            release.countDown()
        }
        val s = vm.state.first { it.statsReady }
        assertEquals(StatsTimeInterval.DAYS_30, s.statsInterval)
        assertEquals(2, s.stats.totalSends)
    }

    private fun climb(name: String, difficulty: Double?, layoutId: Long) = ClimbWithStats(
        uuid = "found", layoutId = layoutId, setterUsername = null, name = name, frames = "",
        framesCount = 1, difficultyAverage = difficulty, qualityAverage = null, ascensionistCount = null,
    )

    /** A logbook of one send whose page and full list come from "the database" before and after the repair. */
    private fun repairable(before: AscentWithClimb, after: AscentWithClimb): AtomicBoolean {
        val persisted = AtomicBoolean(false)
        every { personalBoardRepo.getUserLogbookPage(any(), any()) } answers { listOf(if (persisted.get()) after else before) }
        every { personalBoardRepo.getUserLogbookAllLight() } answers { listOf(if (persisted.get()) after else before) }
        every { personalBoardRepo.countUserLogbook() } returns 1L
        every { personalBoardRepo.updateAscentDenormalized(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            persisted.set(true)
        }
        return persisted
    }

    @Test
    fun aRepairThatFindsTheClimbReloadsOnceAndRefreshesTheOverview() = runTest {
        val bare = entry("u1", "found", today, isSend = true, tries = 1)
            .copy(climbName = "", difficultyAverage = null, layoutId = null)
        val fixed = bare.copy(climbName = "Found it", difficultyAverage = 26.0, layoutId = 1L)
        repairable(bare, fixed)
        val resolver = mockk<ClimbNameResolver>()
        every { resolver.resolveClimb(any(), any()) } returns climb("Found it", 26.0, layoutId = 1L)
        val vm = buildViewModel(resolver)
        vm.state.first { it.atAGlance.hardestSend?.difficulty == 26.0 }
        Thread.sleep(500)
        assertEquals("Found it", vm.state.value.ascents.single().climbName)
        // One reload after the repair, then no more: the repaired entry needs no lookup.
        verify(exactly = 2) { personalBoardRepo.getUserLogbookPage(any(), any()) }
        verify(exactly = 1) { resolver.resolveClimb(any(), any()) }
    }

    @Test
    fun aRepairThatOnlyMovesTheLayoutReloadsOnce() = runTest {
        // The catalogue has no grade for the climb either, but knows its layout.
        val bare = entry("u1", "found", today, isSend = true, tries = 1).copy(difficultyAverage = null, layoutId = null)
        val moved = bare.copy(layoutId = 7L)
        repairable(bare, moved)
        val resolver = mockk<ClimbNameResolver>()
        every { resolver.resolveClimb(any(), any()) } returns climb(bare.climbName, null, layoutId = 7L)
        val vm = buildViewModel(resolver)
        vm.state.first { !it.isLoading && it.ascents.isNotEmpty() }
        verify(timeout = 5_000, exactly = 2) { resolver.resolveClimb(any(), any()) }
        Thread.sleep(500)
        // The reload looks the ungraded climb up again, finds nothing new and stops.
        verify(exactly = 2) { personalBoardRepo.getUserLogbookPage(any(), any()) }
        verify(exactly = 2) { resolver.resolveClimb(any(), any()) }
    }
}
