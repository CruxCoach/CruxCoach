package com.cruxcoach.android.ui.settings

import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import kotlinx.coroutines.CancellationException
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
    private val _unavailable = MutableStateFlow(false)
    /** The training database did not open: the section shows no units row. */
    val unavailable: StateFlow<Boolean> = _unavailable.asStateFlow()

    init {
        guarded(onFailure = { _unavailable.value = true }) {
            service.ensureReady()
            service.repo.observeProfile().collect { _units.value = it.units }
        }
    }

    fun select(system: UnitSystem) {
        guarded {
            service.ensureReady()
            service.repo.updateProfile { Units.withUnits(it, system) }
        }
    }

    /** The settings stay usable when the training database cannot open (full storage, damaged file): no units row then. */
    private fun guarded(onFailure: () -> Unit = {}, block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("UnitsSettings", "Training database unavailable: ${e.javaClass.simpleName}")
                onFailure()
            }
        }
    }
}

@Composable
internal fun UnitsSection(viewModel: UnitsSettingsViewModel) {
    val units by viewModel.units.collectAsStateWithLifecycle()
    val unavailable by viewModel.unavailable.collectAsStateWithLifecycle()
    Text(
        stringResource(R.string.settings_units_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    Text(stringResource(R.string.settings_units_desc), style = MaterialTheme.typography.bodySmall)
    val options = listOf(
        UnitSystem.METRIC to stringResource(R.string.settings_units_metric),
        UnitSystem.IMPERIAL to stringResource(R.string.settings_units_us),
    )
    val selected = units
    when {
        unavailable -> Unit
        selected != null -> SettingsChoices(options, selected, viewModel::select, Modifier.testTag("settings_units"))
        // The units come from the training database a moment after the page opens. Until then
        // the same row holds its place unseen, so the page below does not jump when they arrive
        // (one check mark either way, so the chips wrap the same).
        else -> SettingsChoices(
            options,
            selected = UnitSystem.METRIC,
            onSelect = {},
            modifier = Modifier.testTag("settings_units_loading").alpha(0f).clearAndSetSemantics {},
        )
    }
}
