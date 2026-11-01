package com.cruxcoach.android.ui.spraywall

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.spraywall.SprayWallPhotoStore
import com.cruxcoach.android.spraywall.SprayWallRepository
import com.cruxcoach.domain.spraywall.SprayAscent
import com.cruxcoach.domain.spraywall.SprayClimb
import com.cruxcoach.domain.spraywall.SprayClimbRules
import com.cruxcoach.domain.spraywall.SprayHold
import com.cruxcoach.domain.spraywall.SprayWall
import com.cruxcoach.domain.spraywall.SprayWallHistory
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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

data class SprayClimbDetailState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val climb: SprayClimb? = null,
    val wall: SprayWall? = null,
    /** The version the climb is shown on: the current one, or for a climb from before a reset its own. */
    val version: SprayWallVersion? = null,
    val photo: ImageBitmap? = null,
    val holds: List<SprayHold> = emptyList(),
    val lastKnown: Map<Long, SprayHold> = emptyMap(),
    val ascents: List<SprayAscent> = emptyList(),
    val gradeScale: GradeScale = GradeScale.FRENCH,
) {
    /** Set before the wall was reset with a new photo. */
    val earlier: Boolean get() = wall != null && version != null && version.photoFile != wall.current.photoFile
    val lostHolds: Int
        get() = climb?.let { c -> SprayClimbRules.lostHoldCount(c, holds.mapTo(HashSet()) { it.id }) } ?: 0
}

@HiltViewModel
class SprayClimbDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: SprayWallRepository,
    private val photos: SprayWallPhotoStore,
    private val preferences: UserPreferences,
) : ViewModel() {
    private val climbUuid: String = savedState[SprayWallRoutes.ARG_CLIMB] ?: ""
    private val _state = MutableStateFlow(SprayClimbDetailState())
    val state: StateFlow<SprayClimbDetailState> = _state.asStateFlow()
    private var photoOf: String? = null
    private val _logged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val logged: SharedFlow<Unit> = _logged.asSharedFlow()

    init {
        viewModelScope.launch { repository.changes.collect { reload() } }
        viewModelScope.launch { preferences.gradeScale.collect { scale -> _state.update { it.copy(gradeScale = scale) } } }
    }

    private suspend fun reload() {
        val climb = repository.climb(climbUuid)
        val wall = climb?.let { repository.wall(it.wallId) }
        if (climb == null || wall == null) {
            _state.update { it.copy(loading = false, notFound = true) }
            return
        }
        val versions = repository.versions(wall.id)
        val earlier = SprayWallHistory.earlierVersions(versions, wall.current)
        val version = climb.version.takeIf { it in earlier }?.let { n -> versions.firstOrNull { it.number == n } } ?: wall.current
        val all = repository.allHolds(wall.id)
        _state.update {
            it.copy(
                loading = false, notFound = false, climb = climb, wall = wall, version = version,
                holds = all.filter { h -> h.version == version.number },
                lastKnown = SprayWallHistory.lastKnown(all.filter { h -> h.version <= version.number }),
                ascents = repository.ascents(climbUuid),
            )
        }
        if (photoOf != version.photoFile) {
            photoOf = version.photoFile
            _state.update { it.copy(photo = photos.load(version.photoFile)?.asImageBitmap()) }
        }
    }

    fun log(isSend: Boolean, tries: Int, difficulty: Int?, comment: String) {
        val climb = _state.value.climb ?: return
        viewModelScope.launch {
            repository.logAscent(climb, isSend, tries, difficulty, comment)
            _logged.emit(Unit)
        }
    }

    fun deleteAscent(uuid: String) {
        viewModelScope.launch { repository.deleteAscent(uuid) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SprayClimbDetailScreen(
    onBack: () -> Unit,
    onEdit: (wallId: String, climbUuid: String) -> Unit,
    viewModel: SprayClimbDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var logDialog by remember { mutableStateOf<Boolean?>(null) } // true = send, false = attempts
    LaunchedEffect(viewModel) { viewModel.logged.collect { snackbar.showSnackbar(context.getString(R.string.spray_log_saved)) } }
    val climb = state.climb
    val wall = state.wall
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(climb?.name.orEmpty()) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (climb != null && climb.isMine) {
                        IconButton(onClick = { onEdit(climb.wallId, climb.uuid) }, modifier = Modifier.testTag("spray_climb_edit")) {
                            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.spray_climb_edit))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val version = state.version
        if (state.notFound || climb == null || wall == null || version == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (state.notFound) Text(stringResource(R.string.spray_not_found))
            }
            return@Scaffold
        }
        val maxCanvasHeight = screenHeightShare(0.65f)
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                SprayWallCanvas(
                    photo = state.photo,
                    photoWidth = version.photoWidth,
                    photoHeight = version.photoHeight,
                    holds = climbCanvasHolds(state.holds, state.lastKnown, climb.holds, showOthers = false),
                    dimPhoto = true,
                    modifier = Modifier.sprayPhotoSize(version.aspectRatio, maxCanvasHeight),
                )
            }
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            climb.difficulty?.let { formatSprayGrade(it, state.gradeScale) } ?: stringResource(R.string.spray_climb_project),
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.weight(1f).testTag("spray_climb_grade_label"),
                        )
                        Text("${wall.angle}°", style = MaterialTheme.typography.titleMedium)
                    }
                    if (state.earlier) {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.testTag("spray_earlier_banner")) {
                            Text(stringResource(R.string.spray_climb_earlier_banner), modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    } else if (state.lostHolds > 0) {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.testTag("spray_lost_banner")) {
                            Text(
                                pluralStringResource(R.plurals.spray_climb_lost_banner, state.lostHolds, state.lostHolds),
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    if (climb.description.isNotBlank()) Text(climb.description, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { logDialog = true }, modifier = Modifier.weight(1f).testTag("spray_log_send")) {
                            Text(stringResource(R.string.spray_log_send))
                        }
                        OutlinedButton(onClick = { logDialog = false }, modifier = Modifier.weight(1f).testTag("spray_log_attempt")) {
                            Text(stringResource(R.string.spray_log_attempt))
                        }
                    }
                    Text(stringResource(R.string.spray_logbook_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    if (state.ascents.isEmpty()) {
                        Text(stringResource(R.string.spray_logbook_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(state.ascents, key = { it.uuid }) { ascent ->
                AscentRow(ascent, state.gradeScale) { viewModel.deleteAscent(ascent.uuid) }
                HorizontalDivider()
            }
        }
    }
    logDialog?.let { isSend ->
        LogDialog(
            isSend = isSend,
            initialDifficulty = climb?.difficulty,
            scale = state.gradeScale,
            onDismiss = { logDialog = null },
            onLog = { tries, difficulty, comment ->
                logDialog = null
                viewModel.log(isSend, tries, difficulty, comment)
            },
        )
    }
}

@Composable
private fun AscentRow(ascent: SprayAscent, scale: GradeScale, onDelete: () -> Unit) {
    val date = runCatching {
        LocalDateTime.parse(ascent.climbedAt).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
    }.getOrDefault(ascent.climbedAt)
    var confirm by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = {
            Text(stringResource(if (ascent.isSend) R.string.spray_logbook_send else R.string.spray_logbook_attempt, date))
        },
        supportingContent = {
            val parts = listOfNotNull(
                stringResource(R.string.spray_logbook_tries, ascent.tries),
                ascent.difficulty?.let { formatSprayGrade(it, scale) },
                ascent.comment,
            )
            Text(parts.joinToString(" · "))
        },
        trailingContent = {
            IconButton(onClick = { confirm = true }) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.spray_logbook_delete))
            }
        },
        modifier = Modifier.testTag("spray_ascent_row"),
    )
    if (confirm) {
        SprayConfirmDialog(
            title = stringResource(R.string.spray_logbook_delete),
            text = date,
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { confirm = false; onDelete() },
            onDismiss = { confirm = false },
        )
    }
}

