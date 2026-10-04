package com.cruxcoach.android.data

import com.cruxcoach.data.repository.PersonalBoardRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** M-097: a finished rest must hand the session back to the training clock. */
@OptIn(ExperimentalCoroutinesApi::class)
class BoardSessionRestTimerTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `an expired rest resumes the session it paused`() {
        // The session's own ticker is already looping; let it run its next tick.
        val main = StandardTestDispatcher()
        Dispatchers.setMain(main)
        val repo = mockk<PersonalBoardRepository>(relaxed = true)
        every { repo.getActiveSession() } returns null
        every { repo.insertBoardSession(any(), any(), any(), any(), any(), any()) } returns 1
        val manager = BoardSessionManager(repo, mockk(relaxed = true), mockk(relaxed = true))
        manager.startSession()

        main.scheduler.runCurrent()
        manager.startRestTimer(0)
        main.scheduler.advanceTimeBy(600)
        main.scheduler.runCurrent()

        assertTrue(manager.restTimer.value.isFinished)
        assertFalse(manager.state.value.isPaused)
    }

    @Test
    fun `a rest without a session still counts down and finishes`() {
        val repo = mockk<PersonalBoardRepository>(relaxed = true)
        every { repo.getActiveSession() } returns null
        val manager = BoardSessionManager(repo, mockk(relaxed = true), mockk(relaxed = true))

        manager.startRestTimer(0)

        assertTrue(manager.restTimer.value.isFinished)
        assertFalse(manager.state.value.isActive)
    }
}
