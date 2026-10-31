package com.cruxcoach.android.ui.spraywall

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.spraywall.SprayWallPhotoStore
import com.cruxcoach.android.spraywall.SprayWallRepository
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
import java.io.File
import javax.inject.Inject

data class SprayWallSetupState(
    val wallId: String? = null,
    val loading: Boolean = false,
    val name: String = "",
    val angle: Int = DEFAULT_ANGLE,
    val photo: SprayWallPhotoStore.StoredPhoto? = null,
    val preview: ImageBitmap? = null,
    val importing: Boolean = false,
    val photoFailed: Boolean = false,
    val saving: Boolean = false,
    val notFound: Boolean = false,
) {
    val isEdit: Boolean get() = wallId != null
    val canSave: Boolean get() = !saving && !importing && name.isNotBlank() && (isEdit || photo != null)

    companion object {
        const val DEFAULT_ANGLE = 40
        const val MAX_ANGLE = 70
        const val ANGLE_STEP = 5
    }
}

sealed interface SprayWallSetupEvent {
    data class Created(val wallId: String) : SprayWallSetupEvent
    data object Saved : SprayWallSetupEvent
    data object Deleted : SprayWallSetupEvent
}

@HiltViewModel
class SprayWallSetupViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: SprayWallRepository,
    private val photos: SprayWallPhotoStore,
) : ViewModel() {
    private val wallId: String? = savedState[SprayWallRoutes.ARG_WALL]
    private val _state = MutableStateFlow(SprayWallSetupState(wallId = wallId, loading = wallId != null))
    val state: StateFlow<SprayWallSetupState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<SprayWallSetupEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<SprayWallSetupEvent> = _events.asSharedFlow()

    /** A photo imported for a new wall that was never saved is deleted again. */
    private var orphanPhoto: String? = null

    init {
        if (wallId != null) viewModelScope.launch {
            val wall = repository.wall(wallId)
            if (wall == null) {
                _state.update { it.copy(loading = false, notFound = true) }
                return@launch
            }
            _state.update { it.copy(loading = false, name = wall.name, angle = wall.angle) }
            val preview = photos.load(wall.photoFile, PREVIEW_LONG_SIDE)?.asImageBitmap()
            _state.update { it.copy(preview = preview) }
        }
    }

    fun setDefaultName(name: String) {
        if (!_state.value.isEdit && _state.value.name.isEmpty()) _state.update { it.copy(name = name) }
    }

    fun setName(name: String) = _state.update { it.copy(name = name.take(NAME_LIMIT)) }

    fun setAngle(angle: Int) = _state.update {
        it.copy(angle = angle.coerceIn(0, SprayWallSetupState.MAX_ANGLE))
    }

    fun onPhoto(uri: Uri, deleteAfter: File? = null) {
        if (_state.value.isEdit) return
        _state.update { it.copy(importing = true, photoFailed = false) }
        viewModelScope.launch {
            try {
                val stored = photos.import(uri)
                orphanPhoto?.let(photos::delete)
                orphanPhoto = stored.fileName
                val preview = photos.load(stored.fileName, PREVIEW_LONG_SIDE)?.asImageBitmap()
                _state.update { it.copy(importing = false, photo = stored, preview = preview) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(importing = false, photoFailed = true) }
            } finally {
                deleteAfter?.delete()
            }
        }
    }

    fun save() {
        val s = _state.value
        if (!s.canSave) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            if (s.wallId != null) {
                repository.updateWall(s.wallId, s.name, s.angle)
                _events.emit(SprayWallSetupEvent.Saved)
            } else {
                val photo = s.photo ?: return@launch
                val id = repository.createWall(s.name, s.angle, photo)
                orphanPhoto = null
                _events.emit(SprayWallSetupEvent.Created(id))
            }
        }
    }

    fun delete() {
        val id = wallId ?: return
        viewModelScope.launch {
            repository.deleteWall(id)
            _events.emit(SprayWallSetupEvent.Deleted)
        }
    }

    override fun onCleared() {
        orphanPhoto?.let(photos::delete)
    }

    private companion object {
        const val PREVIEW_LONG_SIDE = 1280
        const val NAME_LIMIT = 80
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SprayWallSetupScreen(
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
    onDeleted: () -> Unit,
    viewModel: SprayWallSetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val defaultName = stringResource(R.string.spray_wall_name_default)
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.setDefaultName(defaultName) }
    LaunchedEffect(viewModel) {
        viewModel.events.collect {
            when (it) {
                is SprayWallSetupEvent.Created -> onCreated(it.wallId)
                SprayWallSetupEvent.Saved -> onBack()
                SprayWallSetupEvent.Deleted -> onDeleted()
            }
        }
    }

    val capture = remember { File(File(context.cacheDir, "spray_capture").apply { mkdirs() }, "capture.jpg") }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.isEdit) R.string.spray_wall_edit_title else R.string.spray_wall_new_title)) },
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
            PhotoTile(state.preview, state.importing, state.photo?.let { it.width.toFloat() / it.height } ?: (4f / 3f))
            if (state.isEdit) {
                Text(stringResource(R.string.spray_wall_photo_fixed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(stringResource(R.string.spray_wall_photo_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.photoFailed) {
                    Text(stringResource(R.string.spray_wall_photo_failed), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("spray_photo_failed"))
                }
                OutlinedButton(
                    onClick = { if (cameraNeedsGrant(context)) cameraPermission.launch(Manifest.permission.CAMERA) else takePicture.launch(captureUri) },
                    enabled = !state.importing,
                    modifier = Modifier.fillMaxWidth().testTag("spray_take_photo"),
                ) {
                    Icon(Icons.Default.PhotoCamera, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.spray_wall_take_photo))
                }
                OutlinedButton(
                    onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = !state.importing,
                    modifier = Modifier.fillMaxWidth().testTag("spray_pick_photo"),
                ) {
                    Icon(Icons.Default.PhotoLibrary, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(stringResource(if (state.photo == null) R.string.spray_wall_pick_photo else R.string.spray_wall_photo_change))
                }
            }
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
                    onValueChange = { viewModel.setAngle((it / SprayWallSetupState.ANGLE_STEP).toInt() * SprayWallSetupState.ANGLE_STEP) },
                    valueRange = 0f..SprayWallSetupState.MAX_ANGLE.toFloat(),
                    steps = SprayWallSetupState.MAX_ANGLE / SprayWallSetupState.ANGLE_STEP - 1,
                    modifier = Modifier.testTag("spray_wall_angle"),
                )
                Text(stringResource(R.string.spray_wall_angle_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!state.isEdit && state.photo == null && !state.importing) {
                Text(stringResource(R.string.spray_wall_photo_missing), style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = viewModel::save,
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth().testTag("spray_wall_save"),
            ) {
                Text(stringResource(if (state.isEdit) R.string.action_save else R.string.spray_wall_continue))
            }
            Text(stringResource(R.string.spray_walls_private_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.isEdit) {
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

@Composable
private fun PhotoTile(preview: ImageBitmap?, importing: Boolean, aspect: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(aspect.coerceIn(0.5f, 2f))
            .heightIn(max = 480.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .testTag("spray_photo_tile"),
        contentAlignment = Alignment.Center,
    ) {
        when {
            importing -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text(stringResource(R.string.spray_wall_photo_loading), modifier = Modifier.padding(top = 8.dp))
            }
            preview != null -> Image(preview, contentDescription = stringResource(R.string.spray_cd_photo), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            else -> Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The camera intent needs the CAMERA permission once the app declares it. */
private fun cameraNeedsGrant(context: Context): Boolean {
    val declared = runCatching {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.contains(Manifest.permission.CAMERA) == true
    }.getOrDefault(false)
    return declared && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
}
