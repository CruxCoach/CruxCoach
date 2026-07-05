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

    private fun releaseWithApk(tag: String): CodebergRelease = CodebergRelease(
        id = 1,
        tagName = tag,
        htmlUrl = "https://codeberg.org/CruxCoach/CruxCoach/releases/tag/$tag",
        assets = listOf(
            CodebergAsset(name = "CruxCoach-$tag.apk", browserDownloadUrl = "https://x/$tag.apk", size = 1000),
            CodebergAsset(name = "CruxCoach-$tag.apk.sha256", browserDownloadUrl = "https://x/$tag.apk.sha256", size = 64),
        ),
    )

    @Test
    fun `a newer pending version clears the prior version's dismiss and re-arm state`() = runTest {
        stubGateAllowed()
        simulatedRealtimeMs = 10_000L
        // Installed = BuildConfig.VERSION_NAME (0.2.1 in the test build). The user
        // was already notified about an older pending 0.2.5 and swiped it away;
        // now a newer 9.9.9 is available. The dismiss/re-arm state must NOT carry
        // over, else 9.9.9 could never be re-surfaced.
        val seed = UpdaterState(
            autoCheckEnabled = true,
            lastCheckBootRealtime = 0L,
            pendingVersionName = "0.2.5",
            notifDismissedAtEpochMs = 123L,
            notifReArmCount = 3,
        )
        coEvery { preferences.snapshot() } returns seed
        val captured = mutableListOf<(UpdaterState) -> UpdaterState>()
        coEvery { preferences.update(capture(captured)) } just Runs
        coEvery { client.fetchReleases(any(), any()) } returns
            CodebergReleaseClient.Result.Success(listOf(releaseWithApk("v9.9.9")), "etag-b")
        coEvery { client.fetchSha256(any()) } returns "a".repeat(64)

        val outcome = checker().maybeCheck(UpdateChecker.Trigger.PERIODIC)

        assertTrue("expected Update, was $outcome", outcome is UpdateChecker.CheckOutcome.Update)
        val finalState = captured.fold(seed) { s, f -> f(s) }
        assertEquals(null, finalState.notifDismissedAtEpochMs)
        assertEquals(0, finalState.notifReArmCount)
        assertEquals("9.9.9", finalState.pendingVersionName)
    }

    @Test
    fun `re-detecting the same pending version keeps its dismiss state`() = runTest {
        stubGateAllowed()
        simulatedRealtimeMs = 10_000L
        val seed = UpdaterState(
            autoCheckEnabled = true,
            lastCheckBootRealtime = 0L,
            pendingVersionName = "9.9.9",
            notifDismissedAtEpochMs = 123L,
            notifReArmCount = 3,
        )
        coEvery { preferences.snapshot() } returns seed
        val captured = mutableListOf<(UpdaterState) -> UpdaterState>()
        coEvery { preferences.update(capture(captured)) } just Runs
        coEvery { client.fetchReleases(any(), any()) } returns
            CodebergReleaseClient.Result.Success(listOf(releaseWithApk("v9.9.9")), "etag-b")
        coEvery { client.fetchSha256(any()) } returns "a".repeat(64)

        checker().maybeCheck(UpdateChecker.Trigger.PERIODIC)

        val finalState = captured.fold(seed) { s, f -> f(s) }
        assertEquals(123L, finalState.notifDismissedAtEpochMs)
        assertEquals(3, finalState.notifReArmCount)
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
