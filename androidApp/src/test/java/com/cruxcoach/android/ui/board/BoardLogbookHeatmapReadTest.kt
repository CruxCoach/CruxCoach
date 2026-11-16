package com.cruxcoach.android.ui.board

import android.content.Context
import com.cruxcoach.android.community.OwnKilterClimbPublisher
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.IntensityZoneManager
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.AscentWithClimb
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.ClimbFrameRow
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.domain.board.IntensityZones
import io.mockk.every
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/**
 * "Alle Climbs" reads the frames of every climb of the board (Kilter: about 250,000). Each period or board
 * tap in the statistics started another read, side by side, until the app ran out of memory (RC1 walk
 * 2026-11-16). The heat does not depend on the period or the board filter: it is read once per mode and
 * board, and never twice at a time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoardLogbookHeatmapReadTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val personalBoardRepo = mockk<PersonalBoardRepository>(relaxed = true)
    private val boardRepository = mockk<BoardRepository>(relaxed = true)
    private val userPreferences = mockk<UserPreferences>(relaxed = true)
    private val zoneManager = mockk<IntensityZoneManager>(relaxed = true)
    private val ownClimbPublisher = mockk<OwnKilterClimbPublisher>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)

    private val today = LocalDate.now()
    private val logbook = listOf(
        entry("s1", "c1", today, tries = 1),
        entry("s2", "c2", today.minusDays(40), tries = 3),
    )

    private val reads = AtomicInteger()
    private val reading = AtomicInteger()
    private val mostAtOnce = AtomicInteger()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { userPreferences.gradeScale } returns flowOf(GradeScale.FRENCH)
        every { userPreferences.boardBrand } returns flowOf("kilter")
        every { userPreferences.boardLayoutId } returns flowOf(1)
        every { userPreferences.boardProductSizeId } returns flowOf(10)
        every { zoneManager.zones } returns
            MutableStateFlow(IntensityZones(warmUpCeiling = 10.0, optimalCeiling = 20.0, isPersonalized = false))
        every { personalBoardRepo.getUserLogbookPage(any(), any()) } returns logbook
        every { personalBoardRepo.countUserLogbook() } returns logbook.size.toLong()
        every { personalBoardRepo.getUserLogbookAllLight() } returns logbook
        every { ownClimbPublisher.getOwnAuthoredClimbs() } returns emptyList()
        every { boardRepository.getAllFramesForHeatmapAllAngles(any(), any(), any(), any(), any(), any()) } answers {
            val now = reading.incrementAndGet()
            mostAtOnce.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            reads.incrementAndGet()
            try {
                // A slow catalogue read, so taps can come while it runs.
                Thread.sleep(300)
                listOf(ClimbFrameRow("c1", "p1145r12p1146r13p1200r14"), ClimbFrameRow("c2", "p1145r12p1300r15"))
            } finally {
                reading.decrementAndGet()
            }
        }
    }

    @After
    fun tearDown() {
        Thread.sleep(400)
        Dispatchers.resetMain()
    }

    private fun entry(uuid: String, climb: String, day: LocalDate, tries: Int) = AscentWithClimb(
        uuid = uuid, climbUuid = climb, angle = 40, isMirror = false, bidCount = tries.toLong(),
        quality = null, difficulty = null, comment = null, climbedAt = "$day 18:00:00",
        climbName = "Problem $climb", climbFrames = "", difficultyAverage = 20.0, isSend = true,
    )

    private fun buildViewModel() = BoardLogbookViewModel(
        personalBoardRepo = personalBoardRepo,
        boardRepository = boardRepository,
        userPreferences = userPreferences,
        zoneManager = zoneManager,
        climbNavState = mockk(relaxed = true),
        climbNameResolver = mockk(relaxed = true),
        ownClimbPublisher = ownClimbPublisher,
        context = context,
        holdTypeAssets = mockk(relaxed = true),
    )

    @Test
    fun allClimbsIsReadOncePerBoardAndNeverTwiceAtATime() = runTest {
        val vm = buildViewModel()
        vm.state.first { it.statsReady && it.heatmapBoardSelection != null }

        vm.setHeatmapMode(HeatmapMode.GLOBAL)
        // Period and board taps while the read runs, and after it.
        StatsTimeInterval.entries.forEach { vm.setStatsInterval(it) }
        vm.setCustomDateRange(today.minusDays(60), today)
        vm.setBoardFilter("kilter")
        vm.state.first { it.heatmapData.isNotEmpty() }
        vm.setStatsInterval(StatsTimeInterval.DAYS_30)
        vm.setBoardFilter(null)
        Thread.sleep(700)

        assertEquals(1, reads.get())
        assertEquals(1, mostAtOnce.get())
        assertTrue(vm.state.value.heatmapData.isNotEmpty())
    }

    @Test
    fun switchingModesReadsAgainButOneReadAtATime() = runTest {
        val vm = buildViewModel()
        vm.state.first { it.statsReady && it.heatmapBoardSelection != null }

        vm.setHeatmapMode(HeatmapMode.GLOBAL)
        vm.setHeatmapMode(HeatmapMode.START)
        vm.setHeatmapMode(HeatmapMode.HAND)
        vm.state.first { it.heatmapData.isNotEmpty() }
        Thread.sleep(700)

        assertEquals(1, mostAtOnce.get())
        assertEquals(HeatmapMode.HAND, vm.state.value.heatmapMode)
        assertTrue(vm.state.value.heatmapData.isNotEmpty())
    }
}
