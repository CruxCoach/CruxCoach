package com.cruxcoach.android.updater

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the elapsedRealtime-based throttle in [UpdateChecker] (§6.12).
 *
 * Uses a captured wall-time provider so tests can advance simulated
 * boot-time without touching the real clock. The UpdaterPreferences,
 * CodebergReleaseClient, and InstallSourceGate collaborators are mocked
 * — this test is strictly about the skip/throttle/allow decision tree.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateCheckerThrottleTest {

    private val installSourceGate: InstallSourceGate = mockk(relaxed = true)
    private val preferences: UpdaterPreferences = mockk(relaxed = true)
    private val client: CodebergReleaseClient = mockk(relaxed = true)

    private var simulatedRealtimeMs: Long = 10_000L

    private fun checker(): UpdateChecker = UpdateChecker(
        preferences = preferences,
        client = client,
        installSourceGate = installSourceGate,
        elapsedRealtimeProvider = { simulatedRealtimeMs },
    )

    private fun stubGateAllowed() {
        every { installSourceGate.selfUpdateAllowed() } returns true
    }

    private fun stubGateDenied() {
        every { installSourceGate.selfUpdateAllowed() } returns false
    }

    private fun stubPrefsSnapshot(state: UpdaterState) {
        coEvery { preferences.snapshot() } returns state
        coEvery { preferences.update(any()) } just Runs
    }

    @Test
    fun `install-source gate short-circuits before any snapshot read`() = runTest {
        stubGateDenied()

        val outcome = checker().maybeCheck(UpdateChecker.Trigger.PERIODIC)

        assertTrue(outcome is UpdateChecker.CheckOutcome.Skipped)
        assertEquals("install_source_gated", (outcome as UpdateChecker.CheckOutcome.Skipped).reason)
    }

    @Test
    fun `auto-check disabled skips non-manual triggers`() = runTest {
        stubGateAllowed()
        stubPrefsSnapshot(UpdaterState(autoCheckEnabled = false))

        val outcome = checker().maybeCheck(UpdateChecker.Trigger.APP_FOREGROUND)

        assertTrue(outcome is UpdateChecker.CheckOutcome.Skipped)
        assertEquals("auto_check_disabled", (outcome as UpdateChecker.CheckOutcome.Skipped).reason)
    }

    @Test
    fun `manual trigger bypasses the auto-check-disabled guard`() = runTest {
        stubGateAllowed()
        stubPrefsSnapshot(UpdaterState(autoCheckEnabled = false))
        coEvery { client.fetchReleases(any(), any()) } returns CodebergReleaseClient.Result.NotModified

        val outcome = checker().maybeCheck(UpdateChecker.Trigger.MANUAL)

        assertTrue(outcome is UpdateChecker.CheckOutcome.NotModified)
    }

    @Test
    fun `throttle skips when last check was within 2h window`() = runTest {
        stubGateAllowed()
        simulatedRealtimeMs = 3_600_000L + 100L // boot-time is 1h100ms past the last check
        stubPrefsSnapshot(
            UpdaterState(
                autoCheckEnabled = true,
                lastCheckBootRealtime = 100L,
            ),
        )

        val outcome = checker().maybeCheck(UpdateChecker.Trigger.PERIODIC)

        assertTrue(outcome is UpdateChecker.CheckOutcome.Throttled)
        // Remaining ~= 2h - 1h = 1h
        val remaining = (outcome as UpdateChecker.CheckOutcome.Throttled).remainingMs
        assertTrue("remaining $remaining should be > 0", remaining > 0)
        assertTrue("remaining $remaining should be < 2h", remaining < UpdateChecker.MIN_CHECK_INTERVAL_MS)
    }

    @Test
    fun `throttle bypassed for manual trigger even if within window`() = runTest {
        stubGateAllowed()
        simulatedRealtimeMs = 3_600_000L + 100L
        stubPrefsSnapshot(
            UpdaterState(
                autoCheckEnabled = true,
                lastCheckBootRealtime = 100L,
            ),
        )
        coEvery { client.fetchReleases(any(), any()) } returns CodebergReleaseClient.Result.NotModified

        val outcome = checker().maybeCheck(UpdateChecker.Trigger.MANUAL)

        assertTrue(
            "MANUAL ignores throttle — expected NotModified, was $outcome",
            outcome is UpdateChecker.CheckOutcome.NotModified,
        )
    }

    @Test
    fun `throttle passes once interval has elapsed`() = runTest {
        stubGateAllowed()
        simulatedRealtimeMs = UpdateChecker.MIN_CHECK_INTERVAL_MS + 100L + 1L
        stubPrefsSnapshot(
            UpdaterState(
                autoCheckEnabled = true,
                lastCheckBootRealtime = 100L,
            ),
        )
        coEvery { client.fetchReleases(any(), any()) } returns CodebergReleaseClient.Result.NotModified

        val outcome = checker().maybeCheck(UpdateChecker.Trigger.PERIODIC)

        assertTrue(outcome is UpdateChecker.CheckOutcome.NotModified)
    }

    @Test
    fun `first-ever check is allowed regardless of realtime`() = runTest {
        stubGateAllowed()
        simulatedRealtimeMs = 1L // extremely low — would be throttled if 0 wasn't treated as "never"
        stubPrefsSnapshot(
            UpdaterState(
                autoCheckEnabled = true,
                lastCheckBootRealtime = 0L,
            ),
        )
        coEvery { client.fetchReleases(any(), any()) } returns CodebergReleaseClient.Result.NotModified

        val outcome = checker().maybeCheck(UpdateChecker.Trigger.PERIODIC)

        assertTrue(outcome is UpdateChecker.CheckOutcome.NotModified)
    }

    @Test
    fun `reboot resets throttle — negative sinceBoot must not block the check`() = runTest {
        stubGateAllowed()
        // The last check ran 1h into a previous long-uptime session (stored
        // boot-realtime is large), then the device rebooted so the current
        // uptime is tiny. Old behaviour: sinceBoot = 5_000 - 3_600_000 < 0, and
        // `negative < interval` throttled every non-manual check for hours until
        // uptime climbed past the stale value. The reboot guard must allow it.
        simulatedRealtimeMs = 5_000L
        stubPrefsSnapshot(
            UpdaterState(
                autoCheckEnabled = true,
                lastCheckBootRealtime = 3_600_000L,
            ),
        )
        coEvery { client.fetchReleases(any(), any()) } returns CodebergReleaseClient.Result.NotModified

        val outcome = checker().maybeCheck(UpdateChecker.Trigger.PERIODIC)

        assertTrue(
            "a post-reboot check must not be throttled, was $outcome",
            outcome is UpdateChecker.CheckOutcome.NotModified,
        )
    }
}
