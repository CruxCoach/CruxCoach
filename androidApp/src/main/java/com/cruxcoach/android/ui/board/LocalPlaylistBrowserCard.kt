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
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.OrangeAccent

/** Playlist-UX browser banner backed only by the private local 0.2.2 queue. */
@Composable
internal fun LocalPlaylistBrowserCard(
    currentClimbName: String?,
    currentIndex: Int,
    totalCount: Int,
    hasPrevious: Boolean,
    hasNext: Boolean,
    canLight: Boolean,
    onPrevious: () -> Unit,
    onLight: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
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
                    color = OrangeAccent.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = null,
                        tint = OrangeAccent,
                        modifier = Modifier.padding(6.dp).size(21.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.playlist_banner_label),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (currentIndex >= 0 && totalCount > 0) {
                            Text(
                                "${currentIndex + 1}/$totalCount",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = OrangeAccent,
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.cd_open),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(Modifier.size(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            currentClimbName ?: stringResource(R.string.playlist_player_title),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = onPrevious,
                            enabled = hasPrevious,
                            modifier = Modifier.size(36.dp).testTag("playlist_banner_previous"),
                        ) {
                            Icon(
                                Icons.Default.SkipPrevious,
                                contentDescription = stringResource(R.string.cd_previous),
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        IconButton(
                            onClick = onLight,
                            enabled = canLight,
                            modifier = Modifier.size(36.dp).testTag("playlist_banner_light"),
                        ) {
                            Icon(
                                Icons.Default.Lightbulb,
                                contentDescription = stringResource(R.string.playlist_send_to_board),
                                tint = if (canLight) OrangeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(21.dp),
                            )
                        }
                        IconButton(
                            onClick = onNext,
                            enabled = hasNext,
                            modifier = Modifier.size(36.dp).testTag("playlist_banner_next"),
                        ) {
                            Icon(
                                Icons.Default.SkipNext,
                                contentDescription = stringResource(R.string.cd_next),
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
