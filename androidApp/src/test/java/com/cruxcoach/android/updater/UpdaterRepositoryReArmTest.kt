package com.cruxcoach.android.updater

import android.content.Context
import io.mockk.Runs
import io.mockk.coEvery
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
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Verifies the §6.10 notification re-arm ([UpdaterRepository.maybeReArmPendingNotification]):
 * a pending update the user swiped away is re-surfaced once its escalating
 * backoff has elapsed, and stays quiet otherwise. Runs on every trigger via
 * [UpdaterRepository.checkNow], so an ETag-304 NotModified check can no longer
 * strand a dismissed update forever.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdaterRepositoryReArmTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val context: Context = mockk(relaxed = true)
    private val preferences: UpdaterPreferences = mockk(relaxed = true)
    private val checker: UpdateChecker = mockk(relaxed = true)
    private val downloader: ApkDownloader = mockk(relaxed = true)
    private val verifier: IntegrityVerifier = mockk(relaxed = true)
    private val installer: ApkInstaller = mockk(relaxed = true)
    private val notifier: UpdateNotifier = mockk(relaxed = true)
    private val installSourceGate: InstallSourceGate = mockk(relaxed = true)
    private val registry: UpdateSourceRegistry = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun repository(): UpdaterRepository = UpdaterRepository(
        context = context,
        preferences = preferences,
        checker = checker,
        downloader = downloader,
        verifier = verifier,
        installer = installer,
        notifier = notifier,
        installSourceGate = installSourceGate,
        registry = registry,
        ioDispatcher = dispatcher,
    )

    private fun pendingState(
        dismissedAgoMs: Long?,
        dismissCount: Int = 1,
        stage: PipelineStage = PipelineStage.PENDING_DOWNLOAD,
    ) = UpdaterState(
        pipelineStage = stage,
        pendingTagName = "v0.2.0",
        pendingVersionName = "0.2.0",
        pendingDownloadUrls = listOf("https://example/CruxCoach-v0.2.0.apk"),
        pendingApkSha256 = "a".repeat(64),
        pendingApkSizeBytes = 1000L,
        pendingApkSha256Url = "https://example/CruxCoach-v0.2.0.apk.sha256",
        pendingReleasePageUrl = "https://example/releases/tag/v0.2.0",
        notifDismissedAtEpochMs = dismissedAgoMs?.let { System.currentTimeMillis() - it },
        notifReArmCount = dismissCount,
    )

    @Test
    fun `re-posts a dismissed pending update once the backoff elapses`() = runTest {
        // dismissCount=1 → 24h backoff; dismissed 25h ago → due.
        coEvery { preferences.snapshot() } returns pendingState(dismissedAgoMs = TimeUnit.HOURS.toMillis(25))
        coEvery { preferences.update(any()) } just Runs

        repository().maybeReArmPendingNotification()

        verify(exactly = 1) { notifier.showPendingDownload(any()) }
    }

    @Test
    fun `does not re-post before the backoff elapses`() = runTest {
        coEvery { preferences.snapshot() } returns pendingState(dismissedAgoMs = TimeUnit.HOURS.toMillis(1))

        repository().maybeReArmPendingNotification()

        verify(exactly = 0) { notifier.showPendingDownload(any()) }
    }

    @Test
    fun `does not re-post an undismissed (still-showing) notification`() = runTest {
        coEvery { preferences.snapshot() } returns pendingState(dismissedAgoMs = null)

        repository().maybeReArmPendingNotification()

        verify(exactly = 0) { notifier.showPendingDownload(any()) }
    }

    @Test
    fun `does not re-post when nothing is pending`() = runTest {
        coEvery { preferences.snapshot() } returns UpdaterState(pipelineStage = PipelineStage.NONE)

        repository().maybeReArmPendingNotification()

        verify(exactly = 0) { notifier.showPendingDownload(any()) }
    }

    @Test
    fun `escalates backoff — a heavily-dismissed update waits far longer`() = runTest {
        // dismissCount=12 → 30d backoff; dismissed 3 days ago → NOT yet due.
        coEvery { preferences.snapshot() } returns
            pendingState(dismissedAgoMs = TimeUnit.DAYS.toMillis(3), dismissCount = 12)

        repository().maybeReArmPendingNotification()

        verify(exactly = 0) { notifier.showPendingDownload(any()) }
    }
}
