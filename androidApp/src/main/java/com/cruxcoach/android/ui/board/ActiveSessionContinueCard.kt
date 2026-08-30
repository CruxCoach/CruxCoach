package com.cruxcoach.android.ui.board

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.android.ui.theme.CruxCoachSpacing
import com.cruxcoach.domain.board.ActiveSessionPhase
import com.cruxcoach.domain.board.ActiveSessionState
import com.cruxcoach.domain.board.BoardConnectionState

/** Fixture-friendly compact state; navigation and live clocks remain outside. */
@Composable
fun ActiveSessionContinueCard(
    state: ActiveSessionState,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val phaseLabel: String
    val phaseColor: Color
    val containerColor: Color
    val phaseIcon = when (state.phase) {
        ActiveSessionPhase.ACTIVE -> {
            phaseLabel = stringResource(R.string.session_continue_active)
            phaseColor = CruxCoachDesign.colors.positive
            containerColor = CruxCoachDesign.colors.positiveContainer
            Icons.Default.PlayArrow
        }
        ActiveSessionPhase.PAUSED -> {
            phaseLabel = stringResource(R.string.session_continue_paused)
            phaseColor = CruxCoachDesign.colors.caution
            containerColor = CruxCoachDesign.colors.cautionContainer
            Icons.Default.Pause
        }
        ActiveSessionPhase.RESTING -> {
            phaseLabel = stringResource(R.string.session_continue_resting)
            phaseColor = CruxCoachDesign.colors.caution
            containerColor = CruxCoachDesign.colors.cautionContainer
            Icons.Default.Timer
        }
    }
    val phaseStateDescription = when (state.phase) {
        ActiveSessionPhase.RESTING -> stringResource(
            R.string.session_continue_rest_remaining,
            formatPortableDuration(state.restSecondsRemaining ?: 0),
        )
        else -> phaseLabel
    }

    Surface(
        onClick = onContinue,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = CruxCoachSpacing.minimumTouchTarget)
            .semantics(mergeDescendants = true) {
                stateDescription = phaseStateDescription
            }
            .testTag("session_continue"),
        shape = CruxCoachDesign.shapes.large,
        color = containerColor,
    ) {
        Column(
            modifier = Modifier.padding(CruxCoachSpacing.large),
            verticalArrangement = Arrangement.spacedBy(CruxCoachSpacing.small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(CruxCoachSpacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(phaseIcon, contentDescription = null, tint = phaseColor)
                Text(
                    text = phaseLabel,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = phaseColor,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.session_continue_action),
                    style = MaterialTheme.typography.labelLarge,
                    color = CruxCoachDesign.colors.brandAccent,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = CruxCoachDesign.colors.brandAccent,
                )
            }

            state.currentClimb?.let { climb ->
                Text(
                    text = climb.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.playlist_angle_label, climb.angle.toInt()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } ?: Text(
                text = stringResource(R.string.session_continue_no_current_climb),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.phase == ActiveSessionPhase.RESTING) {
                Text(
                    text = stringResource(
                        R.string.session_continue_rest_remaining,
                        formatPortableDuration(state.restSecondsRemaining ?: 0),
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    color = phaseColor,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = stringResource(
                    R.string.session_continue_metrics,
                    formatPortableDuration(state.activeSeconds),
                    state.sendCount,
                    state.attemptCount,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.connection != BoardConnectionState.CONNECTED) {
                Text(
                    text = stringResource(
                        if (state.connection == BoardConnectionState.CONNECTING) {
                            R.string.board_ble_connecting
                        } else {
                            R.string.ble_disconnected
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal fun formatPortableDuration(totalSeconds: Long): String {
    val safeSeconds = totalSeconds.coerceAtLeast(0)
    val hours = safeSeconds / 3_600
    val minutes = (safeSeconds % 3_600) / 60
    val seconds = safeSeconds % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}
