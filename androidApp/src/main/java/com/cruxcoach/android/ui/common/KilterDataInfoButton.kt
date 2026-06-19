package com.cruxcoach.android.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
    var show by remember { mutableStateOf(false) }
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
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text(stringResource(R.string.kilter_data_info_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.kilter_data_info_login),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.kilter_data_info_offline),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.kilter_data_info_local),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.kilter_data_info_publish),
                        style = MaterialTheme.typography.bodyMedium,
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
