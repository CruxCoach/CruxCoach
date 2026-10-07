package com.cruxcoach.android.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.athlete.logic.Units
import com.cruxcoach.athlete.model.UnitSystem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The athlete's unit system, shown next to the language: training, body and nutrition all follow it. */
@HiltViewModel
class UnitsSettingsViewModel @Inject constructor(private val service: AthleteService) : ViewModel() {
    private val _units = MutableStateFlow<UnitSystem?>(null)
    val units: StateFlow<UnitSystem?> = _units.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            service.repo.observeProfile().collect { _units.value = it.units }
        }
    }

    fun select(system: UnitSystem) {
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            service.repo.updateProfile { Units.withUnits(it, system) }
        }
    }
}

@Composable
internal fun UnitsSection(viewModel: UnitsSettingsViewModel) {
    val units by viewModel.units.collectAsStateWithLifecycle()
    Text(
        stringResource(R.string.settings_units_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    Text(stringResource(R.string.settings_units_desc), style = MaterialTheme.typography.bodySmall)
    units?.let { selected ->
        SettingsChoices(
            options = listOf(
                UnitSystem.METRIC to stringResource(R.string.settings_units_metric),
                UnitSystem.IMPERIAL to stringResource(R.string.settings_units_us),
            ),
            selected = selected,
            onSelect = viewModel::select,
            modifier = Modifier.testTag("settings_units"),
        )
    }
}
