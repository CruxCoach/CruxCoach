package com.cruxcoach.android.data

import android.app.Application
import com.cruxcoach.android.notification.AppNotificationService
import com.cruxcoach.data.repository.PersonalBoardRepository
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BoardSessionManagerRestTest {
    @Test
    fun `planned rest expiry resumes the session clock`() {
        val repository = mockk<PersonalBoardRepository>(relaxed = true)
        every { repository.getActiveSession() } returns null
        every {
            repository.insertBoardSession(any(), any(), any(), any(), any(), any())
        } returns 42L
        val manager = BoardSessionManager(
            personalBoardRepo = repository,
            notificationService = mockk<AppNotificationService>(relaxed = true),
            alarmScheduler = mockk<RestTimerAlarmScheduler>(relaxed = true),
        )

        manager.startSession()
        manager.startRestTimer(durationSeconds = 0)

        assertTrue(manager.state.value.isPaused)
        assertEquals(PauseReason.PLANNED_REST, manager.state.value.pauseReason)

        BoardSessionManager::class.java.getDeclaredMethod("tick").apply {
            isAccessible = true
            invoke(manager)
        }

        assertFalse(manager.state.value.isPaused)
        assertEquals(null, manager.state.value.pauseReason)
        assertTrue(manager.restTimer.value.isFinished)
        manager.endSession()
    }
}
