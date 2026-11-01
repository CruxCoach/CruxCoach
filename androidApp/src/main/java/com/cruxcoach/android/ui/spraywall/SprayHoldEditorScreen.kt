package com.cruxcoach.android.ui.spraywall

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.cruxcoach.android.spraywall.HoldModel
import com.cruxcoach.android.spraywall.HoldModelStore
import com.cruxcoach.android.spraywall.OnnxHoldDetector
import com.cruxcoach.android.spraywall.SprayWallPhotoStore
import com.cruxcoach.android.spraywall.SprayWallRepository
import com.cruxcoach.domain.spraywall.EditableHold
import com.cruxcoach.domain.spraywall.SprayHoldEditorState
import com.cruxcoach.domain.spraywall.SpraySuggestion
import com.cruxcoach.domain.spraywall.SprayVersionKind
import com.cruxcoach.domain.spraywall.SprayWall
import com.cruxcoach.domain.spraywall.detect.RgbImage
import com.cruxcoach.domain.spraywall.detect.SegSettings
import com.cruxcoach.domain.spraywall.detect.SprayHoldDetector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SprayHoldEditorUiState(
    val loading: Boolean = true,
    /** Step 2 of adding a wall: saving goes on to the details. */
    val first: Boolean = false,
    val notFound: Boolean = false,
    val wall: SprayWall? = null,
    val photo: ImageBitmap? = null,
    val editor: SprayHoldEditorState = SprayHoldEditorState(holds = emptyList()),
    /** The holds as last saved, to tell whether there is anything to save. */
    val saved: List<EditableHold> = emptyList(),
    val addMode: Boolean = false,
    val saving: Boolean = false,
    val detecting: Boolean = false,
    /** The detector ran on this photo (so an empty result is a real result). */
    val detected: Boolean = false,
    /** Tiles done and in total while the hold model runs, for the scan overlay. */
    val detectProgress: Pair<Int, Int>? = null,
    /** The hold model: missing until the climber downloads it, then used instead of the classical detector. */
    val model: HoldModelStore.State = HoldModelStore.State.Missing,
) {
    val dirty: Boolean get() = editor.holds != saved
    /** A reset whose holds were never saved: leaving may drop it again. */
    val unfinishedReset: Boolean get() = wall?.current?.kind == SprayVersionKind.RESET && saved.isEmpty()
}

sealed interface SprayHoldEditorMessage {
    data class Saved(val newVersion: Boolean) : SprayHoldEditorMessage
    /** Adding a wall: the holds are stored, on to the details. */
    data object FirstDone : SprayHoldEditorMessage
    /** A reset has its holds: back to the wall. */
    data object ResetDone : SprayHoldEditorMessage
    data object Failed : SprayHoldEditorMessage
    data object Limit : SprayHoldEditorMessage
    data class Detected(val count: Int) : SprayHoldEditorMessage
    data object DetectFailed : SprayHoldEditorMessage
}

/** Canvas ids for suggestions, far away from real (positive) and new (small negative) hold ids. */
private const val SUGGESTION_ID_BASE = -1_000_000_000L

