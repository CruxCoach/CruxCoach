package com.cruxcoach.android.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.data.BoardSessionManager
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.ErrorRed
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.ui.theme.SuccessGreen
import com.cruxcoach.android.ui.theme.WarningYellow

val LocalBoardSessionManager = staticCompositionLocalOf<BoardSessionManager> {
    error("BoardSessionManager not provided")
}

/**
 * Self-contained rest timer banner that reads from [LocalBoardSessionManager].
 * Shows nothing when the timer is idle. Place inside each screen's topBar Column
 * (below TopAppBar) so it appears under the app bar on every screen.
 */
@Composable
fun RestTimerBannerSlot() {
    val sessionManager = LocalBoardSessionManager.current
    val restTimerState by sessionManager.restTimer.collectAsStateWithLifecycle()

    if (!restTimerState.isRunning && !restTimerState.isFinished) return

    val progress = if (restTimerState.totalSeconds > 0) {
        restTimerState.secondsRemaining.toFloat() / restTimerState.totalSeconds
    } else 0f
    val timerColor = when {
        restTimerState.isFinished -> SuccessGreen
        restTimerState.secondsRemaining <= 10 -> ErrorRed
        restTimerState.secondsRemaining <= 30 -> WarningYellow
        else -> OrangeAccent
    }

    Surface(
        color = timerColor.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Timer,
                        contentDescription = null,
                        tint = timerColor,
                        modifier = Modifier.size(18.dp)
                    )
                    if (restTimerState.isFinished) {
                        Text(
                            stringResource(R.string.rest_timer_done),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = timerColor
                        )
                    } else {
                        val sec = restTimerState.secondsRemaining
                        val min = sec / 60
                        val s = sec % 60
                        Text(
                            if (min > 0) "%d:%02d".format(min, s) else "${s}s",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = timerColor
                        )
                        Text(
                            stringResource(R.string.rest_timer_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(
                    onClick = {
                        if (restTimerState.isFinished) sessionManager.dismissRestTimerFinished()
                        else sessionManager.cancelRestTimer()
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = if (restTimerState.isFinished) stringResource(R.string.cd_dismiss) else stringResource(R.string.cd_cancel_timer),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            if (!restTimerState.isFinished) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .padding(top = 2.dp),
                    color = timerColor,
                    trackColor = timerColor.copy(alpha = 0.15f),
                    strokeCap = StrokeCap.Round
                )
            }
        }
    }
}
