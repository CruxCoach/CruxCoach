package com.cruxcoach.android.athlete.health

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.android.ui.training.formatNumber
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HealthConnectCardState(
    val enabled: Boolean = false,
    val availability: HealthConnectAvailability = HealthConnectAvailability.UNSUPPORTED,
    val permitted: Boolean = false,
    val syncing: Boolean = false,
    val lastSync: HealthSyncResult? = null,
    val sleepHours: Double? = null,
)

@HiltViewModel
class HealthConnectSettingsViewModel @Inject constructor(
    private val source: HealthConnectSource,
    private val service: AthleteService,
) : ViewModel() {

    private val _state = MutableStateFlow(HealthConnectCardState())
    val state: StateFlow<HealthConnectCardState> = _state.asStateFlow()

    val permissionContract = source.permissionContract()

    init {
        refresh()
        viewModelScope.launch { source.lastSync.collect { r -> _state.update { it.copy(lastSync = r) } } }
    }

    fun refresh() = viewModelScope.launch(Dispatchers.IO) {
        service.ensureReady()
        val enabled = service.repo.profile().coach.healthConnectEnabled
        val availability = source.availability()
        val permitted = availability == HealthConnectAvailability.AVAILABLE && source.hasAllPermissions()
        _state.update { it.copy(enabled = enabled, availability = availability, permitted = permitted) }
    }

    /** Turning on only stores the wish; the screen asks for permissions when they are missing. */
    fun setEnabled(on: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        service.ensureReady()
        service.repo.updateProfile { it.copy(coach = it.coach.copy(healthConnectEnabled = on)) }
        _state.update { it.copy(enabled = on) }
        if (on && _state.value.permitted) syncNow()
    }

    fun onPermissionsResult(granted: Set<String>) {
        val permitted = granted.containsAll(HealthConnectSource.PERMISSIONS)
        _state.update { it.copy(permitted = permitted) }
        if (permitted) syncNow() else refresh()
    }

    fun installIntent() = source.installIntent()
    fun settingsIntent() = source.settingsIntent()

    fun syncNow() = viewModelScope.launch(Dispatchers.IO) {
        _state.update { it.copy(syncing = true) }
        source.syncClimbing()
        val sleep = source.lastNightSleepHours(service.today())
        _state.update { it.copy(syncing = false, sleepHours = sleep) }
    }
}

/**
 * Settings card for the optional Health Connect link: on/off, install or
 * update hint, permission request, manual sync with the last result, and
 * the privacy line. For the training settings screen (integrator places it).
 */
@Composable
fun HealthConnectSettingsCard(
    modifier: Modifier = Modifier,
    viewModel: HealthConnectSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(viewModel.permissionContract) { granted -> viewModel.onPermissionsResult(granted) }
    // Back from Health Connect's own settings: access may have changed there.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    fun open(intent: android.content.Intent, fallback: android.content.Intent? = null) {
        try { context.startActivity(intent) } catch (_: ActivityNotFoundException) {
            fallback?.let { runCatching { context.startActivity(it) } }
        } catch (_: SecurityException) { }
    }

    OutlinedCard(modifier.fillMaxWidth().testTag("health_connect_card")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.trd_hc_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.trd_hc_summary), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = state.enabled,
                    onCheckedChange = { on ->
                        viewModel.setEnabled(on)
                        if (on && state.availability == HealthConnectAvailability.AVAILABLE && !state.permitted) {
                            runCatching { launcher.launch(HealthConnectSource.PERMISSIONS) }
                        }
                    },
                    enabled = state.availability != HealthConnectAvailability.UNSUPPORTED || state.enabled,
                    modifier = Modifier.testTag("health_connect_switch"),
                )
            }
            when (state.availability) {
                HealthConnectAvailability.UNSUPPORTED ->
                    Text(stringResource(R.string.trd_hc_unsupported), style = MaterialTheme.typography.bodySmall)
                HealthConnectAvailability.NEEDS_INSTALL_OR_UPDATE -> {
                    Text(stringResource(R.string.trd_hc_install_hint), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { open(viewModel.installIntent(), android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://play.google.com/store/apps/details?id=${HealthConnectSource.PROVIDER_PACKAGE}"))) },
                        modifier = Modifier.testTag("health_connect_install")) {
                        Text(stringResource(R.string.trd_hc_install))
                    }
                }
                HealthConnectAvailability.AVAILABLE -> if (state.enabled) {
                    if (!state.permitted) {
                        Text(stringResource(R.string.trd_hc_permission_hint), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { runCatching { launcher.launch(HealthConnectSource.PERMISSIONS) } },
                            modifier = Modifier.testTag("health_connect_permit")) {
                            Text(stringResource(R.string.trd_hc_permit))
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = viewModel::syncNow, enabled = !state.syncing,
                                modifier = Modifier.testTag("health_connect_sync")) {
                                Text(stringResource(if (state.syncing) R.string.trd_hc_syncing else R.string.trd_hc_sync_now))
                            }
                            TextButton(onClick = { open(viewModel.settingsIntent()) }) {
                                Text(stringResource(R.string.trd_hc_manage))
                            }
                        }
                        state.lastSync?.let { r ->
                            Text(
                                if (r.failed) stringResource(R.string.trd_hc_sync_failed)
                                else stringResource(R.string.trd_hc_sync_result, r.added, r.updated, r.skippedManual),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("health_connect_result"),
                            )
                        }
                        state.sleepHours?.let { h ->
                            Text(stringResource(R.string.trd_hc_sleep_last_night, formatNumber(h)),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Text(stringResource(R.string.trd_hc_privacy), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
