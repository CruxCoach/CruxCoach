package com.cruxcoach.android.updater

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
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
import javax.inject.Inject
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
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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

    fun requestDownloadDialog() {
        _downloadDialogRequested.value = true
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
        return outcome
    }

    private suspend fun onNewerUpdateDetected(info: UpdateInfo) {
        // No auto-download: every download is gated behind an in-app
        // confirmation dialog. Notification brings the user into the app.
        notifier.showPendingDownload(info)
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
                val status = downloader.query(id) ?: break
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

    /** Called by [ApkInstallStatusReceiver]. */
    fun onInstallOutcome(outcome: InstallOutcome) {
        scope.launch {
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

    /** §6.10 re-arm. Simple stage machine: +24h, then 72h × 10, then 30d forever. */
    suspend fun onNotificationDismissed() {
        preferences.update {
            it.copy(
                notifDismissedAtEpochMs = System.currentTimeMillis(),
                notifReArmCount = it.notifReArmCount + 1,
            )
        }
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
