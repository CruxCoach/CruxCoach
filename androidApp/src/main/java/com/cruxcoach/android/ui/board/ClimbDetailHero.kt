package com.cruxcoach.android.ui.board

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.android.ui.theme.CruxCoachSpacing
import com.cruxcoach.domain.board.BoardConnectionState
import com.cruxcoach.domain.board.BoardDeliveryTarget
import com.cruxcoach.domain.board.ClimbDetailScreenState

/** Isolated board-first candidate; the full production detail screen is not wired to it yet. */
@Composable
fun ClimbDetailHero(
    state: ClimbDetailScreenState.Content,
    gradeLabel: String,
    boardLabel: String,
    onConnect: () -> Unit,
    onDeliver: () -> Unit,
    onLogAttempt: () -> Unit,
    onLogSend: () -> Unit,
    modifier: Modifier = Modifier,
    boardContent: @Composable () -> Unit,
) {
    val spacing = CruxCoachDesign.spacing
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(spacing.large),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.large),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .heightIn(min = 240.dp, max = 360.dp)
                .testTag("detail_board_hero"),
            shape = CruxCoachDesign.shapes.large,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box(contentAlignment = Alignment.Center) { boardContent() }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.xSmall),
        ) {
            Text(
                text = state.identity.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            state.identity.setterName?.let { setter ->
                Text(
                    text = stringResource(R.string.board_detail_by_setter, setter),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(
                    R.string.detail_hero_metadata,
                    gradeLabel,
                    state.identity.angle,
                    boardLabel,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        DeliveryControl(state, onConnect, onDeliver)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            OutlinedButton(
                onClick = onLogAttempt,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = spacing.minimumTouchTarget)
                    .testTag("detail_log_attempt"),
            ) { Text(stringResource(R.string.board_log_attempt)) }
            Button(
                onClick = onLogSend,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = spacing.minimumTouchTarget)
                    .testTag("detail_log_send"),
            ) { Text(stringResource(R.string.board_log_send)) }
        }
    }
}

@Composable
private fun DeliveryControl(
    state: ClimbDetailScreenState.Content,
    onConnect: () -> Unit,
    onDeliver: () -> Unit,
) {
    val delivery = state.delivery
    val stateLabel = when {
        delivery.isSending -> stringResource(R.string.board_detail_sending)
        delivery.isSent -> stringResource(R.string.board_detail_sent)
        delivery.connection == BoardConnectionState.CONNECTING ->
            stringResource(R.string.board_ble_connecting)
        delivery.connection == BoardConnectionState.DISCONNECTED ->
            stringResource(R.string.ble_disconnected)
        else -> stringResource(R.string.detail_delivery_ready)
    }
    val modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = CruxCoachSpacing.minimumTouchTarget)
        .semantics { stateDescription = stateLabel }
        .testTag("detail_delivery")

    when {
        delivery.isSending -> OutlinedButton(onClick = {}, enabled = false, modifier = modifier) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(end = CruxCoachSpacing.small)
                    .size(20.dp),
            )
            Text(stateLabel)
        }
        delivery.connection == BoardConnectionState.CONNECTING ->
            OutlinedButton(onClick = {}, enabled = false, modifier = modifier) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(end = CruxCoachSpacing.small)
                        .size(20.dp),
                )
                Text(stateLabel)
            }
        delivery.connection == BoardConnectionState.DISCONNECTED ->
            Button(onClick = onConnect, modifier = modifier) {
                Icon(Icons.Default.Bluetooth, contentDescription = null)
                Text(
                    stringResource(R.string.playlist_connect_board_confirm),
                    modifier = Modifier.padding(start = CruxCoachSpacing.small),
                )
            }
        delivery.isSent -> Surface(
            modifier = modifier,
            shape = CruxCoachDesign.shapes.medium,
            color = CruxCoachDesign.colors.positiveContainer,
        ) {
            Row(
                modifier = Modifier.padding(CruxCoachSpacing.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = CruxCoachDesign.colors.positive,
                )
                Text(
                    stateLabel,
                    modifier = Modifier.padding(start = CruxCoachSpacing.small),
                    color = CruxCoachDesign.colors.onPositiveContainer,
                )
            }
        }
        delivery.decision.showAction &&
            delivery.decision.target == BoardDeliveryTarget.SHARED_QUEUE ->
            Button(onClick = onDeliver, modifier = modifier) {
                Icon(Icons.Default.Queue, contentDescription = null)
                Text(
                    stringResource(R.string.cd_add_climb_to_shared_queue),
                    modifier = Modifier.padding(start = CruxCoachSpacing.small),
                )
            }
        delivery.decision.showAction -> Button(onClick = onDeliver, modifier = modifier) {
            Icon(Icons.Default.Lightbulb, contentDescription = null)
            Text(
                stringResource(R.string.cd_light_climb_on_board),
                modifier = Modifier.padding(start = CruxCoachSpacing.small),
            )
        }
        else -> Surface(
            modifier = modifier,
            shape = CruxCoachDesign.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(CruxCoachSpacing.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Bluetooth, contentDescription = null)
                Text(
                    stateLabel,
                    modifier = Modifier.padding(start = CruxCoachSpacing.small),
                )
            }
        }
    }
}
