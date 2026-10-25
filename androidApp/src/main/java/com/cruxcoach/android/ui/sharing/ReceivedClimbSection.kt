package com.cruxcoach.android.ui.sharing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
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
import com.cruxcoach.android.sharing.OwnOnClimb
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
    private val service: dagger.Lazy<SharingService>,
    private val boards: BoardRepository,
    private val feature: com.cruxcoach.android.sharing.SharingFeature,
) : ViewModel() {
    private val _received = MutableStateFlow<List<ReceivedOnClimb>>(emptyList())
    val received: StateFlow<List<ReceivedOnClimb>> = _received.asStateFlow()
    private val _own = MutableStateFlow<List<OwnOnClimb>>(emptyList())
    /** Own records about this climb and who sees them ("who sees this?"). */
    val own: StateFlow<List<OwnOnClimb>> = _own.asStateFlow()

    fun load(climbUuid: String) {
        viewModelScope.launch {
            // Unfinished feature: off (always in release builds) means not even a query.
            if (!feature.isEnabled()) return@launch
            withContext(Dispatchers.IO) {
                val ids = runCatching { boards.equivalentClimbUuids(climbUuid) + climbUuid }.getOrDefault(setOf(climbUuid))
                _received.value = runCatching { service.get().receivedOnClimb(ids) }.getOrDefault(emptyList())
                _own.value = runCatching { service.get().whoSeesClimb(ids) }.getOrDefault(emptyList())
            }
        }
    }

    /** "Never share this record" from the climb sheet; applies to everyone at once. */
    fun setNever(climbUuid: String, recordId: String, never: Boolean) {
        viewModelScope.launch {
            if (!feature.isEnabled()) return@launch
            withContext(Dispatchers.IO) { runCatching { service.get().setNeverShare(recordId, never) } }
            load(climbUuid)
        }
    }
}

@Composable
internal fun ReceivedClimbSection(climbUuid: String, viewModel: ReceivedClimbViewModel = hiltViewModel(key = "received_$climbUuid")) {
    LaunchedEffect(climbUuid) { viewModel.load(climbUuid) }
    val received by viewModel.received.collectAsStateWithLifecycle()
    val own by viewModel.own.collectAsStateWithLifecycle()
    ReceivedClimbContent(received)
    OwnClimbContent(own) { id, never -> viewModel.setNever(climbUuid, id, never) }
}

/** Who sees the own records about this climb, with "never share" per record. */
@Composable
internal fun OwnClimbContent(own: List<OwnOnClimb>, onNever: (String, Boolean) -> Unit) {
    if (own.isEmpty()) return
    HorizontalDivider()
    Column(Modifier.fillMaxWidth().testTag("boarddetail_who_sees"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.sharing_who_climb_title), style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        own.forEach { item ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(value = item.never, role = Role.Switch, onValueChange = { onNever(item.record.id, it) })
                    .testTag("boarddetail_never_${item.record.id}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(recordLabel(item.record), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            item.never -> stringResource(R.string.sharing_who_never_state)
                            item.audience.isEmpty() -> stringResource(R.string.sharing_who_only_you)
                            else -> stringResource(R.string.sharing_who_visible, item.audience.joinToString(", "))
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(stringResource(R.string.sharing_who_never_toggle), style = MaterialTheme.typography.labelMedium)
                Switch(checked = item.never, onCheckedChange = null)
            }
        }
        Text(stringResource(R.string.sharing_who_climb_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
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
