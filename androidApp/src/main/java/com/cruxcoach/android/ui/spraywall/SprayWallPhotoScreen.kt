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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
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
import com.cruxcoach.domain.spraywall.SprayClimbRules
import com.cruxcoach.domain.spraywall.SprayCropRect
import com.cruxcoach.domain.spraywall.SprayGym
import com.cruxcoach.domain.spraywall.SprayWallNostr
import com.cruxcoach.domain.spraywall.SprayWallVisibility
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

/**
 * The photo step: a new wall's first photo, or the new photo of a reset
 * ("Umschrauben") when [wallId] is set. Camera or library, then crop and
 * quarter turns; continuing stores the wall (or the reset) and goes on to the
 * holds.
 */
data class SprayWallPhotoState(
    val wallId: String? = null,
    val wallName: String = "",
    /** A reset of a public wall: the new photo will be published too. */
    val wallPublic: Boolean = false,
    val photo: SprayWallPhotoStore.StoredPhoto? = null,
    val preview: ImageBitmap? = null,
    val importing: Boolean = false,
    val adjusting: Boolean = false,
    val photoFailed: Boolean = false,
    val saving: Boolean = false,
    val notFound: Boolean = false,
) {
    val isReset: Boolean get() = wallId != null
    val canContinue: Boolean get() = photo != null && !importing && !saving
}

sealed interface SprayWallPhotoEvent {
    data class Done(val wallId: String) : SprayWallPhotoEvent
    data object Failed : SprayWallPhotoEvent
}

