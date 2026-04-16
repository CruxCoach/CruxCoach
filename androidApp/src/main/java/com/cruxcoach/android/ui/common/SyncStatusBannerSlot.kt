package com.cruxcoach.android.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.data.BoardDatabaseImporter.ImportStep
import com.cruxcoach.android.data.BoardSyncManager
import com.cruxcoach.android.ui.theme.ErrorRed
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.ui.theme.SuccessGreen
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import kotlinx.coroutines.delay

private const val SUCCESS_BANNER_DURATION_MS = 5_000L

val LocalBoardSyncManager = staticCompositionLocalOf<BoardSyncManager> {
    error("BoardSyncManager not provided")
}

val LocalNavigateToSync = staticCompositionLocalOf<() -> Unit> {
    error("NavigateToSync not provided")
}

/**
 * App-wide sync status banner. Shows progress while syncing,
 * brief success/error message after completion, then auto-hides.
 * Place below TopAppBar in every screen (next to RestTimerBannerSlot).
 */
@Composable
fun SyncStatusBannerSlot() {
    val syncManager = LocalBoardSyncManager.current
    val syncState by syncManager.state.collectAsStateWithLifecycle()
    val gen = syncState.syncGeneration

    // Generation 0 = no sync ever started in this session → ignore
    var dismissedError by remember { mutableStateOf<String?>(null) }
    var showSuccess by remember { mutableStateOf(false) }

    // Reset dismissed error when a new sync starts
    LaunchedEffect(gen) {
        if (gen > 0) {
            dismissedError = null
        }
    }

    // Show success banner across screens for a fixed window (max 5s total).
    // The completion timestamp lives in BoardSyncManager, so navigating to
    // another screen does not restart the timer — each screen only shows
    // for the remaining time.
    val completedAt = syncState.lastSyncCompletedAtMillis
    LaunchedEffect(completedAt) {
        if (completedAt == null) {
            showSuccess = false
            return@LaunchedEffect
        }
        val remaining = SUCCESS_BANNER_DURATION_MS - (System.currentTimeMillis() - completedAt)
        if (remaining <= 0) {
            showSuccess = false
            return@LaunchedEffect
        }
        showSuccess = true
        delay(remaining)
        showSuccess = false
    }

    val isSyncing = syncState.isSyncing && gen > 0
    val hasError = gen > 0 && syncState.errorMessage != null && syncState.errorMessage != dismissedError
    val visible = isSyncing || showSuccess || hasError

    val navigateToSync = LocalNavigateToSync.current

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut()
    ) {
        when {
            isSyncing -> SyncProgressBanner(syncState.importStep, onClick = navigateToSync)
            hasError -> SyncErrorBanner(
                message = syncState.errorMessage ?: "",
                onDismiss = { dismissedError = syncState.errorMessage },
                onClick = navigateToSync
            )
            showSuccess -> SyncSuccessBanner(onClick = navigateToSync)
        }
    }
}

@Composable
private fun SyncProgressBanner(step: ImportStep?, onClick: () -> Unit) {
    Surface(
        color = OrangeAccent.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Sync,
                    contentDescription = null,
                    tint = OrangeAccent,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    stepLabel(step),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = OrangeAccent
                )
            }
            val progress = stepProgress(step)
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .padding(top = 2.dp),
                    color = OrangeAccent,
                    trackColor = OrangeAccent.copy(alpha = 0.15f),
                    strokeCap = StrokeCap.Round
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .padding(top = 2.dp),
                    color = OrangeAccent,
                    trackColor = OrangeAccent.copy(alpha = 0.15f),
                    strokeCap = StrokeCap.Round
                )
            }
        }
    }
}

@Composable
private fun SyncSuccessBanner(onClick: () -> Unit) {
    Surface(
        color = SuccessGreen.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = SuccessGreen,
                modifier = Modifier.size(18.dp)
            )
            Text(
                stringResource(R.string.sync_complete),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = SuccessGreen
            )
        }
    }
}

@Composable
private fun SyncErrorBanner(message: String, onDismiss: () -> Unit, onClick: () -> Unit) {
    Surface(
        color = ErrorRed.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = ErrorRed,
                modifier = Modifier.size(18.dp)
            )
            Text(
                message,
                style = MaterialTheme.typography.labelMedium,
                color = ErrorRed,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                maxLines = 1
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_close),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun stepLabel(step: ImportStep?): String = when (step) {
    is ImportStep.CheckingUpdate -> stringResource(R.string.sync_checking_update)
    is ImportStep.FetchingManifest -> stringResource(R.string.sync_checking_update)
    is ImportStep.Download -> {
        if (step.totalBytes > 0) {
            val mb = step.bytesRead / 1_048_576
            val totalMb = step.totalBytes / 1_048_576
            stringResource(R.string.sync_downloading_progress, mb, totalMb)
        } else stringResource(R.string.sync_downloading)
    }
    is ImportStep.DownloadChunk -> {
        if (step.cumulativeTotalBytes > 0) {
            val mb = step.cumulativeBytesRead / 1_048_576
            val totalMb = step.cumulativeTotalBytes / 1_048_576
            stringResource(R.string.sync_downloading_progress, mb, totalMb)
        } else stringResource(R.string.sync_downloading)
    }
    is ImportStep.Extract -> stringResource(R.string.sync_extracting)

    is ImportStep.ImportClimbs -> {
        if (step.scanned == 0 && step.total > 0) stringResource(R.string.sync_importing_climbs_bulk, step.total)
        else stringResource(R.string.sync_importing_climbs, step.scanned, step.total)
    }
    is ImportStep.ImportStats -> {
        if (step.scanned == 0 && step.total > 0) stringResource(R.string.sync_importing_stats_bulk, step.total)
        else stringResource(R.string.sync_importing_stats, step.scanned, step.total)
    }
    is ImportStep.ImportLayout -> stringResource(R.string.sync_importing_layout)
    is ImportStep.Done -> stringResource(R.string.sync_done)
    null -> stringResource(R.string.sync_running)
}

private fun stepProgress(step: ImportStep?): Float? = when (step) {
    is ImportStep.Download -> if (step.totalBytes > 0) step.bytesRead.toFloat() / step.totalBytes else null
    is ImportStep.DownloadChunk -> if (step.cumulativeTotalBytes > 0) step.cumulativeBytesRead.toFloat() / step.cumulativeTotalBytes else null
    is ImportStep.ImportClimbs -> if (step.total > 0 && step.scanned > 0) step.scanned.toFloat() / step.total else null
    is ImportStep.ImportStats -> if (step.total > 0 && step.scanned > 0) step.scanned.toFloat() / step.total else null
    else -> null
}
