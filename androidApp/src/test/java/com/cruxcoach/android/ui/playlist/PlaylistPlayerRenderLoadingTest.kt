package com.cruxcoach.android.ui.playlist

import com.cruxcoach.android.ble.QueueItem
import com.cruxcoach.android.data.PlaylistPlaybackState
import com.cruxcoach.android.ui.board.ClimbRenderData
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PlaylistPlayerRenderLoadingTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `new occurrence clears stale Quantum render and cancels its late result`() = runTest {
        val quantumItem = QueueItem("quantum", 40)
        val kilterItem = QueueItem("kilter", 50)
        val playback = MutableStateFlow(stateWith(quantumItem))
        val quantumRender = mockk<ClimbRenderData>()
        val kilterRender = mockk<ClimbRenderData>()
        val quantumStarted = CompletableDeferred<Unit>()
        val kilterStarted = CompletableDeferred<Unit>()
        val releaseQuantum = CompletableDeferred<Unit>()
        val releaseKilter = CompletableDeferred<Unit>()
        val updates = mutableListOf<Pair<ClimbRenderData?, Boolean>>()

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            collectPlaylistRenders(
                playbackStates = playback,
                load = { item ->
                    when (item.climbUuid) {
                        quantumItem.climbUuid -> {
                            quantumStarted.complete(Unit)
                            releaseQuantum.await()
                            quantumRender
                        }
                        else -> {
                            kilterStarted.complete(Unit)
                            releaseKilter.await()
                            kilterRender
                        }
                    }
                },
                update = { render, loading -> updates += render to loading },
            )
        }

        quantumStarted.await()
        assertNull(updates.last().first)

        playback.value = stateWith(kilterItem)
        kilterStarted.await()

        // The old Quantum board/rack is gone before the Kilter catalogue read
        // completes, so the next occurrence can only show its loading state.
        assertNull(updates.last().first)
        releaseQuantum.complete(Unit)
        advanceUntilIdle()
        assertFalse(updates.any { it.first === quantumRender })

        releaseKilter.complete(Unit)
        advanceUntilIdle()
        assertSame(kilterRender, updates.last().first)
        assertFalse(updates.last().second)
    }

    private fun stateWith(item: QueueItem) = PlaylistPlaybackState(
        isActive = true,
        queue = listOf(item),
        currentIndex = 0,
        currentClimb = item,
    )
}
