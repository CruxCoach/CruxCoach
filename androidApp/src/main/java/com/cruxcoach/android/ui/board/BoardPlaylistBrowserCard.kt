package com.cruxcoach.android.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.boardcell.BoardPlaylistProjectionPendingReason
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.ui.theme.SuccessGreen

/**
 * The way into the board's shared list from the browser.
 *
 * Deliberately part of what scrolls rather than pinned above it. A board list
 * is something you go to, glance at and come back from — not a permanent strip
 * competing with the climbs you came to the browser to look through. The
 * pinned version of this carried its own transport controls, which is how the
 * wall ended up with two places that could light it; this card has exactly one
 * job, which is to open the list.
 *
 * It renders nothing at all when this device is not on a board, so the browser
 * gains no empty furniture for a feature that is not in play.
 */
@Composable
fun BoardPlaylistBrowserCard(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BoardPlaylistViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!state.available) return

    val current = state.rows.getOrNull(state.currentIndex)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .testTag("board_playlist_browser_card"),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            OrangeAccent.copy(alpha = 0.22f),
                            OrangeAccent.copy(alpha = 0.06f),
                        ),
                    ),
                )
                .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = OrangeAccent.copy(alpha = 0.22f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = null,
                        tint = OrangeAccent,
                        modifier = Modifier.padding(6.dp).size(22.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.board_playlist_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    val subtitle = buildString {
                        state.boardName?.let { append(it) }
                        if (state.memberCount > 0) {
                            if (isNotEmpty()) append(" · ")
                            append(pluralStringResource(R.plurals.board_people_count,
                                state.memberCount, state.memberCount))
                        }
                    }
                    if (subtitle.isNotEmpty()) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.cd_open),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            if (current == null) {
                Text(
                    stringResource(R.string.board_playlist_card_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${state.currentIndex + 1}/${state.rows.size}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = OrangeAccent,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        buildString {
                            append(current.name)
                            current.gradeLabel?.let { append("  $it") }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { (state.currentIndex + 1f) / state.rows.size },
                    color = OrangeAccent,
                    trackColor = OrangeAccent.copy(alpha = 0.15f),
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                )
                Spacer(Modifier.height(6.dp))
                // The same honest split the list makes: what the group is on,
                // and whether that is what the wall is showing. Only stated
                // here — the lamp that closes the gap lives on the list and in
                // the player, and nowhere else.
                val pending = state.pendingProjection
                val (status, tint) = when {
                    pending != null -> stringResource(
                        when (pending.reason) {
                            BoardPlaylistProjectionPendingReason.BOARD_WRITE_FAILED ->
                                R.string.board_playlist_send_write_failed
                            BoardPlaylistProjectionPendingReason.CLIMB_UNAVAILABLE ->
                                R.string.board_playlist_send_unavailable
                        },
                    ) to MaterialTheme.colorScheme.error
                    state.selectionOnBoard ->
                        stringResource(R.string.board_playlist_on_board) to SuccessGreen
                    state.boardClimbUnknown ->
                        stringResource(R.string.board_playlist_board_unknown) to
                            MaterialTheme.colorScheme.onSurfaceVariant
                    else -> stringResource(R.string.board_playlist_not_on_board) to
                        MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(status, style = MaterialTheme.typography.labelSmall, color = tint)
            }
            state.restore?.let { offer ->
                Spacer(Modifier.height(6.dp))
                Text(
                    pluralStringResource(R.plurals.board_playlist_restore_title,
                        offer.entryCount, offer.entryCount) + " · " +
                        stringResource(R.string.board_playlist_restore_remaining,
                            offer.secondsRemaining),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = OrangeAccent,
                )
            }
        }
    }
}
