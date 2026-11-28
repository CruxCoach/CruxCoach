package com.cruxcoach.android.ui.board

import com.cruxcoach.android.data.BoardSessionManager
import com.cruxcoach.android.data.BoardSessionState
import com.cruxcoach.android.data.IntensityZoneManager
import com.cruxcoach.android.ui.navigation.ClimbNavigationState
import com.cruxcoach.data.repository.AscentWithClimb
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.data.repository.PersonalBoardRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
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
    fun `every successful quick log triggers its immediate follow-up`() = runBlocking {
        val state = MutableStateFlow(ClimbDetailState(isLoading = false, climb = climb))
        val repo = mockk<PersonalBoardRepository>(relaxed = true)
        every { repo.getUserHistoryForClimb(climb.uuid) } returns emptyList()
        every { repo.observeClimbHistory() } returns flowOf(emptyList())
        val quickFollowUps = Channel<Unit>(capacity = Channel.UNLIMITED)
        val logger = logger(
            state = state,
            repo = repo,
            session = mockk(relaxed = true),
            onSaved = {},
            onQuickSaved = { quickFollowUps.trySend(Unit).getOrThrow() },
        )

        repeat(2) {
            logger.quickLog(isSend = false)
            withTimeout(5_000) { quickFollowUps.receive() }
        }

        assertTrue(quickFollowUps.tryReceive().isFailure)
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

    @Test
    fun `two quick attempts are consolidated into one open logbook entry`() = runBlocking {
        val state = MutableStateFlow(ClimbDetailState(isLoading = false, climb = climb))
        val repo = mockk<PersonalBoardRepository>(relaxed = true)
        val logger = logger(state, repo, mockk(relaxed = true)) { }
        every { repo.getUserHistoryForClimb(climb.uuid) } returns emptyList()
        every { repo.observeClimbHistory() } returns flowOf(emptyList())

        logger.quickLog(isSend = false)
        withTimeout(5_000) { state.first { !it.isQuickLogging && it.quickLogFeedback != null } }
        val entryUuid = state.value.quickLogFeedback!!.entryUuid
        logger.quickLog(isSend = false)
        withTimeout(5_000) {
            state.first { !it.isQuickLogging && it.quickLogFeedback?.entryUuid == entryUuid }
        }

        verify(exactly = 1) {
            repo.insertBid(
                uuid = entryUuid,
                climbUuid = climb.uuid,
                angle = any(),
                isMirror = any(),
                bidCount = 1L,
                comment = any(),
                climbedAt = any(),
                synced = any(),
                climbName = any(),
                difficultyAverage = any(),
                boardBrand = any(),
                layoutId = any(),
            )
        }
        verify { repo.updateBid(entryUuid, 2L, null) }
    }

    @Test
    fun `quick send absorbs open attempts and undo restores them`() = runBlocking {
        val state = MutableStateFlow(ClimbDetailState(isLoading = false, climb = climb))
        val repo = mockk<PersonalBoardRepository>(relaxed = true)
        val session = mockk<BoardSessionManager>(relaxed = true)
        val logger = logger(state, repo, session) { }
        every { repo.getUserHistoryForClimb(climb.uuid) } returns emptyList()
        every { repo.observeClimbHistory() } returns flowOf(emptyList())

        repeat(2) {
            logger.quickLog(isSend = false)
            withTimeout(5_000) { state.first { !it.isQuickLogging && it.quickLogFeedback != null } }
        }
        val entryUuid = state.value.quickLogFeedback!!.entryUuid
        logger.quickLog(isSend = true)
        withTimeout(5_000) { state.first { !it.isQuickLogging && it.quickLogFeedback?.isSend == true } }

        val promoted = mutableListOf<com.cruxcoach.data.repository.QuickLogSendInput>()
        verify { repo.promoteQuickBidToSend(capture(promoted)) }
        assertEquals(entryUuid, promoted.single().uuid)
        assertEquals(3L, promoted.single().bidCount)

        logger.undoQuickLog()
        val restored = mutableListOf<com.cruxcoach.data.repository.QuickLogBidInput>()
        verify(timeout = 5_000) { repo.restoreQuickBidFromSend(capture(restored)) }
        assertEquals(entryUuid, restored.single().uuid)
        assertEquals(2L, restored.single().bidCount)
        verify { session.undoRecordedAscent() }
    }

    private fun entry(uuid: String, climbUuid: String, climbedAt: String) = AscentWithClimb(
        uuid = uuid,
        climbUuid = climbUuid,
        angle = 40L,
        isMirror = false,
        bidCount = 1L,
        quality = null,
        difficulty = null,
        comment = null,
        climbedAt = climbedAt,
        climbName = climb.name,
        climbFrames = climb.frames,
        difficultyAverage = climb.difficultyAverage,
    )

    /** An entry kept under a remapped duplicate's uuid (climb_aliases). */
    private val aliasEntry = entry("alias-entry", "climb-1-alias", "2026-11-01 10:00:00")

    /** The detail VM's wiring: the climb's own uuid plus its alias. */
    private fun aliasHistory(repo: PersonalBoardRepository): (String) -> List<AscentWithClimb> = { uuid ->
        mergedClimbHistory(listOf(uuid, "climb-1-alias"), repo::getUserHistoryForClimb)
    }

    @Test
    fun `alias entries stay in the history after a quick log`() = runBlocking {
        val state = MutableStateFlow(ClimbDetailState(isLoading = false, climb = climb, userAscents = listOf(aliasEntry)))
        val repo = mockk<PersonalBoardRepository>(relaxed = true)
        val own = entry("own-send", climb.uuid, "2026-11-20 18:00:00")
        every { repo.getUserHistoryForClimb(climb.uuid) } returns listOf(own)
        every { repo.getUserHistoryForClimb("climb-1-alias") } returns listOf(aliasEntry)
        every { repo.observeClimbHistory() } returns flowOf(emptyList())
        val logger = logger(state, repo, mockk(relaxed = true), climbHistory = aliasHistory(repo)) { }

        logger.quickLog(isSend = false)
        val logged = withTimeout(5_000) { state.first { !it.isQuickLogging && it.quickLogFeedback != null } }

        assertEquals(listOf(own, aliasEntry), logged.userAscents)
    }

    @Test
    fun `alias entries stay in the history after a delete`() = runBlocking {
        val own = entry("own-send", climb.uuid, "2026-11-20 18:00:00")
        val state = MutableStateFlow(
            ClimbDetailState(isLoading = false, climb = climb, userAscents = listOf(own, aliasEntry)),
        )
        val repo = mockk<PersonalBoardRepository>(relaxed = true)
        // After the delete the climb's own uuid has nothing left; the alias entry remains.
        every { repo.getUserHistoryForClimb(climb.uuid) } returns emptyList()
        every { repo.getUserHistoryForClimb("climb-1-alias") } returns listOf(aliasEntry)
        val logger = logger(state, repo, mockk(relaxed = true), climbHistory = aliasHistory(repo)) { }

        logger.requestDelete(own.uuid)
        logger.confirmDelete()
        val deleted = withTimeout(5_000) { state.first { it.ascent.deleteConfirmUuid == null } }

        verify { repo.deleteAscent(own.uuid) }
        assertEquals(listOf(aliasEntry), deleted.userAscents)
    }

    @Test
    fun `merged history lists each entry once, newest first`() {
        val older = entry("a", "climb-1", "2026-10-01 10:00:00")
        val newer = entry("b", "climb-1-alias", "2026-11-01 10:00:00")
        val byUuid = mapOf("climb-1" to listOf(older), "climb-1-alias" to listOf(newer, older))

        val merged = mergedClimbHistory(listOf("climb-1", "climb-1-alias")) { byUuid[it].orEmpty() }

        assertEquals(listOf(newer, older), merged)
    }

    @Test
    fun `failed quick log re-enables actions and does not change session totals`() = runBlocking {
        val state = MutableStateFlow(ClimbDetailState(isLoading = false, climb = climb))
        val repo = mockk<PersonalBoardRepository>(relaxed = true)
        val session = mockk<BoardSessionManager>(relaxed = true)
        every {
            repo.insertBid(
                uuid = any(),
                climbUuid = any(),
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
        } throws IllegalStateException("database unavailable")
        val logger = logger(state, repo, session) { }

        logger.quickLog(isSend = false)
        val failed = withTimeout(5_000) { state.first { it.quickLogFailed } }

        assertFalse(failed.isQuickLogging)
        assertEquals(null, failed.quickLogFeedback)
        verify(exactly = 0) { session.recordBid() }
    }

    private fun logger(
        state: MutableStateFlow<ClimbDetailState>,
        repo: PersonalBoardRepository,
        session: BoardSessionManager,
        onQuickSaved: (Boolean) -> Unit = {},
        climbHistory: ((String) -> List<AscentWithClimb>)? = null,
        onSaved: (Boolean) -> Unit,
    ): AscentLogger {
        // A quick log reads the running session's start to continue an attempt
        // from an earlier visit; a relaxed mock's state is no session state.
        every { session.state } returns MutableStateFlow(BoardSessionState())
        return AscentLogger(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            state = state,
            personalBoardRepo = repo,
            sessionManager = session,
            zoneManager = mockk<IntensityZoneManager>(relaxed = true),
            climbNavState = mockk<ClimbNavigationState>(relaxed = true),
            currentClimbUuid = { climb.uuid },
            climbHistory = climbHistory ?: repo::getUserHistoryForClimb,
            onAscentSaved = onSaved,
            onQuickLogSaved = onQuickSaved,
        )
    }
}
