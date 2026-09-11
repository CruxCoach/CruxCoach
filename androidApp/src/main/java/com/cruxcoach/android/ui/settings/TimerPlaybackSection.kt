package com.cruxcoach.android.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.cruxcoach.android.R

@Composable
internal fun RoutePlaybackSection(
    routePlayback: RoutePlaybackSettings,
    onFrameSpeedChange: (Float) -> Unit,
    onUseSetterSpeedChange: (Boolean) -> Unit,
    onCountdownChange: (Boolean) -> Unit,
    onCountdownSecondsChange: (Int) -> Unit,
    onAutoLoopChange: (Boolean) -> Unit
) {
    Text(
        stringResource(R.string.settings_playback_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )

    Text(
        stringResource(R.string.settings_playback_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    // Setter speed toggle
    SettingsToggleRow(
        title = stringResource(R.string.settings_playback_setter_speed),
        description = stringResource(R.string.settings_playback_setter_speed_desc),
        checked = routePlayback.useSetterSpeed,
        onCheckedChange = onUseSetterSpeedChange,
    )

    // Frame speed (custom default)
    val speedLabel = if (routePlayback.frameSpeed == routePlayback.frameSpeed.toLong().toFloat()) {
        "${routePlayback.frameSpeed.toInt()}s"
    } else {
        "%.1fs".format(routePlayback.frameSpeed)
    }
    val speedDescription = if (routePlayback.useSetterSpeed) stringResource(R.string.settings_playback_fallback_speed, speedLabel)
        else stringResource(R.string.settings_playback_frame_speed, speedLabel)
    Text(
        speedDescription,
        style = MaterialTheme.typography.bodyMedium
    )
    Text(
        if (routePlayback.useSetterSpeed) stringResource(R.string.settings_playback_fallback_speed_desc)
        else stringResource(R.string.settings_playback_frame_speed_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Slider(
        value = routePlayback.frameSpeed,
        onValueChange = onFrameSpeedChange,
        valueRange = 1f..15f,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = speedDescription }
            .testTag("settings_route_speed"),
        colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary)
    )

    // Countdown toggle
    SettingsToggleRow(
        title = stringResource(R.string.settings_playback_countdown),
        description = stringResource(R.string.settings_playback_countdown_desc),
        checked = routePlayback.countdown,
        onCheckedChange = onCountdownChange,
    )

    // Countdown duration slider (only when enabled)
    if (routePlayback.countdown) {
        val countdownDescription = stringResource(R.string.settings_playback_countdown_duration, routePlayback.countdownSeconds)
        Text(
            countdownDescription,
            style = MaterialTheme.typography.bodyMedium
        )
        Slider(
            value = routePlayback.countdownSeconds.toFloat(),
            onValueChange = { onCountdownSecondsChange(it.toInt()) },
            valueRange = 1f..10f,
            steps = 8,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = countdownDescription },
            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary)
        )
    }

    // Auto-loop toggle
    SettingsToggleRow(
        title = stringResource(R.string.settings_playback_auto_loop),
        description = stringResource(R.string.settings_playback_auto_loop_desc),
        checked = routePlayback.autoLoop,
        onCheckedChange = onAutoLoopChange,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RestTimerSection(
    restTimer: RestTimerSettings,
    onDurationChange: (Int) -> Unit,
    onAutoStartChange: (Boolean) -> Unit
) {
    Text(
        stringResource(R.string.settings_timer_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )

    Text(
        stringResource(R.string.settings_timer_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(4.dp))
    // Stepper renders its own current value, so separate "Duration: …"
    // and "Or set exactly:" lines above it would only repeat what's
    // already visible — both removed.
    DurationStepper(
        seconds = restTimer.durationSeconds,
        onChange = onDurationChange,
        minSeconds = 5,
        maxSeconds = 30 * 60,
        minuteLabel = stringResource(R.string.settings_duration_minutes_label),
        secondLabel = stringResource(R.string.settings_duration_seconds_label),
    )

    // Auto-start toggle
    SettingsToggleRow(
        title = stringResource(R.string.settings_timer_auto_start),
        description = stringResource(R.string.settings_timer_auto_start_desc),
        checked = restTimer.autoStart,
        onCheckedChange = onAutoStartChange,
    )
}
