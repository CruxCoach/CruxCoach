package com.cruxcoach.android.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R

/**
 * Info affordance for the Kilter account / import option: an ⓘ icon button
 * that explains how data is exchanged with Kilter — what signing in does, what
 * stays local on the device, and what is written back to Kilter. Mirrors the
 * board-filter menu's info button (0.2.0). Place it next to the Kilter login /
 * import header in onboarding and in Settings.
 */
@Composable
fun KilterDataInfoButton(modifier: Modifier = Modifier) {
    var show by rememberSaveable { mutableStateOf(false) }
    IconButton(
        onClick = { show = true },
        modifier = modifier.testTag("kilter_data_info_button"),
    ) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = stringResource(R.string.kilter_data_info_action),
        )
    }
    if (show) {
        val scrollState = rememberScrollState()
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text(stringResource(R.string.kilter_data_info_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(scrollState),
                ) {
                    InfoText(
                        listOf(
                            stringResource(R.string.kilter_data_info_login),
                            stringResource(R.string.kilter_data_info_offline),
                            stringResource(R.string.kilter_data_info_local),
                            stringResource(R.string.kilter_data_info_publish),
                        ).joinToString("\n\n"),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { show = false }) {
                    Text(stringResource(R.string.action_ok))
                }
            },
        )
    }
}