@HiltViewModel
class SprayWallPhotoViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: SprayWallRepository,
    private val photos: SprayWallPhotoStore,
) : ViewModel() {
    private val wallId: String? = savedState[SprayWallRoutes.ARG_WALL]
    /** Set up from a place on the map: the new wall belongs to that gym. */
    private val gym: SprayGym? = savedState.get<String>(SprayWallRoutes.ARG_GYM_ID)
        ?.takeIf { SprayWallNostr.validVenueKey(it) }
        ?.let { SprayGym(it, savedState.get<String>(SprayWallRoutes.ARG_GYM_NAME).orEmpty().take(SprayClimbRules.MAX_NAME_LENGTH)) }
    private val _state = MutableStateFlow(SprayWallPhotoState(wallId = wallId))
    val state: StateFlow<SprayWallPhotoState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<SprayWallPhotoEvent>(extraBufferCapacity = 2)
    val events: SharedFlow<SprayWallPhotoEvent> = _events.asSharedFlow()

    /** A photo imported here and not handed to a wall yet; deleted when it is replaced or the screen goes. */
    private var orphanPhoto: String? = null

    init {
        if (wallId != null) viewModelScope.launch {
            val wall = repository.wall(wallId)
            if (wall == null || !wall.isMine) _state.update { it.copy(notFound = true) }
            // A wall being withdrawn publishes nothing more: the new photo stays on the phone.
            else _state.update { it.copy(wallName = wall.name, wallPublic = wall.visibility == SprayWallVisibility.PUBLIC && wall.withdrawStarted == null) }
        }
    }

    fun onPhoto(uri: Uri, deleteAfter: File? = null) {
        _state.update { it.copy(importing = true, photoFailed = false) }
        viewModelScope.launch {
            try {
                val stored = photos.import(uri)
                replaceOrphan(stored.fileName)
                val preview = photos.load(stored.fileName, PREVIEW_LONG_SIDE)?.asImageBitmap()
                // A fresh photo goes straight to crop and rotate.
                _state.update { it.copy(importing = false, photo = stored, preview = preview, adjusting = preview != null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(importing = false, photoFailed = true) }
            } finally {
                deleteAfter?.delete()
            }
        }
    }

    fun openAdjust() = _state.update { if (it.preview != null) it.copy(adjusting = true) else it }
    fun closeAdjust() = _state.update { it.copy(adjusting = false) }

    fun adjust(quarterTurns: Int, crop: SprayCropRect) {
        val photo = _state.value.photo ?: return
        _state.update { it.copy(adjusting = false) }
        if (quarterTurns % 4 == 0 && crop.isFull) return
        _state.update { it.copy(importing = true) }
        viewModelScope.launch {
            try {
                val adjusted = photos.adjust(photo.fileName, quarterTurns, crop)
                replaceOrphan(adjusted.fileName)
                val preview = photos.load(adjusted.fileName, PREVIEW_LONG_SIDE)?.asImageBitmap()
                _state.update { it.copy(importing = false, photo = adjusted, preview = preview) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(importing = false, photoFailed = true) }
            }
        }
    }

    /** Stores the new wall (private, with [defaultName]) or the reset, then hands over to the hold editor. */
    fun continueWith(defaultName: String) {
        val s = _state.value
        val photo = s.photo ?: return
        if (!s.canContinue) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val id = if (wallId == null) {
                    repository.createWall(defaultName, DEFAULT_ANGLE, SprayWallVisibility.PRIVATE, gym, photo, emptyList())
                } else {
                    repository.resetWall(wallId, photo, emptyList())
                    wallId
                }
                orphanPhoto = null
                _events.emit(SprayWallPhotoEvent.Done(id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saving = false) }
                _events.emit(SprayWallPhotoEvent.Failed)
            }
        }
    }

    private fun replaceOrphan(fileName: String) {
        orphanPhoto?.let(photos::delete)
        orphanPhoto = fileName
    }

    override fun onCleared() {
        orphanPhoto?.let(photos::delete)
    }

    companion object {
        const val DEFAULT_ANGLE = 40
        private const val PREVIEW_LONG_SIDE = 1280
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SprayWallPhotoScreen(
    onBack: () -> Unit,
    onDone: (wallId: String) -> Unit,
    viewModel: SprayWallPhotoViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val defaultName = stringResource(R.string.spray_wall_name_default)
    val failed = stringResource(R.string.spray_wall_photo_failed)
    LaunchedEffect(viewModel) {
        viewModel.events.collect {
            when (it) {
                is SprayWallPhotoEvent.Done -> onDone(it.wallId)
                SprayWallPhotoEvent.Failed -> android.widget.Toast.makeText(context, failed, android.widget.Toast.LENGTH_SHORT).show()
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
                title = { Text(stringResource(if (state.isReset) R.string.spray_reset_title else R.string.spray_wall_new_title)) },
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
            if (state.isReset) {
                Text(stringResource(R.string.spray_reset_explain, state.wallName), style = MaterialTheme.typography.bodyMedium)
            } else {
                SprayStepLabel(step = 1)
            }
            PhotoTile(state.preview, state.importing, state.photo?.let { it.width.toFloat() / it.height } ?: (4f / 3f))
            val continueButton = @Composable {
                Button(
                    onClick = { viewModel.continueWith(defaultName) },
                    enabled = state.canContinue,
                    modifier = Modifier.fillMaxWidth().testTag("spray_photo_continue"),
                ) {
                    Text(stringResource(R.string.spray_wall_continue))
                }
            }
            // With a photo the next step sits right under it, above the long reset explanation's scroll edge.
            if (state.photo != null) continueButton()
            Text(stringResource(R.string.spray_wall_photo_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.photoFailed) {
                Text(stringResource(R.string.spray_wall_photo_failed), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("spray_photo_failed"))
            }
            OutlinedButton(
                onClick = { if (cameraNeedsGrant(context)) cameraPermission.launch(Manifest.permission.CAMERA) else takePicture.launch(captureUri) },
                enabled = !state.importing && !state.saving,
                modifier = Modifier.fillMaxWidth().testTag("spray_take_photo"),
            ) {
                Icon(Icons.Default.PhotoCamera, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.spray_wall_take_photo))
            }
            OutlinedButton(
                onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !state.importing && !state.saving,
                modifier = Modifier.fillMaxWidth().testTag("spray_pick_photo"),
            ) {
                Icon(Icons.Default.PhotoLibrary, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text(stringResource(if (state.photo == null) R.string.spray_wall_pick_photo else R.string.spray_wall_photo_change))
            }
            if (state.photo != null) {
                OutlinedButton(
                    onClick = viewModel::openAdjust,
                    enabled = !state.importing && !state.saving && state.preview != null,
                    modifier = Modifier.fillMaxWidth().testTag("spray_adjust_open"),
                ) {
                    Icon(Icons.Default.Crop, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.spray_adjust_title))
                }
            }
            if (state.photo == null) continueButton()
            if (state.photo == null && !state.importing) {
                Text(stringResource(R.string.spray_wall_photo_missing), style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(if (state.wallPublic) R.string.spray_wall_photo_public else R.string.spray_wall_photo_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    val preview = state.preview
    if (state.adjusting && preview != null) {
        SprayPhotoAdjustDialog(
            preview = remember(preview) { preview.asAndroidBitmap() },
            onDone = viewModel::adjust,
            onCancel = viewModel::closeAdjust,
        )
    }
}

/** "Step n of 3" above each step of adding a wall. */
@Composable
fun SprayStepLabel(step: Int) {
    val titles = listOf(R.string.spray_step_photo, R.string.spray_step_holds, R.string.spray_step_details)
    Text(
        stringResource(R.string.spray_step, step, titles.size, stringResource(titles[step - 1])),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.testTag("spray_step_$step"),
    )
}

@Composable
private fun PhotoTile(preview: ImageBitmap?, importing: Boolean, aspect: Float) {
    Box(
        // Small enough that the buttons below stay in view with a portrait photo.
        Modifier
            .sprayPhotoSize(aspect, screenHeightShare(0.35f))
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
