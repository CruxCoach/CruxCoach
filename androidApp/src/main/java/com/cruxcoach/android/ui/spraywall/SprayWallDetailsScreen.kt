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
import androidx.compose.material3.Button
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
import com.cruxcoach.android.spraywall.SprayWallRepository
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

/** A wall's details: name and angle. [first] = the last step of adding the wall. */
data class SprayWallDetailsState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val first: Boolean = false,
    val name: String = "",
    val angle: Int = SprayWallPhotoViewModel.DEFAULT_ANGLE,
    val visibility: SprayWallVisibility = SprayWallVisibility.PRIVATE,
    val gym: SprayGym? = null,
    val saving: Boolean = false,
) {
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
            _state.update { it.copy(loading = false, name = wall.name, angle = wall.angle, visibility = wall.visibility, gym = wall.gym) }
        }
    }

    fun setName(name: String) = _state.update { it.copy(name = name.take(SprayWallDetailsState.NAME_LIMIT)) }

    fun setAngle(angle: Int) = _state.update { it.copy(angle = angle.coerceIn(0, SprayWallDetailsState.MAX_ANGLE)) }

    fun save() {
        val s = _state.value
        if (!s.canSave) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            repository.updateWallDetails(wallId, s.name, s.angle, s.visibility, s.gym)
            _events.emit(SprayWallDetailsEvent.Saved)
        }
    }

    fun delete() {
        viewModelScope.launch {
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
            Button(
                onClick = viewModel::save,
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth().testTag("spray_wall_save"),
            ) {
                Text(stringResource(if (state.first) R.string.action_done else R.string.action_save))
            }
            Text(stringResource(R.string.spray_walls_private_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!state.first && !state.loading) {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.testTag("spray_wall_delete")) {
                    Text(stringResource(R.string.spray_wall_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (confirmDelete) {
        SprayConfirmDialog(
            title = stringResource(R.string.spray_wall_delete_confirm_title, state.name),
            text = stringResource(R.string.spray_wall_delete_confirm_text),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { confirmDelete = false; viewModel.delete() },
            onDismiss = { confirmDelete = false },
        )
    }
}
