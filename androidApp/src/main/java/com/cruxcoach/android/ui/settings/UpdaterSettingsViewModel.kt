package com.cruxcoach.android.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.updater.DeviceSupportGate
import com.cruxcoach.android.updater.InstallSourceGate
import com.cruxcoach.android.updater.PipelineStage
import com.cruxcoach.android.updater.UpdateChecker
import com.cruxcoach.android.updater.UpdateAutomationMode
import com.cruxcoach.android.updater.UpdateNotificationReliabilityHelper
import com.cruxcoach.android.updater.UpdaterRepository
import com.cruxcoach.android.updater.UpdaterState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs [UpdaterSettingsSection] (§6.15). Thin state aggregator: exposes
 * the persisted state + computed view fields (gated info, permission
 * nudge, button-in-flight flag), defers all mutation to
 * [UpdaterRepository].
 */
@HiltViewModel
class UpdaterSettingsViewModel @Inject constructor(
    application: Application,
    private val repository: UpdaterRepository,
    private val installSourceGate: InstallSourceGate,
    private val deviceSupportGate: DeviceSupportGate,
) : AndroidViewModel(application) {

    private val _checkingNow = MutableStateFlow(false)
    val checkingNow: StateFlow<Boolean> = _checkingNow.asStateFlow()

    private val _notificationNudgeVisible = MutableStateFlow(false)
    val notificationNudgeVisible: StateFlow<Boolean> = _notificationNudgeVisible.asStateFlow()

    private val _installPermissionGranted = MutableStateFlow(repository.canRequestPackageInstalls())
    val installPermissionGranted: StateFlow<Boolean> = _installPermissionGranted.asStateFlow()

    val storeGated: Boolean get() = !installSourceGate.selfUpdateAllowed()

    /** False once the next release raises minSdk past this device. */
    val receivesFutureUpdates: Boolean get() = deviceSupportGate.receivesFutureUpdates()

    /** Marketing version of the API level the next release requires. */
    val requiredAndroidVersionName: String
        get() = when (val sdk = deviceSupportGate.requiredSdkInt()) {
            28 -> "9"
            29 -> "10"
            30 -> "11"
            31, 32 -> "12"
            33 -> "13"
            34 -> "14"
            35 -> "15"
            else -> "API $sdk"
        }
    val anonymousUpdateMetricsAvailable: Boolean
        get() = repository.anonymousUpdateMetricsAvailable

    val state: StateFlow<UpdaterState> = repository.state.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        UpdaterState(),
    )

    val downloadProgress: StateFlow<Int?> = repository.downloadProgress
    val downloadDialogRequested: StateFlow<Boolean> = repository.downloadDialogRequested

    fun consumeDownloadDialogRequest() = repository.consumeDownloadDialogRequest()

    val pendingPipelineStage: StateFlow<PipelineStage> =
        state.map { it.pipelineStage }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            PipelineStage.NONE,
        )

    fun refreshNotificationNudge() {
        _notificationNudgeVisible.value =
            UpdateNotificationReliabilityHelper.isBlocked(getApplication())
    }

    fun refreshInstallPermission() {
        _installPermissionGranted.value = repository.canRequestPackageInstalls()
        if (_installPermissionGranted.value) repository.resumeAutomaticUpdateIfReady()
    }

    fun checkNow() {
        if (_checkingNow.value) return
        viewModelScope.launch {
            _checkingNow.value = true
            try {
                repository.checkNow(UpdateChecker.Trigger.MANUAL)
            } finally {
                _checkingNow.value = false
            }
        }
    }

    fun setAutoCheck(enabled: Boolean) = viewModelScope.launch {
        repository.setAutoCheck(enabled)
    }

    fun setAutomationMode(mode: UpdateAutomationMode) = viewModelScope.launch {
        repository.setAutomationMode(mode)
    }


    fun setAutoDownloadOnMobile(enabled: Boolean) = viewModelScope.launch {
        repository.setAutoDownloadOnMobile(enabled)
    }

    fun setAnonymousUpdateMetricsEnabled(enabled: Boolean) = viewModelScope.launch {
        repository.setAnonymousUpdateMetricsEnabled(enabled)
    }

    fun downloadNow() {
        viewModelScope.launch {
            val prefs = repository.snapshot()
            val info = prefs.pendingUpdate() ?: return@launch
            // This path follows an explicit confirmation dialog that includes
            // the APK size, so it may use the currently active transport.
            repository.startDownload(info, allowMobile = true)
        }
    }

    fun installPending() = repository.installPending()

    fun openReleasePage() {
        viewModelScope.launch {
            val info = repository.snapshot().pendingUpdate() ?: return@launch
            repository.openReleasePage(info)
        }
    }
}
