package com.cruxcoach.android.updater

import android.content.Context
import com.cruxcoach.android.BuildConfig
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
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

/**
 * A pending update that is not newer than the running app must be thrown away,
 * not acted on.
 *
 * Found on a Nokia 6.1 on 2026-08-06. The device ran 0.2.2 with 0.2.1 still
 * recorded as pending, and every trigger produced this:
 *
 *     event=check_no_update trigger=MANUAL answered=3 failed=0
 *     event=download_complete version=0.2.1 bytes=34558390
 *     event=integrity_ok version=0.2.1 — ready to install
 *     Automatic install session committed
 *     INSTALL_FAILED_VERSION_DOWNGRADE: version code 7 is older than current 8
 *
 * 34.5 MB downloaded, verified, and handed to an install Android was always
 * going to refuse — in the same second the check correctly reported no update.
 * Three copies of the APK had piled up on the device.
 *
 * The cause was structural rather than a slip: the pending block was written
 * when a check found a release and unwritten nowhere, so it had no relationship
 * to what was actually installed. Nothing in the pipeline compared the two.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdaterRepositoryStalePendingTest {

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
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

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
        ioDispatcher = dispatcher,
    )

    /** Pending state for [version], in the stage that makes the resume path fire. */
    private fun pending(version: String) = UpdaterState(
        pipelineStage = PipelineStage.PENDING_DOWNLOAD,
        automationMode = UpdateAutomationMode.AUTO_DOWNLOAD,
        pendingDownloadId = 4711L,
        pendingTagName = "v$version",
        pendingVersionName = version,
        pendingDownloadUrls = listOf("https://example/CruxCoach-v$version.apk"),
        pendingApkSha256 = "a".repeat(64),
        pendingApkSizeBytes = 1000L,
        pendingApkSha256Url = "https://example/CruxCoach-v$version.apk.sha256",
        pendingReleasePageUrl = "https://example/releases/tag/v$version",
    )

    /** One below whatever this build is, so the test tracks VERSION_NAME. */
    private fun olderThanInstalled(): String {
        val installed = SemVer.parseInstalledOrNull(BuildConfig.VERSION_NAME)!!
        return if (installed.patch > 0) {
            "${installed.major}.${installed.minor}.${installed.patch - 1}"
        } else {
            "${installed.major}.${maxOf(0, installed.minor - 1)}.0"
        }
    }

    @Test
    fun `an older pending update is discarded instead of downloaded`() = runTest {
        coEvery { preferences.snapshot() } returns pending(olderThanInstalled())
        coEvery { preferences.update(any()) } just Runs
        coEvery { checker.maybeCheck(any()) } returns UpdateChecker.CheckOutcome.NoUpdate

        repository().checkNow(UpdateChecker.Trigger.MANUAL)

        // The 34.5 MB that used to be spent here, on every single trigger.
        verify(exactly = 0) { downloader.start(any(), any(), any()) }
        verify(exactly = 0) { installer.install(any(), any()) }
        // And the files DownloadManager had already left behind.
        verify(exactly = 1) { downloader.deleteDownloadedApks() }
        // The orphaned DownloadManager job goes with it, or it outlives the
        // state that named it.
        verify(exactly = 1) { downloader.cancel(4711L) }
    }

    @Test
    fun `discarding clears the state so it cannot repeat next trigger`() = runTest {
        val transform = slot<(UpdaterState) -> UpdaterState>()
        val stale = pending(olderThanInstalled())
        coEvery { preferences.snapshot() } returns stale
        coEvery { preferences.update(capture(transform)) } just Runs
        coEvery { checker.maybeCheck(any()) } returns UpdateChecker.CheckOutcome.NoUpdate

        repository().checkNow(UpdateChecker.Trigger.MANUAL)

        // Skipping would have been enough to stop the download once — and would
        // have replayed the whole sequence on the next trigger, forever.
        val cleared = transform.captured(stale)
        assertNull(cleared.pendingTagName)
        assertNull(cleared.pendingVersionName)
        assertNull(cleared.pendingApkSha256)
        assertNull(cleared.pendingDownloadId)
        assertEquals(emptyList<String>(), cleared.pendingDownloadUrls)
        assertEquals(PipelineStage.NONE, cleared.pipelineStage)
        assertNull(cleared.pendingUpdate())
    }

    @Test
    fun `a pending update equal to the installed version is discarded too`() = runTest {
        // The ordinary end of a successful update: the block still describes
        // the version now running. Not newer means nothing left to do.
        val installed = SemVer.parseInstalledOrNull(BuildConfig.VERSION_NAME)!!
        coEvery { preferences.snapshot() } returns pending(installed.toString())
        coEvery { preferences.update(any()) } just Runs
        coEvery { checker.maybeCheck(any()) } returns UpdateChecker.CheckOutcome.NoUpdate

        repository().checkNow(UpdateChecker.Trigger.MANUAL)

        verify(exactly = 0) { downloader.start(any(), any(), any()) }
        verify(exactly = 1) { downloader.deleteDownloadedApks() }
    }

    @Test
    fun `a genuinely newer pending update is still acted on`() = runTest {
        // The guard must not cost the feature it protects.
        val installed = SemVer.parseInstalledOrNull(BuildConfig.VERSION_NAME)!!
        val newer = "${installed.major}.${installed.minor}.${installed.patch + 1}"
        coEvery { preferences.snapshot() } returns pending(newer)
        coEvery { preferences.update(any()) } just Runs
        coEvery { checker.maybeCheck(any()) } returns UpdateChecker.CheckOutcome.NoUpdate
        // Stop at the transport gate. This test is about the guard NOT firing;
        // letting it run on into the download would exercise the progress
        // monitor, whose `while (true)` reads a relaxed mock that never
        // reports a terminal state — mockk records every one of those calls
        // and the JVM runs out of heap, taking the rest of the class with it.
        // Measured: 15.4 s here versus 0.56 s for the guard-only tests.
        io.mockk.every { downloader.currentTransport() } returns ApkDownloader.Transport.OFFLINE

        repository().checkNow(UpdateChecker.Trigger.MANUAL)

        // Only the discard must not happen. `cancel` deliberately says nothing
        // here: this path also cancels an orphaned DownloadManager job before
        // starting a new one, which is pre-existing and correct.
        verify(exactly = 0) { downloader.deleteDownloadedApks() }
    }
}
