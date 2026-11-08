package com.cruxcoach.android.ui.spraywall

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.spraywall.SprayWallPhotoStore
import com.cruxcoach.android.spraywall.SprayWallPublisher
import com.cruxcoach.android.spraywall.SprayWallRepository
import com.cruxcoach.domain.board.KilterGradeMapper
import com.cruxcoach.domain.spraywall.SprayClimbRules
import com.cruxcoach.domain.spraywall.SprayHold
import com.cruxcoach.domain.spraywall.SprayHoldRole
import com.cruxcoach.domain.spraywall.SprayWall
import com.cruxcoach.domain.spraywall.SprayWallHistory
import com.cruxcoach.domain.spraywall.SprayWallVersion
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
import javax.inject.Inject

data class SprayClimbEditorState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val climbUuid: String? = null,
    val wall: SprayWall? = null,
    /** The version the climb is set on: the current one, or for a climb from before a reset its own. */
    val version: SprayWallVersion? = null,
    val photo: ImageBitmap? = null,
    /** The holds of [version]. */
    val wallHolds: List<SprayHold> = emptyList(),
    /** Where each hold was last seen, so a climb's hold that came off can be drawn dashed. */
    val lastKnown: Map<Long, SprayHold> = emptyMap(),
    val holds: Map<Long, SprayHoldRole> = emptyMap(),
    /** Holds the climb had when the editor opened; it may keep those even if they came off the wall. */
    val originalHolds: Map<Long, SprayHoldRole> = emptyMap(),
    val brush: SprayHoldRole = SprayHoldRole.START,
    val name: String = "",
    val description: String = "",
    val graded: Boolean = true,
    val difficulty: Int = KilterGradeMapper.DEFAULT_SETTER_GRADE_ID,
    val original: Snapshot? = null,
    val showProblems: Boolean = false,
    val saving: Boolean = false,
    val gradeScale: GradeScale = GradeScale.FRENCH,
) {
    data class Snapshot(val holds: Map<Long, SprayHoldRole>, val name: String, val description: String, val difficulty: Int?)

    val isEdit: Boolean get() = climbUuid != null
    val holdsOnWall: Set<Long> get() = wallHolds.mapTo(HashSet()) { it.id }
    val problems: List<SprayClimbRules.Problem>
        get() = SprayClimbRules.problems(name, holds, holdsOnWall, originalHolds.keys)
    private val current: Snapshot get() = Snapshot(holds, name.trim(), description.trim(), difficulty.takeIf { graded })
    val dirty: Boolean get() = !loading && current != (original ?: Snapshot(emptyMap(), "", "", KilterGradeMapper.DEFAULT_SETTER_GRADE_ID))
}

sealed interface SprayClimbEditorEvent {
    data class Saved(val uuid: String) : SprayClimbEditorEvent
    data object Deleted : SprayClimbEditorEvent
    data object RoleLimit : SprayClimbEditorEvent
    data object Failed : SprayClimbEditorEvent
}

