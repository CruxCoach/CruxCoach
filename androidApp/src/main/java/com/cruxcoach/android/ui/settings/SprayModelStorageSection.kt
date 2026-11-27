package com.cruxcoach.android.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.spraywall.HoldModel
import com.cruxcoach.android.spraywall.HoldModelStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The spray-wall hold model on the phone (FEAT-078). It can go: hold finding
 * then uses the classical detector until the model is downloaded again.
 */
@HiltViewModel
class SprayModelStorageViewModel @Inject constructor(
    private val models: HoldModelStore,
) : ViewModel() {
    val model: StateFlow<HoldModelStore.State> = models.state

    fun remove() {
        viewModelScope.launch { models.remove() }
    }
}

/** Shown only while the model is downloaded. */
@Composable
internal fun SprayModelStorageSection(onRemove: () -> Unit) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("settings_spray_storage")) {
        Text(stringResource(R.string.spray_settings_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        StorageRow(
            label = stringResource(R.string.spray_settings_model, Formatter.formatShortFileSize(context, HoldModel.BYTES)),
            hint = stringResource(R.string.spray_settings_model_hint),
            enabled = true,
            tag = "settings_spray_model_remove",
            onRemove = onRemove,
        )
    }
}
