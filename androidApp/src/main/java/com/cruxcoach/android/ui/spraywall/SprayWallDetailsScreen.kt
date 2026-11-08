package com.cruxcoach.android.ui.spraywall

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.spraywall.SprayWallPublisher
import com.cruxcoach.android.spraywall.SprayWallRepository
import com.cruxcoach.android.spraywall.SprayWithdrawal
import com.cruxcoach.android.ui.map.MapVenue
import com.cruxcoach.domain.spraywall.SprayGym
import com.cruxcoach.domain.spraywall.SprayWall
import com.cruxcoach.domain.spraywall.SprayWallVisibility
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A wall's details: name, angle, who sees it and its gym. [first] = the last step of adding the wall. */
data class SprayWallDetailsState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val first: Boolean = false,
    val name: String = "",
    val angle: Int = SprayWallPhotoViewModel.DEFAULT_ANGLE,
    val visibility: SprayWallVisibility = SprayWallVisibility.PRIVATE,
    val gym: SprayGym? = null,
    /** On Nostr right now: going private withdraws it. */
    val published: Boolean = false,
    /** The relays took part of a withdrawal that did not finish: the only way on is to try withdrawing again. */
    val withdrawStarted: Boolean = false,
    val saving: Boolean = false,
    /** Taking the wall off the relays and Blossom: that waits for them, up to half a minute. */
    val withdrawing: Boolean = false,
    /** The map's venues while the gym picker is open (null = still loading). */
    val venues: List<MapVenue>? = null,
    val pickingGym: Boolean = false,
    val dialog: Dialog? = null,
    /** The last withdrawal did not work: the wall is still public (and still here, when it was to be deleted). */
    val withdrawFailed: WithdrawFailure? = null,
) {
    enum class Dialog { CONFIRM_PUBLIC, NO_IDENTITY, CONFIRM_WITHDRAW }

    /** [keptEdits]: the name, angle or gym changes were saved all the same (the wall stays public). */
    data class WithdrawFailure(val reason: SprayWithdrawal, val deleting: Boolean, val keptEdits: Boolean = false)

    val canSave: Boolean get() = !loading && !saving && name.isNotBlank()

    companion object {
        const val MAX_ANGLE = 70
        const val ANGLE_STEP = 5
        const val NAME_LIMIT = 80
    }
}

sealed interface SprayWallDetailsEvent {
    data object Saved : SprayWallDetailsEvent
    data object Deleted : SprayWallDetailsEvent
}

