package com.cruxcoach.android.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardLedMode

@Composable
internal fun DisplaySection(
    gradeScale: GradeScale,
    darkMode: DarkModeSetting,
    keepScreenOn: Boolean,
    onGradeScaleChange: (GradeScale) -> Unit,
    onDarkModeChange: (DarkModeSetting) -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit,
) {
    SettingsGroupHeader(stringResource(R.string.settings_display_appearance))
    SettingsChoices(
        options = DarkModeSetting.entries.map { mode -> mode to stringResource(
            when (mode) {
                DarkModeSetting.SYSTEM -> R.string.settings_theme_system
                DarkModeSetting.LIGHT -> R.string.settings_theme_light
                DarkModeSetting.DARK -> R.string.settings_theme_dark
            },
        ) },
        selected = darkMode,
        onSelect = onDarkModeChange,
    )
    HorizontalDivider()
    SettingsGroupHeader(stringResource(R.string.settings_display_grade_scale))
    SettingsChoices(
        options = GradeScale.entries.map { it to it.label },
        selected = gradeScale,
        onSelect = onGradeScaleChange,
        modifier = Modifier.testTag("settings_grade_scale"),
    )
    HorizontalDivider()
    SettingsToggleRow(
        title = stringResource(R.string.settings_ble_keep_screen_on),
        description = stringResource(R.string.settings_ble_keep_screen_on_desc),
        checked = keepScreenOn,
        onCheckedChange = onKeepScreenOnChange,
    )
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
    SettingsInfoHeading(
        title = stringResource(R.string.settings_board_auto_download),
        description = stringResource(R.string.settings_board_auto_download_desc),
    )
    SettingsChoices(
        options = SyncInterval.entries.map { it to stringResource(it.labelRes) },
        selected = syncInterval,
        onSelect = onSyncIntervalChange,
        modifier = Modifier.testTag("settings_sync_interval"),
    )

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
            Text(stringResource(R.string.settings_board_model_change), color = MaterialTheme.colorScheme.primary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoonBoardLedPositionSection(
    ledMode: MoonBoardLedMode,
    onModeChange: (MoonBoardLedMode) -> Unit,
) {
    SettingsInfoHeading(
        title = stringResource(R.string.settings_moonboard_led_position_title),
        description = stringResource(R.string.settings_moonboard_led_position_desc),
    )
    SettingsChoices(
        options = MoonBoardLedMode.entries.map { mode -> mode to stringResource(
            when (mode) {
                MoonBoardLedMode.BELOW -> R.string.settings_moonboard_led_position_below
                MoonBoardLedMode.ABOVE -> R.string.settings_moonboard_led_position_above
                MoonBoardLedMode.BOTH -> R.string.settings_moonboard_led_position_both
            },
        ) },
        selected = ledMode,
        onSelect = onModeChange,
        modifier = Modifier.testTag("settings_moonboard_led_position"),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BoardSendModeSection(
    singleConnectionMode: BoardSendMode,
    multiConnectionMode: BoardSendMode,
    onSingleConnectionModeChange: (BoardSendMode) -> Unit,
    onMultiConnectionModeChange: (BoardSendMode) -> Unit,
) {
    SettingsInfoHeading(
        title = stringResource(R.string.settings_board_send_mode_title),
        description = stringResource(R.string.settings_board_send_mode_desc),
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

}

@Composable
internal fun BoardProjectionLifecycleHint(boardBrand: BoardBrand) {
    SettingsInfoHeading(
        title = stringResource(R.string.settings_board_connections_info),
        description = stringResource(
            if (boardBrand == BoardBrand.MOONBOARD) {
                R.string.settings_board_projection_lifecycle_moonboard
            } else {
                R.string.settings_board_projection_lifecycle_retained
            },
        ),
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
    SettingsChoices(
        options = BoardSendMode.entries.map { option -> option to stringResource(
            if (option == BoardSendMode.AUTOMATIC) R.string.settings_board_send_mode_automatic
            else R.string.settings_board_send_mode_explicit,
        ) },
        selected = mode,
        onSelect = onModeChange,
        modifier = Modifier.testTag(testTag),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BleAutoDisconnectSection(
    bleAutoDisconnectSeconds: Int,
    onAutoDisconnectChange: (Int) -> Unit,
) {
    SettingsInfoHeading(
        title = stringResource(R.string.settings_ble_auto_disconnect_title),
        description = stringResource(R.string.settings_ble_auto_disconnect_desc_retained),
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

    // Keep the switch and its conditional duration control together.
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingsToggleRow(
            title = stringResource(R.string.settings_ble_auto_disconnect_enable),
            description = if (autoDisconnectEnabled) "" else stringResource(R.string.settings_ble_auto_disconnect_off_hint),
            checked = autoDisconnectEnabled,
            onCheckedChange = { on -> onAutoDisconnectChange(if (on) lastEnabledSeconds else 0) },
            modifier = Modifier.testTag("ble_auto_disconnect_toggle"),
        )

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
            else MaterialTheme.colorScheme.primary
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
    SettingsInfoHeading(
        title = stringResource(R.string.settings_page_account),
        description = stringResource(R.string.key_section_description) + "\n\n" +
            stringResource(R.string.key_section_nostr_intro),
    )
    Text(
        stringResource(R.string.settings_account_key_reminder),
        style = MaterialTheme.typography.bodyMedium,
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
