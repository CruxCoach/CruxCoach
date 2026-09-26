package com.cruxcoach.android.data

import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Restoring the last board climb must not restart its 30-minute window. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class BoardStateRestoreTest {
    private val userPreferences = mockk<UserPreferences>(relaxed = true)
    private val nameResolver = mockk<ClimbNameResolver> {
        every { resolveName(any(), any()) } returns "wu"
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun persisted(minutesAgo: Long) {
        every { userPreferences.lastClimbUuid } returns flowOf("c1f3ebca")
        every { userPreferences.lastClimbAngle } returns flowOf(40)
        every { userPreferences.lastClimbTimestamp } returns
            flowOf(System.currentTimeMillis() - minutesAgo * 60_000L)
        every { userPreferences.lastClimbProjectionSurvivesDisconnect } returns flowOf(true)
    }

    @Test
    fun `a recent climb comes back with its original time and is not saved again`() = runTest {
        persisted(minutesAgo = 25)
        val manager = BoardStateManager(userPreferences, nameResolver)

        manager.restore()

        val restored = manager.lastClimb.value
        assertEquals("c1f3ebca", restored?.uuid)
        assertEquals(
            25L,
            (System.currentTimeMillis() - restored!!.timestamp) / 60_000L,
        )
        coVerify(exactly = 0) { userPreferences.setLastClimb(any(), any(), any()) }
    }

    @Test
    fun `a climb older than the window stays gone`() = runTest {
        persisted(minutesAgo = 40)
        val manager = BoardStateManager(userPreferences, nameResolver)

        manager.restore()

        assertNull(manager.lastClimb.value)
    }
}
