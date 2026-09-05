package com.cruxcoach.android.ui.board

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.cruxcoach.android.R
import com.cruxcoach.data.repository.ClimbBetaLink

/** Compact entry point in the existing climb header; never takes a board row. */
@Composable
internal fun BetaVideoAction(count: Int, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp).testTag("beta_videos_toggle"),
    ) {
        BadgedBox(badge = {
            Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                Text(if (count > 99) "99+" else count.toString())
            }
        }) {
            Icon(
                Icons.Default.PlayCircleOutline,
                contentDescription = stringResource(R.string.beta_videos, count),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** The gallery has its own scroll area so opening beta never shrinks the board. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BetaVideoSheet(
    links: List<ClimbBetaLink>,
    selectedAngle: Int,
    climbName: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenFailed: () -> Unit,
) {
    val context = LocalContext.current
    if (!expanded) return
    var openFailed by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onToggle,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("beta_videos_sheet"),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.beta_videos, links.size),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        climbName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onToggle) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.beta_video_close))
                }
            }
            Text(
                stringResource(R.string.beta_video_external_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
            if (openFailed) {
                Text(
                    stringResource(R.string.beta_video_open_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                itemsIndexed(links, key = { _, link -> "${link.boardBrand}:${link.climbUuid}:${link.url}" }) { index, link ->
                    Surface(
                        onClick = {
                            openFailed = !openBetaLink(context, link)
                            if (openFailed) onOpenFailed()
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth().testTag("beta_video_$index"),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Box(
                                modifier = Modifier.size(width = 80.dp, height = 96.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (link.thumbnail != null) {
                                    AsyncImage(
                                        model = link.thumbnail,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                Box(
                                    Modifier.size(36.dp).background(Color.Black.copy(alpha = 0.65f), CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                                }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(
                                    link.foreignUsername?.takeIf { it.isNotBlank() }
                                        ?: stringResource(R.string.beta_video_number, index + 1),
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    betaVideoProviderLabel(link),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                link.angle?.let { angle ->
                                    Text(
                                        if (angle == selectedAngle) stringResource(R.string.beta_video_current_angle, angle)
                                        else stringResource(R.string.beta_video_other_angle, angle),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (angle == selectedAngle) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = stringResource(R.string.beta_video_open, index + 1),
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun betaVideoProviderLabel(link: ClimbBetaLink): String = when (link.provider.lowercase()) {
    "instagram" -> "Instagram"
    "kaya", "kayaclimb", "app.kayaclimb.com" -> "KAYA"
    "youtube", "youtube.com", "www.youtube.com", "youtu.be" -> "YouTube"
    else -> Uri.parse(link.url).host?.removePrefix("www.") ?: link.provider
}
