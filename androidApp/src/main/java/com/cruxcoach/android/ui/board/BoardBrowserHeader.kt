package com.cruxcoach.android.ui.board

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.android.ui.theme.CruxCoachSpacing
import com.cruxcoach.domain.board.BoardConnectionState
import com.cruxcoach.domain.board.BrowserBoardContext
import com.cruxcoach.domain.board.BrowserConnection

/** First isolated browser redesign region: physical context plus direct find. */
@Composable
fun BoardBrowserHeader(
    board: BrowserBoardContext,
    connection: BrowserConnection,
    query: String,
    activeFilterCount: Int,
    onSelectBoard: () -> Unit,
    onConnectBoard: () -> Unit,
    onQueryChanged: (String) -> Unit,
    onOpenFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = CruxCoachSpacing.large,
                vertical = CruxCoachSpacing.small,
            ),
        verticalArrangement = Arrangement.spacedBy(CruxCoachSpacing.small),
    ) {
        BoardBrowserContextHeader(
            board = board,
            connection = connection,
            onSelectBoard = onSelectBoard,
            onConnectBoard = onConnectBoard,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(CruxCoachSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChanged,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = CruxCoachSpacing.minimumTouchTarget)
                    .testTag("browser_search_field_candidate"),
                placeholder = { Text(stringResource(R.string.board_browser_search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { onQueryChanged("") }) {
                            Icon(
                                Icons.Default.Clear,
                                contentDescription = stringResource(R.string.cd_clear_search),
                            )
                        }
                    }
                } else null,
                singleLine = true,
                shape = CruxCoachDesign.shapes.medium,
            )
            FilledTonalIconButton(
                onClick = onOpenFilters,
                modifier = Modifier
                    .size(CruxCoachSpacing.minimumTouchTarget)
                    .testTag("browser_filter_candidate"),
            ) {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = if (activeFilterCount == 0) {
                        stringResource(R.string.cd_filter)
                    } else {
                        stringResource(R.string.board_browser_active_filters, activeFilterCount)
                    },
                    tint = if (activeFilterCount > 0) {
                        CruxCoachDesign.colors.brandAccent
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/** Production-safe first region: board identity and hardware state only. */
@Composable
fun BoardBrowserContextHeader(
    board: BrowserBoardContext,
    connection: BrowserConnection,
    onSelectBoard: () -> Unit,
    onConnectBoard: () -> Unit,
    modifier: Modifier = Modifier,
    connectionTestTag: String = "browser_connection",
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CruxCoachSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = onSelectBoard,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = CruxCoachSpacing.minimumTouchTarget)
                .testTag("browser_board_context"),
            shape = CruxCoachDesign.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(
                    horizontal = CruxCoachSpacing.medium,
                    vertical = CruxCoachSpacing.small,
                ),
                horizontalArrangement = Arrangement.spacedBy(CruxCoachSpacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = buildString {
                            append(board.brand.displayName)
                            board.productName?.let { append(" · ").append(it) }
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.board_filter_angle, board.angle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(imageVector = Icons.Default.ExpandMore, contentDescription = null)
            }
        }

        val connectionColor: Color
        val connectionLabel: String
        when (connection.state) {
            BoardConnectionState.DISCONNECTED -> {
                connectionColor = MaterialTheme.colorScheme.onSurfaceVariant
                connectionLabel = stringResource(R.string.ble_disconnected)
            }
            BoardConnectionState.CONNECTING -> {
                connectionColor = CruxCoachDesign.colors.brandAccent
                connectionLabel = stringResource(R.string.board_ble_connecting)
            }
            BoardConnectionState.CONNECTED -> {
                connectionColor = CruxCoachDesign.colors.positive
                connectionLabel = connection.boardName?.let {
                    stringResource(R.string.ble_connected, it)
                } ?: stringResource(R.string.board_ble_connected)
            }
        }
        TextButton(
            onClick = onConnectBoard,
            modifier = Modifier
                .heightIn(min = CruxCoachSpacing.minimumTouchTarget)
                .widthIn(min = CruxCoachSpacing.minimumTouchTarget)
                .testTag(connectionTestTag),
        ) {
            Icon(
                imageVector = if (connection.state == BoardConnectionState.CONNECTED) {
                    Icons.Default.BluetoothConnected
                } else {
                    Icons.Default.Bluetooth
                },
                contentDescription = null,
                tint = connectionColor,
                modifier = Modifier.size(CruxCoachSpacing.xLarge),
            )
            Text(
                text = connectionLabel,
                color = connectionColor,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = CruxCoachSpacing.xSmall),
            )
        }
    }
}
