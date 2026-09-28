package com.cruxcoach.android.ui.board.creator

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A publish dialog may start at most one publish, however often it is tapped. */
class ConsumePendingTest {
    private data class Dialogs(val pendingPublishConfirm: Boolean)

    @Test
    fun `only the first tap takes the dialog`() {
        val state = MutableStateFlow(Dialogs(pendingPublishConfirm = true))

        val first = state.consumePending({ it.pendingPublishConfirm }) { it.copy(pendingPublishConfirm = false) }
        val second = state.consumePending({ it.pendingPublishConfirm }) { it.copy(pendingPublishConfirm = false) }

        assertTrue(first)
        assertFalse(second)
        assertFalse(state.value.pendingPublishConfirm)
    }

    @Test
    fun `nothing is taken without a pending dialog`() {
        val state = MutableStateFlow(Dialogs(pendingPublishConfirm = false))

        assertFalse(state.consumePending({ it.pendingPublishConfirm }) { it.copy(pendingPublishConfirm = false) })
    }

    @Test
    fun `simultaneous taps publish once`() {
        val state = MutableStateFlow(Dialogs(pendingPublishConfirm = true))
        val taken = AtomicInteger()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        repeat(8) {
            pool.execute {
                start.await()
                if (state.consumePending({ s -> s.pendingPublishConfirm }) { s -> s.copy(pendingPublishConfirm = false) }) {
                    taken.incrementAndGet()
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))

        assertEquals(1, taken.get())
    }
}
