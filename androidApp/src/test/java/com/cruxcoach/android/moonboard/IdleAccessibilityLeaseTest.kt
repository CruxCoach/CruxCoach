package com.cruxcoach.android.moonboard

import android.os.Handler
import android.os.Looper
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class IdleAccessibilityLeaseTest {
    @Test
    fun `idle bound service relinquishes access at fixed deadline`() {
        var relinquished = 0
        val lease = IdleAccessibilityLease(
            Handler(Looper.getMainLooper()),
            MOON_IDLE_ACCESSIBILITY_LEASE_MS,
            isIdle = { true },
            relinquish = { relinquished++ },
        )

        lease.arm()
        shadowOf(Looper.getMainLooper()).idleFor(
            MOON_IDLE_ACCESSIBILITY_LEASE_MS - 1,
            TimeUnit.MILLISECONDS,
        )
        assertEquals(0, relinquished)
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS)
        assertEquals(1, relinquished)
    }

    @Test
    fun `starting a scan cancels idle lease and rebind can rearm it`() {
        var relinquished = 0
        var idle = true
        val lease = IdleAccessibilityLease(
            Handler(Looper.getMainLooper()),
            MOON_IDLE_ACCESSIBILITY_LEASE_MS,
            isIdle = { idle },
            relinquish = { relinquished++ },
        )

        lease.arm()
        lease.cancel()
        idle = false
        shadowOf(Looper.getMainLooper()).idleFor(
            MOON_IDLE_ACCESSIBILITY_LEASE_MS,
            TimeUnit.MILLISECONDS,
        )
        assertEquals(0, relinquished)

        idle = true
        lease.arm()
        shadowOf(Looper.getMainLooper()).idleFor(
            MOON_IDLE_ACCESSIBILITY_LEASE_MS,
            TimeUnit.MILLISECONDS,
        )
        assertEquals(1, relinquished)
    }

    @Test
    fun `rearming replaces rather than extends with duplicate callbacks`() {
        var relinquished = 0
        val lease = IdleAccessibilityLease(
            Handler(Looper.getMainLooper()),
            MOON_IDLE_ACCESSIBILITY_LEASE_MS,
            isIdle = { true },
            relinquish = { relinquished++ },
        )

        lease.arm()
        shadowOf(Looper.getMainLooper()).idleFor(
            MOON_IDLE_ACCESSIBILITY_LEASE_MS / 2,
            TimeUnit.MILLISECONDS,
        )
        lease.arm()
        shadowOf(Looper.getMainLooper()).idleFor(
            MOON_IDLE_ACCESSIBILITY_LEASE_MS,
            TimeUnit.MILLISECONDS,
        )

        assertEquals(1, relinquished)
    }
}
