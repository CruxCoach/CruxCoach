package com.cruxcoach.android.ui.board

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.settings.DurationStepper
import com.cruxcoach.android.ui.theme.ErrorRed
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.ui.theme.SuccessGreen
import com.cruxcoach.android.ui.theme.WarningYellow

@Composable
internal fun RestTimerCard(
    secondsRemaining: Int,
    totalSeconds: Int,
    isFinished: Boolean,
    onCancel: () -> Unit,
    onDismissFinished: () -> Unit
) {
    val progress = if (totalSeconds > 0) secondsRemaining.toFloat() / totalSeconds else 0f
    val timerColor = when {
        isFinished -> SuccessGreen
        secondsRemaining <= 10 -> ErrorRed
        secondsRemaining <= 30 -> WarningYellow
        else -> OrangeAccent
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("boarddetail_rest_timer"),
        colors = CardDefaults.cardColors(
            containerColor = if (isFinished) SuccessGreen.copy(alpha = 0.1f)
                             else timerColor.copy(alpha = 0.08f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Default.Timer,
                        contentDescription = null,
                        tint = timerColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        stringResource(if (isFinished) R.string.board_rest_timer_finished else R.string.board_rest_timer_rest),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = timerColor
                    )
                }
                IconButton(
                    onClick = if (isFinished) onDismissFinished else onCancel,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.cd_cancel_timer),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (!isFinished) {
                // Big countdown
                Text(
                    formatTimerDuration(secondsRemaining),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = timerColor
                )

                // Progress bar
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = timerColor,
                    trackColor = timerColor.copy(alpha = 0.15f),
                    strokeCap = StrokeCap.Round
                )
            } else {
                Text(
                    stringResource(R.string.board_rest_timer_ready),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = SuccessGreen
                )
            }
        }
    }
}

internal fun formatTimerDuration(totalSeconds: Int): String {
    val min = totalSeconds / 60
    val sec = totalSeconds % 60
    return if (min > 0) "%d:%02d".format(min, sec) else "${sec}s"
}

/**
 * Per-use custom rest duration picker for the detail screen. Pre-filled
 * with the settings default but does NOT persist — the settings value
 * stays the default + the post-logging auto-start duration.
 */
@Composable
internal fun RestTimerStartDialog(
    initialSeconds: Int,
    onStart: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var seconds by remember { mutableIntStateOf(initialSeconds.coerceAtLeast(5)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.rest_timer_set_title), fontWeight = FontWeight.Bold)
        },
        text = {
            DurationStepper(
                seconds = seconds,
                onChange = { seconds = it },
                minSeconds = 5,
                maxSeconds = 60 * 60,
                minuteLabel = stringResource(R.string.settings_duration_minutes_label),
                secondLabel = stringResource(R.string.settings_duration_seconds_label),
            )
        },
        confirmButton = {
            Button(
                onClick = { onStart(seconds) },
                colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(stringResource(R.string.rest_timer_start), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