@Composable
private fun LogDialog(
    isSend: Boolean,
    initialDifficulty: Int?,
    scale: GradeScale,
    onDismiss: () -> Unit,
    onLog: (tries: Int, difficulty: Int?, comment: String) -> Unit,
) {
    var tries by remember { mutableIntStateOf(1) }
    var rate by remember { mutableStateOf(false) }
    var difficulty by remember { mutableIntStateOf(initialDifficulty ?: com.cruxcoach.domain.board.KilterGradeMapper.DEFAULT_SETTER_GRADE_ID) }
    var comment by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isSend) R.string.spray_log_send else R.string.spray_log_attempt)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.spray_log_tries, tries), style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = tries.toFloat(),
                    onValueChange = { tries = it.toInt().coerceAtLeast(1) },
                    valueRange = 1f..20f,
                    steps = 18,
                    modifier = Modifier.testTag("spray_log_tries"),
                )
                if (isSend) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.spray_log_rate), modifier = Modifier.weight(1f))
                        Switch(checked = rate, onCheckedChange = { rate = it }, modifier = Modifier.testTag("spray_log_rate"))
                    }
                    if (rate) {
                        Text(stringResource(R.string.spray_climb_grade, formatSprayGrade(difficulty, scale)), style = MaterialTheme.typography.labelLarge)
                        Slider(
                            value = difficulty.toFloat(),
                            onValueChange = { difficulty = SprayClimbRules.clampDifficulty(it.toInt()) },
                            valueRange = SprayClimbRules.MIN_DIFFICULTY.toFloat()..SprayClimbRules.MAX_DIFFICULTY.toFloat(),
                            steps = SprayClimbRules.MAX_DIFFICULTY - SprayClimbRules.MIN_DIFFICULTY - 1,
                        )
                    }
                }
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it.take(500) },
                    label = { Text(stringResource(R.string.spray_log_comment)) },
                    modifier = Modifier.fillMaxWidth().testTag("spray_log_comment"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onLog(tries, difficulty.takeIf { isSend && rate }, comment) }, modifier = Modifier.testTag("spray_log_confirm")) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
