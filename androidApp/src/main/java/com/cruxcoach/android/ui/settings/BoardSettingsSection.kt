package com.cruxcoach.android.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.data.DarkModeSetting
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.SyncInterval
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.OrangeAccent

@Composable
internal fun DisplaySection(
    gradeScale: GradeScale,
    darkMode: DarkModeSetting,
    onGradeScaleChange: (GradeScale) -> Unit,
    onDarkModeChange: (DarkModeSetting) -> Unit
) {
    Text(
        stringResource(R.string.settings_display_appearance),
        style = MaterialTheme.typography.bodyMedium
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DarkModeSetting.entries.forEach { mode ->
            FilterChip(
                selected = darkMode == mode,
                onClick = { onDarkModeChange(mode) },
                label = { Text(mode.label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = OrangeAccent.copy(alpha = 0.2f),
                    selectedLabelColor = OrangeAccent
                )
            )
        }
    }

    Text(
        stringResource(R.string.settings_display_grade_scale),
        style = MaterialTheme.typography.bodyMedium
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag("settings_grade_scale")
    ) {
        GradeScale.entries.forEach { scale ->
            FilterChip(
                selected = gradeScale == scale,
                onClick = { onGradeScaleChange(scale) },
                label = { Text(scale.label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = OrangeAccent.copy(alpha = 0.2f),
                    selectedLabelColor = OrangeAccent
                )
            )
        }
    }
}

@Composable
internal fun BoardSyncSection(
    syncInterval: SyncInterval,
    lastSyncTimestamp: String?,
    onSyncIntervalChange: (SyncInterval) -> Unit,
    onNavigateToSync: () -> Unit
) {
    Text(
        stringResource(R.string.settings_board_data_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )

    lastSyncTimestamp?.let {
        Text(
            stringResource(R.string.settings_board_last_sync, it),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    // Auto-sync interval picker
    Text(stringResource(R.string.settings_board_auto_download), style = MaterialTheme.typography.bodyMedium)
    Text(
        stringResource(R.string.settings_board_auto_download_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag("settings_sync_interval")
    ) {
        SyncInterval.entries.forEach { interval ->
            FilterChip(
                selected = syncInterval == interval,
                onClick = { onSyncIntervalChange(interval) },
                label = { Text(interval.label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = OrangeAccent.copy(alpha = 0.2f),
                    selectedLabelColor = OrangeAccent
                )
            )
        }
    }

    Button(
        onClick = onNavigateToSync,
        modifier = Modifier.fillMaxWidth().testTag("settings_sync_button"),
        colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(stringResource(R.string.settings_board_sync_button), fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun BoardModelSection(
    boardModelName: String,
    onChangeModel: () -> Unit
) {
    Text(
        "Board-Modell",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = boardModelName.ifEmpty { "Nicht konfiguriert" },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onChangeModel) {
            Text("Ändern", color = OrangeAccent)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BleAutoDisconnectSection(
    bleAutoDisconnectMinutes: Int,
    keepScreenOn: Boolean,
    onAutoDisconnectChange: (Int) -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit
) {
    Text(
        stringResource(R.string.settings_ble_auto_disconnect_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )

    Text(
        stringResource(R.string.settings_ble_auto_disconnect_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    val options = listOf(
        0 to stringResource(R.string.settings_ble_disconnect_off),
        1 to stringResource(R.string.settings_ble_disconnect_1min),
        5 to stringResource(R.string.settings_ble_disconnect_5min),
        10 to stringResource(R.string.settings_ble_disconnect_10min),
        15 to stringResource(R.string.settings_ble_disconnect_15min),
        30 to stringResource(R.string.settings_ble_disconnect_30min)
    )

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.testTag("settings_ble_auto_disconnect")
    ) {
        options.forEach { (minutes, label) ->
            FilterChip(
                selected = bleAutoDisconnectMinutes == minutes,
                onClick = { onAutoDisconnectChange(minutes) },
                label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = OrangeAccent.copy(alpha = 0.2f),
                    selectedLabelColor = OrangeAccent
                )
            )
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_ble_keep_screen_on), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.settings_ble_keep_screen_on_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = keepScreenOn,
            onCheckedChange = onKeepScreenOnChange,
            colors = SwitchDefaults.colors(checkedTrackColor = OrangeAccent)
        )
    }
}

@Composable
internal fun AssessmentSection(
    hasAssessment: Boolean,
    onNavigateToAssessment: () -> Unit
) {
    Text(
        stringResource(R.string.settings_assessment_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )

    Text(
        if (hasAssessment)
            stringResource(R.string.settings_assessment_exists)
        else
            stringResource(R.string.settings_assessment_missing),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Button(
        onClick = onNavigateToAssessment,
        modifier = Modifier.fillMaxWidth().testTag("settings_assessment_button"),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (hasAssessment) MaterialTheme.colorScheme.secondaryContainer
            else OrangeAccent
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            if (hasAssessment) stringResource(R.string.settings_assessment_redo) else stringResource(R.string.settings_assessment_start),
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
internal fun AccountKeysSection(
    onNavigateToKeyManagement: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            stringResource(R.string.key_section_nostr_intro),
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Text(
        stringResource(R.string.key_section_description),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    OutlinedButton(
        onClick = onNavigateToKeyManagement,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(stringResource(R.string.key_button_manage))
    }
}
