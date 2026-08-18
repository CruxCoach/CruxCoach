package com.cruxcoach.android.ui.playlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.settings.DurationStepper
import com.cruxcoach.android.ui.theme.ErrorRed
import com.cruxcoach.android.ui.theme.OrangeAccent

/** Rest duration editor: quick presets plus precise minute/second controls. */
@Composable
internal fun RestDurationDialog(
    title: String,
    initialSeconds: Long,
    affectedCount: Int = 1,
    onConfirm: (Long) -> Unit,
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    var seconds by rememberSaveable(initialSeconds) {
        mutableStateOf(initialSeconds.coerceIn(10L, 3600L).toInt())
    }
    val presets = listOf(30, 60, 120, 180, 300, 600)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (affectedCount > 1) {
                    Text(
                        stringResource(R.string.playlist_rest_edit_multiple, affectedCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                presets.chunked(3).forEach { rowPresets ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowPresets.forEach { preset ->
                            FilterChip(
                                selected = seconds == preset,
                                onClick = { seconds = preset },
                                label = { Text(formatRest(preset.toLong())) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("playlist_rest_preset_$preset"),
                            )
                        }
                    }
                }
                Text(
                    formatRest(seconds.toLong()),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = OrangeAccent,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("playlist_rest_duration_stepper"),
                    contentAlignment = Alignment.Center,
                ) {
                    DurationStepper(
                        seconds = seconds,
                        onChange = { seconds = it },
                        minSeconds = 10,
                        maxSeconds = 3600,
                        minuteLabel = stringResource(R.string.settings_duration_minutes_label),
                        secondLabel = stringResource(R.string.settings_duration_seconds_label),
                        contentDescriptionDecMinute = stringResource(R.string.playlist_rest_decrease_minute),
                        contentDescriptionIncMinute = stringResource(R.string.playlist_rest_increase_minute),
                        contentDescriptionDecSecond = stringResource(R.string.playlist_rest_decrease_second),
                        contentDescriptionIncSecond = stringResource(R.string.playlist_rest_increase_second),
                    )
                }
                Text(
                    stringResource(R.string.playlist_rest_range),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onDelete != null) {
                    TextButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("playlist_rest_delete"),
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = ErrorRed,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(
                                if (affectedCount > 1) {
                                    R.string.playlist_delete_rests
                                } else {
                                    R.string.playlist_delete_rest
                                },
                            ),
                            color = ErrorRed,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(seconds.toLong()) },
                colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                shape = RoundedCornerShape(12.dp),
            ) { Text(stringResource(R.string.action_save), fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

internal fun formatRest(seconds: Long): String {
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return when {
        minutes == 0L -> "$remainingSeconds s"
        remainingSeconds == 0L -> "$minutes min"
        else -> "$minutes min $remainingSeconds s"
    }
}
