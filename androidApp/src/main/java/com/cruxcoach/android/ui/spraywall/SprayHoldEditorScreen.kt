package com.cruxcoach.android.ui.spraywall

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.spraywall.SprayWallPhotoStore
import com.cruxcoach.android.spraywall.SprayWallRepository
import com.cruxcoach.domain.spraywall.EditableHold
import com.cruxcoach.domain.spraywall.SprayHoldEditorState
import com.cruxcoach.domain.spraywall.SprayWall
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SprayHoldEditorUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val wall: SprayWall? = null,
    val photo: ImageBitmap? = null,
    val editor: SprayHoldEditorState = SprayHoldEditorState(holds = emptyList()),
    /** The holds as last saved, to tell whether there is anything to save. */
    val saved: List<EditableHold> = emptyList(),
    val addMode: Boolean = false,
    val saving: Boolean = false,
) {
    val dirty: Boolean get() = editor.holds != saved
}

sealed interface SprayHoldEditorMessage {
    data class Saved(val keptForClimbs: Int) : SprayHoldEditorMessage
    data object Failed : SprayHoldEditorMessage
    data object Limit : SprayHoldEditorMessage
}

@HiltViewModel
class SprayHoldEditorViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: SprayWallRepository,
    private val photos: SprayWallPhotoStore,
) : ViewModel() {
    private val wallId: String = savedState[SprayWallRoutes.ARG_WALL] ?: ""
    private val _state = MutableStateFlow(SprayHoldEditorUiState())
    val state: StateFlow<SprayHoldEditorUiState> = _state.asStateFlow()
    private val _messages = MutableSharedFlow<SprayHoldEditorMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<SprayHoldEditorMessage> = _messages.asSharedFlow()

    init {
        viewModelScope.launch {
            val wall = repository.wall(wallId)
            if (wall == null) {
                _state.update { it.copy(loading = false, notFound = true) }
                return@launch
            }
            val editor = SprayHoldEditorState.from(repository.holds(wallId))
            // An empty wall starts in Add mode: there is nothing to pick yet.
            _state.update { it.copy(loading = false, wall = wall, editor = editor, saved = editor.holds, addMode = editor.holds.isEmpty()) }
            val bitmap = photos.load(wall.photoFile)?.asImageBitmap()
            _state.update { it.copy(photo = bitmap) }
        }
    }

    private fun edit(block: (SprayHoldEditorState) -> SprayHoldEditorState) = _state.update { it.copy(editor = block(it.editor)) }

    private fun addAt(x: Float, y: Float) {
        if (!_state.value.editor.canAdd) {
            _messages.tryEmit(SprayHoldEditorMessage.Limit)
            return
        }
        edit { it.add(x, y) }
    }

    fun onTap(x: Float, y: Float, holdId: Long?) {
        val editor = _state.value.editor
        when {
            holdId != null -> edit { it.select(if (editor.selectedId == holdId) null else holdId) }
            _state.value.addMode -> addAt(x, y)
            else -> edit { it.select(null) }
        }
    }

    /** Press and hold: on a hold it picks it up for a move, on bare wall it places a new hold under the finger. */
    fun onLongPress(x: Float, y: Float, holdId: Long?) {
        if (holdId != null) edit { it.select(holdId).beginMove() } else addAt(x, y)
    }

    fun onDragStart() = edit { it.beginMove() }
    fun onDrag(x: Float, y: Float) = edit { it.moveSelected(x, y) }
    fun onResizeStart() = edit { it.beginResize() }
    fun onResize(radius: Float) = edit { it.resizeSelected(radius) }
    fun deleteSelected() = edit { it.deleteSelected() }
    fun undo() = edit { it.undo() }
    fun redo() = edit { it.redo() }
    fun setAddMode(on: Boolean) = _state.update { it.copy(addMode = on) }

    fun save() {
        val s = _state.value
        if (s.saving || !s.dirty) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val result = repository.saveHolds(wallId, s.editor.holds)
                // Reload so new holds carry their stored ids; undo starts afresh.
                val editor = SprayHoldEditorState.from(repository.holds(wallId)).copy(lastRadius = s.editor.lastRadius)
                _state.update { it.copy(saving = false, editor = editor, saved = editor.holds) }
                _messages.emit(SprayHoldEditorMessage.Saved(result.keptForClimbs))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saving = false) }
                _messages.emit(SprayHoldEditorMessage.Failed)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SprayHoldEditorScreen(
    onBack: () -> Unit,
    viewModel: SprayHoldEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var confirmLeave by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    val leave: () -> Unit = { if (state.dirty) { confirmLeave = true } else { onBack() } }
    BackHandler(enabled = state.dirty) { confirmLeave = true }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            val text = when (message) {
                is SprayHoldEditorMessage.Saved ->
                    if (message.keptForClimbs > 0) context.getString(R.string.spray_holds_saved_kept, message.keptForClimbs)
                    else context.getString(R.string.spray_holds_saved)
                SprayHoldEditorMessage.Failed -> context.getString(R.string.spray_holds_save_failed)
                SprayHoldEditorMessage.Limit -> context.getString(R.string.spray_holds_limit, SprayHoldEditorState.MAX_HOLDS)
            }
            snackbar.showSnackbar(text)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.spray_holds_title)) },
                navigationIcon = {
                    IconButton(onClick = leave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { showHelp = !showHelp }) {
                        Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.action_show_info, stringResource(R.string.spray_holds_title)))
                    }
                    TextButton(onClick = viewModel::save, enabled = state.dirty && !state.saving, modifier = Modifier.testTag("spray_holds_save")) {
                        Text(stringResource(R.string.action_save))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val wall = state.wall
        if (state.notFound || wall == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (state.notFound) Text(stringResource(R.string.spray_not_found))
            }
            return@Scaffold
        }
        val editor = state.editor
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (showHelp || (editor.holds.isEmpty() && !state.dirty)) {
                Text(
                    stringResource(R.string.spray_holds_help),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("spray_holds_help"),
                )
            }
            SprayWallCanvas(
                photo = state.photo,
                photoWidth = wall.photoWidth,
                photoHeight = wall.photoHeight,
                holds = editor.holds.map {
                    SprayCanvasHold(it.id, it.x, it.y, it.radius, if (it.id == editor.selectedId) SprayHoldLook.PICKED else SprayHoldLook.PLAIN)
                },
                gestures = SprayCanvasGestures(
                    onTap = viewModel::onTap,
                    onLongPress = viewModel::onLongPress,
                    draggableHoldId = editor.selectedId,
                    onDragStart = viewModel::onDragStart,
                    onDrag = viewModel::onDrag,
                    dragAfterLongPress = true,
                ),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    val selected = editor.selected
                    if (selected != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.spray_holds_size), style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.width(12.dp))
                            var resizing by remember(selected.id) { mutableStateOf(false) }
                            Slider(
                                value = selected.radius,
                                onValueChange = {
                                    if (!resizing) { resizing = true; viewModel.onResizeStart() }
                                    viewModel.onResize(it)
                                },
                                onValueChangeFinished = { resizing = false },
                                valueRange = SprayHoldEditorState.MIN_RADIUS..SprayHoldEditorState.MAX_RADIUS,
                                modifier = Modifier.weight(1f).testTag("spray_hold_size"),
                            )
                            IconButton(onClick = viewModel::deleteSelected, modifier = Modifier.testTag("spray_hold_delete")) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.spray_holds_delete))
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = viewModel::undo, enabled = editor.canUndo, modifier = Modifier.testTag("spray_holds_undo")) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.spray_holds_undo))
                        }
                        IconButton(onClick = viewModel::redo, enabled = editor.canRedo, modifier = Modifier.testTag("spray_holds_redo")) {
                            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = stringResource(R.string.spray_holds_redo))
                        }
                        Text(
                            stringResource(R.string.spray_holds_count, editor.holds.size),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f).padding(start = 8.dp).testTag("spray_holds_count"),
                        )
                        FilterChip(
                            selected = state.addMode,
                            onClick = { viewModel.setAddMode(!state.addMode) },
                            label = { Text(stringResource(R.string.spray_holds_mode_add)) },
                            leadingIcon = { Icon(Icons.Default.AddCircleOutline, contentDescription = null) },
                            modifier = Modifier.testTag("spray_holds_add_mode"),
                        )
                    }
                }
            }
        }
    }
    if (confirmLeave) {
        SprayDiscardDialog(onDiscard = { confirmLeave = false; onBack() }, onKeep = { confirmLeave = false })
    }
}
