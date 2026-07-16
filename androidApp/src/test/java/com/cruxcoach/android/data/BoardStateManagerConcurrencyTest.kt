package com.cruxcoach.android.data

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoardStateManagerConcurrencyTest {

    @Test
    fun `older slow name lookup cannot overwrite the latest climb`() = runTest {
        val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val firstStarted = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val preferences = mockk<UserPreferences>()
            val resolver = mockk<ClimbNameResolver>()
            coEvery { preferences.setLastClimb(any(), any()) } just Runs
            every { resolver.resolveName("older", 30) } answers {
                firstStarted.countDown()
                check(releaseFirst.await(5, TimeUnit.SECONDS))
                "Older"
            }
            every { resolver.resolveName("latest", 40) } returns "Latest"
            val manager = BoardStateManager(preferences, resolver, managerScope)

            val older = async(Dispatchers.Default) { manager.setLastClimb("older", 30) }
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS), "older lookup did not start")
            val latest = async(Dispatchers.Default) { manager.setLastClimb("latest", 40) }
            latest.await()
            releaseFirst.countDown()
            older.await()

            assertEquals("latest", manager.lastClimb.value?.uuid)
            assertEquals("Latest", manager.lastClimb.value?.name)
            coVerify(exactly = 0) { preferences.setLastClimb("older", 30) }
            coVerify(exactly = 1) { preferences.setLastClimb("latest", 40) }

        } finally {
            managerScope.cancel()
        }
    }
}
