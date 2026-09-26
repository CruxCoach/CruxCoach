package com.cruxcoach.android.data

import com.cruxcoach.data.repository.Board_sessions
import com.cruxcoach.data.repository.PersonalBoardRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * A board session is always played through a queue that lives in memory, so a
 * session still open in the database when the process starts belongs to a queue
 * that no longer exists.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoardSessionRecoveryTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a session left over from a previous process ends where it was last recorded`() {
        val repo = mockk<PersonalBoardRepository>(relaxed = true)
        every { repo.getActiveSession() } returns Board_sessions(
            id = 7,
            startedAt = "2026-09-23T20:00",
            endedAt = null,
            totalDurationSeconds = 1_500,
            pauseDurationSeconds = 60,
            ascentCount = 3,
            bidCount = 5,
        )

        val manager = BoardSessionManager(repo, mockk(relaxed = true), mockk(relaxed = true))

        // Nothing runs on invisibly for the next training to continue ...
        assertFalse(manager.state.value.isActive)
        // ... and the night after the crash is not booked as training.
        verify(exactly = 1) {
            repo.endBoardSession(
                id = 7,
                endedAt = "2026-09-23T20:25",
                totalDurationSeconds = 1_500,
                pauseDurationSeconds = 60,
                ascentCount = 3,
                bidCount = 5,
            )
        }
    }

    @Test
    fun `a new training after the leftover was ended starts its own session`() {
        val repo = mockk<PersonalBoardRepository>(relaxed = true)
        every { repo.getActiveSession() } returns Board_sessions(
            id = 7, startedAt = "2026-09-23T20:00", endedAt = null,
            totalDurationSeconds = 1_500, pauseDurationSeconds = 0, ascentCount = 0, bidCount = 0,
        )
        every { repo.insertBoardSession(any(), any(), any(), any(), any(), any()) } returns 8

        val manager = BoardSessionManager(repo, mockk(relaxed = true), mockk(relaxed = true))
        manager.startSession()

        verify(exactly = 1) { repo.insertBoardSession(any(), null, 0L, 0L, 0L, 0L) }
    }
}
