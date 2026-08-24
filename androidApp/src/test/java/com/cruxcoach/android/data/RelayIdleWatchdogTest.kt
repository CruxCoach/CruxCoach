package com.cruxcoach.android.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RelayIdleWatchdogTest {
    @Test
    fun `zero clients time out once at the monotonic boundary`() = runTest {
        var clients = 0
        var timeouts = 0
        val watchdog = RelayIdleWatchdog(
            scope = this,
            timeoutMs = 100,
            pollMs = 10,
            nowMs = { testScheduler.currentTime },
            clientCount = { clients },
            onTimeout = { timeouts++ },
        )

        watchdog.start()
        runCurrent()
        advanceTimeBy(99)
        runCurrent()
        assertEquals(0, timeouts)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, timeouts)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(1, timeouts)
    }

    @Test
    fun `last client departure receives a complete new idle window`() = runTest {
        var clients = 1
        var timeouts = 0
        val watchdog = RelayIdleWatchdog(
            scope = this,
            timeoutMs = 100,
            pollMs = 10,
            nowMs = { testScheduler.currentTime },
            clientCount = { clients },
            onTimeout = { timeouts++ },
        )

        watchdog.start()
        runCurrent()
        advanceTimeBy(250)
        runCurrent()
        assertEquals(0, timeouts)
        clients = 0
        advanceTimeBy(10) // transition is observed here
        runCurrent()
        advanceTimeBy(99)
        runCurrent()
        assertEquals(0, timeouts)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, timeouts)
    }

    @Test
    fun `activity resets expiry and stop cancels late callback`() = runTest {
        var timeouts = 0
        val watchdog = RelayIdleWatchdog(
            scope = this,
            timeoutMs = 100,
            pollMs = 10,
            nowMs = { testScheduler.currentTime },
            clientCount = { 0 },
            onTimeout = { timeouts++ },
        )

        watchdog.start()
        runCurrent()
        advanceTimeBy(80)
        runCurrent()
        watchdog.activity()
        advanceTimeBy(80)
        runCurrent()
        assertEquals(0, timeouts)
        watchdog.stop()
        advanceTimeBy(200)
        runCurrent()
        assertEquals(0, timeouts)
    }
}
