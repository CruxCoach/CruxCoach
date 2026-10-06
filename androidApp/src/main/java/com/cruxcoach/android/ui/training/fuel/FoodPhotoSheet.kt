package com.cruxcoach.android.ui.training.fuel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.foodvision.VisionModelStore
import com.cruxcoach.android.ui.training.formatNumber
import com.cruxcoach.athlete.logic.BlsFood
import com.cruxcoach.athlete.logic.VisionCapability
import com.cruxcoach.athlete.logic.VisionSupport
import com.cruxcoach.athlete.model.Meal
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.roundToInt

/**
 * The photo entry in the fueling screen (FEAT-069). On hardware that cannot
 * run the model it stays visible but greyed out; tapping it explains why.
 */
@Composable
fun FoodPhotoButton(support: VisionSupport, tooSlow: Boolean, onOpen: () -> Unit, onExplain: () -> Unit) {
    val available = support is VisionSupport.Supported && !tooSlow
    val unavailableText = stringResource(R.string.fvp_unavailable_short)
    OutlinedButton(
        onClick = if (available) onOpen else onExplain,
        modifier = Modifier
            .testTag("fuel_photo")
            .semantics { if (!available) stateDescription = unavailableText },
    ) {
        Icon(Icons.Default.PhotoCamera, null, Modifier.size(18.dp).alpha(if (available) 1f else DISABLED_ALPHA))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.fvp_button), Modifier.alpha(if (available) 1f else DISABLED_ALPHA))
    }
}

private const val DISABLED_ALPHA = 0.38f

/** Why this device does not get photo recognition, with the alternatives. */
@Composable
fun FoodPhotoUnavailableDialog(state: FoodPhotoState, onRemoveModel: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val reason = when {
        state.tooSlow -> stringResource(R.string.fvp_unavailable_slow,
            (((state.model as? VisionModelStore.State.Ready)?.probe?.estimatedMs ?: 0L) / 1000).toInt())
        else -> when ((state.support as? VisionSupport.Unsupported)?.reason) {
            VisionSupport.Reason.LOW_RAM -> stringResource(R.string.fvp_unavailable_ram,
                VisionCapability.SMALL_NOMINAL_RAM_GB, formatGb(state.facts.totalRamBytes))
            VisionSupport.Reason.CPU_FEATURES -> stringResource(R.string.fvp_unavailable_cpu)
            VisionSupport.Reason.NOT_ARM64 -> stringResource(R.string.fvp_unavailable_abi)
            null -> stringResource(R.string.fvp_unavailable_short)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("fuel_photo_unavailable"),
        title = { Text(stringResource(R.string.fvp_unavailable_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(reason)
                Text(stringResource(R.string.fvp_unavailable_alternatives), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.fvp_ok)) } },
        dismissButton = if (state.model !is VisionModelStore.State.Missing) {
            {
                TextButton(onClick = { onRemoveModel(); onDismiss() }, modifier = Modifier.testTag("fuel_photo_remove")) {
                    Text(stringResource(R.string.fvp_remove_model, Formatter.formatShortFileSize(context, state.target?.downloadBytes ?: 0)))
                }
            }
        } else null,
    )
}

private fun formatGb(bytes: Long): String = formatNumber((bytes / 100_000_000.0).roundToInt() / 10.0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoodPhotoSheet(
    day: String,
    initialMeal: Meal,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    viewModel: FoodPhotoViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.onOpen() }
    LaunchedEffect(state.phase) { if (state.phase is PhotoPhase.Saved) { viewModel.onClose(); onSaved() } }
    val close = { viewModel.onClose(); onDismiss() }

    ModalBottomSheet(onDismissRequest = close, modifier = Modifier.testTag("fuel_photo_sheet")) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.fvp_title), style = MaterialTheme.typography.titleLarge)
            when (val phase = state.phase) {
                is PhotoPhase.Failed -> ErrorPane(phase.error, onRetry = viewModel::dismissError)
                is PhotoPhase.Probing -> WaitPane(phase.startedAt, stringResource(R.string.fvp_probing), null, viewModel::cancel)
                is PhotoPhase.Analyzing -> AnalyzingPane(phase, viewModel::cancel)
                is PhotoPhase.Review -> ReviewPane(phase, initialMeal, viewModel, onSave = { meal -> viewModel.save(day, meal) })
                PhotoPhase.Saved -> Unit
                PhotoPhase.Idle -> when (val model = state.model) {
                    VisionModelStore.State.Missing, is VisionModelStore.State.Failed -> SetupPane(state, viewModel::startDownload)
                    is VisionModelStore.State.Downloading -> DownloadPane(state, model, onCancel = viewModel::removeModel)
                    is VisionModelStore.State.Verifying -> {
                        val since = remember { SystemClock.elapsedRealtime() }
                        WaitPane(since, stringResource(R.string.fvp_verifying), null, null)
                    }
                    is VisionModelStore.State.Ready -> ReadyPane(context, state, model, viewModel)
                }
            }
        }
    }
}

