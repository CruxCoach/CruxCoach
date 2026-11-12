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
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
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
import com.cruxcoach.android.ui.common.InfoButton
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /** Taking the wall off the relays and Blossom, from here or the wall page: that waits for them, up to half a minute. */
    val withdrawing: Boolean = false,
    /** The map's venues while the gym picker is open (null = still loading). */
    val venues: List<MapVenue>? = null,
    val pickingGym: Boolean = false,
    val dialog: Dialog? = null,
    /** The last withdrawal did not work: the wall is still public (and still here, when it was to be deleted). */
    val withdrawFailed: WithdrawFailure? = null,
) {
    enum class Dialog { CONFIRM_PUBLIC, NO_IDENTITY, CONFIRM_WITHDRAW }

    /**
     * [keptEdits]: the name, angle or gym changes were saved all the same. [started]: the relays took part of the
     * withdrawal, so they no longer show the wall, but it is not finished.
     */
    data class WithdrawFailure(val reason: SprayWithdrawal, val deleting: Boolean, val keptEdits: Boolean = false, val started: Boolean = false)

    val canSave: Boolean get() = !loading && !saving && !withdrawing && name.isNotBlank()

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

    /** The climber chose who sees the wall on this screen; until then it shows (and saves) what is stored. */
    private var visibilityChosen = false

    /**
     * One read of the wall at a time, and it is on the screen before the next one is taken. Every change, the start
     * and end of a withdrawal and a failed withdrawal each read the wall on their own; unordered, a read from before a
     * write could land after a newer one and put back what the wall was (the mark of a withdrawal, say).
     */
    private val wallRead = Mutex()

    init {
        // Also every later change: a withdrawal the wall page finished while this screen is open, a first publish that
        // ended. The name, angle and gym are read once; they are what the climber edits here.
        viewModelScope.launch { repository.changes.collect { refresh() } }
        viewModelScope.launch {
            publisher.withdrawing.collect { walls ->
                _state.update { it.copy(withdrawing = wallId in walls) }
                refresh()
            }
        }
    }

    private suspend fun refresh() = wallRead.withLock {
        val wall = repository.wall(wallId)
        _state.update { s ->
            when {
                wall != null && wall.isMine -> {
                    val read = if (s.loading) s.copy(loading = false, name = wall.name, angle = wall.angle, gym = wall.gym) else s
                    read.copy(
                        visibility = if (visibilityChosen) s.visibility else wall.visibility,
                        published = wall.visibility == SprayWallVisibility.PUBLIC && wall.publishedVersion != null,
                        // A withdrawal marks the wall when it starts: while it runs, the screen stays as it was.
                        withdrawStarted = if (s.withdrawing && !s.loading) s.withdrawStarted else wall.withdrawStarted != null,
                    )
                }
                // Once shown, a wall deleted here stays on screen until the screen closes.
                s.loading -> s.copy(loading = false, notFound = true)
                else -> s
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
        if (dialog == null) visibilityChosen = true
        _state.update { if (dialog == null) it.copy(visibility = visibility) else it.copy(dialog = dialog) }
    }

    fun confirmDialog() {
        val chosen = when (_state.value.dialog) {
            SprayWallDetailsState.Dialog.CONFIRM_PUBLIC -> SprayWallVisibility.PUBLIC
            SprayWallDetailsState.Dialog.CONFIRM_WITHDRAW -> SprayWallVisibility.PRIVATE
            else -> null
        }
        if (chosen != null) visibilityChosen = true
        _state.update { it.copy(visibility = chosen ?: it.visibility, dialog = null) }
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
            if (before == null) {
                // Deleted meanwhile (on another screen): nothing left to save.
                _events.emit(SprayWallDetailsEvent.Saved)
                return@launch
            }
            // As stored, unless chosen here: a wall withdrawn on the wall page while this screen was open stays private.
            val visibility = if (visibilityChosen) s.visibility else before.visibility
            val changed = !before.hasDetails(s)
            if (before.visibility == SprayWallVisibility.PUBLIC && visibility == SprayWallVisibility.PRIVATE) {
                // Taking a public wall back. The details first, with it, so leaving the screen while it runs loses none
                // of them. Their head is held back by the withdrawal's mark: it goes out only if no relay took a
                // tombstone, and turning private drops it.
                val result = withdraw(before = {
                    repository.updateWallDetails(wallId, s.name, s.angle, SprayWallVisibility.PUBLIC, s.gym, sendHead = changed)
                })
                if (result != SprayWithdrawal.DONE) {
                    val started = failed(result, deleting = false, keptEdits = changed)
                    // Declined in the signer: not asked at once to publish the wall the climber meant to make private.
                    if (changed && !started && result != SprayWithdrawal.SIGNING_FAILED) publisher.publishSoon()
                    return@launch
                }
            }
            // A public wall's name, angle and gym are in its head event, which only a new version would send again.
            // Any public wall: one whose first publish ends while this screen is open is published by now. Not one
            // the relays already took back in part: it waits for the withdrawal to finish.
            val sendHead = visibility == SprayWallVisibility.PUBLIC && changed && before.withdrawStarted == null
            repository.updateWallDetails(wallId, s.name, s.angle, visibility, s.gym, sendHead)
            if (visibility == SprayWallVisibility.PUBLIC) publisher.publishSoon()
            _events.emit(SprayWallDetailsEvent.Saved)
        }
    }

    fun delete() {
        if (_state.value.saving || _state.value.withdrawing) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            // A public wall is taken back first (also when a publish failed partway); afterwards nothing here could
            // withdraw it, so it stays until that worked. The withdrawal deletes it, also when this screen was left.
            if (repository.wall(wallId)?.visibility == SprayWallVisibility.PUBLIC) {
                val result = withdraw(whenDone = { repository.deleteWall(wallId) })
                if (result != SprayWithdrawal.DONE) {
                    failed(result, deleting = true, keptEdits = false)
                    return@launch
                }
            } else {
                repository.deleteWall(wallId)
            }
            _events.emit(SprayWallDetailsEvent.Deleted)
        }
    }

    /** A withdrawal the relays took part of: tries the rest again; done, the wall is private. */
    fun withdrawAgain() {
        if (_state.value.saving || _state.value.withdrawing) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = withdraw()
            if (result != SprayWithdrawal.DONE) {
                failed(result, deleting = false, keptEdits = false)
                return@launch
            }
            wallRead.withLock {
                visibilityChosen = false
                _state.update {
                    it.copy(saving = false, visibility = SprayWallVisibility.PRIVATE, published = false, withdrawStarted = false)
                }
            }
        }
    }

    /**
     * [SprayWithdrawal.DONE] when the wall is off Nostr and Blossom; anything else left it public. It runs on in the
     * publisher when this screen closes; [SprayWallDetailsState.withdrawing] follows it from there.
     */
    private suspend fun withdraw(
        before: suspend () -> Unit = {},
        whenDone: suspend () -> Unit = {},
    ): SprayWithdrawal = publisher.withdrawWall(wallId, before, whenDone)

    /**
     * The screen says why the withdrawal did not work; the wall stays public (and may now be withdrawn in part, the
     * result). Who sees the wall shows what is stored again.
     */
    private suspend fun failed(result: SprayWithdrawal, deleting: Boolean, keptEdits: Boolean): Boolean = wallRead.withLock {
        val started = repository.wall(wallId)?.withdrawStarted != null
        visibilityChosen = false
        _state.update {
            it.copy(
                saving = false,
                visibility = SprayWallVisibility.PUBLIC,
                withdrawStarted = started,
                withdrawFailed = SprayWallDetailsState.WithdrawFailure(result, deleting, keptEdits, started),
            )
        }
        started
    }

    private fun SprayWall.hasDetails(s: SprayWallDetailsState) = name == s.name.trim() && angle == s.angle && gym == s.gym
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SprayWallDetailsScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onDeleted: () -> Unit,
    onOpenAccount: () -> Unit = {},
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
                title = { SprayBarTitle(stringResource(if (state.first) R.string.spray_wall_new_title else R.string.spray_wall_edit_title)) },
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
            }
            if (state.withdrawStarted) {
                WithdrawUnfinishedCard(withdrawing = state.withdrawing, enabled = !state.saving, onRetry = viewModel::withdrawAgain)
            } else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SprayFieldLabel(stringResource(R.string.spray_visibility_title), stringResource(R.string.spray_visibility_info))
                // The app's two-way choice: the selected side carries the check and the fill.
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(
                        Triple(SprayWallVisibility.PRIVATE, R.string.spray_visibility_private, "spray_visibility_private"),
                        Triple(SprayWallVisibility.PUBLIC, R.string.spray_visibility_public, "spray_visibility_public"),
                    ).forEachIndexed { index, (visibility, label, tag) ->
                        SegmentedButton(
                            selected = state.visibility == visibility,
                            onClick = { viewModel.chooseVisibility(visibility) },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                            // At the largest fonts the check would push the label out of a 320 dp row; the fill still marks the choice.
                            icon = { if (LocalDensity.current.fontScale < 1.5f) SegmentedButtonDefaults.Icon(state.visibility == visibility) },
                            label = { Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.testTag(tag),
                        )
                    }
                }
                Text(
                    stringResource(if (state.visibility == SprayWallVisibility.PUBLIC) R.string.spray_visibility_public_hint else R.string.spray_visibility_private_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SprayFieldLabel(stringResource(R.string.spray_gym_title), stringResource(R.string.spray_gym_hint))
                OutlinedButton(onClick = viewModel::openGymPicker, modifier = Modifier.fillMaxWidth().testTag("spray_gym_pick")) {
                    Icon(if (state.gym == null) Icons.Default.Home else Icons.Default.Place, contentDescription = null)
                    Text(state.gym?.name?.ifBlank { null } ?: stringResource(R.string.spray_gym_none), modifier = Modifier.padding(start = 8.dp))
                }
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
            // Straight to where the key is set up, instead of a path through Settings to remember.
            confirmLabel = stringResource(R.string.spray_publish_setup_account),
            onConfirm = { viewModel.dismissDialog(); onOpenAccount() },
            onDismiss = viewModel::dismissDialog,
        )
        null -> Unit
    }
    state.withdrawFailed?.let { failure ->
        val reason = withdrawFailureReason(failure.reason, failure.started)
        SprayConfirmDialog(
            title = stringResource(
                when {
                    failure.deleting -> R.string.spray_delete_failed_title
                    failure.started -> R.string.spray_withdraw_unfinished_title
                    else -> R.string.spray_withdraw_failed_title
                },
            ),
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

/**
 * Why taking a wall back did not work, in one sentence. [started]: the relays took part of it, so the wall is no longer
 * public there, but the withdrawal is not finished.
 */
@Composable
internal fun withdrawFailureReason(reason: SprayWithdrawal, started: Boolean): String = stringResource(
    when (reason) {
        SprayWithdrawal.NO_IDENTITY -> R.string.spray_withdraw_failed_identity
        SprayWithdrawal.SIGNING_FAILED ->
            if (started) R.string.spray_withdraw_failed_signing_started else R.string.spray_withdraw_failed_signing
        SprayWithdrawal.PHOTOS_LEFT -> R.string.spray_withdraw_failed_photos
        else -> if (started) R.string.spray_withdraw_failed_relays_started else R.string.spray_withdraw_failed_relays
    },
)

/**
 * A withdrawal the relays took part of (a photo left on its server, a declined signature): nothing of the wall is
 * published until it is finished, so trying again is the one thing offered. While one runs (also one started on
 * another screen, which marks the wall from its start) only its progress shows.
 */
@Composable
internal fun WithdrawUnfinishedCard(withdrawing: Boolean, enabled: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = modifier.fillMaxWidth().testTag("spray_withdraw_unfinished"),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!withdrawing) Text(stringResource(R.string.spray_withdraw_unfinished))
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

/** A form field's label with its explanation behind the info symbol. */
@Composable
private fun SprayFieldLabel(label: String, info: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        InfoButton(label, info)
    }
}
