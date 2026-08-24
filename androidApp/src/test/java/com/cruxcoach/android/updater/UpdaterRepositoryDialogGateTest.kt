package com.cruxcoach.android.updater

import android.content.Context
import android.content.Intent
import com.cruxcoach.android.BuildConfig
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards the state-gate added to [UpdaterRepository.requestDownloadDialog]
 * (open-source-readiness / security-posture #13, 2026-04-22): the dialog
 * signal must only fire when the pipeline is actually in
 * [PipelineStage.PENDING_DOWNLOAD], so an external app that spoofs the
 * `updater_show_download_dialog` intent extra against our exported
 * MainActivity cannot force a download confirmation prompt at will.
 *
 * Everything the repository touches other than [UpdaterPreferences] is
 * mocked (relaxed) — we care only about the snapshot-based gate and the
 * resulting value of [UpdaterRepository.downloadDialogRequested].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
class UpdaterRepositoryDialogGateTest {

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
        // Inject the test dispatcher so requestDownloadDialog's internal
        // `scope.launch { ... }` completes synchronously before the
        // following assertion reads `downloadDialogRequested.value`.
        ioDispatcher = dispatcher,
    )

    private fun stubStage(stage: PipelineStage) {
        coEvery { preferences.snapshot() } returns UpdaterState(pipelineStage = stage)
    }

    @Test
    fun `sets dialog signal when pipeline is PENDING_DOWNLOAD`() = runTest {
        stubStage(PipelineStage.PENDING_DOWNLOAD)
        val repo = repository()

        repo.requestDownloadDialog()

        assertTrue(
            "legitimate notification-tap must still open the download dialog",
            repo.downloadDialogRequested.value,
        )
    }

    @Test
    fun `ignores request when pipeline is NONE`() = runTest {
        stubStage(PipelineStage.NONE)
        val repo = repository()

        repo.requestDownloadDialog()

        assertFalse(
            "external-app intent spoof must not open the dialog when nothing is pending",
            repo.downloadDialogRequested.value,
        )
    }

    @Test
    fun `ignores request when pipeline is DOWNLOADING`() = runTest {
        stubStage(PipelineStage.DOWNLOADING)
        val repo = repository()

        repo.requestDownloadDialog()

        assertFalse(repo.downloadDialogRequested.value)
    }

    @Test
    fun `ignores request when pipeline is READY_TO_INSTALL`() = runTest {
        stubStage(PipelineStage.READY_TO_INSTALL)
        val repo = repository()

        repo.requestDownloadDialog()

        assertFalse(repo.downloadDialogRequested.value)
    }

    @Test
    fun `ignores request when pipeline is BLOCKED_CERT_MISMATCH`() = runTest {
        stubStage(PipelineStage.BLOCKED_CERT_MISMATCH)
        val repo = repository()

        repo.requestDownloadDialog()

        assertFalse(repo.downloadDialogRequested.value)
    }

    @Test
    fun `consumeDownloadDialogRequest resets the signal`() = runTest {
        stubStage(PipelineStage.PENDING_DOWNLOAD)
        val repo = repository()

        repo.requestDownloadDialog()
        assertTrue(repo.downloadDialogRequested.value)

        repo.consumeDownloadDialogRequest()
        assertFalse(repo.downloadDialogRequested.value)
    }

    @Test
    fun `legitimate-then-spoofed sequence keeps the signal off after consume`() = runTest {
        // First: legitimate notif-tap while PENDING_DOWNLOAD → dialog opens, user
        // dismisses it, UI consumes the signal.
        stubStage(PipelineStage.PENDING_DOWNLOAD)
        val repo = repository()
        repo.requestDownloadDialog()
        repo.consumeDownloadDialogRequest()
        assertFalse(repo.downloadDialogRequested.value)

        // Next: pipeline has progressed — the install is already done and the
        // stage is NONE. A spoofed external-app intent at this point must not
        // re-open the dialog.
        stubStage(PipelineStage.NONE)
        repo.requestDownloadDialog()

        assertFalse(
            "spoofed post-install intent must not re-open the dialog",
            repo.downloadDialogRequested.value,
        )
    }

    @Test
    fun `covers every PipelineStage value to catch new stages added in future`() = runTest {
        // Belt-and-braces: if a new PipelineStage is added, it will appear here
        // and force the author to decide whether it's dialog-worthy.
        val dialogStages = setOf(PipelineStage.PENDING_DOWNLOAD)

        for (stage in PipelineStage.entries) {
            stubStage(stage)
            val repo = repository()
            repo.requestDownloadDialog()

            val expected = stage in dialogStages
            assertEquals(
                "PipelineStage.$stage → expected dialog=$expected",
                expected,
                repo.downloadDialogRequested.value,
            )
        }
    }

    @Test
    fun `release-page handoff ignores network supplied URL`() {
        val launched = slot<Intent>()
        every { context.startActivity(capture(launched)) } returns Unit
        val hostile = UpdateInfo(
            tagName = "v9.9.9",
            versionName = "9.9.9",
            version = SemVer.parseOrNull("v9.9.9")!!,
            downloadUrls = listOf("https://downloads.example/app.apk"),
            apkSha256Url = "https://downloads.example/app.apk.sha256",
            apkSizeBytes = 42,
            apkSha256 = "a".repeat(64),
            releaseNotesMarkdown = "",
            releasePageUrl = "https://attacker.invalid/release",
            publishedAtEpochSeconds = 0,
        )

        repository().openReleasePage(hostile)

        verify(exactly = 1) { context.startActivity(any()) }
        assertEquals(Intent.ACTION_VIEW, launched.captured.action)
        assertEquals(BuildConfig.UPDATER_RELEASE_PAGE_URL, launched.captured.dataString)
        assertTrue(launched.captured.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }
}