@HiltViewModel
class SprayClimbEditorViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: SprayWallRepository,
    private val photos: SprayWallPhotoStore,
    private val preferences: UserPreferences,
    private val publisher: SprayWallPublisher,
) : ViewModel() {
    private val wallId: String = savedState[SprayWallRoutes.ARG_WALL] ?: ""
    private val climbUuid: String? = savedState[SprayWallRoutes.ARG_CLIMB]
    private val _state = MutableStateFlow(SprayClimbEditorState(climbUuid = climbUuid))
    val state: StateFlow<SprayClimbEditorState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<SprayClimbEditorEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<SprayClimbEditorEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch { preferences.gradeScale.collect { scale -> _state.update { it.copy(gradeScale = scale) } } }
        viewModelScope.launch {
            val wall = repository.wall(wallId)
            val climb = climbUuid?.let { repository.climb(it) }
            if (wall == null || (climbUuid != null && (climb == null || !climb.isMine))) {
                _state.update { it.copy(loading = false, notFound = true) }
                return@launch
            }
            // A climb from before a reset stays on the photo it was set on.
            val versions = repository.versions(wallId)
            val earlier = SprayWallHistory.earlierVersions(versions, wall.current)
            val version = climb?.version?.takeIf { it in earlier }?.let { n -> versions.firstOrNull { it.number == n } } ?: wall.current
            val wallHolds = repository.holds(wallId, version.number)
            val lastKnown = SprayWallHistory.lastKnown(repository.allHolds(wallId).filter { it.version <= version.number || version == wall.current })
            keepVersion = version.number.takeIf { version != wall.current }
            _state.update {
                if (climb == null) {
                    it.copy(loading = false, wall = wall, version = version, wallHolds = wallHolds, lastKnown = lastKnown)
                } else {
                    it.copy(
                        loading = false, wall = wall, version = version, wallHolds = wallHolds, lastKnown = lastKnown,
                        holds = climb.holds, originalHolds = climb.holds,
                        brush = SprayHoldRole.HAND,
                        name = climb.name, description = climb.description,
                        graded = climb.difficulty != null,
                        difficulty = climb.difficulty ?: KilterGradeMapper.DEFAULT_SETTER_GRADE_ID,
                        original = SprayClimbEditorState.Snapshot(climb.holds, climb.name.trim(), climb.description.trim(), climb.difficulty),
                    )
                }
            }
            _state.update { it.copy(photo = photos.load(version.photoFile)?.asImageBitmap()) }
        }
    }

    /** Set for a climb from before a reset: it keeps its version instead of moving to the current one. */
    private var keepVersion: Int? = null

    fun onTap(holdId: Long?) {
        val s = _state.value
        holdId ?: return
        // A hold that came off the wall can only be repainted or taken off a climb that already had it.
        if (holdId !in s.holdsOnWall && holdId !in s.originalHolds) return
        val next = SprayClimbRules.tap(s.holds, holdId, s.brush)
        if (next == null) {
            _events.tryEmit(SprayClimbEditorEvent.RoleLimit)
            return
        }
        // After the first start hold, the natural next role is a hand hold.
        val brush = if (s.brush == SprayHoldRole.START && next.count { it.value == SprayHoldRole.START } >= SprayClimbRules.MAX_START) SprayHoldRole.HAND else s.brush
        _state.update { it.copy(holds = next, brush = brush) }
    }

    fun setBrush(role: SprayHoldRole) = _state.update { it.copy(brush = role) }
    fun setName(name: String) = _state.update { it.copy(name = name.take(SprayClimbRules.MAX_NAME_LENGTH + 20)) }
    fun setDescription(text: String) = _state.update { it.copy(description = text.take(SprayClimbRules.MAX_DESCRIPTION_LENGTH)) }
    fun setGraded(on: Boolean) = _state.update { it.copy(graded = on) }
    fun setDifficulty(value: Int) = _state.update { it.copy(difficulty = SprayClimbRules.clampDifficulty(value)) }

    fun save() {
        val s = _state.value
        if (s.saving || s.loading) return
        if (s.problems.isNotEmpty()) {
            _state.update { it.copy(showProblems = true) }
            return
        }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val uuid = repository.saveClimb(
                    uuid = s.climbUuid, wallId = wallId, name = s.name, description = s.description,
                    difficulty = s.difficulty.takeIf { s.graded }, holds = s.holds, version = keepVersion,
                )
                _events.emit(SprayClimbEditorEvent.Saved(uuid))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saving = false) }
                _events.emit(SprayClimbEditorEvent.Failed)
            }
        }
    }

    fun delete() {
        val uuid = climbUuid ?: return
        viewModelScope.launch {
            // A published climb is taken back from Nostr too.
            repository.deleteClimb(uuid)?.let { publisher.withdrawClimbSoon(it, _state.value.wall?.ownerPubkey) }
            _events.emit(SprayClimbEditorEvent.Deleted)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SprayClimbEditorScreen(
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    onDeleted: () -> Unit,
    viewModel: SprayClimbEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val leave: () -> Unit = { if (state.dirty) { confirmLeave = true } else { onBack() } }
    BackHandler(enabled = state.dirty) { confirmLeave = true }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SprayClimbEditorEvent.Saved -> onSaved(event.uuid)
                SprayClimbEditorEvent.Deleted -> onDeleted()
                SprayClimbEditorEvent.RoleLimit -> snackbar.showSnackbar(context.getString(R.string.spray_climb_role_limit))
                SprayClimbEditorEvent.Failed -> snackbar.showSnackbar(context.getString(R.string.spray_climb_save_failed))
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.isEdit) R.string.spray_climb_edit_title else R.string.spray_climb_new_title)) },
                navigationIcon = {
                    IconButton(onClick = leave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::save, enabled = !state.saving && !state.loading, modifier = Modifier.testTag("spray_climb_save")) {
                        Text(stringResource(R.string.action_save))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val wall = state.wall
        val version = state.version
        if (state.notFound || wall == null || version == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (state.notFound) Text(stringResource(R.string.spray_not_found))
            }
            return@Scaffold
        }
        val maxCanvasHeight = screenHeightShare(0.6f)
        Column(Modifier.fillMaxSize().padding(padding)) {
            SprayWallCanvas(
                photo = state.photo,
                photoWidth = version.photoWidth,
                photoHeight = version.photoHeight,
                holds = climbCanvasHolds(state.wallHolds, state.lastKnown, state.holds, showOthers = true),
                gestures = SprayCanvasGestures(onTap = { _, _, id -> viewModel.onTap(id) }),
                modifier = Modifier.sprayPhotoSize(version.aspectRatio, maxCanvasHeight),
            )
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.spray_climb_brush_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (wall.visibility == SprayWallVisibility.PUBLIC) {
                    Text(stringResource(R.string.spray_climb_public_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("spray_climb_public_note"))
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        SprayHoldRole.START to R.string.climb_creator_count_start,
                        SprayHoldRole.HAND to R.string.climb_creator_count_hand,
                        SprayHoldRole.FOOT to R.string.climb_creator_count_foot,
                        SprayHoldRole.FINISH to R.string.climb_creator_count_finish,
                    ).forEach { (role, label) ->
                        FilterChip(
                            selected = state.brush == role,
                            onClick = { viewModel.setBrush(role) },
                            label = { Text(stringResource(label, state.holds.values.count { it == role })) },
                            leadingIcon = { Canvas(Modifier.size(12.dp)) { drawCircle(role.color()) } },
                            modifier = Modifier.testTag("spray_brush_${role.wire}"),
                        )
                    }
                }
                OutlinedTextField(
                    value = state.name,
                    onValueChange = viewModel::setName,
                    label = { Text(stringResource(R.string.spray_climb_name)) },
                    singleLine = true,
                    isError = state.showProblems && (SprayClimbRules.Problem.NAME_MISSING in state.problems || SprayClimbRules.Problem.NAME_TOO_LONG in state.problems),
                    modifier = Modifier.fillMaxWidth().testTag("spray_climb_name"),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.spray_climb_graded), modifier = Modifier.weight(1f))
                    Switch(checked = state.graded, onCheckedChange = viewModel::setGraded, modifier = Modifier.testTag("spray_climb_graded"))
                }
                if (state.graded) {
                    Text(stringResource(R.string.spray_climb_grade, formatSprayGrade(state.difficulty, state.gradeScale)), style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = state.difficulty.toFloat(),
                        onValueChange = { viewModel.setDifficulty(it.toInt()) },
                        valueRange = SprayClimbRules.MIN_DIFFICULTY.toFloat()..SprayClimbRules.MAX_DIFFICULTY.toFloat(),
                        steps = SprayClimbRules.MAX_DIFFICULTY - SprayClimbRules.MIN_DIFFICULTY - 1,
                        modifier = Modifier.testTag("spray_climb_grade"),
                    )
                } else {
                    Text(stringResource(R.string.spray_climb_ungraded_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(
                    value = state.description,
                    onValueChange = viewModel::setDescription,
                    label = { Text(stringResource(R.string.spray_climb_description)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().testTag("spray_climb_description"),
                )
                if (state.showProblems) {
                    state.problems.forEach { problem ->
                        Text(problemText(problem), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("spray_climb_problem"))
                    }
                }
                Button(onClick = viewModel::save, enabled = !state.saving, modifier = Modifier.fillMaxWidth().testTag("spray_climb_save_bottom")) {
                    Text(stringResource(R.string.action_save))
                }
                if (state.isEdit) {
                    TextButton(onClick = { confirmDelete = true }, modifier = Modifier.testTag("spray_climb_delete")) {
                        Text(stringResource(R.string.spray_climb_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
    if (confirmLeave) {
        SprayDiscardDialog(onDiscard = { confirmLeave = false; onBack() }, onKeep = { confirmLeave = false })
    }
    if (confirmDelete) {
        SprayConfirmDialog(
            title = stringResource(R.string.spray_climb_delete_confirm_title, state.name),
            text = stringResource(R.string.spray_climb_delete_confirm_text),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { confirmDelete = false; viewModel.delete() },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun problemText(problem: SprayClimbRules.Problem): String = when (problem) {
    SprayClimbRules.Problem.NAME_MISSING -> stringResource(R.string.spray_problem_name)
    SprayClimbRules.Problem.NAME_TOO_LONG -> stringResource(R.string.spray_problem_name_long)
    SprayClimbRules.Problem.NO_START, SprayClimbRules.Problem.TOO_MANY_START -> stringResource(R.string.spray_problem_start)
    SprayClimbRules.Problem.NO_FINISH, SprayClimbRules.Problem.TOO_MANY_FINISH -> stringResource(R.string.spray_problem_finish)
    SprayClimbRules.Problem.TOO_FEW_HOLDS -> stringResource(R.string.spray_problem_few_holds, SprayClimbRules.MIN_HOLDS)
    SprayClimbRules.Problem.TOO_MANY_HOLDS -> stringResource(R.string.spray_problem_many_holds, SprayClimbRules.MAX_HOLDS)
    SprayClimbRules.Problem.HOLD_GONE -> stringResource(R.string.spray_problem_gone)
}
