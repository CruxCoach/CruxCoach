package com.cruxcoach.android.ui.paintbox

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ui.board.*
import com.cruxcoach.android.ui.common.InfoButton
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
    val importing = state.imageImport != null || state.imageLoading
    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.importImage(context, uri)
    }
    val brand = document?.board?.brand?.let(BoardBrand::fromWire) ?: BoardBrand.KILTER
    val ready = document != null && !state.loading && state.boardReady
    val connected = connection == ConnectionState.CONNECTED || connection == ConnectionState.SENDING
    var boardPicker by remember { mutableStateOf(false) }
    var bluetooth by remember { mutableStateOf(false) }
    var colorPicker by remember { mutableStateOf(false) }
    var library by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var clearConfirm by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var cancelAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var askUnsaved by remember { mutableStateOf(false) }
    var switchTo by remember { mutableStateOf<PaintDocument?>(null) }
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
    fun cancelPending() { pendingAction = null; cancelAction?.invoke(); cancelAction = null }
    fun runPending() { cancelAction = null; pendingAction?.invoke(); pendingAction = null }
    // The app's board changed while the picture has unsaved changes: ask once the sheet is closed.
    LaunchedEffect(state.boardChange, state.dirty, bluetooth, askUnsaved, saveDialog) {
        if (state.boardChange != null && !bluetooth && !askUnsaved && !saveDialog) {
            if (!state.dirty) { viewModel.openBoardChange(); return@LaunchedEffect }
            pendingAction = viewModel::openBoardChange
            cancelAction = viewModel::keepBoard
            askUnsaved = true
        }
    }
    LaunchedEffect(bluetooth) { if (bluetooth) viewModel.connectionSheetOpened() }
    fun back() { if (importing) viewModel.cancelImage() else navigate(onBack) }
    fun openSave(copy: Boolean) { name = document?.name.orEmpty(); saveCopy = copy; saveDialog = true }
    BackHandler { back() }
    Scaffold(
        modifier = Modifier.testTag("paintbox_screen"),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (importing) R.string.paintbox_photo_title else R.string.paintbox_title),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    if (importing) IconButton(onClick = { back() }, modifier = Modifier.testTag("paintbox_photo_cancel")) {
                        Icon(Icons.Default.Close, stringResource(R.string.action_cancel))
                    } else IconButton(onClick = { back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (importing) {
                        InfoButton(stringResource(R.string.paintbox_photo_title), stringResource(R.string.paintbox_info_photo))
                        IconButton(
                            onClick = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            enabled = !state.imageLoading,
                        ) { Icon(Icons.Outlined.AddPhotoAlternate, stringResource(R.string.paintbox_other_photo)) }
                    } else {
                        val info = stringResource(R.string.paintbox_info) +
                            if (brand == BoardBrand.QUANTUM) "\n\n" + stringResource(R.string.paintbox_info_quantum) else ""
                        InfoButton(stringResource(R.string.paintbox_title), info)
                        IconButton(onClick = { bluetooth = true }, modifier = Modifier.testTag("paintbox_bluetooth")) {
                            Icon(if (connected) Icons.Outlined.BluetoothConnected else Icons.Outlined.Bluetooth,
                                stringResource(R.string.paintbox_bluetooth))
                        }
                        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("paintbox_menu")) {
                            Icon(Icons.Default.MoreVert, stringResource(R.string.action_more_options))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.paintbox_image_import)) },
                                onClick = { menu = false; imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                leadingIcon = { Icon(Icons.Outlined.AddPhotoAlternate, null) },
                                enabled = ready && !state.saving,
                                modifier = Modifier.testTag("paintbox_image_import"),
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.paintbox_load)) },
                                onClick = { menu = false; viewModel.refreshLibrary(); library = true },
                                leadingIcon = { Icon(Icons.Outlined.PhotoLibrary, null) },
                                modifier = Modifier.testTag("paintbox_pictures"),
                            )
                            val canSave = document != null && !state.loading && !state.saving && !state.unsupportedBoard
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.paintbox_save)) },
                                onClick = { menu = false; openSave(copy = false) },
                                leadingIcon = { Icon(Icons.Outlined.Save, null) },
                                enabled = canSave,
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.paintbox_save_as)) },
                                onClick = { menu = false; openSave(copy = true) },
                                leadingIcon = { Icon(Icons.Outlined.SaveAs, null) },
                                enabled = canSave,
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.paintbox_clear)) },
                                onClick = { menu = false; clearConfirm = true },
                                leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                enabled = document?.colors?.isNotEmpty() == true,
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            PaintBoxActions(
                state = state, importing = importing, ready = ready, connected = connected,
                onConnect = { bluetooth = true }, onSend = viewModel::sendOnce, onLive = { viewModel.toggleLive() },
                onAcceptImage = viewModel::acceptImage,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            if (document != null && !importing) Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clickable(enabled = !state.saving, role = Role.Button) { navigate { boardPicker = true } }
                    .padding(horizontal = 4.dp, vertical = 4.dp)
                    .testTag("paintbox_board"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(document.board.label.ifBlank { brand.displayName }, color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                Icon(Icons.Default.ArrowDropDown, null, tint = MaterialTheme.colorScheme.primary)
            }
            if (importing) PhotoTools(state, viewModel) else if (ready) PaintTools(state, viewModel) { colorPicker = true }
            val boardShown = !state.imageLoading && !state.loading && state.boardReady && document != null
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp),
                contentAlignment = if (boardShown) Alignment.TopCenter else Alignment.Center) {
                when {
                    state.imageLoading -> CircularProgressIndicator()
                    state.imageImport != null -> PaintImageEditor(state, viewModel)
                    state.loading -> CircularProgressIndicator()
                    state.unsupportedBoard -> BoardProblem(stringResource(R.string.paintbox_unsupported_board, brand.displayName)) {
                        Button(onClick = { navigate { boardPicker = true } }) {
                            Text(stringResource(R.string.paintbox_board), textAlign = TextAlign.Center)
                        }
                    }
                    state.missingBoard -> BoardProblem(stringResource(R.string.paintbox_missing_board)) {
                        Button(onClick = { navigate(onDownloadBoards) }) {
                            Text(stringResource(R.string.paintbox_download), textAlign = TextAlign.Center)
                        }
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
        }
    }
    // A board picked in the sheet's "other board" prompt reaches PaintBox as a board change,
    // which asks about unsaved changes once; opening PaintBox's own picker too would ask twice.
    if (bluetooth) BleConnectionSheet(onDismiss = { bluetooth = false }, onBoardMismatchExit = {
        bluetooth = false
        if (importing) viewModel.cancelImage()
    })
    if (boardPicker) BoardPickerDialog(onDismiss = { boardPicker = false }, onSelected = { boardPicker = false; viewModel.boardPicked() })
    if (colorPicker) ColorPickerBottomSheet(
        roleLabel = stringResource(R.string.paintbox_brush), currentByte = state.brush,
        onColorSelected = { viewModel.brush(it); colorPicker = false }, onDismiss = { colorPicker = false },
        allowedColors = PaintPalette.forBoard(brand).toSet(),
        // Colors a photo brought, or the pipette picked, that the named swatches do not offer.
        extraColors = PaintPalette.pictureColors(state.brush, document?.colors.orEmpty(), brand),
        extraLabel = stringResource(R.string.paintbox_colors_picture),
    )
    if (clearConfirm) AlertDialog(onDismissRequest = { clearConfirm = false },
        title = { Text(stringResource(R.string.paintbox_clear)) }, text = { Text(stringResource(R.string.paintbox_clear_confirm)) },
        confirmButton = { TextButton(onClick = { clearConfirm = false; viewModel.clear() }) { Text(stringResource(R.string.paintbox_clear)) } },
        dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text(stringResource(R.string.action_cancel)) } })
    if (askUnsaved) AlertDialog(onDismissRequest = { askUnsaved = false; cancelPending() },
        title = { Text(stringResource(R.string.paintbox_unsaved)) },
        text = if (state.boardChange != null) {
            { Text(stringResource(R.string.paintbox_board_changed)) }
        } else null,
        confirmButton = { TextButton(onClick = { askUnsaved = false; openSave(copy = false) }) { Text(stringResource(R.string.paintbox_save)) } },
        dismissButton = { Row {
            TextButton(onClick = { askUnsaved = false; runPending() }) { Text(stringResource(R.string.paintbox_discard)) }
            TextButton(onClick = { askUnsaved = false; cancelPending() }) { Text(stringResource(R.string.action_cancel)) }
        } })
    switchTo?.let { picture -> AlertDialog(onDismissRequest = { switchTo = null },
        title = { Text(stringResource(R.string.paintbox_switch_board)) },
        text = { Text(stringResource(R.string.paintbox_switch_board_text,
            picture.board.label.ifBlank { BoardBrand.fromWire(picture.board.brand).displayName })) },
        confirmButton = { TextButton(onClick = { switchTo = null; navigate { viewModel.load(picture) } }) {
            Text(stringResource(R.string.paintbox_switch))
        } },
        dismissButton = { TextButton(onClick = { switchTo = null }) { Text(stringResource(R.string.action_cancel)) } }) }
    // Send or Live refused: the kept picture is for another board than the app's (the connected box's).
    if (state.otherBoard && document != null) AlertDialog(onDismissRequest = viewModel::dismissOtherBoard,
        title = { Text(stringResource(R.string.paintbox_switch_board)) },
        text = { Text(stringResource(R.string.paintbox_other_board, document.board.label.ifBlank { brand.displayName })) },
        confirmButton = { TextButton(onClick = { viewModel.dismissOtherBoard(); navigate(viewModel::openAppBoard) }) {
            Text(stringResource(R.string.paintbox_switch))
        } },
        dismissButton = { TextButton(onClick = viewModel::dismissOtherBoard) { Text(stringResource(R.string.action_cancel)) } })
    if (saveDialog) AlertDialog(onDismissRequest = { if (!state.saving) { saveDialog = false; cancelPending() } },
        title = { Text(stringResource(if (saveCopy) R.string.paintbox_save_as else R.string.paintbox_save)) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
            enabled = !state.saving, label = { Text(stringResource(R.string.paintbox_name)) }) },
        confirmButton = { TextButton(enabled = name.isNotBlank() && !state.saving, onClick = {
            viewModel.save(name, saveCopy) { saveDialog = false; runPending() }
        }) { Text(stringResource(R.string.paintbox_save)) } },
        dismissButton = { TextButton(enabled = !state.saving, onClick = { saveDialog = false; cancelPending() }) { Text(stringResource(R.string.action_cancel)) } })
    if (library) PaintLibraryDialog(state.library, viewModel, onDismiss = { library = false }, onLoad = {
        library = false
        if (viewModel.switchesBoard(it)) switchTo = it else navigate { viewModel.load(it) }
    }, onSaveCurrent = if (document != null && state.boardReady && document.colors.isNotEmpty()) {
        { library = false; openSave(copy = false) }
    } else null)
}