@HiltViewModel
class SprayHoldEditorViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: SprayWallRepository,
    private val photos: SprayWallPhotoStore,
    private val models: HoldModelStore,
) : ViewModel() {
    private val wallId: String = savedState[SprayWallRoutes.ARG_WALL] ?: ""
    private val _state = MutableStateFlow(SprayHoldEditorUiState(first = savedState.get<Boolean>(SprayWallRoutes.ARG_FIRST) == true))
    val state: StateFlow<SprayHoldEditorUiState> = _state.asStateFlow()
    private val _messages = MutableSharedFlow<SprayHoldEditorMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<SprayHoldEditorMessage> = _messages.asSharedFlow()

    private var segmentation: OnnxHoldDetector? = null

    init {
        viewModelScope.launch { models.state.collect { model -> _state.update { it.copy(model = model) } } }
        viewModelScope.launch {
            val wall = repository.wall(wallId)
            if (wall == null || !wall.isMine) {
                _state.update { it.copy(loading = false, notFound = true) }
                return@launch
            }
            val editor = SprayHoldEditorState.from(repository.holds(wallId, wall.current.number))
            // An empty wall starts in Add mode: there is nothing to pick yet.
            _state.update { it.copy(loading = false, wall = wall, editor = editor, saved = editor.holds, addMode = editor.holds.isEmpty()) }
            val bitmap = photos.load(wall.photoFile)?.asImageBitmap()
            _state.update { it.copy(photo = bitmap) }
            // A fresh photo: look for holds straight away.
            if (editor.holds.isEmpty()) detect()
        }
    }

    private fun edit(block: (SprayHoldEditorState) -> SprayHoldEditorState) = _state.update { it.copy(editor = block(it.editor)) }

    fun detect() {
        val wall = _state.value.wall ?: return
        if (_state.value.detecting) return
        _state.update { it.copy(detecting = true) }
        viewModelScope.launch {
            try {
                val useModel = models.isReady()
                val found = withContext(Dispatchers.Default) {
                    val candidates = if (useModel) {
                        val image = photos.bitmap(wall.photoFile, MODEL_SETTINGS.longSide) ?: return@withContext emptyList()
                        val detector = segmentation ?: OnnxHoldDetector(models.file).also { segmentation = it }
                        detector.detect(image, MODEL_SETTINGS) { done, total -> _state.update { it.copy(detectProgress = done to total) } }
                            .candidates.also { image.recycle() }
                    } else {
                        val (w, h, pixels) = photos.pixels(wall.photoFile, DETECT_LONG_SIDE) ?: return@withContext emptyList()
                        SprayHoldDetector.detect(RgbImage(w, h, pixels))
                    }
                    candidates.map { SpraySuggestion(it.x, it.y, it.radius, it.confidence, it.outline) }
                }
                _state.update {
                    val editor = it.editor.withSuggestions(found, wall.aspectRatio)
                    // The model's scores mean something else than the classical detector's: start at its own threshold.
                    it.copy(
                        detecting = false,
                        detected = true,
                        detectProgress = null,
                        editor = if (useModel) editor.setThreshold(MODEL_SETTINGS.threshold) else editor,
                    )
                }
                _messages.emit(SprayHoldEditorMessage.Detected(_state.value.editor.visibleSuggestions.size))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(detecting = false, detectProgress = null) }
                _messages.emit(SprayHoldEditorMessage.DetectFailed)
            }
        }
    }

    private fun addAt(x: Float, y: Float) {
        if (!_state.value.editor.canAdd) {
            _messages.tryEmit(SprayHoldEditorMessage.Limit)
            return
        }
        edit { it.add(x, y) }
    }

    private fun suggestionFor(id: Long?): SpraySuggestion? {
        if (id == null || id > SUGGESTION_ID_BASE) return null
        val index = (SUGGESTION_ID_BASE - id).toInt()
        return _state.value.editor.visibleSuggestions.getOrNull(index)
    }

    fun onTap(x: Float, y: Float, holdId: Long?) {
        val editor = _state.value.editor
        val suggestion = suggestionFor(holdId)
        when {
            suggestion != null -> edit { it.accept(suggestion) }
            holdId != null -> edit { it.select(if (editor.selectedId == holdId) null else holdId) }
            _state.value.addMode -> addAt(x, y)
            else -> edit { it.select(null) }
        }
    }

    /** Press and hold: on a hold it picks it up for a move, on bare wall it places a new hold under the finger. */
    fun onLongPress(x: Float, y: Float, holdId: Long?) {
        val suggestion = suggestionFor(holdId)
        when {
            suggestion != null -> edit { it.accept(suggestion) }
            holdId != null -> edit { it.select(holdId).beginMove() }
            else -> addAt(x, y)
        }
    }

    fun onDragStart() = edit { it.beginMove() }
    fun onDrag(x: Float, y: Float) = edit { it.moveSelected(x, y) }
    fun onResizeStart() = edit { it.beginResize() }
    fun onResize(radius: Float) = edit { it.resizeSelected(radius) }
    fun deleteSelected() = edit { it.deleteSelected() }
    fun undo() = edit { it.undo() }
    fun redo() = edit { it.redo() }
    fun setAddMode(on: Boolean) = _state.update { it.copy(addMode = on) }
    fun setThreshold(value: Float) = edit { it.setThreshold(value) }
    fun acceptSuggestions() = edit { it.acceptVisible() }
    fun dismissSuggestions() = edit { it.dismissSuggestions() }

    fun save() {
        val s = _state.value
        if (s.saving) return
        if (!s.dirty) {
            if (s.first) _messages.tryEmit(SprayHoldEditorMessage.FirstDone)
            return
        }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val result = repository.saveHolds(wallId, s.editor.holds)
                // Reload so new holds carry their stored ids; undo starts afresh, open suggestions stay.
                val wall = repository.wall(wallId)
                val holds = repository.holds(wallId, result.version)
                val editor = SprayHoldEditorState.from(holds).copy(
                    lastRadius = s.editor.lastRadius,
                    threshold = s.editor.threshold,
                ).withSuggestions(s.editor.suggestions, s.wall?.aspectRatio ?: 1f)
                _state.update { it.copy(saving = false, wall = wall ?: it.wall, editor = editor, saved = editor.holds) }
                _messages.emit(
                    when {
                        s.first -> SprayHoldEditorMessage.FirstDone
                        // The first holds on a reset's photo finish the reset: back to the wall, like adding one goes on.
                        s.unfinishedReset -> SprayHoldEditorMessage.ResetDone
                        else -> SprayHoldEditorMessage.Saved(result.newVersion)
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saving = false) }
                _messages.emit(SprayHoldEditorMessage.Failed)
            }
        }
    }

    /** Leaving a reset before its holds were saved: the previous version becomes current again. */
    fun discardUnfinishedReset(onDone: () -> Unit) {
        viewModelScope.launch {
            repository.discardCurrentVersion(wallId)
            onDone()
        }
    }

    /** Downloads the hold model once, then looks for holds again with it. */
    fun downloadModel() {
        viewModelScope.launch { if (models.download()) detect() }
    }

    override fun onCleared() {
        segmentation?.close()
    }

    private companion object {
        /**
         * Tuned on 28 labelled wall photos: 3×3 tiles of a 1536 px working
         * photo find small holds the published 2×2 setting misses, a stricter
         * overlap (0.3) drops duplicates from neighbouring tiles, and 0.5 is
         * the best default threshold. The floor keeps unsure holds for the
         * sensitivity slider.
         */
        val MODEL_SETTINGS = SegSettings(
            longSide = 1536,
            rows = 3,
            cols = 3,
            threshold = 0.5f,
            floor = 0.3f,
            nmsIou = 0.3f,
            resampler = SegSettings.Resampler.NATIVE,
        )
        const val DETECT_LONG_SIDE = 1024
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SprayHoldEditorScreen(
    onBack: () -> Unit,
    onFirstSaved: () -> Unit,
    viewModel: SprayHoldEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var confirmLeave by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    val askBeforeLeaving = state.dirty || state.unfinishedReset
    val leave: () -> Unit = { if (askBeforeLeaving) { confirmLeave = true } else { onBack() } }
    BackHandler(enabled = askBeforeLeaving) { confirmLeave = true }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            if (message == SprayHoldEditorMessage.FirstDone) {
                onFirstSaved()
                return@collect
            }
            if (message == SprayHoldEditorMessage.ResetDone) {
                onBack()
                return@collect
            }
            val text = when (message) {
                SprayHoldEditorMessage.FirstDone, SprayHoldEditorMessage.ResetDone -> return@collect
                is SprayHoldEditorMessage.Saved ->
                    context.getString(if (message.newVersion) R.string.spray_holds_saved_version else R.string.spray_holds_saved)
                SprayHoldEditorMessage.Failed -> context.getString(R.string.spray_holds_save_failed)
                SprayHoldEditorMessage.Limit -> context.getString(R.string.spray_holds_limit, SprayHoldEditorState.MAX_HOLDS)
                is SprayHoldEditorMessage.Detected ->
                    if (message.count > 0) context.resources.getQuantityString(R.plurals.spray_detect_found, message.count, message.count)
                    else context.getString(R.string.spray_detect_none)
                SprayHoldEditorMessage.DetectFailed -> context.getString(R.string.spray_detect_failed)
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
                    IconButton(onClick = viewModel::detect, enabled = !state.detecting && state.wall != null, modifier = Modifier.testTag("spray_detect")) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = stringResource(R.string.spray_detect))
                    }
                    IconButton(onClick = { showHelp = !showHelp }) {
                        Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.action_show_info, stringResource(R.string.spray_holds_title)))
                    }
                    TextButton(
                        onClick = viewModel::save,
                        enabled = (state.dirty || state.first) && !state.saving && state.wall != null,
                        modifier = Modifier.testTag("spray_holds_save"),
                    ) {
                        Text(stringResource(if (state.first) R.string.action_next else R.string.action_save))
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
        val visible = editor.visibleSuggestions
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.first) Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { SprayStepLabel(step = 2) }
            if (showHelp || (state.first && editor.holds.isEmpty() && editor.suggestions.isEmpty() && !state.detecting)) {
                Text(
                    stringResource(R.string.spray_holds_help),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("spray_holds_help"),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                SprayWallCanvas(
                    photo = state.photo,
                    photoWidth = wall.photoWidth,
                    photoHeight = wall.photoHeight,
                    holds = visible.mapIndexed { i, s -> SprayCanvasHold(SUGGESTION_ID_BASE - i, s.x, s.y, s.radius, SprayHoldLook.SUGGESTED, outline = s.outline) } +
                        editor.holds.map {
                            SprayCanvasHold(it.id, it.x, it.y, it.radius, if (it.id == editor.selectedId) SprayHoldLook.PICKED else SprayHoldLook.PLAIN, outline = it.outline)
                        },
                    gestures = SprayCanvasGestures(
                        onTap = viewModel::onTap,
                        onLongPress = viewModel::onLongPress,
                        draggableHoldId = editor.selectedId,
                        onDragStart = viewModel::onDragStart,
                        onDrag = viewModel::onDrag,
                        dragAfterLongPress = true,
                    ),
                    modifier = Modifier.fillMaxSize(),
                )
                if (state.detecting) ScanOverlay(state.detectProgress, Modifier.fillMaxSize())
            }
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (state.model !is HoldModelStore.State.Ready) ModelCard(state.model, viewModel::downloadModel)
                    if (editor.suggestions.isNotEmpty()) SuggestionBar(editor, viewModel)
                    val selected = editor.selected
                    // The row keeps its height either way, so picking a hold does not shrink the photo under the finger.
                    if (selected == null) {
                        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterStart) {
                            Text(
                                stringResource(R.string.spray_holds_size_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("spray_hold_size_hint"),
                            )
                        }
                    } else {
                        Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
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
        if (state.unfinishedReset && !state.dirty) {
            SprayConfirmDialog(
                title = stringResource(R.string.spray_reset_unfinished_title),
                text = stringResource(R.string.spray_reset_unfinished_text),
                confirmLabel = stringResource(R.string.spray_reset_discard),
                dismissLabel = stringResource(R.string.spray_discard_keep),
                onConfirm = { confirmLeave = false; viewModel.discardUnfinishedReset(onBack) },
                onDismiss = { confirmLeave = false },
            )
        } else {
            SprayDiscardDialog(onDiscard = { confirmLeave = false; onBack() }, onKeep = { confirmLeave = false })
        }
    }
}

