package com.cruxcoach.android.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.DarkBackground
import com.cruxcoach.android.ui.theme.OrangeAccent

@Composable
internal fun RoutePlaybackControls(
    state: ClimbDetailState,
    viewModel: BoardClimbDetailViewModel
) {
    val pb = state.playback
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header row: Preview, Frame counter, Loop
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { viewModel.togglePreview() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        if (pb.showPreview) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = stringResource(if (pb.showPreview) R.string.cd_preview_off else R.string.cd_preview_on),
                        tint = if (pb.showPreview) OrangeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Text(
                    stringResource(R.string.board_playback_frame, pb.currentFrameIndex + 1, pb.totalFrames),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                IconButton(
                    onClick = { viewModel.toggleLoop() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Repeat,
                        contentDescription = stringResource(if (pb.isLooping) R.string.cd_loop_off else R.string.cd_loop_on),
                        tint = if (pb.isLooping) OrangeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Transport controls
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { viewModel.goToFrame(0) },
                    enabled = pb.currentFrameIndex > 0 && !pb.isPlaying,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = stringResource(R.string.cd_first_frame), modifier = Modifier.size(24.dp))
                }

                IconButton(
                    onClick = { viewModel.previousFrame() },
                    enabled = (pb.currentFrameIndex > 0 || pb.isLooping) && !pb.isPlaying,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_previous_frame), modifier = Modifier.size(24.dp))
                }

                FilledIconButton(
                    onClick = {
                        if (pb.isPlaying) viewModel.stopPlayback() else viewModel.startPlayback()
                    },
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = OrangeAccent)
                ) {
                    Icon(
                        if (pb.isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (pb.isPlaying) stringResource(R.string.cd_stop) else stringResource(R.string.cd_play),
                        tint = DarkBackground,
                        modifier = Modifier.size(28.dp)
                    )
                }

                IconButton(
                    onClick = { viewModel.nextFrame() },
                    enabled = (pb.currentFrameIndex < pb.totalFrames - 1 || pb.isLooping) && !pb.isPlaying,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.cd_next_frame), modifier = Modifier.size(24.dp))
                }

                IconButton(
                    onClick = { viewModel.goToFrame(pb.totalFrames - 1) },
                    enabled = pb.currentFrameIndex < pb.totalFrames - 1 && !pb.isPlaying,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Default.SkipNext, contentDescription = stringResource(R.string.cd_last_frame), modifier = Modifier.size(24.dp))
                }
            }

            // Frame dots (only if <= 20 frames)
            if (pb.totalFrames <= 20) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (i in 0 until pb.totalFrames) {
                        Box(
                            modifier = Modifier
                                .size(if (i == pb.currentFrameIndex) 10.dp else 8.dp)
                                .background(
                                    color = if (i == pb.currentFrameIndex) OrangeAccent
                                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                    shape = CircleShape
                                )
                                .clickable(enabled = !pb.isPlaying) { viewModel.goToFrame(i) }
                        )
                    }
                }
            }

            // Speed slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(stringResource(R.string.board_playback_speed), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = pb.speedSec,
                    onValueChange = { viewModel.updatePlaybackSpeed(it) },
                    valueRange = 1f..15f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = OrangeAccent, activeTrackColor = OrangeAccent)
                )
                Text(
                    formatSpeed(pb.speedSec),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = OrangeAccent
                )
            }
        }
    }
}

internal fun formatSpeed(seconds: Float): String {
    return if (seconds == seconds.toLong().toFloat()) "${seconds.toInt()}s" else "%.1fs".format(seconds)
}
