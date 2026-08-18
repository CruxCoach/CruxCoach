package com.cruxcoach.android.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 0.2.3 raises minSdk from 26 to 28 (v3 signing, and with it the certificate
 * lineage a key rotation needs, does not exist before API 28). The devices
 * that drops — Android 8.0 and 8.1 — have to be told while they can still
 * receive a release, which is what this gate decides.
 */
class DeviceSupportGateTest {

    private fun gate(sdkInt: Int, next: Int = 28) =
        DeviceSupportGate(sdkInt = sdkInt, minSdkNextRelease = next)

    @Test
    fun `Android 8_0 and 8_1 are past their last release`() {
        assertFalse(gate(sdkInt = 26).receivesFutureUpdates(), "API 26 / Android 8.0")
        assertFalse(gate(sdkInt = 27).receivesFutureUpdates(), "API 27 / Android 8.1")
    }

    @Test
    fun `Android 9 is exactly on the boundary and keeps updates`() {
        // Off-by-one here would strand every Android 9 device — the largest
        // group affected by the minSdk bump, and the one that must NOT be.
        assertTrue(gate(sdkInt = 28).receivesFutureUpdates())
    }

    @Test
    fun `newer devices keep updates`() {
        assertTrue(gate(sdkInt = 33).receivesFutureUpdates())
        assertTrue(gate(sdkInt = 36).receivesFutureUpdates())
    }

    @Test
    fun `the required level is reported for the user-facing message`() {
        assertEquals(28, gate(sdkInt = 26).requiredSdkInt())
    }

    @Test
    fun `a future bump moves the boundary without touching the gate`() {
        // The constant is build-time configurable so the next drop is a
        // one-line change in the release before it.
        assertFalse(gate(sdkInt = 28, next = 31).receivesFutureUpdates())
        assertTrue(gate(sdkInt = 31, next = 31).receivesFutureUpdates())
    }
}
