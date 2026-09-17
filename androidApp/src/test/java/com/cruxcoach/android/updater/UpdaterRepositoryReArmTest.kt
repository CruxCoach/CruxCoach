package com.cruxcoach.android.updater

import android.content.Context
import io.mockk.every
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
        every { notifier.showPendingDownload(any()) } returns true
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
    fun `later reminders remain due after seventy two hours`() = runTest {
        // No monthly tier: even dismissal 12 is due after 72 hours.
        coEvery { preferences.snapshot() } returns
            pendingState(dismissedAgoMs = TimeUnit.HOURS.toMillis(73), dismissCount = 12)

        repository().maybeReArmPendingNotification()

        verify(exactly = 1) { notifier.showPendingDownload(any()) }
    }
    private fun statefulPreferences(initial: UpdaterState): () -> UpdaterState {
        var current = initial
        coEvery { preferences.snapshot() } answers { current }
        coEvery { preferences.update(any()) } answers {
            current = firstArg<(UpdaterState) -> UpdaterState>()(current)
        }
        return { current }
    }

    private fun futureState(dismissedAgoMs: Long? = null) = pendingState(dismissedAgoMs).copy(
        pendingTagName = "v99.0.0", pendingVersionName = "99.0.0",
    )

    @Test fun `repeated successful discovery respects dismissal`() = runTest {
        val initial = futureState(TimeUnit.HOURS.toMillis(1))
        statefulPreferences(initial)
        coEvery { checker.maybeCheck(any()) } returns UpdateChecker.CheckOutcome.Update(initial.pendingUpdate()!!)
        val repo = repository()
        repeat(3) { repo.checkNow(UpdateChecker.Trigger.APP_FOREGROUND) }
        verify(exactly = 0) { notifier.showPendingDownload(any()) }
    }

    @Test fun `repeated discovery and permission callback notify once`() = runTest {
        val initial = futureState()
        val current = statefulPreferences(initial)
        coEvery { checker.maybeCheck(any()) } returns UpdateChecker.CheckOutcome.Update(initial.pendingUpdate()!!)
        val repo = repository()
        repeat(3) { repo.checkNow(UpdateChecker.Trigger.NETWORK_AVAILABLE) }
        repo.reNotifyPendingUpdateIfAny()
        verify(exactly = 1) { notifier.showPendingDownload(any()) }
        assertEquals(initial.pendingTagName, current().lastNotifiedTagName)
    }

    @Test fun `blocked notification can be delivered after permission grant`() = runTest {
        val initial = futureState()
        val current = statefulPreferences(initial)
        coEvery { checker.maybeCheck(any()) } returns UpdateChecker.CheckOutcome.Update(initial.pendingUpdate()!!)
        every { notifier.showPendingDownload(any()) } returnsMany listOf(false, true)
        val repo = repository()
        repo.checkNow(UpdateChecker.Trigger.APP_FOREGROUND)
        assertEquals(null, current().lastNotifiedTagName)
        repo.reNotifyPendingUpdateIfAny()
        assertEquals(initial.pendingTagName, current().lastNotifiedTagName)
        verify(exactly = 2) { notifier.showPendingDownload(any()) }
    }

    @Test fun `due reminder is not posted twice by discovery and rearm`() = runTest {
        val initial = futureState(TimeUnit.HOURS.toMillis(25))
        val current = statefulPreferences(initial)
        coEvery { checker.maybeCheck(any()) } returns UpdateChecker.CheckOutcome.Update(initial.pendingUpdate()!!)
        val repo = repository()
        repo.checkNow(UpdateChecker.Trigger.PERIODIC)
        repo.maybeReArmPendingNotification()
        verify(exactly = 1) { notifier.showPendingDownload(any()) }
        assertEquals(null, current().notifDismissedAtEpochMs)
        assertEquals(1, current().notifReArmCount)
    }

    @Test fun `disabled automatic checks do not revive a dismissed reminder`() = runTest {
        val current = statefulPreferences(futureState(TimeUnit.DAYS.toMillis(40)).copy(autoCheckEnabled = false))
        coEvery { checker.maybeCheck(any()) } returns UpdateChecker.CheckOutcome.Skipped("auto_check_disabled")
        val repo = repository()
        repo.checkNow(UpdateChecker.Trigger.APP_FOREGROUND)
        repo.maybeReArmPendingNotification()
        assertNotNull(current().notifDismissedAtEpochMs)
        verify(exactly = 0) { notifier.showPendingDownload(any()) }
    }

    @Test fun `third dismissal waits seventy two hours`() = runTest {
        statefulPreferences(pendingState(TimeUnit.HOURS.toMillis(25), dismissCount = 3))
        repository().maybeReArmPendingNotification()
        verify(exactly = 0) { notifier.showPendingDownload(any()) }
    }

    @Test fun `blocked reminder keeps its dismissal timestamp`() = runTest {
        val current = statefulPreferences(pendingState(TimeUnit.HOURS.toMillis(25)))
        every { notifier.showPendingDownload(any()) } returns false
        repository().maybeReArmPendingNotification()
        assertNotNull(current().notifDismissedAtEpochMs)
    }

    @Test fun `new release gets one notification even after older release was shown`() = runTest {
        val initial = futureState().copy(lastNotifiedTagName = "v98.0.0")
        statefulPreferences(initial)
        coEvery { checker.maybeCheck(any()) } returns UpdateChecker.CheckOutcome.Update(initial.pendingUpdate()!!)
        val repo = repository()
        repo.checkNow(UpdateChecker.Trigger.APP_FOREGROUND)
        repo.checkNow(UpdateChecker.Trigger.APP_FOREGROUND)
        verify(exactly = 1) { notifier.showPendingDownload(any()) }
    }

    @Test fun `not modified response still delivers a due reminder exactly once`() = runTest {
        statefulPreferences(futureState(TimeUnit.HOURS.toMillis(25)))
        coEvery { checker.maybeCheck(any()) } returns UpdateChecker.CheckOutcome.NotModified
        val repo = repository()
        repeat(2) { repo.checkNow(UpdateChecker.Trigger.PERIODIC) }
        verify(exactly = 1) { notifier.showPendingDownload(any()) }
    }

    @Test fun `source tag spelling does not reannounce the same version`() = runTest {
        val initial = futureState().copy(pendingTagName = "99.0.0", lastNotifiedTagName = "v99.0.0")
        statefulPreferences(initial)
        coEvery { checker.maybeCheck(any()) } returns UpdateChecker.CheckOutcome.Update(initial.pendingUpdate()!!)
        repository().checkNow(UpdateChecker.Trigger.APP_FOREGROUND)
        verify(exactly = 0) { notifier.showPendingDownload(any()) }
    }

    @Test fun `second reminder is due after twenty four hours`() = runTest {
        statefulPreferences(pendingState(TimeUnit.HOURS.toMillis(25), dismissCount = 2))
        repository().maybeReArmPendingNotification()
        verify(exactly = 1) { notifier.showPendingDownload(any()) }
    }

    @Test fun `second reminder does not appear before twenty four hours`() = runTest {
        statefulPreferences(pendingState(TimeUnit.HOURS.toMillis(23), dismissCount = 2))
        repository().maybeReArmPendingNotification()
        verify(exactly = 0) { notifier.showPendingDownload(any()) }
    }

    @Test fun `third reminder is due after seventy two hours`() = runTest {
        statefulPreferences(pendingState(TimeUnit.HOURS.toMillis(73), dismissCount = 3))
        repository().maybeReArmPendingNotification()
        verify(exactly = 1) { notifier.showPendingDownload(any()) }
    }

}
