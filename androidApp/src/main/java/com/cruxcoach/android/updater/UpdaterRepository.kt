package com.cruxcoach.android.updater

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.util.Log
import com.cruxcoach.android.BuildConfig
import com.cruxcoach.android.util.ExternalInputPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Public entry point for the updater (§4). Everything the UI, the
 * periodic worker, and the install-status receiver touch is here;
 * lower-level classes are implementation detail.
 *
 * All mutations route through [UpdaterPreferences.update] so the
 * pipeline stage is always consistent even across process restarts.
 */
@Singleton
class UpdaterRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val preferences: UpdaterPreferences,
    private val checker: UpdateChecker,
    private val downloader: ApkDownloader,
    private val verifier: IntegrityVerifier,
    private val installer: ApkInstaller,
    private val notifier: UpdateNotifier,
    private val installSourceGate: InstallSourceGate,
    @param:Named("io") private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val exceptionHandler = CoroutineExceptionHandler { _, e ->
        // Fire-and-forget updater coroutines have no caller to observe a throw.
        // SupervisorJob isolates siblings but does NOT swallow exceptions, so
        // without this a transient DownloadManager/DataStore/notifier failure
        // would surface as a whole-app crash. Log and drop instead.
        Log.e(TAG, "Uncaught exception in updater coroutine", e)
    }
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher + exceptionHandler)
    private var downloadMonitorJob: Job? = null

    val state: Flow<UpdaterState> = preferences.state

    /** Live download progress in percent [0..100], null when no download in flight. */
    private val _downloadProgress = MutableStateFlow<Int?>(null)
    val downloadProgress: StateFlow<Int?> = _downloadProgress.asStateFlow()

    /** Signal to the UI that the download-confirm dialog should open now.
     *  Set by [requestDownloadDialog] (typically from the notification tap
     *  intent); cleared by the UI once the dialog is shown or dismissed. */
    private val _downloadDialogRequested = MutableStateFlow(false)
    val downloadDialogRequested: StateFlow<Boolean> = _downloadDialogRequested.asStateFlow()

    /** Signal to the UI to open the download-confirm dialog.
     *
     *  Only honoured when the pipeline is actually in [PipelineStage.PENDING_DOWNLOAD].
     *  Without this guard, any app on the device could start MainActivity with
     *  `updater_show_download_dialog=true` and spawn a dialog at will (MainActivity
     *  is exported for LAUNCHER). When there's no pending download, the "honest"
     *  notification tap is a no-op anyway, so this check matches user intent and
     *  drops external-app spoofs without changing legitimate UX. */
    fun requestDownloadDialog() {
        scope.launch {
            if (preferences.snapshot().pipelineStage == PipelineStage.PENDING_DOWNLOAD) {
                _downloadDialogRequested.value = true
            }
        }
    }

    fun consumeDownloadDialogRequest() {
        _downloadDialogRequested.value = false
    }

    suspend fun snapshot(): UpdaterState = preferences.snapshot()

    fun selfUpdateAllowed(): Boolean = installSourceGate.selfUpdateAllowed()

    /**
     * Runs a check round and reacts to the outcome: auto-downloads when the
     * network policy permits (§6.14), posts a PENDING_DOWNLOAD notification
     * otherwise. Called from every trigger source.
     */
    suspend fun checkNow(trigger: UpdateChecker.Trigger): UpdateChecker.CheckOutcome {
        val outcome = checker.maybeCheck(trigger)
        if (outcome is UpdateChecker.CheckOutcome.Update) {
            onNewerUpdateDetected(outcome.info)
        }
        // Re-surface a dismissed-but-pending update on its backoff, INDEPENDENT of
        // the network outcome. maybeCheck returns NotModified on an ETag 304, which
        // otherwise short-circuits before any notification work — so without this a
        // once-dismissed update could never be re-shown by any later check.
        maybeReArmPendingNotification()
        return outcome
    }

    private suspend fun onNewerUpdateDetected(info: UpdateInfo) {
        // No auto-download: every download is gated behind an in-app
        // confirmation dialog. Notification brings the user into the app.
        notifier.showPendingDownload(info)
    }

    /**
     * Re-posts a `PENDING_DOWNLOAD` notification from cached state. Used after
     * the user grants POST_NOTIFICATIONS post-hoc: the first check fired while
     * the permission dialog was still up, so [UpdateNotifier.notify]'s
     * `areNotificationsEnabled()` guard dropped the notification. State is
     * cached, so we re-emit without a second network round-trip.
     *
     * No-ops when the user has already moved past PENDING_DOWNLOAD (download
     * started, install ready, …) or dismissed a prior notification — we don't
     * want to resurrect a surface they already acted on.
     */
    fun reNotifyPendingUpdateIfAny() {
        scope.launch {
            val prefs = preferences.snapshot()
            if (prefs.pipelineStage != PipelineStage.PENDING_DOWNLOAD) return@launch
            if (prefs.notifDismissedAtEpochMs != null) return@launch
            val info = prefs.pendingUpdate() ?: return@launch
            notifier.showPendingDownload(info)
        }
    }

    /**
     * Re-attaches to an in-flight APK download after process death. The
     * `DownloadManager` runs in its own system process, so a download enqueued
     * before the OS killed us keeps going (or already finished) — but the
     * [monitorDownload] coroutine died with our process, leaving the pipeline
     * stuck in `DOWNLOADING` with no verify/install and a frozen progress bar.
     * Called from [UpdaterCoordinator.start] on every launch: if a pending
     * download id survives in prefs, re-query and resume monitoring so the flow
     * finishes (or fails cleanly). If `DownloadManager` no longer knows the id
     * (cleared / expired), reset to `PENDING_DOWNLOAD` and re-notify so the user
     * can restart instead of waiting forever.
     */
    fun resumePendingDownloadIfAny() {
        scope.launch {
            val prefs = preferences.snapshot()
            if (prefs.pipelineStage != PipelineStage.DOWNLOADING) return@launch
            val info = prefs.pendingUpdate()
            val id = prefs.pendingDownloadId
            if (id == null || info == null) {
                // Inconsistent state (DOWNLOADING but nothing to resume) — reset
                // so the user can re-trigger from a clean surface.
                preferences.update {
                    it.copy(pendingDownloadId = null, pipelineStage = PipelineStage.PENDING_DOWNLOAD)
                }
                return@launch
            }
            if (downloader.query(id) == null) {
                Log.i(TAG, "event=resume_download_gone id=$id — resetting to pending")
                preferences.update {
                    it.copy(pendingDownloadId = null, pipelineStage = PipelineStage.PENDING_DOWNLOAD)
                }
                notifier.showPendingDownload(info)
                return@launch
            }
            Log.i(TAG, "event=resume_download id=$id version=${info.versionName}")
            monitorDownload(info, id)
        }
    }

    /** Starts (or resumes) the download and watches it to completion. */
    fun startDownload(info: UpdateInfo, allowMobile: Boolean) {
        scope.launch {
            val enqueue = downloader.start(info, allowMobile)
            when (enqueue) {
                is ApkDownloader.StartResult.Enqueued -> {
                    preferences.update {
                        it.copy(
                            pendingDownloadId = enqueue.id,
                            pipelineStage = PipelineStage.DOWNLOADING,
                        )
                    }
                    notifier.showDownloading(info, progressPercent = 0)
                    monitorDownload(info, enqueue.id)
                }
                is ApkDownloader.StartResult.InsufficientStorage -> {
                    Log.w(TAG, "Insufficient storage: need=${enqueue.neededBytes} free=${enqueue.freeBytes}")
                    preferences.update {
                        it.copy(pipelineStage = PipelineStage.PENDING_DOWNLOAD)
                    }
                    notifier.showDownloadError(info, reason = UpdateNotifier.DownloadError.NO_SPACE)
                }
                is ApkDownloader.StartResult.Error -> {
                    Log.w(TAG, "Download enqueue failed: ${enqueue.message}")
                    preferences.update {
                        it.copy(pipelineStage = PipelineStage.PENDING_DOWNLOAD)
                    }
                    notifier.showDownloadError(info, reason = UpdateNotifier.DownloadError.GENERIC)
                }
            }
        }
    }

    private fun monitorDownload(info: UpdateInfo, id: Long) {
        downloadMonitorJob?.cancel()
        _downloadProgress.value = 0
        downloadMonitorJob = scope.launch {
            var lastNotifyPct = -1
            while (true) {
                // Guard the poll query: DownloadManager.query can throw
                // (SQLiteException / IllegalStateException on some OEMs). A
                // transient throw ends the monitor cleanly — the pipeline stays
                // DOWNLOADING and resumePendingDownloadIfAny re-attaches on the
                // next launch — instead of crashing out of the loop.
                val status = try {
                    downloader.query(id)
                } catch (e: Exception) {
                    Log.w(TAG, "event=download_query_threw ending monitor", e)
                    null
                } ?: break
                when (status.state) {
                    ApkDownloader.State.SUCCESSFUL -> {
                        _downloadProgress.value = 100
                        onDownloadFinished(info)
                        _downloadProgress.value = null
                        return@launch
                    }
                    ApkDownloader.State.FAILED -> {
                        Log.w(TAG, "Download failed — reason=${status.reason}")
                        preferences.update {
                            it.copy(
                                pendingDownloadId = null,
                                pipelineStage = PipelineStage.PENDING_DOWNLOAD,
                            )
                        }
                        _downloadProgress.value = null
                        downloader.clearCacheFor(info.versionName)
                        notifier.showDownloadError(info, reason = UpdateNotifier.DownloadError.GENERIC)
                        return@launch
                    }
                    else -> {
                        val pct = status.progressPercent
                        if (pct != null) _downloadProgress.value = pct
                        if (pct != null && pct != lastNotifyPct) {
                            lastNotifyPct = pct
                            notifier.showDownloading(info, pct)
                        }
                    }
                }
                delay(PROGRESS_POLL_MS)
            }
            _downloadProgress.value = null
        }
    }

    private suspend fun onDownloadFinished(info: UpdateInfo) {
        val apk = downloader.targetFileFor(info.versionName)
        when (val result = verifier.verify(apk, info.apkSha256)) {
            IntegrityVerifier.Result.Ok -> {
                preferences.update { it.copy(pipelineStage = PipelineStage.READY_TO_INSTALL) }
                notifier.showReadyToInstall(info)
            }
            is IntegrityVerifier.Result.CertMismatch -> {
                Log.w(TAG, "Cert mismatch — handoff to browser per §5.4.3")
                downloader.clearCacheFor(info.versionName)
                preferences.update {
                    it.copy(
                        pendingDownloadId = null,
                        pipelineStage = PipelineStage.BLOCKED_CERT_MISMATCH,
                        lastCheckResult = CheckResult.BLOCKED_CERT_MISMATCH,
                    )
                }
                notifier.showCertMismatch(info)
            }
            is IntegrityVerifier.Result.SignerUnavailable -> {
                // The signer cert couldn't be extracted (ROM quirk + no usable
                // v1 signature). Re-downloading can't fix this, so hand off to
                // the release page like a cert mismatch instead of looping on
                // CORRUPT forever — a retry there could never install.
                Log.w(TAG, "Signer unavailable ($result) — handoff to browser")
                downloader.clearCacheFor(info.versionName)
                preferences.update {
                    it.copy(
                        pendingDownloadId = null,
                        pipelineStage = PipelineStage.BLOCKED_CERT_MISMATCH,
                        lastCheckResult = CheckResult.BLOCKED_CERT_MISMATCH,
                    )
                }
                notifier.showCertMismatch(info)
            }
            IntegrityVerifier.Result.PayloadMismatch,
            IntegrityVerifier.Result.PayloadMissing,
            is IntegrityVerifier.Result.PayloadError,
                -> {
                Log.w(TAG, "Integrity verification failed ($result)")
                downloader.clearCacheFor(info.versionName)
                preferences.update {
                    it.copy(
                        pendingDownloadId = null,
                        pipelineStage = PipelineStage.PENDING_DOWNLOAD,
                    )
                }
                notifier.showDownloadError(info, reason = UpdateNotifier.DownloadError.CORRUPT)
            }
        }
    }

    /** Manual "Installieren" from notification / Settings. */
    fun installPending() {
        scope.launch {
            val prefs = preferences.snapshot()
            val info = prefs.pendingUpdate() ?: return@launch
            val apk = downloader.targetFileFor(info.versionName)
            when (val result = installer.install(apk)) {
                is ApkInstaller.InstallResult.Committed -> {
                    Log.i(TAG, "Install session ${result.sessionId} committed")
                }
                is ApkInstaller.InstallResult.Error -> {
                    Log.w(TAG, "Install commit failed: ${result.message}")
                    notifier.showInstallError(info, status = Int.MIN_VALUE, message = result.message)
                }
            }
        }
    }

    /**
     * Called by [ApkInstallStatusReceiver]. [onDone] fires when the async
     * terminal-status work finishes so the receiver's `goAsync()` PendingResult
     * can be finished — otherwise the STATUS_SUCCESS cleanup (state reset, APK
     * delete, notification cancel) can be dropped when the OS reaps the
     * freshly-replaced idle process before this coroutine runs.
     */
    fun onInstallOutcome(outcome: InstallOutcome, onDone: () -> Unit = {}) {
        scope.launch {
            try {
            val prefs = preferences.snapshot()
            val info = prefs.pendingUpdate()
            when (outcome) {
                InstallOutcome.Success -> {
                    info?.let { downloader.clearCacheFor(it.versionName) }
                    preferences.update {
                        UpdaterState(
                            autoCheckEnabled = it.autoCheckEnabled,
                            autoDownloadOnWifi = it.autoDownloadOnWifi,
                            autoDownloadOnMobile = it.autoDownloadOnMobile,
                            lastCheckAtEpochMs = it.lastCheckAtEpochMs,
                            lastCheckBootRealtime = it.lastCheckBootRealtime,
                            lastCheckEtag = it.lastCheckEtag,
                            lastCheckResult = CheckResult.NO_UPDATE,
                        )
                    }
                    notifier.cancel()
                }
                is InstallOutcome.Failed -> {
                    when (outcome.status) {
                        PackageInstaller.STATUS_FAILURE_ABORTED -> {
                            // User cancelled consent — stay ready to install.
                            info?.let { notifier.showReadyToInstall(it) }
                        }
                        else -> {
                            if (info != null) notifier.showInstallError(info, outcome.status, outcome.message)
                            if (outcome.status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                                info?.let { downloader.clearCacheFor(it.versionName) }
                                preferences.update {
                                    it.copy(
                                        pendingDownloadId = null,
                                        pipelineStage = PipelineStage.PENDING_DOWNLOAD,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            } finally {
                onDone()
            }
        }
    }

    /**
     * Called by [ApkInstallStatusReceiver] on STATUS_PENDING_USER_ACTION.
     * Surfaces the system install-consent dialog reliably: a manifest
     * BroadcastReceiver's direct startActivity can be silently dropped by
     * background-activity-start limits (API 29+, stricter on 34/35) with no
     * exception thrown, stranding the pipeline at READY_TO_INSTALL. We attempt
     * the direct launch (smooth for the foreground "Installieren" tap) AND
     * always post a tappable notification whose contentIntent is the consent
     * IntentSender, so the user's tap provides a fresh background-activity-start
     * grant either way. [onDone] finishes the receiver's goAsync() PendingResult.
     */
    fun onConsentRequired(consentIntent: Intent, onDone: () -> Unit = {}) {
        scope.launch {
            try {
                consentIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(consentIntent) }
                    .onFailure { Log.w(TAG, "Direct consent launch failed", it) }
                val info = preferences.snapshot().pendingUpdate()
                if (info != null) notifier.showConsentRequired(info, consentIntent)
            } finally {
                onDone()
            }
        }
    }

    /** §5.4.3 — one-tap handoff to the Codeberg release page. */
    fun openReleasePage(info: UpdateInfo) {
        val safeUrl = ExternalInputPolicy.trustedReleasePageUrlOrNull(
            info.releasePageUrl,
            BuildConfig.UPDATER_API_BASE,
        ) ?: run {
            Log.w(TAG, "Refusing untrusted release-page URL")
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "No browser available for release page", e)
        }
    }

    /**
     * User swiped the pending-update notification away. Stamp the dismissal time
     * and bump the dismissal count — [maybeReArmPendingNotification] uses both to
     * re-surface the update later on an escalating backoff (§6.10).
     */
    suspend fun onNotificationDismissed() {
        preferences.update {
            it.copy(
                notifDismissedAtEpochMs = System.currentTimeMillis(),
                notifReArmCount = it.notifReArmCount + 1,
            )
        }
    }

    /**
     * §6.10 re-arm: re-post a pending update the user swiped away, on an
     * escalating backoff keyed to how many times they've dismissed it — ~24h
     * after the first dismissal, ~72h for the next ten, then ~30d forever. Called
     * from [checkNow] on every trigger (foreground / network / 24h periodic), so
     * even a run that returns NotModified (ETag 304) gets a chance to re-surface
     * the update. No-op unless there is a dismissed, still-pending download whose
     * backoff has elapsed.
     */
    suspend fun maybeReArmPendingNotification() {
        val prefs = preferences.snapshot()
        if (prefs.pipelineStage != PipelineStage.PENDING_DOWNLOAD) return
        val dismissedAt = prefs.notifDismissedAtEpochMs ?: return // showing / never dismissed
        val info = prefs.pendingUpdate() ?: return
        if (System.currentTimeMillis() - dismissedAt < reArmDelayMs(prefs.notifReArmCount)) return
        notifier.showPendingDownload(info)
        // It's on screen again → clear the dismissed marker. The dismissal COUNT
        // is kept (it drives the escalating backoff); a fresh swipe bumps it via
        // onNotificationDismissed and lengthens the next interval.
        preferences.update { it.copy(notifDismissedAtEpochMs = null) }
        Log.i(TAG, "event=notif_rearmed version=${info.versionName} dismissCount=${prefs.notifReArmCount}")
    }

    /** Backoff before re-showing a dismissed update, by dismissal count (§6.10). */
    private fun reArmDelayMs(dismissCount: Int): Long = when {
        dismissCount <= 1 -> TimeUnit.HOURS.toMillis(24)
        dismissCount <= 11 -> TimeUnit.HOURS.toMillis(72) // dismissals 2..11 — the "×10" window
        else -> TimeUnit.DAYS.toMillis(30)
    }

    suspend fun setAutoCheck(enabled: Boolean) =
        preferences.update { it.copy(autoCheckEnabled = enabled) }

    suspend fun setAutoDownloadOnWifi(enabled: Boolean) =
        preferences.update { it.copy(autoDownloadOnWifi = enabled) }

    suspend fun setAutoDownloadOnMobile(enabled: Boolean) =
        preferences.update { it.copy(autoDownloadOnMobile = enabled) }

    companion object {
        private const val TAG = "UpdaterRepository"
        private const val PROGRESS_POLL_MS = 1_500L
    }
}
