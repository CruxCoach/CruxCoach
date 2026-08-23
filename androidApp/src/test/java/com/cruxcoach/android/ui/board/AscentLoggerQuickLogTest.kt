package com.cruxcoach.android.ui.board

import com.cruxcoach.android.data.BoardSessionManager
import com.cruxcoach.android.data.IntensityZoneManager
import com.cruxcoach.android.ui.navigation.ClimbNavigationState
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.data.repository.PersonalBoardRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AscentLoggerQuickLogTest {

    private val climb = ClimbWithStats(
        uuid = "climb-1",
        layoutId = 1L,
        setterUsername = "setter",
        name = "Quick Log",
        frames = "p1100r12",
        framesCount = 1L,
        difficultyAverage = 17.0,
        qualityAverage = 3.0,
        ascensionistCount = 10L,
        origin = "kilter",
        source = "kilter",
        syncStatus = "synced",
    )

    @Test
    fun `quick attempt is immediate but sync callback waits for undo window`() = runBlocking {
        val state = MutableStateFlow(ClimbDetailState(isLoading = false, climb = climb))
        val repo = mockk<PersonalBoardRepository>(relaxed = true)
        val session = mockk<BoardSessionManager>(relaxed = true)
        var finalized = false
        every { repo.getUserHistoryForClimb(climb.uuid) } returns emptyList()
        every { repo.observeClimbHistory() } returns flowOf(emptyList())
        val logger = logger(state, repo, session) { finalized = true }

        logger.quickLog(isSend = false)
        val feedback = withTimeout(5_000) {
            state.first { it.quickLogFeedback != null }.quickLogFeedback!!
        }

        verify {
            repo.insertBid(
                uuid = feedback.entryUuid,
                climbUuid = climb.uuid,
                angle = any(),
                isMirror = any(),
                bidCount = any(),
                comment = any(),
                climbedAt = any(),
                synced = any(),
                climbName = any(),
                difficultyAverage = any(),
                boardBrand = any(),
                layoutId = any(),
            )
        }
        verify { session.recordBid() }
        assertFalse(finalized)

        logger.consumeQuickLogFeedback()
        assertTrue(finalized)
    }

    @Test
    fun `undo quick send deletes log and reverses session count without syncing`() = runBlocking {
        val state = MutableStateFlow(ClimbDetailState(isLoading = false, climb = climb))
        val repo = mockk<PersonalBoardRepository>(relaxed = true)
        val session = mockk<BoardSessionManager>(relaxed = true)
        var finalized = false
        every { repo.getUserHistoryForClimb(climb.uuid) } returns emptyList()
        every { repo.observeClimbHistory() } returns flowOf(emptyList())
        coEvery { repo.recordClimbHistory(any(), any(), any(), any(), any(), any(), any(), any()) } returns Unit
        val logger = logger(state, repo, session) { finalized = true }

        logger.quickLog(isSend = true)
        val feedback = withTimeout(5_000) {
            state.first { it.quickLogFeedback != null }.quickLogFeedback!!
        }
        logger.undoQuickLog()

        verify(timeout = 5_000) { repo.deleteAscent(feedback.entryUuid) }
        verify(timeout = 5_000) { session.undoRecordedAscent() }
        assertFalse(finalized)
        coVerify { repo.recordClimbHistory(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    private fun logger(
        state: MutableStateFlow<ClimbDetailState>,
        repo: PersonalBoardRepository,
        session: BoardSessionManager,
        onSaved: (Boolean) -> Unit,
    ) = AscentLogger(
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
        state = state,
        personalBoardRepo = repo,
        sessionManager = session,
        zoneManager = mockk<IntensityZoneManager>(relaxed = true),
        climbNavState = mockk<ClimbNavigationState>(relaxed = true),
        currentClimbUuid = { climb.uuid },
        onAscentSaved = onSaved,
    )
}
