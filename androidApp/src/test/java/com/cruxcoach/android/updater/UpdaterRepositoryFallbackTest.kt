package com.cruxcoach.android.updater

import android.content.Context
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

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

    private val info = UpdateInfo(
        tagName = "v9.9.9",
        versionName = "9.9.9",
        version = SemVer.parseOrNull("9.9.9")!!,
        apkUrl = "https://codeberg.example/CruxCoach-v9.9.9.apk",
        apkFallbackUrl = "https://cdn.example/${"a".repeat(64)}",
        apkSha256Url = "https://codeberg.example/CruxCoach-v9.9.9.apk.sha256",
        apkSizeBytes = 1234L,
        apkSha256 = "a".repeat(64),
        releaseNotesMarkdown = "notes",
        releasePageUrl = "https://codeberg.example/releases/v9.9.9",
        publishedAtEpochSeconds = 1L,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { preferences.snapshot() } returns UpdaterState()
        coEvery { preferences.update(any()) } just Runs
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
        ioDispatcher = dispatcher,
    )

    @Test
    fun `enqueue failure falls through to the direct APK fallback`() = runTest {
        every { downloader.start(info, allowMobile = false, sourceIndex = 0) } returns
            ApkDownloader.StartResult.Error("Codeberg unavailable")
        every { downloader.start(info, allowMobile = false, sourceIndex = 1) } returns
            ApkDownloader.StartResult.Error("Zapstore unavailable")

        repository().startDownload(info, allowMobile = false)

        verify(exactly = 1) { downloader.start(info, allowMobile = false, sourceIndex = 0) }
        verify(exactly = 1) { downloader.start(info, allowMobile = false, sourceIndex = 1) }
        verify(exactly = 1) {
            notifier.showDownloadError(info, UpdateNotifier.DownloadError.GENERIC)
        }
    }

    @Test
    fun `repeated release check does not duplicate an active download`() = runTest {
        val active = UpdaterState(
            pipelineStage = PipelineStage.DOWNLOADING,
            automationMode = UpdateAutomationMode.AUTO_INSTALL,
            pendingTagName = info.tagName,
            pendingVersionName = info.versionName,
            pendingApkUrl = info.apkUrl,
            pendingApkFallbackUrl = info.apkFallbackUrl,
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
            pendingApkUrl = info.apkUrl,
            pendingApkFallbackUrl = info.apkFallbackUrl,
            pendingApkSha256 = info.apkSha256,
            pendingApkSizeBytes = info.apkSizeBytes,
            pendingApkSha256Url = info.apkSha256Url,
            pendingReleasePageUrl = info.releasePageUrl,
            pipelineStage = PipelineStage.PENDING_DOWNLOAD,
            automationMode = UpdateAutomationMode.AUTO_DOWNLOAD,
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
    fun `system download completion verifies an automatic background download`() = runTest {
        var state = UpdaterState(
            pendingDownloadId = 42L,
            pendingTagName = info.tagName,
            pendingVersionName = info.versionName,
            pendingApkUrl = info.apkUrl,
            pendingApkFallbackUrl = info.apkFallbackUrl,
            pendingApkSha256 = info.apkSha256,
            pendingApkSizeBytes = info.apkSizeBytes,
            pendingApkSha256Url = info.apkSha256Url,
            pendingReleasePageUrl = info.releasePageUrl,
            pendingReleaseNotesMarkdown = info.releaseNotesMarkdown,
            pendingDownloadSourceIndex = 0,
            pendingAllowMobile = true,
            pipelineStage = PipelineStage.DOWNLOADING,
            automationMode = UpdateAutomationMode.AUTO_DOWNLOAD,
        )
        coEvery { preferences.snapshot() } answers { state }
        coEvery { preferences.update(any()) } coAnswers {
            state = firstArg<(UpdaterState) -> UpdaterState>().invoke(state)
        }
        val apk = File("build/tmp/pending-update-${info.versionName}.apk")
        every { downloader.query(42L) } returns ApkDownloader.Status(
            state = ApkDownloader.State.SUCCESSFUL,
            totalBytes = info.apkSizeBytes,
            bytesSoFar = info.apkSizeBytes,
            reason = 0,
            localUri = apk.toURI().toString(),
        )
        every { downloader.targetFileFor(info.versionName) } returns apk
        every { verifier.verify(apk, info.apkSha256) } returns IntegrityVerifier.Result.Ok
        var completed = false

        repository().onDownloadManagerCompleted(42L) { completed = true }

        assertEquals(true, completed)
        assertEquals(PipelineStage.READY_TO_INSTALL, state.pipelineStage)
        verify(exactly = 1) { verifier.verify(apk, info.apkSha256) }
        verify(exactly = 1) {
            notifier.showReadyToInstall(match { it.tagName == info.tagName })
        }
        verify(exactly = 0) { installer.install(any(), any()) }
    }

    @Test
    fun `successful self update clears discovery throttle and etag`() = runTest {
        var state = UpdaterState(
            lastCheckBootRealtime = 123L,
            lastCheckEtag = "latest-etag",
            pendingTagName = info.tagName,
            pendingVersionName = info.versionName,
            pendingApkUrl = info.apkUrl,
            pendingApkFallbackUrl = info.apkFallbackUrl,
            pendingApkSha256 = info.apkSha256,
            pendingApkSizeBytes = info.apkSizeBytes,
            pendingApkSha256Url = info.apkSha256Url,
            pendingReleasePageUrl = info.releasePageUrl,
            pipelineStage = PipelineStage.INSTALLING,
            automationMode = UpdateAutomationMode.AUTO_INSTALL,
        )
        coEvery { preferences.snapshot() } answers { state }
        coEvery { preferences.update(any()) } coAnswers {
            state = firstArg<(UpdaterState) -> UpdaterState>().invoke(state)
        }
        var completed = false

        repository().onInstallOutcome(InstallOutcome.Success) { completed = true }

        assertEquals(true, completed)
        assertEquals(null, state.lastCheckEtag)
        assertEquals(0L, state.lastCheckBootRealtime)
        assertEquals(UpdateAutomationMode.AUTO_INSTALL, state.automationMode)
        assertEquals(PipelineStage.NONE, state.pipelineStage)
    }
}
