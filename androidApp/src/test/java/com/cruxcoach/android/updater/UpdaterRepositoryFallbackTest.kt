package com.cruxcoach.android.updater

import android.content.Context
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdaterRepositoryFallbackTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val context: Context = mockk(relaxed = true)
    private val preferences: UpdaterPreferences = mockk(relaxed = true)
    private val checker: UpdateChecker = mockk(relaxed = true)
    private val downloader: ApkDownloader = mockk(relaxed = true)
    private val verifier: IntegrityVerifier = mockk(relaxed = true)
    private val installer: ApkInstaller = mockk(relaxed = true)
    private val notifier: UpdateNotifier = mockk(relaxed = true)
    private val installSourceGate: InstallSourceGate = mockk(relaxed = true)
    private val verifiedUpdateMetrics: VerifiedUpdateMetrics = mockk(relaxed = true)
    private val registry: UpdateSourceRegistry = mockk(relaxed = true)

    private val info = UpdateInfo(
        tagName = "v9.9.9",
        versionName = "9.9.9",
        version = SemVer.parseOrNull("9.9.9")!!,
        downloadUrls = listOf(
            "https://codeberg.org/CruxCoach/CruxCoach/releases/download/" +
                "v9.9.9/CruxCoach-v9.9.9.apk",
            "https://cdn.zapstore.dev/${"a".repeat(64)}",
            "https://blossom.primal.net/${"a".repeat(64)}",
        ),
        apkSha256Url = "https://codeberg.org/CruxCoach/CruxCoach/releases/download/" +
            "v9.9.9/CruxCoach-v9.9.9.apk.sha256",
        apkSizeBytes = 1234L,
        apkSha256 = "a".repeat(64),
        releaseNotesMarkdown = "notes",
        releasePageUrl = "https://codeberg.org/CruxCoach/CruxCoach/releases/tag/v9.9.9",
        publishedAtEpochSeconds = 1L,
    )

    /**
     * The source list the fixture's [info] URLs belong to, in the same order.
     * Resolution goes through the real [resolveSourceId] rather than a
     * hardcoded label, so these tests break if the URL→source mapping ever
     * stops agreeing with the configured list.
     */
    private val testSources = listOf(
        UpdateSource(
            id = "codeberg",
            kind = UpdateSource.Kind.FORGE,
            url = "https://codeberg.org/api/v1",
            owner = "CruxCoach",
            repo = "CruxCoach",
        ),
        UpdateSource(
            id = "zapstore",
            kind = UpdateSource.Kind.NOSTR,
            url = "wss://relay.zapstore.dev",
            cdn = "https://cdn.zapstore.dev",
        ),
        UpdateSource(
            id = "blossom",
            kind = UpdateSource.Kind.BLOSSOM,
            url = "https://blossom.primal.net",
        ),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { preferences.snapshot() } returns UpdaterState()
        coEvery { preferences.update(any()) } just Runs
        every { verifiedUpdateMetrics.isConfigured } returns true
        coEvery { registry.sourceIdForUrl(any()) } coAnswers {
            resolveSourceId(firstArg(), testSources)
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun repository() = UpdaterRepository(
        context = context,
        preferences = preferences,
        checker = checker,
        downloader = downloader,
        verifier = verifier,
        installer = installer,
        notifier = notifier,
        installSourceGate = installSourceGate,
        registry = registry,
        verifiedUpdateMetrics = verifiedUpdateMetrics,
        ioDispatcher = dispatcher,
    )

    private class StateHolder(var value: UpdaterState)

    private fun persist(initial: UpdaterState): StateHolder {
        val holder = StateHolder(initial)
        coEvery { preferences.snapshot() } answers { holder.value }
        coEvery { preferences.update(any()) } coAnswers {
            holder.value = firstArg<(UpdaterState) -> UpdaterState>().invoke(holder.value)
        }
        return holder
    }

    private fun downloadingState(
        id: Long = 42L,
        sourceIndex: Int = 0,
        metricsEnabled: Boolean = true,
        lastMetricsAttemptVersion: String? = null,
    ) = UpdaterState(
        pendingDownloadId = id,
        pendingTagName = info.tagName,
        pendingVersionName = info.versionName,
        pendingDownloadUrls = info.downloadUrls,
        pendingApkSha256 = info.apkSha256,
        pendingApkSizeBytes = info.apkSizeBytes,
        pendingApkSha256Url = info.apkSha256Url,
        pendingReleasePageUrl = info.releasePageUrl,
        pendingReleaseNotesMarkdown = info.releaseNotesMarkdown,
        pendingDownloadSourceIndex = sourceIndex,
        pendingAllowMobile = true,
        pipelineStage = PipelineStage.DOWNLOADING,
        automationMode = UpdateAutomationMode.AUTO_UPDATE,
        anonymousUpdateMetricsEnabled = metricsEnabled,
        lastAnonymousMetricsAttemptVersion = lastMetricsAttemptVersion,
    )

    private fun status(apk: File) = ApkDownloader.Status(
        state = ApkDownloader.State.SUCCESSFUL,
        totalBytes = info.apkSizeBytes,
        bytesSoFar = info.apkSizeBytes,
        reason = 0,
        localUri = apk.toURI().toString(),
    )

    @Test
    fun `enqueue failure walks every configured source before giving up`() = runTest {
        // Three sources in the fixture — the walk must exhaust all of them,
        // not stop after the historical two.
        info.downloadUrls.indices.forEach { index ->
            every { downloader.start(info, allowMobile = false, sourceIndex = index) } returns
                ApkDownloader.StartResult.Error("source $index unavailable")
        }

        repository().startDownload(info, allowMobile = false)

        info.downloadUrls.indices.forEach { index ->
            verify(exactly = 1) {
                downloader.start(info, allowMobile = false, sourceIndex = index)
            }
        }
        verify(exactly = 1) {
            notifier.showDownloadError(info, UpdateNotifier.DownloadError.GENERIC)
        }
    }

    @Test
    fun `the walk stops at the first source that enqueues`() = runTest {
        every { downloader.start(info, allowMobile = false, sourceIndex = 0) } returns
            ApkDownloader.StartResult.Error("primary unavailable")
        every { downloader.start(info, allowMobile = false, sourceIndex = 1) } returns
            ApkDownloader.StartResult.Enqueued(
                id = 77L,
                target = File("build/tmp/pending-update-${info.versionName}.apk"),
            )
        // A successful enqueue hands off to monitorDownload, whose poll loop
        // only exits on a null query, SUCCESSFUL, or FAILED. Leaving query()
        // to the relaxed mock returns State.PENDING (the first enum constant)
        // forever, so the loop spins on virtual time while mockk records every
        // call — which exhausts the heap rather than failing the assertion.
        // Returning null ends the monitor immediately; this test is about
        // where the source walk stops, not about download progress.
        every { downloader.query(77L) } returns null

        repository().startDownload(info, allowMobile = false)

        verify(exactly = 1) { downloader.start(info, allowMobile = false, sourceIndex = 1) }
        verify(exactly = 0) { downloader.start(info, allowMobile = false, sourceIndex = 2) }
        verify(exactly = 0) {
            notifier.showDownloadError(info, UpdateNotifier.DownloadError.GENERIC)
        }
    }

    @Test
    fun `repeated release check does not duplicate an active download`() = runTest {
        val active = UpdaterState(
            pipelineStage = PipelineStage.DOWNLOADING,
            automationMode = UpdateAutomationMode.AUTO_UPDATE,
            pendingTagName = info.tagName,
            pendingVersionName = info.versionName,
            pendingDownloadUrls = info.downloadUrls,
            pendingApkSha256 = info.apkSha256,
            pendingApkSizeBytes = info.apkSizeBytes,
            pendingApkSha256Url = info.apkSha256Url,
            pendingReleasePageUrl = info.releasePageUrl,
        )
        coEvery { preferences.snapshot() } returns active
        coEvery { checker.maybeCheck(UpdateChecker.Trigger.MANUAL) } returns
            UpdateChecker.CheckOutcome.Update(info)

        repository().checkNow(UpdateChecker.Trigger.MANUAL)

        verify(exactly = 0) { downloader.start(any(), any(), any()) }
        verify(exactly = 0) { downloader.currentTransport() }
    }

    @Test
    fun `automatic update behavior is opt in`() {
        assertEquals(UpdateAutomationMode.NOTIFY, UpdaterState().automationMode)
    }

    @Test
    fun `cached automatic update starts even when discovery is throttled`() = runTest {
        val pending = UpdaterState(
            pendingTagName = info.tagName,
            pendingVersionName = info.versionName,
            pendingDownloadUrls = info.downloadUrls,
            pendingApkSha256 = info.apkSha256,
            pendingApkSizeBytes = info.apkSizeBytes,
            pendingApkSha256Url = info.apkSha256Url,
            pendingReleasePageUrl = info.releasePageUrl,
            pipelineStage = PipelineStage.PENDING_DOWNLOAD,
            automationMode = UpdateAutomationMode.AUTO_UPDATE,
        )
        coEvery { preferences.snapshot() } returns pending
        coEvery { checker.maybeCheck(UpdateChecker.Trigger.NETWORK_AVAILABLE) } returns
            UpdateChecker.CheckOutcome.Throttled(1_000L)
        every { downloader.currentTransport() } returns ApkDownloader.Transport.WIFI
        val cachedInfo = pending.pendingUpdate()!!
        every { downloader.start(cachedInfo, allowMobile = false, sourceIndex = 0) } returns
            ApkDownloader.StartResult.InsufficientStorage(2_000L, 1_000L)

        repository().checkNow(UpdateChecker.Trigger.NETWORK_AVAILABLE)

        verify(exactly = 1) {
            downloader.start(cachedInfo, allowMobile = false, sourceIndex = 0)
        }
    }

    @Test
    fun `duplicate system completion dispatches at most one verified update count`() = runTest {
        val state = persist(downloadingState())
        val apk = File("build/tmp/pending-update-${info.versionName}.apk")
        every { downloader.query(42L) } returns status(apk)
        every { downloader.targetFileFor(info.versionName) } returns apk
        every { verifier.verify(apk, info.apkSha256) } returns IntegrityVerifier.Result.Ok
        var completed = false
        var duplicateCompleted = false
        val repository = repository()

        repository.onDownloadManagerCompleted(42L) { completed = true }
        repository.onDownloadManagerCompleted(42L) { duplicateCompleted = true }

        assertEquals(true, completed)
        assertEquals(true, duplicateCompleted)
        assertEquals(PipelineStage.READY_TO_INSTALL, state.value.pipelineStage)
        assertEquals(info.versionName, state.value.lastAnonymousMetricsAttemptVersion)
        verify(exactly = 1) { verifier.verify(apk, info.apkSha256) }
        verify(exactly = 1) {
            verifiedUpdateMetrics.recordVerifiedUpdate(info.versionName, "codeberg")
        }
        verify(exactly = 1) {
            notifier.showReadyToInstall(match { it.tagName == info.tagName })
        }
        verify(exactly = 0) { installer.install(any(), any()) }
    }

    @Test
    fun `automatic update requests a user-confirmed install after verification`() = runTest {
        val state = persist(downloadingState())
        val apk = File("build/tmp/pending-update-${info.versionName}.apk")
        every { downloader.query(42L) } returns status(apk)
        every { downloader.targetFileFor(info.versionName) } returns apk
        every { verifier.verify(apk, info.apkSha256) } returns IntegrityVerifier.Result.Ok
        every { installer.canRequestPackageInstalls() } returns true
        every { installer.install(apk, deferUserConfirmation = true) } returns
            ApkInstaller.InstallResult.Committed(77)

        repository().onDownloadManagerCompleted(42L)

        assertEquals(PipelineStage.INSTALLING, state.value.pipelineStage)
        verify(exactly = 1) { installer.install(apk, deferUserConfirmation = true) }
        verify(exactly = 0) { notifier.showReadyToInstall(any()) }
    }

    @Test
    fun `opted out verified download never dispatches a count`() = runTest {
        val state = persist(downloadingState(metricsEnabled = false))
        val apk = File("build/tmp/pending-update-${info.versionName}.apk")
        every { downloader.query(42L) } returns status(apk)
        every { downloader.targetFileFor(info.versionName) } returns apk
        every { verifier.verify(apk, info.apkSha256) } returns IntegrityVerifier.Result.Ok

        repository().onDownloadManagerCompleted(42L)

        assertEquals(PipelineStage.READY_TO_INSTALL, state.value.pipelineStage)
        assertNull(state.value.lastAnonymousMetricsAttemptVersion)
        verify(exactly = 0) { verifiedUpdateMetrics.recordVerifiedUpdate(any(), any()) }
        verify(exactly = 1) { notifier.showReadyToInstall(any()) }
    }

    @Test
    fun `persisted attempt suppresses another count for the same target version`() = runTest {
        val state = persist(
            downloadingState(lastMetricsAttemptVersion = info.versionName),
        )
        val apk = File("build/tmp/pending-update-${info.versionName}.apk")
        every { downloader.query(42L) } returns status(apk)
        every { downloader.targetFileFor(info.versionName) } returns apk
        every { verifier.verify(apk, info.apkSha256) } returns IntegrityVerifier.Result.Ok

        repository().onDownloadManagerCompleted(42L)

        assertEquals(PipelineStage.READY_TO_INSTALL, state.value.pipelineStage)
        assertEquals(info.versionName, state.value.lastAnonymousMetricsAttemptVersion)
        verify(exactly = 0) { verifiedUpdateMetrics.recordVerifiedUpdate(any(), any()) }
        verify(exactly = 1) { notifier.showReadyToInstall(any()) }
    }

    @Test
    fun `metrics failure cannot block verified update readiness`() = runTest {
        val state = persist(downloadingState())
        val apk = File("build/tmp/pending-update-${info.versionName}.apk")
        every { downloader.query(42L) } returns status(apk)
        every { downloader.targetFileFor(info.versionName) } returns apk
        every { verifier.verify(apk, info.apkSha256) } returns IntegrityVerifier.Result.Ok
        every {
            verifiedUpdateMetrics.recordVerifiedUpdate(info.versionName, "codeberg")
        } throws IllegalStateException("test-only failure")

        repository().onDownloadManagerCompleted(42L)

        assertEquals(PipelineStage.READY_TO_INSTALL, state.value.pipelineStage)
        assertEquals(info.versionName, state.value.lastAnonymousMetricsAttemptVersion)
        verify(exactly = 1) { notifier.showReadyToInstall(any()) }
        verify(exactly = 0) { installer.install(any(), any()) }
    }

    @Test
    fun `verified fallback records zapstore rather than failed primary source`() = runTest {
        val state = persist(downloadingState())
        val apk = File("build/tmp/pending-update-${info.versionName}.apk")
        every { downloader.query(42L) } returns status(apk)
        every { downloader.query(43L) } returns status(apk)
        every { downloader.targetFileFor(info.versionName) } returns apk
        every { verifier.verify(apk, info.apkSha256) } returnsMany listOf(
            IntegrityVerifier.Result.PayloadMismatch,
            IntegrityVerifier.Result.Ok,
        )
        every {
            downloader.start(
                match { it.versionName == info.versionName },
                allowMobile = true,
                sourceIndex = 1,
            )
        } returns ApkDownloader.StartResult.Enqueued(43L, apk)

        repository().onDownloadManagerCompleted(42L)

        assertEquals(PipelineStage.READY_TO_INSTALL, state.value.pipelineStage)
        assertEquals(1, state.value.pendingDownloadSourceIndex)
        assertEquals(info.versionName, state.value.lastAnonymousMetricsAttemptVersion)
        verify(exactly = 2) { verifier.verify(apk, info.apkSha256) }
        verify(exactly = 1) {
            verifiedUpdateMetrics.recordVerifiedUpdate(info.versionName, "zapstore")
        }
        verify(exactly = 0) {
            verifiedUpdateMetrics.recordVerifiedUpdate(info.versionName, "codeberg")
        }
        verify(exactly = 1) { notifier.showReadyToInstall(any()) }
    }

    @Test
    fun `successful self update clears discovery throttle and etag`() = runTest {
        var state = UpdaterState(
            lastCheckBootRealtime = 123L,
            lastCheckEtags = mapOf("forge" to "latest-etag"),
            pendingTagName = info.tagName,
            pendingVersionName = info.versionName,
            pendingDownloadUrls = info.downloadUrls,
            pendingApkSha256 = info.apkSha256,
            pendingApkSizeBytes = info.apkSizeBytes,
            pendingApkSha256Url = info.apkSha256Url,
            pendingReleasePageUrl = info.releasePageUrl,
            pipelineStage = PipelineStage.INSTALLING,
            automationMode = UpdateAutomationMode.AUTO_UPDATE,
            anonymousUpdateMetricsEnabled = false,
            lastAnonymousMetricsAttemptVersion = info.versionName,
        )
        coEvery { preferences.snapshot() } answers { state }
        coEvery { preferences.update(any()) } coAnswers {
            state = firstArg<(UpdaterState) -> UpdaterState>().invoke(state)
        }
        var completed = false

        repository().onInstallOutcome(InstallOutcome.Success) { completed = true }

        assertEquals(true, completed)
        assertEquals(emptyMap<String, String>(), state.lastCheckEtags)
        assertEquals(0L, state.lastCheckBootRealtime)
        assertEquals(UpdateAutomationMode.AUTO_UPDATE, state.automationMode)
        assertEquals(false, state.anonymousUpdateMetricsEnabled)
        assertEquals(info.versionName, state.lastAnonymousMetricsAttemptVersion)
        assertEquals(PipelineStage.NONE, state.pipelineStage)
    }
}
