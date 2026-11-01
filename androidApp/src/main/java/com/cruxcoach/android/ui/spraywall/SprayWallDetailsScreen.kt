package com.cruxcoach.android.ui.spraywall

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.cruxcoach.android.ui.map.MapVenue
import com.cruxcoach.domain.spraywall.SprayGym
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
    val saving: Boolean = false,
    /** The map's venues while the gym picker is open (null = still loading). */
    val venues: List<MapVenue>? = null,
    val pickingGym: Boolean = false,
    val dialog: Dialog? = null,
) {
    enum class Dialog { CONFIRM_PUBLIC, NO_IDENTITY, CONFIRM_WITHDRAW }

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

    init {
        viewModelScope.launch {
            val wall = repository.wall(wallId)
            if (wall == null || !wall.isMine) {
                _state.update { it.copy(loading = false, notFound = true) }
                return@launch
            }
            _state.update {
                it.copy(
                    loading = false, name = wall.name, angle = wall.angle, visibility = wall.visibility, gym = wall.gym,
                    published = wall.visibility == SprayWallVisibility.PUBLIC && wall.publishedVersion != null,
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
            // Taking a published wall back: tombstones first, while it is still known as public.
            if (s.published && s.visibility == SprayWallVisibility.PRIVATE) publisher.withdrawWall(wallId)
            repository.updateWallDetails(wallId, s.name, s.angle, s.visibility, s.gym)
            if (s.visibility == SprayWallVisibility.PUBLIC) publisher.publishSoon()
            _events.emit(SprayWallDetailsEvent.Saved)
        }
    }

    fun delete() {
        viewModelScope.launch {
            // A published wall is taken back from Nostr first; afterwards nothing here could withdraw it.
            if (_state.value.published) publisher.withdrawWall(wallId)
            repository.deleteWall(wallId)
            _events.emit(SprayWallDetailsEvent.Deleted)
        }
    }
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
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                Text(stringResource(if (state.first) R.string.action_done else R.string.action_save))
            }
            if (!state.first && !state.loading) {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.testTag("spray_wall_delete")) {
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
            text = stringResource(R.string.spray_publish_confirm_text),
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