@HiltViewModel
class SprayWallDetailsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: SprayWallRepository,
    private val publisher: SprayWallPublisher,
    private val venueSource: SprayVenueSource,
) : ViewModel() {
    private val wallId: String = savedState[SprayWallRoutes.ARG_WALL] ?: ""
    private val _state = MutableStateFlow(SprayWallDetailsState(first = savedState.get<Boolean>(SprayWallRoutes.ARG_FIRST) == true))
    val state: StateFlow<SprayWallDetailsState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<SprayWallDetailsEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<SprayWallDetailsEvent> = _events.asSharedFlow()

    /** Public in the database: going private or deleting takes it back first (also when a publish failed partway). */
    private var storedPublic = false

    init {
        viewModelScope.launch {
            val wall = repository.wall(wallId)
            if (wall == null || !wall.isMine) {
                _state.update { it.copy(loading = false, notFound = true) }
                return@launch
            }
            storedPublic = wall.visibility == SprayWallVisibility.PUBLIC
            _state.update {
                it.copy(
                    loading = false, name = wall.name, angle = wall.angle, visibility = wall.visibility, gym = wall.gym,
                    published = wall.visibility == SprayWallVisibility.PUBLIC && wall.publishedVersion != null,
                    withdrawStarted = wall.withdrawStarted != null,
                )
            }
        }
    }

    /** Public asks first (and needs an identity); private on a published wall asks before withdrawing it. */
    fun chooseVisibility(visibility: SprayWallVisibility) {
        val s = _state.value
        if (visibility == s.visibility) return
        val dialog = when {
            visibility == SprayWallVisibility.PUBLIC && !publisher.hasIdentity() -> SprayWallDetailsState.Dialog.NO_IDENTITY
            visibility == SprayWallVisibility.PUBLIC -> SprayWallDetailsState.Dialog.CONFIRM_PUBLIC
            s.published -> SprayWallDetailsState.Dialog.CONFIRM_WITHDRAW
            else -> null
        }
        _state.update { if (dialog == null) it.copy(visibility = visibility) else it.copy(dialog = dialog) }
    }

    fun confirmDialog() = _state.update {
        when (it.dialog) {
            SprayWallDetailsState.Dialog.CONFIRM_PUBLIC -> it.copy(visibility = SprayWallVisibility.PUBLIC, dialog = null)
            SprayWallDetailsState.Dialog.CONFIRM_WITHDRAW -> it.copy(visibility = SprayWallVisibility.PRIVATE, dialog = null)
            else -> it.copy(dialog = null)
        }
    }

    fun dismissDialog() = _state.update { it.copy(dialog = null) }
    fun dismissWithdrawFailure() = _state.update { it.copy(withdrawFailed = null) }

    fun openGymPicker() {
        _state.update { it.copy(pickingGym = true) }
        if (_state.value.venues == null) viewModelScope.launch {
            val venues = runCatching { venueSource.venues() }.getOrDefault(emptyList())
            _state.update { it.copy(venues = venues) }
        }
    }

    fun pickGym(gym: SprayGym?) = _state.update { it.copy(gym = gym, pickingGym = false) }
    fun closeGymPicker() = _state.update { it.copy(pickingGym = false) }

    fun setName(name: String) = _state.update { it.copy(name = name.take(SprayWallDetailsState.NAME_LIMIT)) }

    fun setAngle(angle: Int) = _state.update { it.copy(angle = angle.coerceIn(0, SprayWallDetailsState.MAX_ANGLE)) }

    fun save() {
        val s = _state.value
        if (!s.canSave) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val before = repository.wall(wallId)
            // Taking a public wall back: tombstones and photos first, while it is still known as public.
            if (storedPublic && s.visibility == SprayWallVisibility.PRIVATE) {
                val result = withdraw()
                if (result != SprayWithdrawal.DONE) {
                    // It stays public, but the name, angle and gym edits are not lost.
                    failed(result, deleting = false, keptEdits = keepEdits(s))
                    return@launch
                }
            }
            // A public wall's name, angle and gym are in its head event, which only a new version would send again.
            // Any public wall: one whose first publish ends while this screen is open is published by now. Not one
            // the relays already took back in part: it waits for the withdrawal to finish.
            val sendHead = s.visibility == SprayWallVisibility.PUBLIC && before != null && !before.hasDetails(s) && before.withdrawStarted == null
            repository.updateWallDetails(wallId, s.name, s.angle, s.visibility, s.gym, sendHead)
            if (s.visibility == SprayWallVisibility.PUBLIC) publisher.publishSoon()
            _events.emit(SprayWallDetailsEvent.Saved)
        }
    }

    fun delete() {
        if (_state.value.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            // A public wall is taken back first; afterwards nothing here could withdraw it, so it stays until that worked.
            if (storedPublic) {
                val result = withdraw()
                if (result != SprayWithdrawal.DONE) {
                    failed(result, deleting = true, keptEdits = false)
                    return@launch
                }
            }
            repository.deleteWall(wallId)
            _events.emit(SprayWallDetailsEvent.Deleted)
        }
    }

    /** A withdrawal the relays took part of: tries the rest again; done, the wall is private. */
    fun withdrawAgain() {
        if (_state.value.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = withdraw()
            if (result != SprayWithdrawal.DONE) {
                failed(result, deleting = false, keptEdits = false)
                return@launch
            }
            storedPublic = false
            _state.update {
                it.copy(saving = false, visibility = SprayWallVisibility.PRIVATE, published = false, withdrawStarted = false)
            }
        }
    }

    /** [SprayWithdrawal.DONE] when the wall is off Nostr and Blossom; anything else left it public. */
    private suspend fun withdraw(): SprayWithdrawal {
        _state.update { it.copy(withdrawing = true) }
        val result = publisher.withdrawWall(wallId)
        _state.update { it.copy(withdrawing = false) }
        return result
    }

    /** The screen says why the withdrawal did not work; the wall stays public (and may now be withdrawn in part). */
    private suspend fun failed(result: SprayWithdrawal, deleting: Boolean, keptEdits: Boolean) {
        val started = repository.wall(wallId)?.withdrawStarted != null
        _state.update {
            it.copy(
                saving = false,
                visibility = SprayWallVisibility.PUBLIC,
                withdrawStarted = started,
                withdrawFailed = SprayWallDetailsState.WithdrawFailure(result, deleting, keptEdits),
            )
        }
    }

    /**
     * Saves the name, angle and gym of a wall that stays public after a failed withdrawal; true when any of them
     * changed. The head goes out with them, as on a Save without the withdrawal, if no relay took a tombstone (the
     * relays still show the wall, whatever the reason: no relay, no identity, the signer declining the first one).
     * Once a relay took one, the wall waits for the withdrawal to finish and nothing of it goes out.
     */
    private suspend fun keepEdits(s: SprayWallDetailsState): Boolean {
        val wall = repository.wall(wallId) ?: return false
        if (wall.hasDetails(s)) return false
        val sendHead = wall.withdrawStarted == null
        repository.updateWallDetails(wallId, s.name, s.angle, SprayWallVisibility.PUBLIC, s.gym, sendHead)
        if (sendHead) publisher.publishSoon()
        return true
    }

    private fun SprayWall.hasDetails(s: SprayWallDetailsState) = name == s.name.trim() && angle == s.angle && gym == s.gym
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SprayWallDetailsScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onDeleted: () -> Unit,
    viewModel: SprayWallDetailsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel) {
        viewModel.events.collect {
            when (it) {
                SprayWallDetailsEvent.Saved -> onSaved()
                SprayWallDetailsEvent.Deleted -> onDeleted()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.first) R.string.spray_wall_new_title else R.string.spray_wall_edit_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        if (state.notFound) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.spray_not_found))
            }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.first) SprayStepLabel(step = 3)
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                label = { Text(stringResource(R.string.spray_wall_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("spray_wall_name"),
            )
            Column {
                Text(stringResource(R.string.spray_wall_angle, state.angle), style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = state.angle.toFloat(),
                    onValueChange = { viewModel.setAngle((it / SprayWallDetailsState.ANGLE_STEP).toInt() * SprayWallDetailsState.ANGLE_STEP) },
                    valueRange = 0f..SprayWallDetailsState.MAX_ANGLE.toFloat(),
                    steps = SprayWallDetailsState.MAX_ANGLE / SprayWallDetailsState.ANGLE_STEP - 1,
                    modifier = Modifier.testTag("spray_wall_angle"),
                )
                Text(stringResource(R.string.spray_wall_angle_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.withdrawStarted) {
                WithdrawUnfinishedCard(withdrawing = state.withdrawing, enabled = !state.saving, onRetry = viewModel::withdrawAgain)
            } else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.spray_visibility_title), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.visibility == SprayWallVisibility.PRIVATE,
                        onClick = { viewModel.chooseVisibility(SprayWallVisibility.PRIVATE) },
                        label = { Text(stringResource(R.string.spray_visibility_private)) },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        modifier = Modifier.testTag("spray_visibility_private"),
                    )
                    FilterChip(
                        selected = state.visibility == SprayWallVisibility.PUBLIC,
                        onClick = { viewModel.chooseVisibility(SprayWallVisibility.PUBLIC) },
                        label = { Text(stringResource(R.string.spray_visibility_public)) },
                        leadingIcon = { Icon(Icons.Default.Public, contentDescription = null) },
                        modifier = Modifier.testTag("spray_visibility_public"),
                    )
                }
                Text(
                    stringResource(if (state.visibility == SprayWallVisibility.PUBLIC) R.string.spray_visibility_public_hint else R.string.spray_visibility_private_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.spray_gym_title), style = MaterialTheme.typography.labelLarge)
                OutlinedButton(onClick = viewModel::openGymPicker, modifier = Modifier.fillMaxWidth().testTag("spray_gym_pick")) {
                    Icon(if (state.gym == null) Icons.Default.Home else Icons.Default.Place, contentDescription = null)
                    Text(state.gym?.name?.ifBlank { null } ?: stringResource(R.string.spray_gym_none), modifier = Modifier.padding(start = 8.dp))
                }
                Text(stringResource(R.string.spray_gym_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(
                onClick = viewModel::save,
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth().testTag("spray_wall_save"),
            ) {
                // Withdrawing waits for the relays and the photo servers, up to half a minute: show that it is working.
                if (state.withdrawing) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.spray_wall_withdrawing), modifier = Modifier.padding(start = 8.dp))
                } else {
                    Text(stringResource(if (state.first) R.string.action_done else R.string.action_save))
                }
            }
            if (!state.first && !state.loading) {
                TextButton(onClick = { confirmDelete = true }, enabled = !state.saving, modifier = Modifier.testTag("spray_wall_delete")) {
                    Text(stringResource(R.string.spray_wall_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (state.pickingGym) {
        SprayGymPickerDialog(venues = state.venues, current = state.gym, onPick = viewModel::pickGym, onDismiss = viewModel::closeGymPicker)
    }
    when (state.dialog) {
        SprayWallDetailsState.Dialog.CONFIRM_PUBLIC -> SprayConfirmDialog(
            title = stringResource(R.string.spray_publish_confirm_title),
            // The one line about people sits apart, so it is not lost in the paragraph.
            text = stringResource(R.string.spray_publish_confirm_text) + "\n\n" + stringResource(R.string.spray_publish_no_people),
            confirmLabel = stringResource(R.string.spray_publish_confirm),
            onConfirm = viewModel::confirmDialog,
            onDismiss = viewModel::dismissDialog,
        )
        SprayWallDetailsState.Dialog.CONFIRM_WITHDRAW -> SprayConfirmDialog(
            title = stringResource(R.string.spray_withdraw_title),
            text = stringResource(R.string.spray_withdraw_text),
            confirmLabel = stringResource(R.string.spray_withdraw_confirm),
            onConfirm = viewModel::confirmDialog,
            onDismiss = viewModel::dismissDialog,
        )
        SprayWallDetailsState.Dialog.NO_IDENTITY -> SprayConfirmDialog(
            title = stringResource(R.string.spray_publish_no_identity_title),
            text = stringResource(R.string.spray_publish_no_identity_text),
            confirmLabel = stringResource(R.string.action_ok),
            onConfirm = viewModel::dismissDialog,
            onDismiss = viewModel::dismissDialog,
        )
        null -> Unit
    }
    state.withdrawFailed?.let { failure ->
        val reason = withdrawFailureReason(failure.reason)
        SprayConfirmDialog(
            title = stringResource(if (failure.deleting) R.string.spray_delete_failed_title else R.string.spray_withdraw_failed_title),
            text = buildString {
                append(reason)
                if (failure.deleting) append(" ").append(stringResource(R.string.spray_delete_failed_note))
                if (failure.keptEdits) append(" ").append(stringResource(R.string.spray_withdraw_failed_kept_edits))
            },
            confirmLabel = stringResource(R.string.action_ok),
            onConfirm = viewModel::dismissWithdrawFailure,
            onDismiss = viewModel::dismissWithdrawFailure,
            dismissLabel = null,
        )
    }
    if (confirmDelete) {
        SprayConfirmDialog(
            title = stringResource(R.string.spray_wall_delete_confirm_title, state.name),
            text = stringResource(R.string.spray_wall_delete_confirm_text) +
                if (state.published) " " + stringResource(R.string.spray_wall_delete_public_note) else "",
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { confirmDelete = false; viewModel.delete() },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** Why taking a wall back did not work, in one sentence. */
@Composable
internal fun withdrawFailureReason(reason: SprayWithdrawal): String = stringResource(
    when (reason) {
        SprayWithdrawal.NO_IDENTITY -> R.string.spray_withdraw_failed_identity
        SprayWithdrawal.SIGNING_FAILED -> R.string.spray_withdraw_failed_signing
        SprayWithdrawal.PHOTOS_LEFT -> R.string.spray_withdraw_failed_photos
        else -> R.string.spray_withdraw_failed_relays
    },
)

/**
 * A withdrawal the relays took part of (a photo left on its server, a declined signature): nothing of the wall is
 * published until it is finished, so trying again is the one thing offered.
 */
@Composable
internal fun WithdrawUnfinishedCard(withdrawing: Boolean, enabled: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = modifier.fillMaxWidth().testTag("spray_withdraw_unfinished"),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.spray_withdraw_unfinished))
            Button(onClick = onRetry, enabled = enabled && !withdrawing, modifier = Modifier.testTag("spray_withdraw_again")) {
                if (withdrawing) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.spray_wall_withdrawing), modifier = Modifier.padding(start = 8.dp))
                } else {
                    Text(stringResource(R.string.spray_withdraw_again))
                }
            }
        }
    }
}
