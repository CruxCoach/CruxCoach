package com.cruxcoach.android.ui.board.sync

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.common.BleStatusArea
import com.cruxcoach.android.ui.common.RestTimerBannerSlot
import com.cruxcoach.android.ui.common.SyncStatusBannerSlot
import com.cruxcoach.android.ui.theme.*

/**
 * Standalone route wrapper around [BoardSyncInlineCard]. Still reachable
 * from the BoardBrowser/Logbook "no DB" nudge and the `board_sync?…`
 * deep link; Onboarding and Settings embed the card inline instead so
 * the user doesn't bounce between screens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardSyncScreen(
    onSyncComplete: () -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToBugReport: (title: String, description: String) -> Unit = { _, _ -> },
    viewModel: BoardSyncViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.board_sync_title)) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    },
                )
                RestTimerBannerSlot()
                SyncStatusBannerSlot()
                BleStatusArea()
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BoardSyncInlineCard(
                viewModel = viewModel,
                onNavigateToBugReport = onNavigateToBugReport,
            )

            if (state.syncComplete) {
                Button(
                    onClick = onSyncComplete,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("board_sync_to_browser"),
                    colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(stringResource(R.string.board_sync_to_browser), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
