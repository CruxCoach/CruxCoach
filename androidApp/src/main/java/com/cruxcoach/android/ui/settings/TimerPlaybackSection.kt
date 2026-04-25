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
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.OrangeAccent

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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_playback_setter_speed), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.settings_playback_setter_speed_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = routePlayback.useSetterSpeed,
            onCheckedChange = onUseSetterSpeedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = OrangeAccent)
        )
    }

    // Frame speed (custom default)
    val speedLabel = if (routePlayback.frameSpeed == routePlayback.frameSpeed.toLong().toFloat()) {
        "${routePlayback.frameSpeed.toInt()}s"
    } else {
        "%.1fs".format(routePlayback.frameSpeed)
    }
    Text(
        if (routePlayback.useSetterSpeed) stringResource(R.string.settings_playback_fallback_speed, speedLabel)
        else stringResource(R.string.settings_playback_frame_speed, speedLabel),
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
            .testTag("settings_route_speed"),
        colors = SliderDefaults.colors(thumbColor = OrangeAccent, activeTrackColor = OrangeAccent)
    )

    // Countdown toggle
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(stringResource(R.string.settings_playback_countdown), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.settings_playback_countdown_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = routePlayback.countdown,
            onCheckedChange = onCountdownChange,
            colors = SwitchDefaults.colors(checkedTrackColor = OrangeAccent)
        )
    }

    // Countdown duration slider (only when enabled)
    if (routePlayback.countdown) {
        Text(
            stringResource(R.string.settings_playback_countdown_duration, routePlayback.countdownSeconds),
            style = MaterialTheme.typography.bodyMedium
        )
        Slider(
            value = routePlayback.countdownSeconds.toFloat(),
            onValueChange = { onCountdownSecondsChange(it.toInt()) },
            valueRange = 1f..10f,
            steps = 8,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(thumbColor = OrangeAccent, activeTrackColor = OrangeAccent)
        )
    }

    // Auto-loop toggle
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(stringResource(R.string.settings_playback_auto_loop), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.settings_playback_auto_loop_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = routePlayback.autoLoop,
            onCheckedChange = onAutoLoopChange,
            colors = SwitchDefaults.colors(checkedTrackColor = OrangeAccent)
        )
    }
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(stringResource(R.string.settings_timer_auto_start), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.settings_timer_auto_start_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = restTimer.autoStart,
            onCheckedChange = onAutoStartChange,
            colors = SwitchDefaults.colors(checkedTrackColor = OrangeAccent)
        )
    }
}
