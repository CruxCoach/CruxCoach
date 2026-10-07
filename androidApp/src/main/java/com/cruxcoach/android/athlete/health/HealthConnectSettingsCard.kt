package com.cruxcoach.android.athlete.health

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
    /** Coach reads sleep and climbing sessions (CoachProfile.healthConnectEnabled). */
    val enabled: Boolean = false,
    /** Nutrition writes meals and water (AthleteProfile.healthConnectExport). */
    val exportEnabled: Boolean = false,
    val availability: HealthConnectAvailability = HealthConnectAvailability.UNSUPPORTED,
    val permitted: Boolean = false,
    val exportPermitted: Boolean = false,
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
        val profile = service.repo.profile()
        val availability = source.availability()
        val granted = if (availability == HealthConnectAvailability.AVAILABLE) source.grantedPermissions() else emptySet()
        _state.update {
            it.copy(
                enabled = profile.coach.healthConnectEnabled, exportEnabled = profile.healthConnectExport,
                availability = availability,
                permitted = granted.containsAll(HealthConnectSource.PERMISSIONS),
                exportPermitted = granted.containsAll(HealthConnectExporter.PERMISSIONS),
            )
        }
    }

    /** Turning on only stores the wish; the screen asks for permissions when they are missing. */
    fun setEnabled(on: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        service.ensureReady()
        service.repo.updateProfile { it.copy(coach = it.coach.copy(healthConnectEnabled = on)) }
        _state.update { it.copy(enabled = on) }
        if (on && _state.value.permitted) syncNow()
    }

    /** The nutrition export; the shown day is written when nutrition opens or changes. */
    fun setExportEnabled(on: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        service.ensureReady()
        service.repo.updateProfile { it.copy(healthConnectExport = on) }
        _state.update { it.copy(exportEnabled = on) }
    }

    fun onPermissionsResult(granted: Set<String>) {
        val readNow = granted.containsAll(HealthConnectSource.PERMISSIONS)
        // The result lists only what this request granted; re-read the rest.
        refresh()
        if (readNow && _state.value.enabled) syncNow()
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
 * The app's one place for Health Connect (training settings): one switch per
 * direction – the coach reads sleep and climbing sessions, nutrition writes
 * meals and water – each with its own permissions, the install or update
 * hint for Android 9–13, a manual sync with the last result, and the
 * privacy line.
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
    val available = state.availability == HealthConnectAvailability.AVAILABLE
    fun request(permissions: Set<String>) { runCatching { launcher.launch(permissions) } }

    OutlinedCard(modifier.fillMaxWidth().testTag("health_connect_card")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.trd_hc_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.trd_hc_summary), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            DirectionSwitch(
                title = stringResource(R.string.trd_hc_read_switch),
                hint = stringResource(R.string.trd_hc_read_hint),
                checked = state.enabled,
                enabled = state.availability != HealthConnectAvailability.UNSUPPORTED || state.enabled,
                tag = "health_connect_switch",
            ) { on ->
                viewModel.setEnabled(on)
                if (on && available && !state.permitted) request(HealthConnectSource.PERMISSIONS)
            }
            DirectionSwitch(
                title = stringResource(R.string.trf_hc_switch),
                hint = stringResource(R.string.trf_hc_hint),
                checked = state.exportEnabled,
                enabled = state.availability != HealthConnectAvailability.UNSUPPORTED || state.exportEnabled,
                tag = "health_connect_export_switch",
            ) { on ->
                viewModel.setExportEnabled(on)
                if (on && available && !state.exportPermitted) request(HealthConnectExporter.PERMISSIONS)
            }
            when (state.availability) {
                HealthConnectAvailability.UNSUPPORTED ->
                    Text(stringResource(R.string.trd_hc_unsupported), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("health_connect_unavailable"))
                HealthConnectAvailability.NEEDS_INSTALL_OR_UPDATE -> {
                    Text(stringResource(R.string.trd_hc_install_hint), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { open(viewModel.installIntent(), android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://play.google.com/store/apps/details?id=${HealthConnectSource.PROVIDER_PACKAGE}"))) },
                        modifier = Modifier.testTag("health_connect_install")) {
                        Text(stringResource(R.string.trd_hc_install))
                    }
                }
                HealthConnectAvailability.AVAILABLE -> if (state.enabled || state.exportEnabled) {
                    // Permissions still missing for a switched-on direction.
                    val missing = buildSet {
                        if (state.enabled && !state.permitted) addAll(HealthConnectSource.PERMISSIONS)
                        if (state.exportEnabled && !state.exportPermitted) addAll(HealthConnectExporter.PERMISSIONS)
                    }
                    if (missing.isNotEmpty()) {
                        Text(stringResource(R.string.trd_hc_permission_hint), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { request(missing) }, modifier = Modifier.testTag("health_connect_permit")) {
                            Text(stringResource(R.string.trd_hc_permit))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.enabled && state.permitted) {
                            FilledTonalButton(onClick = viewModel::syncNow, enabled = !state.syncing,
                                modifier = Modifier.testTag("health_connect_sync")) {
                                Text(stringResource(if (state.syncing) R.string.trd_hc_syncing else R.string.trd_hc_sync_now))
                            }
                        }
                        TextButton(onClick = { open(viewModel.settingsIntent()) }) {
                            Text(stringResource(R.string.trd_hc_manage))
                        }
                    }
                    if (state.enabled && state.permitted) {
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

@Composable
private fun DirectionSwitch(title: String, hint: String, checked: Boolean, enabled: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    // The whole row toggles, so TalkBack reads the title with the switch state.
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(start = 8.dp))
    }
}