@Composable
private fun ModelCard(model: HoldModelStore.State, onDownload: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("spray_model")) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.spray_model_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(if (model == HoldModelStore.State.Failed) R.string.spray_model_failed else R.string.spray_model_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (model is HoldModelStore.State.Downloading) {
                val percent = (model.doneBytes * 100 / HoldModel.BYTES).toInt()
                Text(stringResource(R.string.spray_model_downloading, percent), style = MaterialTheme.typography.labelMedium)
            } else {
                Button(onClick = onDownload, modifier = Modifier.testTag("spray_model_download")) {
                    Text(stringResource(R.string.spray_model_download))
                }
            }
        }
    }
}

@Composable
private fun SuggestionBar(editor: SprayHoldEditorState, viewModel: SprayHoldEditorViewModel) {
    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("spray_suggestions")) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color(0xFFFFB300))
                val open = editor.visibleSuggestions.size
                // Everything shown was taken: say so, the slider can still show the less sure ones.
                Text(
                    if (open > 0) stringResource(R.string.spray_detect_suggestions, open) else stringResource(R.string.spray_detect_none_open),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                IconButton(onClick = viewModel::dismissSuggestions, modifier = Modifier.testTag("spray_suggestions_dismiss")) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.spray_detect_dismiss))
                }
            }
            if (editor.visibleSuggestions.isNotEmpty()) {
                Text(stringResource(R.string.spray_detect_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.spray_detect_sensitivity), style = MaterialTheme.typography.labelMedium)
                // More sensitive = a lower confidence threshold.
                Slider(
                    value = 1f - editor.threshold,
                    onValueChange = { viewModel.setThreshold(1f - it) },
                    valueRange = 0.05f..0.95f,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp).testTag("spray_detect_sensitivity"),
                )
                if (editor.visibleSuggestions.isNotEmpty()) {
                    Button(
                        onClick = viewModel::acceptSuggestions,
                        modifier = Modifier.testTag("spray_suggestions_accept"),
                    ) { Text(stringResource(R.string.spray_detect_accept_all)) }
                }
            }
        }
    }
}

/** A band sweeping down the photo while the detector looks for holds. */
@Composable
private fun ScanOverlay(tiles: Pair<Int, Int>?, modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "scan")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "band",
    )
    Box(modifier.testTag("spray_scanning"), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color.Black.copy(alpha = 0.35f))
            val band = size.height * 0.18f
            val y = -band + (size.height + band) * progress
            drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, Color(0x99FFB300), Color.Transparent), startY = y, endY = y + band),
                topLeft = Offset(0f, y),
                size = Size(size.width, band),
            )
        }
        Card {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(end = 12.dp))
                Text(
                    if (tiles == null) stringResource(R.string.spray_detect_running)
                    else stringResource(R.string.spray_detect_running_progress, tiles.first * 100 / tiles.second),
                )
            }
        }
    }
}
