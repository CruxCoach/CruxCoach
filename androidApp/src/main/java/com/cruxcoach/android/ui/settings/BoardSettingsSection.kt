package com.cruxcoach.android.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.cruxcoach.android.ui.theme.InfoBlue
import com.cruxcoach.android.ui.theme.SuccessGreen
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
    singleConnectionMode: BoardSendMode,
    multiConnectionMode: BoardSendMode,
    boardBrand: BoardBrand,
    onSingleConnectionModeChange: (BoardSendMode) -> Unit,
    onMultiConnectionModeChange: (BoardSendMode) -> Unit,
    onRecheckCapacity: () -> Unit,
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

    BoardSendModePicker(
        label = stringResource(R.string.settings_board_send_mode_single),
        mode = singleConnectionMode,
        onModeChange = onSingleConnectionModeChange,
        testTag = "settings_board_send_mode_single",
    )
    BoardSendModePicker(
        label = stringResource(R.string.settings_board_send_mode_multi),
        mode = multiConnectionMode,
        onModeChange = onMultiConnectionModeChange,
        testTag = "settings_board_send_mode_multi",
    )

    // The stored verdict outlives the hardware: a swapped gym controller, or a
    // simulator moved between modes, otherwise keeps the old answer for good —
    // and the app goes on offering behaviour the board no longer has.
    var recheckDone by remember { mutableStateOf(false) }
    TextButton(
        onClick = { onRecheckCapacity(); recheckDone = true },
        modifier = Modifier.testTag("settings_board_capacity_recheck"),
    ) {
        Text(stringResource(R.string.settings_board_capacity_recheck))
    }
    Text(
        stringResource(
            if (recheckDone) {
                R.string.settings_board_capacity_recheck_done
            } else {
                R.string.settings_board_capacity_recheck_desc
            },
        ),
        style = MaterialTheme.typography.bodySmall,
        color = if (recheckDone) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
    )

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoardSendModePicker(
    label: String,
    mode: BoardSendMode,
    onModeChange: (BoardSendMode) -> Unit,
    testTag: String,
) {
    Text(label, style = MaterialTheme.typography.bodyMedium)
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BleAutoDisconnectSection(
    bleAutoDisconnectSeconds: Int,
    boardBrand: BoardBrand,
    onAutoDisconnectChange: (Int) -> Unit,
) {
    Text(
        stringResource(R.string.settings_ble_auto_disconnect_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )

    if (boardBrand == BoardBrand.MOONBOARD) {
        Surface(
            color = InfoBlue.copy(alpha = 0.10f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().testTag("moonboard_auto_disconnect_info"),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = InfoBlue,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.settings_ble_auto_disconnect_moonboard),
                    style = MaterialTheme.typography.bodySmall,
                    color = InfoBlue,
                )
            }
        }
        return
    }

    Text(
        stringResource(R.string.settings_ble_auto_disconnect_desc_retained),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    // Off is a state of its own, not a duration of zero. It was reachable by
    // stepping the duration down to 0:00, which reads as "disconnect
    // immediately" rather than "never" — the opposite of what it does.
    val autoDisconnectEnabled = bleAutoDisconnectSeconds > 0
    // Remember what the user had set so toggling off and on again does not
    // silently reset their duration to a default.
    var lastEnabledSeconds by rememberSaveable {
        mutableIntStateOf(
            if (bleAutoDisconnectSeconds > 0) bleAutoDisconnectSeconds
            else DEFAULT_AUTO_DISCONNECT_SECONDS
        )
    }
    if (bleAutoDisconnectSeconds > 0 && bleAutoDisconnectSeconds != lastEnabledSeconds) {
        lastEnabledSeconds = bleAutoDisconnectSeconds
    }

    // Switch and stepper belong together — the enclosing settings Column
    // spaces its children 16.dp apart, which pushed them into two islands
    // with a lot of dead air around the toggle. Own Column, own spacing.
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag("ble_auto_disconnect_toggle"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.settings_ble_auto_disconnect_enable),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!autoDisconnectEnabled) {
                Text(
                    stringResource(R.string.settings_ble_auto_disconnect_off_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = autoDisconnectEnabled,
            onCheckedChange = { on ->
                onAutoDisconnectChange(if (on) lastEnabledSeconds else 0)
            },
            colors = SwitchDefaults.colors(checkedTrackColor = OrangeAccent),
        )
    }

    // Single source of truth for the duration: shared DurationStepper.
    // The stepper renders its own current value (Min / Sec ± buttons), so
    // a separate "Or set exactly:" label and a "Duration: …" line above
    // it would only repeat what's already visible. Keep the title + desc
    // (settings_ble_auto_disconnect_*) as the user-facing label and let
    // the stepper own the value display. minSeconds is 1 now: zero is the
    // switch's job, and leaving it reachable here would let the stepper
    // silently contradict the switch. Max 60 min matches the longest old
    // preset × 2 — any larger value is almost certainly a typo.
    if (autoDisconnectEnabled) {
        DurationStepper(
            seconds = bleAutoDisconnectSeconds,
            onChange = onAutoDisconnectChange,
            minSeconds = 1,
            maxSeconds = 3600,
            minuteLabel = stringResource(R.string.settings_duration_minutes_label),
            secondLabel = stringResource(R.string.settings_duration_seconds_label),
        )
    }
    }
}

/** Restored when the auto-disconnect switch is turned back on with no prior
 *  duration to return to. Mirrors SettingsUiState's own default. */
private const val DEFAULT_AUTO_DISCONNECT_SECONDS = 60

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
