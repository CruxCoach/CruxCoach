package com.cruxcoach.android.ui.board

import com.cruxcoach.android.data.BoardSessionManager
import com.cruxcoach.android.data.IntensityZoneManager
import com.cruxcoach.android.ui.navigation.ClimbNavigationState
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.data.repository.PersonalBoardRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AscentLoggerTest {

    private val climb = ClimbWithStats(
        uuid = "climb-1",
        layoutId = 8,
        setterUsername = "setter",
        name = "Historical climb",
        frames = "p1100r12",
        framesCount = 1,
        difficultyAverage = 17.5,
        qualityAverage = 2.0,
        ascensionistCount = 3,
        boardBrand = "kilter",
    )

    @Test
    fun `new send persists full context records history and updates session state`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = mockk<PersonalBoardRepository>(relaxed = true)
        every { repo.getUserHistoryForClimb("climb-1") } returns emptyList()
        val session = mockk<BoardSessionManager>(relaxed = true)
        val zones = mockk<IntensityZoneManager>(relaxed = true)
        val navigation = ClimbNavigationState()
        val saved = mutableListOf<Boolean>()
        val state = MutableStateFlow(
            ClimbDetailState(
                climb = climb,
                angle = 45,
                isMirrored = true,
                ascent = AscentFormState(
                    showDialog = true,
                    isSend = true,
                    bidCount = 2,
                    quality = 4,
                    comment = "great",
                    isBenchmark = true,
                ),
            ),
        )
        val logger = AscentLogger(
            scope = this,
            state = state,
            personalBoardRepo = repo,
            sessionManager = session,
            zoneManager = zones,
            climbNavState = navigation,
            currentClimbUuid = { "climb-1" },
            onAscentSaved = saved::add,
            ioDispatcher = dispatcher,
            nowIso = { "2026-07-16T12:00:00" },
            newUuid = { "ascent-1" },
        )

        logger.save()
        advanceUntilIdle()

        verify {
            repo.insertAscent(
                uuid = "ascent-1",
                climbUuid = "climb-1",
                angle = 45,
                isMirror = true,
                attemptId = 0,
                bidCount = 2,
                quality = 4,
                difficulty = 17,
                isBenchmark = true,
                comment = "great",
                climbedAt = "2026-07-16T12:00:00",
                synced = false,
                gymUuid = null,
                wallUuid = null,
                productLayoutUuid = null,
                climbName = "Historical climb",
                difficultyAverage = 17.5,
                climbFrames = "p1100r12",
                framesCount = 1,
                boardBrand = "kilter",
                layoutId = 8,
                externalId = null,
            )
            session.recordAscent()
        }
        coVerify {
            repo.recordClimbHistory(
                climbUuid = "climb-1",
                climbName = "Historical climb",
                angle = 45,
                difficultyAverage = 17.5,
                boardBrand = "kilter",
                layoutId = 8,
                climbedAt = "2026-07-16T12:00:00",
                recordedAt = "2026-07-16T12:00:00",
            )
            zones.recompute()
        }
        assertEquals(listOf(true), saved)
        assertTrue(navigation.statusDataChanged)
        assertEquals(setOf("climb-1"), navigation.changedClimbUuids)
        assertFalse(state.value.ascent.showDialog)
    }

    @Test
    fun `editing a bid updates the bid table without creating session activity`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = mockk<PersonalBoardRepository>(relaxed = true)
        every { repo.getUserHistoryForClimb("climb-1") } returns emptyList()
        val session = mockk<BoardSessionManager>(relaxed = true)
        val zones = mockk<IntensityZoneManager>(relaxed = true)
        val saved = mutableListOf<Boolean>()
        val state = MutableStateFlow(
            ClimbDetailState(
                climb = climb,
                angle = 40,
                ascent = AscentFormState(
                    showDialog = true,
                    editingUuid = "bid-1",
                    isSend = false,
                    bidCount = 5,
                    comment = "project",
                ),
            ),
        )
        val logger = AscentLogger(
            scope = this,
            state = state,
            personalBoardRepo = repo,
            sessionManager = session,
            zoneManager = zones,
            climbNavState = ClimbNavigationState(),
            currentClimbUuid = { "climb-1" },
            onAscentSaved = saved::add,
            ioDispatcher = dispatcher,
        )

        logger.save()
        advanceUntilIdle()

        verify(exactly = 1) {
            repo.updateBid(uuid = "bid-1", bidCount = 5, comment = "project")
        }
        verify(exactly = 0) { repo.updateAscent(any(), any(), any(), any()) }
        verify(exactly = 0) { session.recordAscent() }
        verify(exactly = 0) { session.recordBid() }
        coVerify(exactly = 0) { zones.recompute() }
        assertTrue(saved.isEmpty())
        assertFalse(state.value.ascent.showDialog)
    }
}
