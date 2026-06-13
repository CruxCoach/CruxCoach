package com.cruxcoach.android.ui.community

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.board.OwnPublishFeedback
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.ui.theme.SuccessGreen

/**
 * "Meine Climbs" — the user's own AUTHORED Kilter climbs (authorship
 * gate: connected-account userUuid == recorded kilter_author_uuid), each
 * with its CruxCoach-community publish state and a publish action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyKilterClimbsScreen(
    onNavigateBack: () -> Unit,
    onClimbClick: (uuid: String, angle: Int) -> Unit,
    viewModel: MyKilterClimbsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.feedback) {
        val feedback = state.feedback ?: return@LaunchedEffect
        val msg = when (feedback) {
            OwnPublishFeedback.Published -> context.getString(R.string.own_climb_publish_done)
            OwnPublishFeedback.NoNostrIdentity -> context.getString(R.string.own_climb_publish_no_nostr)
            OwnPublishFeedback.NotAuthor -> context.getString(R.string.own_climb_publish_not_author)
            OwnPublishFeedback.AlreadyPublished -> context.getString(R.string.own_climb_publish_already)
            OwnPublishFeedback.Failed -> context.getString(R.string.climb_creator_publish_failed)
        }
        snackbarHostState.showSnackbar(msg)
        viewModel.consumeFeedback()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.my_climbs_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = OrangeAccent) }
            }
            !state.hasKilterConnection -> {
                EmptyHint(
                    padding = padding,
                    title = stringResource(R.string.my_climbs_no_kilter_title),
                    message = stringResource(R.string.my_climbs_no_kilter_message),
                )
            }
            state.climbs.isEmpty() -> {
                EmptyHint(
                    padding = padding,
                    title = stringResource(R.string.my_climbs_empty_title),
                    message = stringResource(R.string.my_climbs_empty_message),
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.climbs, key = { it.uuid }) { climb ->
                        MyClimbCard(
                            climb = climb,
                            publishEnabled = state.publishingUuid == null,
                            isPublishing = state.publishingUuid == climb.uuid,
                            onClick = { onClimbClick(climb.uuid, climb.angle) },
                            onPublish = { viewModel.publish(climb.uuid) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(padding: PaddingValues, title: String, message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.Groups,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        )
        Spacer(modifier = Modifier.size(12.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.size(4.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MyClimbCard(
    climb: MyClimbItem,
    publishEnabled: Boolean,
    isPublishing: Boolean,
    onClick: () -> Unit,
    onPublish: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("my_climb_card_${climb.uuid}"),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    climb.name.ifBlank { stringResource(R.string.my_climbs_unnamed) },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                if (climb.publishFailed) {
                    Text(
                        stringResource(R.string.my_climbs_publish_retrying),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (climb.published) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = stringResource(R.string.my_climbs_published_badge),
                    tint = SuccessGreen,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    stringResource(R.string.my_climbs_published_badge),
                    style = MaterialTheme.typography.labelMedium,
                    color = SuccessGreen,
                )
            } else {
                TextButton(
                    onClick = onPublish,
                    enabled = publishEnabled && !isPublishing,
                    modifier = Modifier.testTag("my_climb_publish_${climb.uuid}"),
                ) {
                    if (isPublishing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = OrangeAccent,
                        )
                    } else {
                        Text(
                            stringResource(R.string.own_climb_publish_action_short),
                            color = OrangeAccent,
                        )
                    }
                }
            }
        }
    }
}
