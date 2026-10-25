package com.cruxcoach.android.ui.sharing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.sharing.ReceivedOnClimb
import com.cruxcoach.android.sharing.SharingService
import com.cruxcoach.data.repository.BoardRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The read-model join: what current friends shared about this climb, read from
 * the received-records table. Nothing is copied into the canonical logbook or
 * notes; ending a friendship removes these lines.
 */
@HiltViewModel
class ReceivedClimbViewModel @Inject constructor(
    private val service: SharingService,
    private val boards: BoardRepository,
) : ViewModel() {
    private val _received = MutableStateFlow<List<ReceivedOnClimb>>(emptyList())
    val received: StateFlow<List<ReceivedOnClimb>> = _received.asStateFlow()

    fun load(climbUuid: String) {
        viewModelScope.launch {
            _received.value = withContext(Dispatchers.IO) {
                runCatching { service.receivedOnClimb(boards.equivalentClimbUuids(climbUuid) + climbUuid) }.getOrDefault(emptyList())
            }
        }
    }
}

@Composable
internal fun ReceivedClimbSection(climbUuid: String, viewModel: ReceivedClimbViewModel = hiltViewModel(key = "received_$climbUuid")) {
    LaunchedEffect(climbUuid) { viewModel.load(climbUuid) }
    val received by viewModel.received.collectAsStateWithLifecycle()
    ReceivedClimbContent(received)
}

@Composable
internal fun ReceivedClimbContent(received: List<ReceivedOnClimb>) {
    if (received.isEmpty()) return
    HorizontalDivider()
    Column(Modifier.fillMaxWidth().testTag("boarddetail_received"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.sharing_climb_title), style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        received.forEach { entry ->
            Text(
                stringResource(R.string.sharing_climb_line, entry.name, recordLabel(entry.record)),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(stringResource(R.string.sharing_climb_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
