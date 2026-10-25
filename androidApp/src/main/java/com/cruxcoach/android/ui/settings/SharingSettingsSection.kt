package com.cruxcoach.android.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Group
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.sharing.SharingFeature
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SharingFeatureViewModel @Inject constructor(private val feature: SharingFeature) : ViewModel() {
    val available: Boolean get() = feature.available
    val enabled: StateFlow<Boolean> = feature.enabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { feature.setEnabled(enabled) }
    }
}

/**
 * FEAT-062 entry point in Settings, on the account page next to the account
 * keys — a sharing relationship is bound to the npub, so that is where people
 * look for it. Unfinished: only debug (test) builds show the preview switch,
 * and the destination appears only once it is on. Release builds show nothing.
 */
@Composable
internal fun SharingSettingsSection(
    onNavigateToSharing: () -> Unit,
    viewModel: SharingFeatureViewModel = hiltViewModel(),
) {
    if (!viewModel.available) return
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    SharingSettingsContent(enabled, onEnabledChange = viewModel::setEnabled, onNavigateToSharing = onNavigateToSharing)
}

@Composable
internal fun SharingSettingsContent(enabled: Boolean, onEnabledChange: (Boolean) -> Unit, onNavigateToSharing: () -> Unit) {
    Column {
        SettingsToggleRow(
            title = stringResource(R.string.sharing_feature_preview_title),
            description = stringResource(R.string.sharing_feature_preview_summary),
            checked = enabled,
            onCheckedChange = onEnabledChange,
            modifier = Modifier.padding(horizontal = 16.dp).testTag("settings_sharing_preview"),
        )
        if (enabled) {
            SettingsDestinationRow(
                title = stringResource(R.string.sharing_settings_entry),
                summary = stringResource(R.string.sharing_settings_summary),
                icon = Icons.Outlined.Group,
                modifier = Modifier.testTag("settings_open_personal_sharing"),
                onClick = onNavigateToSharing,
            )
        }
    }
}
