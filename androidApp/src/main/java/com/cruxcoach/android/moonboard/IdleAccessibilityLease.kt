package com.cruxcoach.android.moonboard

import android.os.Handler

internal const val MOON_IDLE_ACCESSIBILITY_LEASE_MS = 10L * 60_000L

/**
 * Bounded lifetime for an enabled accessibility service that is not actively
 * performing the one transfer the user requested. Kept separate from Android
 * service plumbing so rebind, cancel, and timeout behavior are deterministic
 * under Robolectric.
 */
internal class IdleAccessibilityLease(
    private val handler: Handler,
    private val timeoutMs: Long,
    private val isIdle: () -> Boolean,
    private val relinquish: () -> Unit,
) {
    private val timeout = Runnable {
        if (isIdle()) relinquish()
    }

    fun arm() {
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, timeoutMs)
    }

    fun cancel() {
        handler.removeCallbacks(timeout)
    }
}
