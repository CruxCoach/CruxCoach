package com.cruxcoach.android.led

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the dispatch order the LED keep-alive hand-off in `CruxCoachApp` relies on:
 * the engine registers itself as a process-lifecycle observer in `start()`, the
 * keep-alive hook registers itself right after, and the whole hand-off is only
 * gapless if lifecycle down events reach the newest observer first (so the hook
 * claims the background before the engine notices the foreground loss) while up
 * events reach it last (so the engine's foreground gate is open again before the
 * hook releases the claim).
 *
 * That is `LifecycleRegistry` behaviour — its backward pass "iterates from newest
 * to oldest so children tear down before parents". The guarantee is load-bearing
 * for a one-beat-free animation switch, so it is tested rather than trusted.
 */
class LedAnimationKeepAliveOrderingTest {

    private class TestOwner : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
        fun send(event: Lifecycle.Event) = registry.handleLifecycleEvent(event)
    }

    @Test
    fun theKeepAliveHookClaimsBeforeTheEngineStopsAndReleasesAfterTheEngineStarts() {
        val owner = TestOwner()
        val order = mutableListOf<String>()

        // Registration order mirrors CruxCoachApp: engine first, keep-alive hook second.
        owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { order += "engine-start" }
            override fun onStop(owner: LifecycleOwner) { order += "engine-stop" }
        })
        owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { order += "keepAlive-start" }
            override fun onStop(owner: LifecycleOwner) { order += "keepAlive-stop" }
        })

        owner.send(Lifecycle.Event.ON_START)
        order.clear()
        owner.send(Lifecycle.Event.ON_STOP)
        owner.send(Lifecycle.Event.ON_START)

        assertEquals(
            listOf(
                "keepAlive-stop", "engine-stop",
                "engine-start", "keepAlive-start",
            ),
            order,
        )
    }
}
