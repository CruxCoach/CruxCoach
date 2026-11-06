package com.cruxcoach.android.ui.paintbox

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cruxcoach.android.R
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ui.board.*
import com.cruxcoach.android.ui.settings.BoardPickerDialog
import com.cruxcoach.android.ui.settings.ColorPickerBottomSheet
import com.cruxcoach.android.ui.theme.rgb332ToComposeColor
import com.cruxcoach.android.util.PerfLogger
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaintBoxScreen(onBack: () -> Unit, onDownloadBoards: () -> Unit, viewModel: PaintBoxViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val document = state.document
    val brand = document?.board?.brand?.let(BoardBrand::fromWire) ?: BoardBrand.KILTER
    var boardPicker by remember { mutableStateOf(false) }
    var bluetooth by remember { mutableStateOf(false) }
    var colorPicker by remember { mutableStateOf(false) }
    var library by remember { mutableStateOf(false) }
    var clearConfirm by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var askUnsaved by remember { mutableStateOf(false) }
    var saveDialog by rememberSaveable { mutableStateOf(false) }
    var saveCopy by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        viewModel.enter()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.enter()
                Lifecycle.Event.ON_STOP -> viewModel.leave()
                else -> Unit
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); viewModel.leave() }
    }
    LaunchedEffect(Unit) { PerfLogger.milestone("PAINTBOX composable entered") }
    val message = state.message?.let { stringResource(it) }
    LaunchedEffect(message) {
        if (message != null) { snackbar.showSnackbar(message); viewModel.message(null) }
    }
    fun navigate(action: () -> Unit) {
        viewModel.pause()
        if (state.dirty) { pendingAction = action; askUnsaved = true } else action()
    }
    BackHandler { navigate(onBack) }
    Scaffold(
        modifier = Modifier.testTag("paintbox_screen"),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.paintbox_title)) },
                navigationIcon = { IconButton(onClick = { navigate(onBack) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                } },
                actions = {
                    IconButton(onClick = { bluetooth = true }) {
                        Icon(Icons.Outlined.Bluetooth, stringResource(R.string.paintbox_bluetooth))
                    }
                    IconToggleButton(checked = state.live, onCheckedChange = { viewModel.toggleLive() },
                        enabled = document != null && !state.loading && !state.missingBoard) {
                        Icon(if (state.live) Icons.Filled.Lightbulb else Icons.Outlined.Lightbulb,
                            stringResource(if (state.live) R.string.paintbox_live_on else R.string.paintbox_live_off))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            TextButton(onClick = { navigate { boardPicker = true } }, enabled = !state.saving) {
                Text(document?.let { it.board.label.ifBlank { brand.displayName } }
                    ?: stringResource(R.string.paintbox_board))
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = !state.eraser, onClick = { viewModel.brush(state.brush) }, label = { Text(stringResource(R.string.paintbox_brush)) })
                FilterChip(selected = state.eraser, onClick = viewModel::eraser, label = { Text(stringResource(R.string.paintbox_eraser)) })
                AssistChip(onClick = { colorPicker = true }, label = { Text(stringResource(R.string.paintbox_color)) },
                    leadingIcon = { Icon(Icons.Outlined.Palette, null, tint = rgb332ToComposeColor(state.brush)) })
                IconButton(onClick = viewModel::undo, enabled = state.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.paintbox_undo)) }
                IconButton(onClick = viewModel::redo, enabled = state.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.paintbox_redo)) }
                IconButton(onClick = { clearConfirm = true }, enabled = document?.colors?.isNotEmpty() == true) { Icon(Icons.Outlined.Delete, stringResource(R.string.paintbox_clear)) }
            }
            Text(stringResource(R.string.paintbox_gestures), style = MaterialTheme.typography.bodySmall)
            if (brand == BoardBrand.QUANTUM) Text(stringResource(R.string.paintbox_quantum_hint), style = MaterialTheme.typography.bodySmall)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when {
                    state.loading -> CircularProgressIndicator()
                    state.missingBoard -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.paintbox_missing_board))
                        TextButton(onClick = { navigate(onDownloadBoards) }) { Text(stringResource(R.string.paintbox_download)) }
                        TextButton(onClick = { document?.let(viewModel::load) }) { Text(stringResource(R.string.paintbox_retry)) }
                    }
                    document != null -> key(document.board) {
                        if (brand == BoardBrand.MOONBOARD) {
                            MoonBoardVisualization(
                                frames = PaintPalette.moonFrames(document.colors),
                                assetState = rememberMoonBoardAsset(document.board.layoutId.toLong()),
                                variant = MoonBoardVariant.fromLayoutId(document.board.layoutId.toLong()),
                                editable = true,
                                paintableHolds = document.board.moonHoldIds(),
                                paintColors = document.colors,
                                modifier = Modifier,
                                onPaint = viewModel::paint, onPaintStart = viewModel::beginStroke, onPaintEnd = viewModel::endStroke,
                            )
                        } else KilterBoardVisualization(
                            holds = emptyList(), placements = state.placements, boardSize = state.boardSize,
                            boardImages = state.images, paintColors = document.colors,
                            onPaint = viewModel::paint, onPaintStart = viewModel::beginStroke, onPaintEnd = viewModel::endStroke,
                            allowZoom = true, modifier = Modifier,
                        )
                    }
                }
            }
            if (state.sending) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { name = document?.name.orEmpty(); saveCopy = false; saveDialog = true }, enabled = document != null && !state.loading && !state.saving) { Text(stringResource(R.string.paintbox_save)) }
                TextButton(onClick = { name = document?.name.orEmpty(); saveCopy = true; saveDialog = true }, enabled = document != null && !state.loading && !state.saving) { Text(stringResource(R.string.paintbox_save_as)) }
                TextButton(onClick = { viewModel.refreshLibrary(); library = true }) { Text(stringResource(R.string.paintbox_load)) }
                TextButton(onClick = viewModel::sendOnce, enabled = connection == ConnectionState.CONNECTED && !state.loading && !state.sending && !state.missingBoard) { Text(stringResource(R.string.paintbox_send)) }
            }
        }
    }
    if (bluetooth) BleConnectionSheet(onDismiss = { bluetooth = false }, onBoardMismatchExit = { bluetooth = false; navigate { boardPicker = true } })
    if (boardPicker) BoardPickerDialog(onDismiss = { boardPicker = false }, onSelected = { boardPicker = false; viewModel.selectCurrentBoard() })
    if (colorPicker) ColorPickerBottomSheet(
        roleLabel = stringResource(R.string.paintbox_brush), currentByte = state.brush,
        onColorSelected = { viewModel.brush(it); colorPicker = false }, onDismiss = { colorPicker = false },
        allowedColors = PaintPalette.forBoard(brand).toSet(),
    )
    if (clearConfirm) AlertDialog(onDismissRequest = { clearConfirm = false },
        title = { Text(stringResource(R.string.paintbox_clear)) }, text = { Text(stringResource(R.string.paintbox_clear_confirm)) },
        confirmButton = { TextButton(onClick = { clearConfirm = false; viewModel.clear() }) { Text(stringResource(R.string.paintbox_clear)) } },
        dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text(stringResource(R.string.action_cancel)) } })
    if (askUnsaved) AlertDialog(onDismissRequest = { askUnsaved = false; pendingAction = null },
        title = { Text(stringResource(R.string.paintbox_unsaved)) },
        confirmButton = { TextButton(onClick = { askUnsaved = false; name = document?.name.orEmpty(); saveCopy = false; saveDialog = true }) { Text(stringResource(R.string.paintbox_save)) } },
        dismissButton = { Row {
            TextButton(onClick = { askUnsaved = false; pendingAction?.invoke(); pendingAction = null }) { Text(stringResource(R.string.paintbox_discard)) }
            TextButton(onClick = { askUnsaved = false; pendingAction = null }) { Text(stringResource(R.string.action_cancel)) }
        } })
    if (saveDialog) AlertDialog(onDismissRequest = { if (!state.saving) { saveDialog = false; pendingAction = null } },
        title = { Text(stringResource(if (saveCopy) R.string.paintbox_save_as else R.string.paintbox_save)) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
            enabled = !state.saving, label = { Text(stringResource(R.string.paintbox_name)) }) },
        confirmButton = { TextButton(enabled = name.isNotBlank() && !state.saving, onClick = {
            viewModel.save(name, saveCopy) { saveDialog = false; pendingAction?.invoke(); pendingAction = null }
        }) { Text(stringResource(R.string.paintbox_save)) } },
        dismissButton = { TextButton(enabled = !state.saving, onClick = { saveDialog = false; pendingAction = null }) { Text(stringResource(R.string.action_cancel)) } })
    if (library) PaintLibraryDialog(state.library, viewModel, onDismiss = { library = false }, onLoad = {
        library = false; navigate { viewModel.load(it) }
    })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaintLibraryDialog(library: PaintLibrary, viewModel: PaintBoxViewModel, onDismiss: () -> Unit, onLoad: (PaintDocument) -> Unit) {
    var deleting by remember { mutableStateOf<PaintDocument?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.paintbox_load), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
        if (library.unreadable > 0) Text(stringResource(R.string.paintbox_unreadable, library.unreadable), modifier = Modifier.padding(16.dp))
        if (library.pictures.isEmpty()) Text(stringResource(R.string.paintbox_empty), modifier = Modifier.padding(16.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            library.pictures.groupBy { it.board.brand }.toSortedMap().forEach { (brand, pictures) ->
                item(key = brand) { Text(BoardBrand.fromWire(brand).displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp)) }
                items(pictures, key = { it.id }) { picture ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        PaintThumbnail(picture, viewModel, Modifier.size(84.dp))
                        TextButton(onClick = { onLoad(picture) }, modifier = Modifier.weight(1f)) {
                            Column {
                                Text(picture.name)
                                Text(picture.board.label.ifBlank { BoardBrand.fromWire(picture.board.brand).displayName },
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        IconButton(onClick = { deleting = picture }) { Icon(Icons.Outlined.Delete, stringResource(R.string.paintbox_delete)) }
                    }
                }
            }
        }
    }
    deleting?.let { picture -> AlertDialog(onDismissRequest = { deleting = null },
        title = { Text(stringResource(R.string.paintbox_delete)) }, text = { Text(picture.name) },
        confirmButton = { TextButton(onClick = { deleting = null; viewModel.delete(picture) }) { Text(stringResource(R.string.paintbox_delete)) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } }) }
}

@Composable
private fun PaintThumbnail(document: PaintDocument, vm: PaintBoxViewModel, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
    if (document.board.brand == "moonboard") {
        MoonBoardVisualization(PaintPalette.moonFrames(document.colors), rememberMoonBoardAsset(document.board.layoutId.toLong()),
            paintColors = document.colors,
            variant = MoonBoardVariant.fromLayoutId(document.board.layoutId.toLong()))
    } else {
        val preview by produceState<PaintBoxState?>(null, document.board) { value = vm.preview(document) }
        val data = preview
        if (data != null) KilterBoardVisualization(emptyList(), data.placements, data.boardSize, data.images, paintColors = document.colors)
    }
    }
}
