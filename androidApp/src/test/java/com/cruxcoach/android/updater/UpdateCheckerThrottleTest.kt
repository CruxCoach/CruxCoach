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
 * Verifies the elapsedRealtime-based throttle in [UpdateChecker] (§6.12) and
 * the FEAT-050 source-traversal rules.
 *
 * Uses a captured wall-time provider so tests can advance simulated
 * boot-time without touching the real clock. The UpdaterPreferences,
 * source list and InstallSourceGate collaborators are mocked — this test is
 * strictly about the skip/throttle/allow decision tree and about which
 * source gets asked next.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateCheckerThrottleTest {

    private val installSourceGate: InstallSourceGate = mockk(relaxed = true)
    private val preferences: UpdaterPreferences = mockk(relaxed = true)
    private val registry: UpdateSourceRegistry = mockk(relaxed = true)
    private val sourceFactory: ReleaseSourceFactory = mockk(relaxed = true)

    private var simulatedRealtimeMs: Long = 10_000L

    private val forgeSource = UpdateSource(
        id = "forge",
        kind = UpdateSource.Kind.FORGE,
        url = "https://codeberg.org/api/v1",
        owner = "CruxCoach",
        repo = "CruxCoach",
    )
    private val nostrSource = UpdateSource(
        id = "zapstore",
        kind = UpdateSource.Kind.NOSTR,
        url = "wss://relay.zapstore.dev",
        cdn = "https://cdn.zapstore.dev",
    )
    private val manifestSource = UpdateSource(
        id = "website",
        kind = UpdateSource.Kind.MANIFEST,
        url = "https://cruxcoach.org/apk-target.json",
    )

    private fun checker(
        deviceSupportGate: DeviceSupportGate = DeviceSupportGate(sdkInt = 99, minSdkNextRelease = 28),
    ): UpdateChecker = UpdateChecker(
        preferences = preferences,
        sourceFactory = sourceFactory,
        installSourceGate = installSourceGate,
        registry = registry,
        deviceSupportGate = deviceSupportGate,
        elapsedRealtimeProvider = { simulatedRealtimeMs },
    )

    /**
     * Wires an ordered source list, each with a canned answer. Also records
     * which sources were actually asked, so tests can assert that traversal
     * stopped where it should.
     */
    private val asked = mutableListOf<String>()

    private fun stubSources(vararg entries: Pair<UpdateSource, ReleaseSource.Result>) {
        coEvery { registry.discoverySources() } returns entries.map { it.first }
        entries.forEach { (source, result) ->
            val releaseSource: ReleaseSource = mockk(relaxed = true)
            every { releaseSource.source } returns source
            every { releaseSource.id } returns source.id
            coEvery { releaseSource.fetchNewerThan(any(), any()) } coAnswers {
                asked += source.id
                result
            }
            every { sourceFactory.create(source) } returns releaseSource
        }
        // Default: derive nothing extra, so downloadUrls is just the primary.
        coEvery { registry.downloadUrlsFor(any(), any(), any()) } coAnswers {
            listOfNotNull(thirdArg<String?>())
        }
    }

    private fun release(version: String, apkUrl: String = "https://x/$version.apk") = DiscoveredRelease(
        tagName = "v$version",
        version = SemVer.parseOrNull(version)!!,
        apkUrl = apkUrl,
        apkSha256 = "a".repeat(64),
        apkSha256Url = "https://x/$version.apk.sha256",
        apkSizeBytes = 1000,
        releaseNotesMarkdown = "notes",
        releasePageUrl = "https://cruxcoach.org/#download",
        publishedAtEpochSeconds = 0,
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
        stubSources(forgeSource to ReleaseSource.Result.NotModified)

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
        stubSources(forgeSource to ReleaseSource.Result.NotModified)

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
        stubSources(forgeSource to ReleaseSource.Result.NotModified)

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
        stubSources(forgeSource to ReleaseSource.Result.NotModified)

        val outcome = checker().maybeCheck(UpdateChecker.Trigger.PERIODIC)

        assertTrue(outcome is UpdateChecker.CheckOutcome.NotModified)
    }

    @Test
    fun `a newer pending version clears the prior version's dismiss and re-arm state`() = runTest {
        stubGateAllowed()
        simulatedRealtimeMs = 10_000L
        // Installed = BuildConfig.VERSION_NAME (0.2.x in the test build). The user
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
        stubSources(forgeSource to ReleaseSource.Result.Success(release("9.9.9"), "etag-b"))

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
        stubSources(forgeSource to ReleaseSource.Result.Success(release("9.9.9"), "etag-b"))

        checker().maybeCheck(UpdateChecker.Trigger.PERIODIC)

        val finalState = captured.fold(seed) { s, f -> f(s) }
        assertEquals(123L, finalState.notifDismissedAtEpochMs)
        assertEquals(3, finalState.notifReArmCount)
    }

    @Test
    fun `re-detecting the same version preserves an active pipeline stage`() = runTest {
        stubGateAllowed()
        simulatedRealtimeMs = 10_000L
        val seed = UpdaterState(
            autoCheckEnabled = true,
            pendingVersionName = "9.9.9",
            pipelineStage = PipelineStage.READY_TO_INSTALL,
            pendingDownloadSourceIndex = 1,
        )
        coEvery { preferences.snapshot() } returns seed
        val captured = mutableListOf<(UpdaterState) -> UpdaterState>()
        coEvery { preferences.update(capture(captured)) } just Runs
        stubSources(forgeSource to ReleaseSource.Result.Success(release("9.9.9"), "etag-b"))

        checker().maybeCheck(UpdateChecker.Trigger.PERIODIC)

        val finalState = captured.fold(seed) { state, transform -> transform(state) }
        assertEquals(PipelineStage.READY_TO_INSTALL, finalState.pipelineStage)
        assertEquals(1, finalState.pendingDownloadSourceIndex)
    }

    @Test
    fun `new release does not replace an in-flight PackageInstaller session`() = runTest {
        stubGateAllowed()
        val seed = UpdaterState(
            lastCheckEtags = mapOf("forge" to "installing-etag"),
            pendingTagName = "v0.2.5",
            pendingVersionName = "0.2.5",
            pendingDownloadUrls = listOf("https://old.example/app.apk"),
            pipelineStage = PipelineStage.INSTALLING,
        )
        coEvery { preferences.snapshot() } returns seed
        val captured = mutableListOf<(UpdaterState) -> UpdaterState>()
        coEvery { preferences.update(capture(captured)) } just Runs
        stubSources(forgeSource to ReleaseSource.Result.Success(release("9.9.9"), "new-etag"))

        val outcome = checker().maybeCheck(UpdateChecker.Trigger.MANUAL)

        assertTrue(outcome is UpdateChecker.CheckOutcome.Update)
        val finalState = captured.fold(seed) { state, transform -> transform(state) }
        assertEquals(PipelineStage.INSTALLING, finalState.pipelineStage)
        assertEquals("0.2.5", finalState.pendingVersionName)
        assertEquals(mapOf("forge" to "installing-etag"), finalState.lastCheckEtags)
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
        stubSources(forgeSource to ReleaseSource.Result.NotModified)

        val outcome = checker().maybeCheck(UpdateChecker.Trigger.PERIODIC)

        assertTrue(
            "a post-reboot check must not be throttled, was $outcome",
            outcome is UpdateChecker.CheckOutcome.NotModified,
        )
    }

    // ---------------------------------------------------------------- FEAT-050

    @Test
    fun `forge outage falls through to the signed Nostr source`() = runTest {
        stubGateAllowed()
        simulatedRealtimeMs = 10_000L
        stubPrefsSnapshot(UpdaterState(autoCheckEnabled = true))
        val cdnUrl = "https://cdn.zapstore.dev/${"a".repeat(64)}"
        stubSources(
            forgeSource to ReleaseSource.Result.Error("HTTP 503"),
            nostrSource to ReleaseSource.Result.Success(release("9.9.9", cdnUrl)),
        )

        val outcome = checker().maybeCheck(UpdateChecker.Trigger.PERIODIC)

        assertTrue("expected update from Nostr, was $outcome", outcome is UpdateChecker.CheckOutcome.Update)
        assertEquals(cdnUrl, (outcome as UpdateChecker.CheckOutcome.Update).info.apkUrl)
        assertEquals(listOf("forge", "zapstore"), asked)
    }

    @Test
    fun `traversal continues past every failing source and reports the last error`() = runTest {
        stubGateAllowed()
        simulatedRealtimeMs = 10_000L
        stubPrefsSnapshot(UpdaterState(autoCheckEnabled = true))
        stubSources(
            forgeSource to ReleaseSource.Result.Error("HTTP 503"),
            nostrSource to ReleaseSource.Result.Error("relay_unavailable"),
            manifestSource to ReleaseSource.Result.Error("HTTP 404"),
        )

        val outcome = checker().maybeCheck(UpdateChecker.Trigger.PERIODIC)

        assertEquals("HTTP 404", (outcome as UpdateChecker.CheckOutcome.Error).message)
        assertEquals(listOf("forge", "zapstore", "website"), asked)
    }

    @Test
    fun `all sources failing does not stamp the throttle, keeping the retry open`() = runTest {
        stubGateAllowed()
        simulatedRealtimeMs = 10_000L
        val seed = UpdaterState(autoCheckEnabled = true, lastCheckBootRealtime = 0L)
        coEvery { preferences.snapshot() } returns seed
        val captured = mutableListOf<(UpdaterState) -> UpdaterState>()
        coEvery { preferences.update(capture(captured)) } just Runs
        stubSources(forgeSource to ReleaseSource.Result.Error("HTTP 503"))

        checker().maybeCheck(UpdateChecker.Trigger.PERIODIC)

        val finalState = captured.fold(seed) { s, f -> f(s) }
        assertEquals(
            "a failed round must leave lastCheckBootRealtime alone so " +
                "NETWORK_AVAILABLE can retry immediately",
            0L,
            finalState.lastCheckBootRealtime,
        )
        assertEquals(CheckResult.ERROR, finalState.lastCheckResult)
    }

    @Test
    fun `every source is asked even after one already reported an update`() = runTest {
        stubGateAllowed()
        simulatedRealtimeMs = 10_000L
        stubPrefsSnapshot(UpdaterState(autoCheckEnabled = true))
        stubSources(
            forgeSource to ReleaseSource.Result.Success(release("9.9.9")),
            nostrSource to ReleaseSource.Result.Success(release("9.9.9")),
        )

        checker().maybeCheck(UpdateChecker.Trigger.PERIODIC)

        assertEquals(listOf("forge", "zapstore"), asked)
    }

    @Test
    fun `a lower-priority source's update is found even when the primary has none`() = runTest {
        // The freeze hazard this design exists to remove: the primary is
        // reachable and simply has nothing newer (an archived repo still
        // serving its final release list). It must not hide the source that
        // does have the release.
        stubGateAllowed()
        simulatedRealtimeMs = 10_000L
        stubPrefsSnapshot(UpdaterState(autoCheckEnabled = true))
        val cdnUrl = "https://cdn.zapstore.dev/${"a".repeat(64)}"
        stubSources(
            forgeSource to ReleaseSource.Result.Success(release = null, etag = "e1"),
            nostrSource to ReleaseSource.Result.Success(release("9.9.9", cdnUrl)),
        )

        val outcome = checker().maybeCheck(UpdateChecker.Trigger.PERIODIC)

        assertTrue("expected the Nostr update, was $outcome", outcome is UpdateChecker.CheckOutcome.Update)
        assertEquals("9.9.9", (outcome as UpdateChecker.CheckOutcome.Update).info.versionName)
        assertEquals(listOf("forge", "zapstore"), asked)
    }

    @Test
    fun `a NotModified primary does not hide a newer release elsewhere`() = runTest {
        stubGateAllowed()
        simulatedRealtimeMs = 10_000L
        stubPrefsSnapshot(UpdaterState(autoCheckEnabled = true))
        stubSources(
            forgeSource to ReleaseSource.Result.NotModified,
            nostrSource to ReleaseSource.Result.Success(release("9.9.9")),
        )

        val outcome = checker().maybeCheck(UpdateChecker.Trigger.PERIODIC)

        assertTrue("expected an update, was $outcome", outcome is UpdateChecker.CheckOutcome.Update)
    }

    @Test
    fun `the highest version across all sources wins`() = runTest {
        stubGateAllowed()
        simulatedRealtimeMs = 10_000L
        stubPrefsSnapshot(UpdaterState(autoCheckEnabled = true))
        stubSources(
            forgeSource to ReleaseSource.Result.Success(release("9.9.9")),
            nostrSource to ReleaseSource.Result.Success(release("10.0.0")),
            manifestSource to ReleaseSource.Result.Success(release("9.9.10")),
        )

        val outcome = checker().maybeCheck(UpdateChecker.Trigger.PERIODIC)

        assertEquals(
            "10.0.0",
            (outcome as UpdateChecker.CheckOutcome.Update).info.versionName,
        )
    }

    @Test
    fun `on a version tie the higher-priority source keeps the first download slot`() = runTest {
        stubGateAllowed()
        simulatedRealtimeMs = 10_000L
        stubPrefsSnapshot(UpdaterState(autoCheckEnabled = true))
        val forgeUrl = "https://codeberg.org/CruxCoach/CruxCoach/releases/download/v9.9.9/x.apk"
        val cdnUrl = "https://cdn.zapstore.dev/${"a".repeat(64)}"
        stubSources(
            forgeSource to ReleaseSource.Result.Success(release("9.9.9", forgeUrl)),
            nostrSource to ReleaseSource.Result.Success(release("9.9.9", cdnUrl)),
        )

        val outcome = checker().maybeCheck(UpdateChecker.Trigger.PERIODIC)

        assertEquals(
            forgeUrl,
            (outcome as UpdateChecker.CheckOutcome.Update).info.apkUrl,
        )
    }

    @Test
    fun `all sources reporting no update yields NoUpdate, not an error`() = runTest {
        stubGateAllowed()
        simulatedRealtimeMs = 10_000L
        stubPrefsSnapshot(UpdaterState(autoCheckEnabled = true))
        stubSources(
            forgeSource to ReleaseSource.Result.Success(release = null, etag = "e1"),
            nostrSource to ReleaseSource.Result.Success(release = null),
        )

        val outcome = checker().maybeCheck(UpdateChecker.Trigger.PERIODIC)

        assertTrue(outcome is UpdateChecker.CheckOutcome.NoUpdate)
        assertEquals(listOf("forge", "zapstore"), asked)
    }

    @Test
    fun `a failing source does not stop the sweep from finding an update`() = runTest {
        stubGateAllowed()
        simulatedRealtimeMs = 10_000L
        stubPrefsSnapshot(UpdaterState(autoCheckEnabled = true))
        stubSources(
            forgeSource to ReleaseSource.Result.Error("HTTP 503"),
            nostrSource to ReleaseSource.Result.Success(release = null),
            manifestSource to ReleaseSource.Result.Success(release("9.9.9")),
        )

        val outcome = checker().maybeCheck(UpdateChecker.Trigger.PERIODIC)

        assertTrue("expected an update, was $outcome", outcome is UpdateChecker.CheckOutcome.Update)
        assertEquals(listOf("forge", "zapstore", "website"), asked)
    }

    @Test
    fun `each source gets its own ETag, never another source's`() = runTest {
        stubGateAllowed()
        simulatedRealtimeMs = 10_000L
        val seenEtags = mutableMapOf<String, String?>()
        stubPrefsSnapshot(
            UpdaterState(
                autoCheckEnabled = true,
                lastCheckEtags = mapOf("forge" to "etag-forge", "zapstore" to "etag-zapstore"),
            ),
        )
        coEvery { registry.discoverySources() } returns listOf(forgeSource, nostrSource)
        coEvery { registry.downloadUrlsFor(any(), any(), any()) } returns listOf("https://x/a.apk")
        listOf(
            forgeSource to ReleaseSource.Result.Error("boom"),
            nostrSource to ReleaseSource.Result.Success(release("9.9.9")),
        ).forEach { (source, result) ->
            val rs: ReleaseSource = mockk(relaxed = true)
            every { rs.source } returns source
            every { rs.id } returns source.id
            coEvery { rs.fetchNewerThan(any(), any()) } coAnswers {
                seenEtags[source.id] = secondArg()
                result
            }
            every { sourceFactory.create(source) } returns rs
        }

        checker().maybeCheck(UpdateChecker.Trigger.PERIODIC)

        assertEquals("etag-forge", seenEtags["forge"])
        assertEquals("etag-zapstore", seenEtags["zapstore"])
    }

    @Test
    fun `a discovered release nobody can serve is treated as a source failure`() = runTest {
        stubGateAllowed()
        simulatedRealtimeMs = 10_000L
        stubPrefsSnapshot(UpdaterState(autoCheckEnabled = true))
        coEvery { registry.discoverySources() } returns listOf(forgeSource, nostrSource)
        // No configured source can produce a URL for these bytes.
        coEvery { registry.downloadUrlsFor(any(), any(), any()) } returns emptyList()
        listOf(
            forgeSource to ReleaseSource.Result.Success(release("9.9.9")),
            nostrSource to ReleaseSource.Result.Error("relay_unavailable"),
        ).forEach { (source, result) ->
            val rs: ReleaseSource = mockk(relaxed = true)
            every { rs.source } returns source
            every { rs.id } returns source.id
            coEvery { rs.fetchNewerThan(any(), any()) } coAnswers {
                asked += source.id
                result
            }
            every { sourceFactory.create(source) } returns rs
        }

        val outcome = checker().maybeCheck(UpdateChecker.Trigger.PERIODIC)

        // A newer version exists but nothing can serve it. Reporting NoUpdate
        // would hide a broken release, so this must surface as an error.
        assertEquals(
            "no_download_urls",
            (outcome as UpdateChecker.CheckOutcome.Error).message,
        )
        assertEquals(listOf("forge", "zapstore"), asked)
    }

    @Test
    fun `a throwing source is contained and the walk continues`() = runTest {
        stubGateAllowed()
        simulatedRealtimeMs = 10_000L
        stubPrefsSnapshot(UpdaterState(autoCheckEnabled = true))
        coEvery { registry.discoverySources() } returns listOf(forgeSource, nostrSource)
        coEvery { registry.downloadUrlsFor(any(), any(), any()) } returns listOf("https://x/a.apk")

        val throwing: ReleaseSource = mockk(relaxed = true)
        every { throwing.source } returns forgeSource
        every { throwing.id } returns forgeSource.id
        coEvery { throwing.fetchNewerThan(any(), any()) } throws IllegalStateException("kaboom")
        every { sourceFactory.create(forgeSource) } returns throwing

        val healthy: ReleaseSource = mockk(relaxed = true)
        every { healthy.source } returns nostrSource
        every { healthy.id } returns nostrSource.id
        coEvery { healthy.fetchNewerThan(any(), any()) } returns
            ReleaseSource.Result.Success(release("9.9.9"))
        every { sourceFactory.create(nostrSource) } returns healthy

        val outcome = checker().maybeCheck(UpdateChecker.Trigger.PERIODIC)

        assertTrue(
            "a source throwing must not abort the whole check, was $outcome",
            outcome is UpdateChecker.CheckOutcome.Update,
        )
    }

    @Test
    fun `a device past its last release stops before any network call`() = runTest {
        stubGateAllowed()
        simulatedRealtimeMs = 10_000L
        stubPrefsSnapshot(UpdaterState(autoCheckEnabled = true))
        stubSources(forgeSource to ReleaseSource.Result.Success(release("9.9.9")))

        val outcome = checker(
            deviceSupportGate = DeviceSupportGate(sdkInt = 27, minSdkNextRelease = 28),
        ).maybeCheck(UpdateChecker.Trigger.PERIODIC)

        assertEquals(
            UpdateChecker.REASON_END_OF_SUPPORT,
            (outcome as UpdateChecker.CheckOutcome.Skipped).reason,
        )
        assertEquals(
            "an update this device can never install must not be fetched, " +
                "let alone offered",
            emptyList<String>(),
            asked,
        )
    }

    @Test
    fun `a device on the boundary still checks normally`() = runTest {
        stubGateAllowed()
        simulatedRealtimeMs = 10_000L
        stubPrefsSnapshot(UpdaterState(autoCheckEnabled = true))
        stubSources(forgeSource to ReleaseSource.Result.Success(release("9.9.9")))

        val outcome = checker(
            deviceSupportGate = DeviceSupportGate(sdkInt = 28, minSdkNextRelease = 28),
        ).maybeCheck(UpdateChecker.Trigger.PERIODIC)

        assertTrue("expected a normal update, was $outcome", outcome is UpdateChecker.CheckOutcome.Update)
    }

    @Test
    fun `an empty source list errors instead of silently never checking`() = runTest {
        stubGateAllowed()
        simulatedRealtimeMs = 10_000L
        stubPrefsSnapshot(UpdaterState(autoCheckEnabled = true))
        coEvery { registry.discoverySources() } returns emptyList()

        val outcome = checker().maybeCheck(UpdateChecker.Trigger.PERIODIC)

        assertEquals(
            "no_discovery_sources",
            (outcome as UpdateChecker.CheckOutcome.Error).message,
        )
    }
}
