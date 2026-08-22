package com.cruxcoach.android.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.ui.theme.SuccessGreen

/**
 * The way into the board's shared list from the browser.
 *
 * Pinned directly below the browser chrome while the climb results scroll.
 * This is the connected board's primary context, so it replaces the generic
 * Nearby row instead of competing with it. The whole surface has exactly one
 * action: open the Board-Playlist in one tap. Board transport controls remain
 * on the playlist itself, so pinning this never creates a second lamp.
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
    val boardLabel = state.boardName ?: stringResource(R.string.fips_mesh_own_active)
    val peopleLabel = state.memberCount.takeIf { it > 0 }?.let { count ->
        pluralStringResource(R.plurals.board_people_count, count, count)
    }
    val climbCountLabel = state.rows.size.takeIf { it > 0 }?.let { count ->
        pluralStringResource(R.plurals.board_playlist_climb_count, count, count)
    }
    val statusTint = when {
        state.pendingProjection != null -> MaterialTheme.colorScheme.error
        !state.synchronized || state.pendingCommands > 0 -> OrangeAccent
        state.selectionOnBoard -> SuccessGreen
        else -> OrangeAccent
    }
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
                .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = statusTint.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = null,
                        tint = statusTint,
                        modifier = Modifier.padding(6.dp).size(21.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            boardLabel,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        peopleLabel?.let {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                it,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.size(2.dp))
                    if (current == null) {
                        Text(
                            buildString {
                                append(stringResource(R.string.board_playlist_title))
                                append(" · ")
                                append(stringResource(R.string.board_playlist_card_empty))
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                buildString {
                                    append(stringResource(R.string.board_playlist_title))
                                    climbCountLabel?.let { append(" · "); append(it) }
                                    append(" · ")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                            Text(
                                current.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${state.currentIndex + 1}/${state.rows.size}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = OrangeAccent,
                            )
                        }
                    }
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.cd_open),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
            if (!state.synchronized || state.pendingCommands > 0) {
                Spacer(Modifier.size(6.dp))
                LinearProgressIndicator(
                    color = OrangeAccent,
                    trackColor = OrangeAccent.copy(alpha = 0.15f),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
