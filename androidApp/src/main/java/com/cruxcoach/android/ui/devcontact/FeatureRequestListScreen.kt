package com.cruxcoach.android.ui.devcontact

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cruxcoach.android.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.ui.theme.OrangeAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeatureRequestListScreen(
    onNavigateBack: () -> Unit,
    onNavigateToThread: (String) -> Unit,
    onNavigateToForm: () -> Unit,
    viewModel: DevContactViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Reload when returning from FeatureRequestScreen (separate ViewModel instance)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.loadMessages()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.devcontact_wishes)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToForm) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_suggest_feature))
                    }
                }
            )
        }
    ) { padding ->
        if (state.featureRequests.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.devcontact_wishes_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = state.featureRequests,
                    key = { it.id }
                ) { request ->
                    MessageListCard(
                        badge = stringResource(R.string.devcontact_badge_wish),
                        badgeColor = OrangeAccent,
                        subject = request.subject ?: request.content.take(50),
                        timestamp = request.timestamp,
                        isRead = request.isRead,
                        isQueued = request.isQueued,
                        isDelivered = request.isDelivered,
                        isSent = request.isSent,
                        onClick = { onNavigateToThread(request.id) },
                        onRetry = { viewModel.retryMessage(request.id) },
                        onDelete = { viewModel.deleteMessage(request.id) }
                    )
                }
            }
        }
    }
}