@Composable
private fun SetupPane(state: FoodPhotoState, onDownload: (Boolean) -> Unit) {
    val context = LocalContext.current
    val model = state.target ?: return
    var allowMobile by rememberSaveable { mutableStateOf(false) }
    val size = Formatter.formatShortFileSize(context, model.downloadBytes)
    Column(Modifier.verticalScroll(rememberScrollState()).testTag("fuel_photo_setup"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.fvp_setup_text, model.displayName, size), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.fvp_setup_estimate), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.fvp_setup_source), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { allowMobile = !allowMobile }) {
            Checkbox(checked = allowMobile, onCheckedChange = { allowMobile = it }, modifier = Modifier.testTag("fuel_photo_mobile"))
            Text(stringResource(R.string.fvp_allow_mobile))
        }
        Button(onClick = { onDownload(allowMobile) }, modifier = Modifier.fillMaxWidth().testTag("fuel_photo_download")) {
            Text(stringResource(R.string.fvp_download, size))
        }
    }
}

@Composable
private fun DownloadPane(state: FoodPhotoState, download: VisionModelStore.State.Downloading, onCancel: () -> Unit) {
    val context = LocalContext.current
    val total = download.model.downloadBytes
    Column(Modifier.testTag("fuel_photo_downloading"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.fvp_downloading, Formatter.formatShortFileSize(context, download.doneBytes),
            Formatter.formatShortFileSize(context, total)))
        LinearProgressIndicator(progress = { (download.doneBytes.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        if (download.waiting) Text(stringResource(R.string.fvp_download_waiting), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onCancel, modifier = Modifier.testTag("fuel_photo_download_cancel")) { Text(stringResource(R.string.tr_action_cancel)) }
    }
}

@Composable
internal fun WaitPane(startedAt: Long, label: String, estimateMs: Long?, onCancel: (() -> Unit)?) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(startedAt) { while (true) { now = SystemClock.elapsedRealtime(); delay(1000) } }
    val seconds = ((now - startedAt) / 1000).toInt()
    Column(Modifier.testTag("fuel_photo_wait"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
        val progress = estimateMs?.takeIf { it > 0 }?.let { (now - startedAt).toFloat() / it }
        if (progress != null) LinearProgressIndicator(progress = { progress.coerceIn(0f, 0.97f) }, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.fvp_elapsed, seconds), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onCancel != null) {
            TextButton(onClick = onCancel, modifier = Modifier.testTag("fuel_photo_cancel")) { Text(stringResource(R.string.tr_action_cancel)) }
        }
    }
}

@Composable
internal fun AnalyzingPane(phase: PhotoPhase.Analyzing, onCancel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        phase.preview?.let { Preview(it) }
        WaitPane(phase.startedAt, stringResource(R.string.fvp_analyzing), phase.estimateMs, onCancel)
        Text(stringResource(R.string.fvp_on_device), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Preview(bitmap: android.graphics.Bitmap) {
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = stringResource(R.string.fvp_photo_description),
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxWidth().height(160.dp),
    )
}

@Composable
private fun ReadyPane(context: Context, state: FoodPhotoState, ready: VisionModelStore.State.Ready, viewModel: FoodPhotoViewModel) {
    val capture = remember { File(File(context.cacheDir, "foodvision").apply { mkdirs() }, "capture.jpg") }
    val captureUri = remember { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", capture) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) viewModel.onPhoto(captureUri, deleteAfter = capture) else capture.delete()
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) takePicture.launch(captureUri)
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) viewModel.onPhoto(uri)
    }
    Column(Modifier.testTag("fuel_photo_ready"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ready.probe?.let { Text(stringResource(R.string.fvp_probe_estimate, (it.estimatedMs / 1000).toInt()), style = MaterialTheme.typography.bodyMedium) }
        Text(stringResource(R.string.fvp_on_device), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.canTakePhotos) {
            Button(
                onClick = { if (cameraNeedsGrant(context)) cameraPermission.launch(Manifest.permission.CAMERA) else takePicture.launch(captureUri) },
                modifier = Modifier.fillMaxWidth().testTag("fuel_photo_take"),
            ) {
                Icon(Icons.Default.PhotoCamera, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.fvp_take_photo))
            }
            OutlinedButton(
                onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                modifier = Modifier.fillMaxWidth().testTag("fuel_photo_pick"),
            ) {
                Icon(Icons.Default.PhotoLibrary, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.fvp_pick_photo))
            }
        } else if (ready.probe == null) {
            TextButton(onClick = viewModel::probe, modifier = Modifier.testTag("fuel_photo_probe")) { Text(stringResource(R.string.fvp_run_probe)) }
        } else if (state.tooSlow) {
            Text(stringResource(R.string.fvp_unavailable_slow, (ready.probe.estimatedMs / 1000).toInt()),
                modifier = Modifier.testTag("fuel_photo_too_slow"))
        }
        TextButton(onClick = viewModel::removeModel, modifier = Modifier.testTag("fuel_photo_remove")) {
            Text(stringResource(R.string.fvp_remove_model, Formatter.formatShortFileSize(context, ready.model.downloadBytes)))
        }
    }
}

