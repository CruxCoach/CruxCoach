package com.cruxcoach.android.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.data.LedHoldColors
import com.cruxcoach.android.ui.settings.BoardMismatchFixAction
import com.cruxcoach.android.ui.theme.ErrorRed
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.ui.theme.SuccessGreen
import com.cruxcoach.android.ui.theme.WarningYellow
import com.cruxcoach.data.repository.brand
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant

/**
 * Parity-preserving production boundary around the current climb hero.
 *
 * Keeping board rendering, route playback, layer controls and BLE feedback in
 * one host lets later visual iterations replace one reviewed region without
 * moving UUID/angle navigation, mirror state, logging, or management actions.
 */
@Composable
internal fun ClimbDetailProductionHeroHost(
    state: ClimbDetailState,
    isSharingEnabled: Boolean,
    viewModel: BoardClimbDetailViewModel,
    onShowDetails: () -> Unit,
    onShowLayers: () -> Unit,
    onNavigateToSetter: (String) -> Unit,
    onNavigateToBugReport: (title: String, description: String) -> Unit,
    bleBugReportTitle: String,
    onFixBoardMismatch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val climb = state.climb ?: return
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("boarddetail_hero")
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CompactClimbOverview(
            state = state,
            onShowDetails = onShowDetails,
            onAngleSelected = viewModel::onAngleSelected,
            onNavigateToSetter = onNavigateToSetter,
            isSharingEnabled = isSharingEnabled,
        )

        // Boards that hold several climbs at once get a legend for what is on
        // the wall directly above the wall itself. The full rack stays a sheet.
        if (climb.brand.supportsIndependentClimbLayers) {
            BoardLayerStrip(state = state, onOpen = onShowLayers)
        }

        // Keep the existing board renderer and countdown spatially stable.
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            if (climb.brand == BoardBrand.MOONBOARD) {
                MoonBoardVisualization(
                    frames = climb.frames,
                    assetState = rememberMoonBoardAsset(climb.layoutId),
                    variant = MoonBoardVariant.fromLayoutId(climb.layoutId),
                    modifier = Modifier.testTag("boarddetail_visualization"),
                )
            } else {
                KilterBoardVisualization(
                    holds = state.holds,
                    placements = state.placements,
                    boardSize = state.boardSize,
                    boardImages = state.boardImages,
                    ledColors = if (climb.brand == BoardBrand.KILTER) {
                        state.ledColors
                    } else {
                        LedHoldColors.standardFor(climb.brand)
                    },
                    previewMode = state.playback.showPreview,
                    currentFrameHolds = if (state.playback.showPreview && state.playback.isRoute) {
                        state.playback.allFrames.getOrElse(state.playback.currentFrameIndex) { emptyList() }
                    } else {
                        null
                    },
                    projectionLayers = if (climb.brand == BoardBrand.QUANTUM) {
                        state.boardLayers.layers
                    } else {
                        emptyList()
                    },
                    modifier = Modifier.testTag("boarddetail_visualization"),
                )
            }
            if (state.playback.countdownSeconds > 0) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${state.playback.countdownSeconds}",
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Bold,
                        color = OrangeAccent,
                    )
                }
            }
        }

        if (state.playback.isRoute) {
            RoutePlaybackControls(state = state, viewModel = viewModel)
        }

        if (state.ble.isSending || state.ble.success || state.ble.error != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    state.ble.isSending -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = OrangeAccent,
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.board_detail_sending),
                            style = MaterialTheme.typography.bodySmall,
                            color = OrangeAccent,
                        )
                    }
                    state.ble.success -> {
                        Icon(
                            Icons.Default.BluetoothConnected,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = SuccessGreen,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.board_detail_sent),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = SuccessGreen,
                        )
                        state.ble.warning?.let { warningRes ->
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                stringResource(warningRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = WarningYellow,
                            )
                        }
                    }
                    state.ble.error != null -> {
                        val bleErrorText = stringResource(state.ble.error)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                bleErrorText,
                                style = MaterialTheme.typography.bodySmall,
                                color = ErrorRed,
                                modifier = Modifier.clickable {
                                    onNavigateToBugReport(bleBugReportTitle, bleErrorText)
                                },
                            )
                            state.ble.mismatch?.let { mismatch ->
                                BoardMismatchFixAction(
                                    mismatch = mismatch,
                                    onOpenPicker = { onFixBoardMismatch() },
                                    compact = true,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
