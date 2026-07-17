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
import com.cruxcoach.android.data.BoardSendMode
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.SyncInterval
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.domain.board.BoardBrand

@Composable
internal fun DisplaySection(
    gradeScale: GradeScale,
    darkMode: DarkModeSetting,
    keepScreenOn: Boolean,
    onGradeScaleChange: (GradeScale) -> Unit,
    onDarkModeChange: (DarkModeSetting) -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit,
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
internal fun BoardSyncSection(
    syncInterval: SyncInterval,
    onSyncIntervalChange: (SyncInterval) -> Unit,
) {
    Text(
        stringResource(R.string.settings_board_data_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )

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
                label = { Text(stringResource(interval.labelRes)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = OrangeAccent.copy(alpha = 0.2f),
                    selectedLabelColor = OrangeAccent
                )
            )
        }
    }

    // Actual download card (progress, re-sync, errors, board-model picker)
    // lives below this section in SettingsScreen, embedded inline via
    // BoardSyncInlineCard — no separate screen navigation.
}

@Composable
internal fun BoardModelSection(
    boardModelName: String,
    onChangeModel: () -> Unit,
) {
    Text(
        stringResource(R.string.settings_board_model_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = boardModelName.ifEmpty { stringResource(R.string.settings_board_model_not_configured) },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onChangeModel) {
            Text(stringResource(R.string.settings_board_model_change), color = OrangeAccent)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BoardSendModeSection(
    mode: BoardSendMode,
    boardBrand: BoardBrand,
    onModeChange: (BoardSendMode) -> Unit,
) {
    Text(
        stringResource(R.string.settings_board_send_mode_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    Text(
        stringResource(R.string.settings_board_send_mode_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_board_send_mode"),
    ) {
        BoardSendMode.entries.forEachIndexed { index, option ->
            SegmentedButton(
                selected = mode == option,
                onClick = { onModeChange(option) },
                shape = SegmentedButtonDefaults.itemShape(index, BoardSendMode.entries.size),
                label = {
                    Text(
                        stringResource(
                            if (option == BoardSendMode.AUTOMATIC) {
                                R.string.settings_board_send_mode_automatic
                            } else {
                                R.string.settings_board_send_mode_explicit
                            },
                        ),
                    )
                },
            )
        }
    }

    Text(
        text = stringResource(
            if (boardBrand == BoardBrand.MOONBOARD) {
                R.string.settings_board_projection_lifecycle_moonboard
            } else {
                R.string.settings_board_projection_lifecycle_retained
            },
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BleAutoDisconnectSection(
    bleAutoDisconnectSeconds: Int,
    onAutoDisconnectChange: (Int) -> Unit,
) {
    Text(
        stringResource(R.string.settings_ble_auto_disconnect_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )

    Text(
        stringResource(R.string.settings_ble_auto_disconnect_desc_retained),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(8.dp))
    // Single source of truth for the duration: shared DurationStepper.
    // The stepper renders its own current value (Min / Sec ± buttons), so
    // a separate "Or set exactly:" label and a "Duration: …" line above
    // it would only repeat what's already visible. Keep the title + desc
    // (settings_ble_auto_disconnect_*) as the user-facing label and let
    // the stepper own the value display. `0 = off` reachable via
    // minSeconds = 0; max 60 min matches the longest old preset × 2 —
    // any larger value is almost certainly a typo.
    DurationStepper(
        seconds = bleAutoDisconnectSeconds,
        onChange = onAutoDisconnectChange,
        minSeconds = 0,
        maxSeconds = 3600,
        minuteLabel = stringResource(R.string.settings_duration_minutes_label),
        secondLabel = stringResource(R.string.settings_duration_seconds_label),
    )

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
    onNavigateToKeyManagement: () -> Unit,
    onNavigateToNostrProfile: () -> Unit = {},
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
        onClick = onNavigateToNostrProfile,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(stringResource(R.string.nostr_profile_settings_label))
    }
    OutlinedButton(
        onClick = onNavigateToKeyManagement,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(stringResource(R.string.key_button_manage))
    }
}