/**
 * ACTION_IMAGE_CAPTURE refuses apps that declare CAMERA without holding it.
 * CruxCoach itself does not declare it, but a library manifest might.
 */
private fun cameraNeedsGrant(context: Context): Boolean {
    val declared = runCatching {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.contains(Manifest.permission.CAMERA) == true
    }.getOrDefault(false)
    return declared && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
}

@Composable
internal fun ErrorPane(error: PhotoError, onRetry: () -> Unit) {
    val context = LocalContext.current
    val text = when (error) {
        PhotoError.OutOfMemory -> stringResource(R.string.fvp_error_memory)
        PhotoError.Image -> stringResource(R.string.fvp_error_image)
        PhotoError.Download -> stringResource(R.string.fvp_error_download)
        PhotoError.Corrupt -> stringResource(R.string.fvp_error_corrupt)
        PhotoError.NoStorage -> stringResource(R.string.fvp_error_no_storage)
        is PhotoError.Space -> stringResource(R.string.fvp_error_space,
            Formatter.formatShortFileSize(context, error.neededBytes), Formatter.formatShortFileSize(context, error.freeBytes))
        is PhotoError.Engine -> stringResource(R.string.fvp_error_engine, error.code)
    }
    Column(Modifier.testTag("fuel_photo_error"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onRetry, modifier = Modifier.testTag("fuel_photo_retry")) { Text(stringResource(R.string.fvp_retry)) }
    }
}

// ── Review ───────────────────────────────────────────────────────────

@Composable
internal fun ReviewPane(phase: PhotoPhase.Review, initialMeal: Meal, viewModel: FoodPhotoViewModel, onSave: (Meal) -> Unit) {
    var meal by rememberSaveable(phase.mealHint) { mutableStateOf(phase.mealHint ?: initialMeal) }
    var choosingFor by remember { mutableStateOf<ReviewItem?>(null) }
    var adding by remember { mutableStateOf(false) }
    val count = phase.items.count { it.included && FoodPhotoViewModel.amountOf(it) != null }
    Column(Modifier.testTag("fuel_photo_review"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        phase.preview?.let { Preview(it) }
        Text(
            when (phase.source) {
                ReviewSource.PHOTO -> stringResource(R.string.fvp_review_hint, phase.seconds)
                ReviewSource.TEXT_RULES -> stringResource(R.string.fvp_review_text_hint)
                ReviewSource.TEXT_MODEL -> stringResource(R.string.fvp_review_text_model_hint, phase.seconds)
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val text = phase.text
        if (phase.source == ReviewSource.TEXT_RULES && text != null && viewModel.modelAvailable) {
            OutlinedButton(onClick = { viewModel.onTextWithModel(text) }, modifier = Modifier.testTag("fuel_text_model")) {
                Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.fvp_text_model))
            }
        }
        if (phase.items.isEmpty()) Text(stringResource(R.string.fvp_review_empty), modifier = Modifier.testTag("fuel_photo_empty"))
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            items(phase.items, key = { it.key }) { item ->
                ReviewRow(item, viewModel, onChoose = { choosingFor = item })
            }
        }
        TextButton(onClick = { adding = true }, modifier = Modifier.testTag("fuel_photo_add")) {
            Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.fvp_add_item))
        }
        Text(stringResource(R.string.trf_qa_meal), style = MaterialTheme.typography.labelLarge)
        MealPicker(meal) { meal = it }
        Button(onClick = { onSave(meal) }, enabled = count > 0, modifier = Modifier.fillMaxWidth().testTag("fuel_photo_save")) {
            Text(pluralStringResource(R.plurals.fvp_save, count, count))
        }
        Text(stringResource(R.string.fvp_attribution), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    choosingFor?.let { item ->
        ChooseFoodDialog(item.candidates.map { it.food }, viewModel, allowNone = true,
            onDismiss = { choosingFor = null }, onPick = { viewModel.choose(item.key, it); choosingFor = null })
    }
    if (adding) {
        ChooseFoodDialog(emptyList(), viewModel, allowNone = false,
            onDismiss = { adding = false }, onPick = { food -> food?.let(viewModel::addFood); adding = false })
    }
}

