package com.cruxcoach.android.updater

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import com.cruxcoach.android.BuildConfig

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
    private val verifiedUpdateMetrics: VerifiedUpdateMetrics = VerifiedUpdateMetrics.NONE,
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
    private val downloadCompletionMutex = Mutex()
    private val anonymousUpdateMetricsMutex = Mutex()
    private val automaticInstallInFlight = AtomicBoolean(false)

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

    val anonymousUpdateMetricsAvailable: Boolean
        get() = verifiedUpdateMetrics.isConfigured

    fun canRequestPackageInstalls(): Boolean = installer.canRequestPackageInstalls()

    /**
     * Runs a check round and reacts to the outcome: auto-downloads when the
     * network policy permits (§6.14), posts a PENDING_DOWNLOAD notification
     * otherwise. Called from every trigger source.
     */
    suspend fun checkNow(trigger: UpdateChecker.Trigger): UpdateChecker.CheckOutcome {
        val outcome = checker.maybeCheck(trigger)
        if (outcome is UpdateChecker.CheckOutcome.Update) {
            onNewerUpdateDetected(outcome.info)
        } else {
            resumePendingAutomationIfAllowed()
        }
        // Re-surface a dismissed-but-pending update on its backoff, INDEPENDENT of
        // the network outcome. maybeCheck returns NotModified on an ETag 304, which
        // otherwise short-circuits before any notification work — so without this a
        // once-dismissed update could never be re-shown by any later check.
        maybeReArmPendingNotification()
        return outcome
    }

    /**
     * A network callback can arrive inside the check throttle window. Re-use a
     * previously verified pending release so automatic mode does not wait for
     * the next two-hour check merely because discovery returned 304/throttled.
     */
    private suspend fun resumePendingAutomationIfAllowed() {
        val prefs = preferences.snapshot()
        if (prefs.pipelineStage != PipelineStage.PENDING_DOWNLOAD ||
            prefs.automationMode == UpdateAutomationMode.NOTIFY
        ) return
        prefs.pendingUpdate()?.let { onNewerUpdateDetected(it) }
    }

    private suspend fun onNewerUpdateDetected(info: UpdateInfo) {
        val prefs = preferences.snapshot()
        when (prefs.pipelineStage) {
            PipelineStage.DOWNLOADING,
            PipelineStage.INSTALLING,
            PipelineStage.BLOCKED_CERT_MISMATCH,
                -> return
            PipelineStage.READY_TO_INSTALL -> {
                if (prefs.automationMode == UpdateAutomationMode.AUTO_INSTALL) {
                    val apk = downloader.targetFileFor(info.versionName)
                    if (!tryAutomaticInstall(info, apk)) notifier.showReadyToInstall(info)
                }
                return
            }
            PipelineStage.NONE,
            PipelineStage.PENDING_DOWNLOAD,
                -> Unit
        }

        // A newly discovered version can replace an older download between
        // check rounds. Stop that orphaned DownloadManager job before acting
        // on the new pending APK.
        prefs.pendingDownloadId?.let { staleId ->
            downloadMonitorJob?.cancel()
            downloadMonitorJob = null
            downloader.cancel(staleId)
            _downloadProgress.value = null
            preferences.update { it.copy(pendingDownloadId = null) }
        }
        val transportAllowed = when (downloader.currentTransport()) {
            ApkDownloader.Transport.WIFI -> true
            ApkDownloader.Transport.CELLULAR -> prefs.autoDownloadOnMobile
            ApkDownloader.Transport.OFFLINE,
            ApkDownloader.Transport.UNKNOWN,
                -> false
        }
        if (prefs.automationMode != UpdateAutomationMode.NOTIFY && transportAllowed) {
            startDownload(info, allowMobile = prefs.autoDownloadOnMobile)
        } else {
            notifier.showPendingDownload(info)
        }
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
            val sourceIndex = prefs.pendingDownloadSourceIndex
            if (info.downloadUrls.getOrNull(sourceIndex) == null) {
                preferences.update {
                    it.copy(
                        pendingDownloadId = null,
                        pendingDownloadSourceIndex = 0,
                        pipelineStage = PipelineStage.PENDING_DOWNLOAD,
                    )
                }
                notifier.showPendingDownload(info)
                return@launch
            }
            Log.i(TAG, "event=resume_download id=$id version=${info.versionName} source=$sourceIndex")
            monitorDownload(
                info,
                id,
                prefs.pendingAllowMobile,
                sourceIndex,
                showProgressNotification = prefs.automationMode == UpdateAutomationMode.NOTIFY,
            )
        }
    }

    /** Starts (or resumes) the download and watches it to completion. */
    suspend fun startDownload(info: UpdateInfo, allowMobile: Boolean) {
        downloadMonitorJob?.cancel()
        enqueueDownload(info, allowMobile, sourceIndex = 0)
    }

    private suspend fun enqueueDownload(info: UpdateInfo, allowMobile: Boolean, sourceIndex: Int) {
        val showProgressNotification =
            preferences.snapshot().automationMode == UpdateAutomationMode.NOTIFY
        val enqueue = downloader.start(info, allowMobile, sourceIndex)
        when (enqueue) {
            is ApkDownloader.StartResult.Enqueued -> {
                preferences.update {
                    it.copy(
                        pendingDownloadId = enqueue.id,
                        pendingDownloadSourceIndex = sourceIndex,
                        pendingAllowMobile = allowMobile,
                        pipelineStage = PipelineStage.DOWNLOADING,
                    )
                }
                if (showProgressNotification) notifier.showDownloading(info, progressPercent = 0)
                monitorDownload(
                    info,
                    enqueue.id,
                    allowMobile,
                    sourceIndex,
                    showProgressNotification,
                )
            }
            is ApkDownloader.StartResult.InsufficientStorage -> {
                Log.w(TAG, "Insufficient storage: need=${enqueue.neededBytes} free=${enqueue.freeBytes}")
                preferences.update { it.copy(pipelineStage = PipelineStage.PENDING_DOWNLOAD) }
                notifier.showDownloadError(info, reason = UpdateNotifier.DownloadError.NO_SPACE)
            }
            is ApkDownloader.StartResult.Error -> {
                Log.w(TAG, "Download enqueue failed for source=$sourceIndex: ${enqueue.message}")
                if (!startNextDownloadSource(info, allowMobile, sourceIndex)) {
                    preferences.update {
                        it.copy(
                            pendingDownloadSourceIndex = 0,
                            pipelineStage = PipelineStage.PENDING_DOWNLOAD,
                        )
                    }
                    notifier.showDownloadError(info, reason = UpdateNotifier.DownloadError.GENERIC)
                }
            }
        }
    }

    private fun monitorDownload(
        info: UpdateInfo,
        id: Long,
        allowMobile: Boolean,
        sourceIndex: Int,
        showProgressNotification: Boolean,
    ) {
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
                        downloadMonitorJob = null
                        handleDownloadTerminal(
                            info = info,
                            id = id,
                            status = status,
                            allowMobile = allowMobile,
                            sourceIndex = sourceIndex,
                            cancelMonitor = false,
                        )
                        _downloadProgress.value = null
                        return@launch
                    }
                    ApkDownloader.State.FAILED -> {
                        _downloadProgress.value = null
                        downloadMonitorJob = null
                        handleDownloadTerminal(
                            info = info,
                            id = id,
                            status = status,
                            allowMobile = allowMobile,
                            sourceIndex = sourceIndex,
                            cancelMonitor = false,
                        )
                        return@launch
                    }
                    else -> {
                        val pct = status.progressPercent
                        if (pct != null) _downloadProgress.value = pct
                        if (showProgressNotification && pct != null && pct != lastNotifyPct) {
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

    /**
     * Completes a DownloadManager job exactly once. The in-process poller and
     * [ApkDownloadCompleteReceiver] can observe the same terminal transition;
     * the mutex plus persisted id check prevents double verification or
     * duplicate PackageInstaller sessions.
     */
    private suspend fun handleDownloadTerminal(
        info: UpdateInfo,
        id: Long,
        status: ApkDownloader.Status,
        allowMobile: Boolean,
        sourceIndex: Int,
        cancelMonitor: Boolean,
    ): Boolean {
        if (status.state != ApkDownloader.State.SUCCESSFUL &&
            status.state != ApkDownloader.State.FAILED
        ) return false
        return downloadCompletionMutex.withLock {
            val current = preferences.snapshot()
            if (current.pipelineStage != PipelineStage.DOWNLOADING ||
                current.pendingDownloadId != id
            ) return@withLock true
            if (cancelMonitor) {
                downloadMonitorJob?.cancel()
                downloadMonitorJob = null
                _downloadProgress.value = null
            }

            when (status.state) {
                ApkDownloader.State.SUCCESSFUL -> onDownloadFinished(info, allowMobile, sourceIndex)
                ApkDownloader.State.FAILED -> {
                    Log.w(TAG, "Download failed — source=$sourceIndex reason=${status.reason}")
                    downloader.clearCacheFor(info.versionName)
                    if (!startNextDownloadSource(info, allowMobile, sourceIndex)) {
                        preferences.update {
                            it.copy(
                                pendingDownloadId = null,
                                pendingDownloadSourceIndex = 0,
                                pipelineStage = PipelineStage.PENDING_DOWNLOAD,
                            )
                        }
                        notifier.showDownloadError(info, reason = UpdateNotifier.DownloadError.GENERIC)
                    }
                }
            }
            true
        }
    }

    /** Continues verification/install after DownloadManager wakes our process. */
    fun onDownloadManagerCompleted(id: Long, onDone: () -> Unit = {}) {
        scope.launch {
            try {
                val prefs = preferences.snapshot()
                if (prefs.pipelineStage != PipelineStage.DOWNLOADING ||
                    prefs.pendingDownloadId != id
                ) return@launch
                val info = prefs.pendingUpdate() ?: return@launch
                val status = downloader.query(id) ?: return@launch
                handleDownloadTerminal(
                    info = info,
                    id = id,
                    status = status,
                    allowMobile = prefs.pendingAllowMobile,
                    sourceIndex = prefs.pendingDownloadSourceIndex,
                    cancelMonitor = true,
                )
            } finally {
                onDone()
            }
        }
    }

    private suspend fun onDownloadFinished(
        info: UpdateInfo,
        allowMobile: Boolean,
        sourceIndex: Int,
    ) {
        val apk = downloader.targetFileFor(info.versionName)
        when (val result = verifier.verify(apk, info.apkSha256)) {
            IntegrityVerifier.Result.Ok -> {
                preferences.update { it.copy(pipelineStage = PipelineStage.READY_TO_INSTALL) }
                // Count only after both verification gates. Persist the attempt
                // before enqueueing and never retry: without a user/event ID the
                // server cannot deduplicate an ambiguous network failure.
                try {
                    recordVerifiedUpdateOnce(info, sourceIndex)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // Metrics are ancillary and must never block install UX.
                    Log.w(TAG, "Anonymous update count failed locally", error)
                }
                val mode = preferences.snapshot().automationMode
                if (mode == UpdateAutomationMode.AUTO_INSTALL && tryAutomaticInstall(info, apk)) {
                    // PackageInstaller owns the rest. A pending user-action
                    // callback is surfaced by ApkInstallStatusReceiver.
                } else {
                    notifier.showReadyToInstall(info)
                }
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
                Log.w(TAG, "Integrity verification failed for source=$sourceIndex ($result)")
                downloader.clearCacheFor(info.versionName)
                downloadMonitorJob = null
                if (!startNextDownloadSource(info, allowMobile, sourceIndex)) {
                    preferences.update {
                        it.copy(
                            pendingDownloadId = null,
                            pendingDownloadSourceIndex = 0,
                            pipelineStage = PipelineStage.PENDING_DOWNLOAD,
                        )
                    }
                    notifier.showDownloadError(info, reason = UpdateNotifier.DownloadError.CORRUPT)
                }
            }
        }
    }

    private suspend fun startNextDownloadSource(
        info: UpdateInfo,
        allowMobile: Boolean,
        failedSourceIndex: Int,
    ): Boolean {
        val next = failedSourceIndex + 1
        if (info.downloadUrls.getOrNull(next) == null) return false
        Log.i(TAG, "event=download_source_fallback from=$failedSourceIndex to=$next")
        enqueueDownload(info, allowMobile, next)
        return true
    }

    private suspend fun recordVerifiedUpdateOnce(
        info: UpdateInfo,
        sourceIndex: Int,
    ) = anonymousUpdateMetricsMutex.withLock {
        if (!verifiedUpdateMetrics.isConfigured) return@withLock
        val downloadUrl = info.downloadUrls.getOrNull(sourceIndex) ?: return@withLock
        val source = anonymousUpdateSource(downloadUrl) ?: run {
            Log.w(TAG, "Anonymous update count skipped for an unknown download source")
            return@withLock
        }
        var shouldDispatch = false
        preferences.update { current ->
            if (!current.anonymousUpdateMetricsEnabled ||
                current.lastAnonymousMetricsAttemptVersion == info.versionName
            ) {
                current
            } else {
                shouldDispatch = true
                current.copy(lastAnonymousMetricsAttemptVersion = info.versionName)
            }
        }
        if (shouldDispatch) {
            verifiedUpdateMetrics.recordVerifiedUpdate(info.versionName, source)
        }
    }

    /** Manual "Installieren" from notification / Settings. */
    fun installPending() {
        scope.launch {
            val prefs = preferences.snapshot()
            val info = prefs.pendingUpdate() ?: return@launch
            val apk = downloader.targetFileFor(info.versionName)
            preferences.update { it.copy(pipelineStage = PipelineStage.INSTALLING) }
            when (val result = installer.install(apk, preferNoUserAction = false)) {
                is ApkInstaller.InstallResult.Committed -> {
                    Log.i(TAG, "Install session ${result.sessionId} committed")
                }
                is ApkInstaller.InstallResult.Error -> {
                    Log.w(TAG, "Install commit failed: ${result.message}")
                    preferences.update { it.copy(pipelineStage = PipelineStage.READY_TO_INSTALL) }
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
                        automaticInstallInFlight.set(false)
                        info?.let { downloader.clearCacheFor(it.versionName) }
                        preferences.update {
                            UpdaterState(
                                autoCheckEnabled = it.autoCheckEnabled,
                                automationMode = it.automationMode,
                                autoDownloadOnMobile = it.autoDownloadOnMobile,
                                anonymousUpdateMetricsEnabled = it.anonymousUpdateMetricsEnabled,
                                lastAnonymousMetricsAttemptVersion =
                                    it.lastAnonymousMetricsAttemptVersion,
                                lastCheckAtEpochMs = it.lastCheckAtEpochMs,
                                // The installed version just changed. Avoid an
                                // old ETag/throttle hiding a newer release that
                                // appeared while PackageInstaller was active.
                                lastCheckBootRealtime = 0L,
                                lastCheckEtag = null,
                                lastCheckResult = CheckResult.NO_UPDATE,
                            )
                        }
                        notifier.cancel()
                    }
                    is InstallOutcome.Failed -> {
                        when (outcome.status) {
                            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                                // User cancelled consent — stay ready to install.
                                automaticInstallInFlight.set(false)
                                preferences.update {
                                    it.copy(pipelineStage = PipelineStage.READY_TO_INSTALL)
                                }
                                info?.let { notifier.showReadyToInstall(it) }
                            }
                            else -> {
                                automaticInstallInFlight.set(false)
                                if (info != null) {
                                    notifier.showInstallError(info, outcome.status, outcome.message)
                                }
                                info?.let { downloader.clearCacheFor(it.versionName) }
                                preferences.update {
                                    it.copy(
                                        pendingDownloadId = null,
                                        pendingDownloadSourceIndex = 0,
                                        pipelineStage = PipelineStage.PENDING_DOWNLOAD,
                                    )
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
    fun onConsentRequired(
        consentIntent: Intent,
        automatic: Boolean,
        onDone: () -> Unit = {},
    ) {
        scope.launch {
            try {
                consentIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (!automatic) {
                    runCatching { context.startActivity(consentIntent) }
                        .onFailure { Log.w(TAG, "Direct consent launch failed", it) }
                }
                val info = preferences.snapshot().pendingUpdate()
                if (info != null) notifier.showConsentRequired(info, consentIntent)
            } finally {
                onDone()
            }
        }
    }

    /** §5.4.3 — one-tap handoff to the Codeberg release page. */
    fun openReleasePage(info: UpdateInfo) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(info.releasePageUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "No browser available for ${info.releasePageUrl}", e)
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

    suspend fun setAutomationMode(mode: UpdateAutomationMode) {
        preferences.update { it.copy(automationMode = mode) }
        if (mode != UpdateAutomationMode.NOTIFY) {
            preferences.snapshot().pendingUpdate()?.let { info -> onNewerUpdateDetected(info) }
        }
    }

    /**
     * Rescue a pipeline stuck in [PipelineStage.INSTALLING].
     *
     * It is the only intermediate stage with no way back: every exit runs off
     * a PackageInstaller callback, and that callback can go missing — the
     * success path itself documents the OS reaping the freshly-replaced
     * process before the coroutine runs, and a reboot before the user answers
     * the consent dialog drops the session entirely. Stuck there, the checker
     * bails out at its own INSTALLING guard, so nothing ever moved again and
     * the UI said "waiting for Android" for good.
     *
     * The version we are running answers it: if it already matches the pending
     * update the install did land and the missing callback was only the
     * bookkeeping; otherwise the APK is still on disk and we go back to
     * READY_TO_INSTALL.
     */
    fun recoverInterruptedInstall() {
        scope.launch {
            val prefs = preferences.snapshot()
            if (prefs.pipelineStage != PipelineStage.INSTALLING) return@launch
            val info = prefs.pendingUpdate()
            automaticInstallInFlight.set(false)
            if (info == null || info.versionName == BuildConfig.VERSION_NAME) {
                Log.i(TAG, "Interrupted install actually succeeded — clearing pipeline")
                info?.let { downloader.clearCacheFor(it.versionName) }
                onInstallOutcome(InstallOutcome.Success)
            } else {
                Log.i(TAG, "Interrupted install did not land — back to READY_TO_INSTALL")
                preferences.update { it.copy(pipelineStage = PipelineStage.READY_TO_INSTALL) }
            }
        }
    }

    fun resumeAutomaticInstallIfReady() {
        scope.launch {
            val prefs = preferences.snapshot()
            if (prefs.automationMode != UpdateAutomationMode.AUTO_INSTALL ||
                prefs.pipelineStage != PipelineStage.READY_TO_INSTALL
            ) return@launch
            val info = prefs.pendingUpdate() ?: return@launch
            val apk = downloader.targetFileFor(info.versionName)
            if (!tryAutomaticInstall(info, apk)) notifier.showReadyToInstall(info)
        }
    }

    private suspend fun tryAutomaticInstall(info: UpdateInfo, apk: java.io.File): Boolean {
        if (!installer.canRequestPackageInstalls()) return false
        if (!automaticInstallInFlight.compareAndSet(false, true)) return true
        preferences.update { it.copy(pipelineStage = PipelineStage.INSTALLING) }
        return when (val install = installer.install(apk, preferNoUserAction = true)) {
            is ApkInstaller.InstallResult.Committed -> {
                Log.i(TAG, "Automatic install session ${install.sessionId} committed")
                true
            }
            is ApkInstaller.InstallResult.Error -> {
                Log.w(TAG, "Automatic install commit failed: ${install.message}")
                automaticInstallInFlight.set(false)
                preferences.update { it.copy(pipelineStage = PipelineStage.READY_TO_INSTALL) }
                false
            }
        }
    }


    suspend fun setAnonymousUpdateMetricsEnabled(enabled: Boolean) =
        anonymousUpdateMetricsMutex.withLock {
            preferences.update { it.copy(anonymousUpdateMetricsEnabled = enabled) }
        }

    suspend fun setAutoDownloadOnMobile(enabled: Boolean) {
        preferences.update { it.copy(autoDownloadOnMobile = enabled) }
        if (enabled) {
            preferences.snapshot().pendingUpdate()?.let { info -> onNewerUpdateDetected(info) }
        }
    }

    companion object {
        private const val TAG = "UpdaterRepository"
        private const val PROGRESS_POLL_MS = 1_500L
    }
}