/** Brush, eraser or pipette, the brush color, undo and redo: one row that fits 320 dp at any font size. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaintTools(state: PaintBoxState, viewModel: PaintBoxViewModel, onColor: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val toolColors = SegmentedButtonDefaults.colors(
            activeContainerColor = MaterialTheme.colorScheme.primary,
            activeContentColor = MaterialTheme.colorScheme.onPrimary,
        )
        // Three tools, the color, undo and redo are six 48 dp targets: 288 of the 296 dp a 320 dp screen leaves.
        val tool = Modifier.width(48.dp)
        SingleChoiceSegmentedButtonRow {
            SegmentedButton(
                selected = !state.eraser && !state.pipette, onClick = { viewModel.brush(state.brush) },
                shape = SegmentedButtonDefaults.itemShape(0, 3), icon = {}, colors = toolColors,
                modifier = tool.testTag("paintbox_brush"),
            ) { Icon(Icons.Outlined.Brush, stringResource(R.string.paintbox_brush)) }
            SegmentedButton(
                selected = state.eraser && !state.pipette, onClick = viewModel::eraser,
                shape = SegmentedButtonDefaults.itemShape(1, 3), icon = {}, colors = toolColors,
                modifier = tool.testTag("paintbox_eraser"),
            ) { Icon(Icons.Outlined.FormatColorReset, stringResource(R.string.paintbox_eraser)) }
            SegmentedButton(
                selected = state.pipette, onClick = viewModel::pipette,
                shape = SegmentedButtonDefaults.itemShape(2, 3), icon = {}, colors = toolColors,
                modifier = tool.testTag("paintbox_pipette"),
            ) { Icon(Icons.Outlined.Colorize, stringResource(R.string.paintbox_pipette)) }
        }
        val colorLabel = stringResource(R.string.paintbox_color)
        IconButton(onClick = onColor, modifier = Modifier.testTag("paintbox_color").semantics { contentDescription = colorLabel }) {
            // The brush color; faded while the eraser is the tool. The pipette changes it.
            Box(Modifier.size(28.dp).alpha(if (state.eraser) 0.38f else 1f).clip(CircleShape)
                .background(rgb332ToComposeColor(state.brush))
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), CircleShape))
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = viewModel::undo, enabled = state.canUndo) {
            Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.paintbox_undo))
        }
        IconButton(onClick = viewModel::redo, enabled = state.canRedo) {
            Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.paintbox_redo))
        }
    }
}

/** While a photo is placed: whether its background stays dark, which color that is, and what to tap to pick it. */
@Composable
private fun PhotoTools(state: PaintBoxState, viewModel: PaintBoxViewModel) {
    val image = state.imageImport
    val enabled = image != null && !state.imageLoading
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(value = image?.backgroundOff == true, enabled = enabled, role = Role.Switch,
                    onValueChange = viewModel::imageBackgroundOff)
                .testTag("paintbox_image_bg_off"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.paintbox_image_bg_off), style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f))
            Switch(checked = image?.backgroundOff == true, onCheckedChange = null, enabled = enabled)
        }
        FilterChip(
            selected = image?.let { it.pickingBackground || it.background != null } == true,
            onClick = viewModel::imageBackgroundTool, enabled = enabled,
            label = { Text(stringResource(R.string.paintbox_image_bg)) },
            leadingIcon = {
                // The color that stays dark: the picked one, else the one found at the photo's border.
                val background = image?.darkBackground
                if (background != null) Box(Modifier.size(FilterChipDefaults.IconSize).clip(CircleShape)
                    .background(Color(0xff000000.toInt() or background))
                    .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), CircleShape))
                else Icon(Icons.Outlined.FormatColorReset, null, Modifier.size(FilterChipDefaults.IconSize))
            },
        )
        if (image?.pickingBackground == true) {
            Text(stringResource(R.string.paintbox_image_pick_bg), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The main action at the bottom: connect, send or live, or apply a photo. */
@Composable
private fun PaintBoxActions(
    state: PaintBoxState,
    importing: Boolean,
    ready: Boolean,
    connected: Boolean,
    onConnect: () -> Unit,
    onSend: () -> Unit,
    onLive: () -> Unit,
    onAcceptImage: () -> Unit,
) {
    if (!importing && !ready) return
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    importing -> Button(
                        onClick = onAcceptImage, enabled = state.imageImport?.ready == true && !state.imageLoading,
                        modifier = Modifier.fillMaxWidth().testTag("paintbox_photo_apply"),
                    ) { Text(stringResource(R.string.paintbox_image_ok)) }
                    !connected -> Button(onClick = onConnect, modifier = Modifier.fillMaxWidth().testTag("paintbox_connect")) {
                        Icon(Icons.Outlined.Bluetooth, null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.paintbox_connect), textAlign = TextAlign.Center)
                    }
                    else -> {
                        val status = state.sending || state.live
                        Row(
                            Modifier.let { if (status) it else it.weight(1f) }.heightIn(min = 48.dp)
                                .toggleable(value = state.live, role = Role.Switch, onValueChange = { onLive() })
                                .testTag("paintbox_live"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Switch(checked = state.live, onCheckedChange = null)
                            Text(stringResource(R.string.paintbox_live), style = MaterialTheme.typography.labelLarge,
                                maxLines = 1, softWrap = false)
                        }
                        when {
                            state.sending -> Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(stringResource(R.string.paintbox_sending), style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                            }
                            state.live -> Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
                                Icon(Icons.Outlined.CheckCircle, null, Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(stringResource(R.string.paintbox_on_board), style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                            }
                            else -> Button(onClick = onSend, modifier = Modifier.testTag("paintbox_send")) {
                                Text(stringResource(R.string.paintbox_send))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BoardProblem(text: String, actions: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        actions()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaintLibraryDialog(
    library: PaintLibrary,
    viewModel: PaintBoxViewModel,
    onDismiss: () -> Unit,
    onLoad: (PaintDocument) -> Unit,
    onSaveCurrent: (() -> Unit)?,
) {
    var deleting by remember { mutableStateOf<PaintDocument?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        PaintLibraryContent(library, viewModel, onLoad = onLoad, onDelete = { deleting = it }, onSaveCurrent = onSaveCurrent)
    }
    deleting?.let { picture -> AlertDialog(onDismissRequest = { deleting = null },
        title = { Text(stringResource(R.string.paintbox_delete)) }, text = { Text(picture.name) },
        confirmButton = { TextButton(onClick = { deleting = null; viewModel.delete(picture) }) { Text(stringResource(R.string.paintbox_delete)) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } }) }
}

/** The saved pictures, by board family; also the content of the library sheet in renders. */
@Composable
internal fun ColumnScope.PaintLibraryContent(
    library: PaintLibrary,
    viewModel: PaintBoxViewModel,
    onLoad: (PaintDocument) -> Unit,
    onDelete: (PaintDocument) -> Unit,
    onSaveCurrent: (() -> Unit)? = null,
) {
    Text(stringResource(R.string.paintbox_load), style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
    Text(stringResource(R.string.paintbox_local_only), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
    if (library.unreadable > 0) Text(
        pluralStringResource(R.plurals.paintbox_unreadable, library.unreadable, library.unreadable),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    if (library.pictures.isEmpty()) Column(
        Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.paintbox_empty), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        if (onSaveCurrent != null) Button(onClick = onSaveCurrent) {
            Text(stringResource(R.string.paintbox_save_current), textAlign = TextAlign.Center)
        }
    }
    LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
        library.pictures.groupBy { it.board.brand }.toSortedMap().forEach { (brand, pictures) ->
            item(key = brand) {
                Text(BoardBrand.fromWire(brand).displayName, style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))
            }
            items(pictures, key = { it.id }) { picture ->
                ListItem(
                    headlineContent = { Text(picture.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(picture.board.label.ifBlank { BoardBrand.fromWire(picture.board.brand).displayName },
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                    },
                    leadingContent = { PaintThumbnail(picture, viewModel, Modifier.size(64.dp)) },
                    trailingContent = {
                        IconButton(onClick = { onDelete(picture) }) {
                            Icon(Icons.Outlined.Delete, stringResource(R.string.paintbox_delete))
                        }
                    },
                    modifier = Modifier.clickable { onLoad(picture) },
                )
            }
        }
    }
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