@Composable
private fun ReviewRow(item: ReviewItem, viewModel: FoodPhotoViewModel, onChoose: () -> Unit) {
    val choice = item.choice
    val title = when {
        item.water -> stringResource(R.string.fvp_water_item)
        choice != null -> FoodPhotoViewModel.blsName(choice)
        else -> item.detected?.let { FoodPhotoViewModel.displayName(it) }.orEmpty()
    }
    val grams = FoodPhotoViewModel.amountOf(item)
    val macros = if (choice != null && grams != null) {
        val f = grams / 100.0
        stringResource(R.string.trf_entry_macros, formatNumber(choice.protein * f), formatNumber(choice.carbs * f), formatNumber(choice.fat * f))
    } else null
    val detectedLine = item.detected?.let { d ->
        val shown = FoodPhotoViewModel.displayName(d)
        if (choice != null && !title.equals(shown, ignoreCase = true)) stringResource(R.string.fvp_detected_as, shown) else null
    }
    ListItem(
        leadingContent = {
            Checkbox(checked = item.included, onCheckedChange = { viewModel.setIncluded(item.key, it) },
                modifier = Modifier.testTag("fuel_photo_include_${item.key}"))
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.water) { Icon(Icons.Default.LocalDrink, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)) }
                Text(title, modifier = Modifier.weight(1f, fill = false))
                if (item.uncertain && choice != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.fvp_uncertain), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary)
                }
            }
        },
        supportingContent = {
            Column {
                when {
                    item.water -> Unit
                    choice == null -> Text(stringResource(R.string.fvp_no_match), color = MaterialTheme.colorScheme.error)
                    else -> macros?.let { Text(it) }
                }
                detectedLine?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        trailingContent = {
            OutlinedTextField(
                value = item.amountText,
                onValueChange = { viewModel.setAmount(item.key, it) },
                suffix = { Text(stringResource(if (item.water) R.string.fvp_ml else R.string.fvp_grams)) },
                singleLine = true,
                isError = grams == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(104.dp).testTag("fuel_photo_amount_${item.key}"),
            )
        },
        modifier = Modifier.clickable(enabled = !item.water, onClick = onChoose).testTag("fuel_photo_item_${item.key}"),
    )
}

@Composable
private fun ChooseFoodDialog(
    suggestions: List<BlsFood>,
    viewModel: FoodPhotoViewModel,
    allowNone: Boolean,
    onDismiss: () -> Unit,
    onPick: (BlsFood?) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf(suggestions) }
    LaunchedEffect(query) {
        if (query.isBlank()) { results = suggestions; return@LaunchedEffect }
        delay(250)
        results = viewModel.search(query)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("fuel_photo_choose"),
        title = { Text(stringResource(R.string.fvp_choose_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it.take(60) }, singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    label = { Text(stringResource(R.string.fvp_choose_search)) },
                    modifier = Modifier.fillMaxWidth().testTag("fuel_photo_search"),
                )
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(results, key = { it.code }) { food ->
                        val desc = FoodPhotoViewModel.blsName(food)
                        ListItem(
                            headlineContent = { Text(desc) },
                            supportingContent = { Text(stringResource(R.string.fvp_per_100, formatNumber(food.kcal),
                                formatNumber(food.protein), formatNumber(food.carbs), formatNumber(food.fat))) },
                            modifier = Modifier.clickable { onPick(food) }.semantics { contentDescription = desc }
                                .testTag("fuel_photo_pick_${food.code}"),
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (allowNone) TextButton(onClick = { onPick(null) }, modifier = Modifier.testTag("fuel_photo_choose_none")) {
                Text(stringResource(R.string.fvp_choose_none))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.tr_action_cancel)) } },
    )
}
