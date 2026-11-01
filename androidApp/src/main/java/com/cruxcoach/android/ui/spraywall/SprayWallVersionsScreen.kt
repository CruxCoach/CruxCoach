package com.cruxcoach.android.ui.spraywall

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import com.cruxcoach.domain.spraywall.SprayHold
import com.cruxcoach.domain.spraywall.SprayVersionKind
import com.cruxcoach.domain.spraywall.SprayWall
import com.cruxcoach.domain.spraywall.SprayWallVersion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

data class SprayWallVersionsState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val wall: SprayWall? = null,
    val versions: List<SprayWallVersion> = emptyList(),
    /** Climbs set on each version. */
    val climbCounts: Map<Int, Int> = emptyMap(),
    val selected: Int? = null,
    val holds: List<SprayHold> = emptyList(),
    val photo: ImageBitmap? = null,
    val restoring: Boolean = false,
) {
    val selectedVersion: SprayWallVersion? get() = versions.firstOrNull { it.number == selected }
    val canRestore: Boolean get() = wall?.isMine == true && selected != null && selected != wall.current.number && !restoring
}

@HiltViewModel
class SprayWallVersionsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: SprayWallRepository,
    private val photos: SprayWallPhotoStore,
) : ViewModel() {
    private val wallId: String = savedState[SprayWallRoutes.ARG_WALL] ?: ""
    private val _state = MutableStateFlow(SprayWallVersionsState())
    val state: StateFlow<SprayWallVersionsState> = _state.asStateFlow()
    private val _restored = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val restored: SharedFlow<Int> = _restored.asSharedFlow()
    private var photoOf: String? = null

    init {
        viewModelScope.launch { repository.changes.collect { reload() } }
    }

    private suspend fun reload() {
        val wall = repository.wall(wallId)
        if (wall == null) {
            _state.update { it.copy(loading = false, notFound = true) }
            return
        }
        val versions = repository.versions(wallId)
        val counts = repository.climbs(wallId).groupingBy { it.version }.eachCount()
        val selected = _state.value.selected?.takeIf { n -> versions.any { it.number == n } } ?: wall.current.number
        _state.update { it.copy(loading = false, wall = wall, versions = versions, climbCounts = counts) }
        select(selected)
    }

    fun select(number: Int) {
        val version = _state.value.versions.firstOrNull { it.number == number } ?: return
        _state.update { it.copy(selected = number) }
        viewModelScope.launch {
            val holds = repository.holds(wallId, number)
            _state.update { if (it.selected == number) it.copy(holds = holds) else it }
            if (photoOf != version.photoFile) {
                photoOf = version.photoFile
                _state.update { it.copy(photo = null) }
                val bitmap = photos.load(version.photoFile, PREVIEW_LONG_SIDE)?.asImageBitmap()
                _state.update { it.copy(photo = bitmap) }
            }
        }
    }

    fun restore() {
        val number = _state.value.selected ?: return
        if (!_state.value.canRestore) return
        _state.update { it.copy(restoring = true) }
        viewModelScope.launch {
            val added = repository.restoreVersion(wallId, number)
            _state.update { it.copy(restoring = false, selected = added) }
            _restored.emit(number)
        }
    }

    private companion object {
        const val PREVIEW_LONG_SIDE = 1600
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SprayWallVersionsScreen(
    onBack: () -> Unit,
    viewModel: SprayWallVersionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel) {
        viewModel.restored.collect { snackbar.showSnackbar(context.getString(R.string.spray_versions_restored, it)) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.spray_versions_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val wall = state.wall
        val shown = state.selectedVersion
        if (state.notFound || wall == null || shown == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (state.notFound) Text(stringResource(R.string.spray_not_found))
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                SprayWallCanvas(
                    photo = state.photo,
                    photoWidth = shown.photoWidth,
                    photoHeight = shown.photoHeight,
                    holds = state.holds.map { SprayCanvasHold(it.id, it.x, it.y, it.radius, SprayHoldLook.PLAIN) },
                    interactive = false,
                    modifier = Modifier.fillMaxWidth().aspectRatio(shown.aspectRatio.coerceIn(0.5f, 2f)).heightIn(max = 380.dp),
                )
            }
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.spray_versions_explain), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.canRestore) {
                        Button(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth().testTag("spray_version_restore")) {
                            Icon(Icons.Default.History, contentDescription = null)
                            Text(stringResource(R.string.spray_versions_restore, shown.number), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
            items(state.versions, key = { it.number }) { version ->
                VersionRow(
                    version = version,
                    current = version.number == wall.current.number,
                    selected = version.number == state.selected,
                    climbs = state.climbCounts[version.number] ?: 0,
                    onClick = { viewModel.select(version.number) },
                )
                HorizontalDivider()
            }
        }
    }
    val selected = state.selectedVersion
    if (confirm && selected != null) {
        SprayConfirmDialog(
            title = stringResource(R.string.spray_versions_restore, selected.number),
            text = stringResource(R.string.spray_versions_restore_text),
            confirmLabel = stringResource(R.string.spray_versions_restore_confirm),
            onConfirm = { confirm = false; viewModel.restore() },
            onDismiss = { confirm = false },
        )
    }
}

@Composable
private fun VersionRow(version: SprayWallVersion, current: Boolean, selected: Boolean, climbs: Int, onClick: () -> Unit) {
    val date = remember(version.createdAt) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(version.createdAt)) }
    val what = when (version.kind) {
        SprayVersionKind.CREATED -> stringResource(R.string.spray_version_created)
        SprayVersionKind.HOLDS -> stringResource(R.string.spray_version_holds)
        SprayVersionKind.RESET -> stringResource(R.string.spray_version_reset)
        SprayVersionKind.RESTORED -> stringResource(R.string.spray_version_restored, version.basedOn ?: 0)
    }
    ListItem(
        headlineContent = {
            Text(
                if (current) stringResource(R.string.spray_version_current, version.number) else stringResource(R.string.spray_version_label, version.number),
                style = MaterialTheme.typography.titleMedium,
            )
        },
        supportingContent = {
            Text(listOf(what, date, stringResource(R.string.spray_version_counts, version.holdCount, climbs)).joinToString(" · "))
        },
        trailingContent = { if (current) Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        colors = if (selected) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else ListItemDefaults.colors(),
        modifier = Modifier.clickable(onClick = onClick).testTag("spray_version_row"),
    )
}
