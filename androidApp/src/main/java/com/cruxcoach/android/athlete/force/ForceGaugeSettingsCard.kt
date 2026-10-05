package com.cruxcoach.android.athlete.force

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.athlete.AthleteService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ForceGaugeSettingsViewModel @Inject constructor(private val service: AthleteService) : ViewModel() {

    val enabled: StateFlow<Boolean> = flow {
        service.ensureReady()
        emitAll(service.repo.observeProfile().map { it.coach.forceGaugeEnabled })
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setEnabled(on: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        service.ensureReady()
        service.repo.updateProfile { it.copy(coach = it.coach.copy(forceGaugeEnabled = on)) }
    }
}

/**
 * Settings entry for the experimental force gauge (Tindeq Progressor): the
 * switch and a button to the measuring screen. For the training settings
 * screen (integrator places it).
 */
@Composable
fun ForceGaugeSettingsCard(
    onOpenForceGauge: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ForceGaugeSettingsViewModel = hiltViewModel(),
) {
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    OutlinedCard(modifier.fillMaxWidth().testTag("force_gauge_card")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.trd_force_card_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.trd_force_card_summary), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = enabled, onCheckedChange = { viewModel.setEnabled(it) }, modifier = Modifier.testTag("force_gauge_switch"))
            }
            if (enabled) {
                FilledTonalButton(onClick = onOpenForceGauge, modifier = Modifier.testTag("force_gauge_open")) {
                    Text(stringResource(R.string.trd_force_card_open))
                }
            }
        }
    }
}
