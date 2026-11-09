package com.cruxcoach.android.ui.spraywall

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.payment.NostrProfileManager
import com.cruxcoach.android.spraywall.SprayWallFetcher
import com.cruxcoach.android.spraywall.SprayWallPhotoStore
import com.cruxcoach.android.spraywall.SprayWallPublisher
import com.cruxcoach.android.spraywall.SprayWallRepository
import com.cruxcoach.android.spraywall.SprayWithdrawal
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.domain.spraywall.SprayClimb
import com.cruxcoach.domain.spraywall.SprayClimbFilter
import com.cruxcoach.domain.spraywall.SprayClimbList
import com.cruxcoach.domain.spraywall.SprayClimbRow
import com.cruxcoach.domain.spraywall.SprayClimbSort
import com.cruxcoach.domain.spraywall.SprayHold
import com.cruxcoach.domain.spraywall.SprayVersionKind
import com.cruxcoach.domain.spraywall.SprayWall
import com.cruxcoach.domain.spraywall.SprayWallHistory
import com.cruxcoach.domain.spraywall.SprayWallVersion
import com.cruxcoach.domain.spraywall.SprayWallVisibility
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SprayWallUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val wall: SprayWall? = null,
    val photo: ImageBitmap? = null,
    val versions: List<SprayWallVersion> = emptyList(),
    val holds: List<SprayHold> = emptyList(),
    val climbs: List<SprayClimb> = emptyList(),
    val sends: Map<String, Int> = emptyMap(),
    val filter: SprayClimbFilter = SprayClimbFilter.ALL,
    val sort: SprayClimbSort = SprayClimbSort.NEWEST,
    val query: String = "",
    val gradeScale: GradeScale = GradeScale.FRENCH,
    /** Why a public wall of mine is not on Nostr yet (null = nothing pending or no attempt). */
    val publishProblem: PublishProblem? = null,
    val publishing: Boolean = false,
    /** Taking the wall back, from here or its details (also one started there before that screen was left). */
    val withdrawing: Boolean = false,
    /** Why the last try did not finish it (null = no try, or it worked). */
    val withdrawFailed: SprayWithdrawal? = null,
    /** The account name of the wall's owner, for someone else's wall; null until known. */
    val ownerName: String? = null,
) {
    enum class PublishProblem { NO_IDENTITY, FAILED }

    val holdsOnWall: Set<Long> get() = holds.mapTo(HashSet()) { it.id }
    val earlierVersions: Set<Int> get() = wall?.let { SprayWallHistory.earlierVersions(versions, it.current) }.orEmpty()
    val rows: List<SprayClimbRow> get() = SprayClimbList.rows(climbs, holdsOnWall, sends, filter, sort, query, earlierVersions)
    val filters: List<SprayClimbFilter> get() = SprayClimbList.filters(climbs, holdsOnWall, earlierVersions)
    /** A reset whose holds were not marked yet. */
    val unfinishedReset: Boolean get() = wall?.current?.let { it.kind == SprayVersionKind.RESET && it.holdCount == 0 } == true
}

@HiltViewModel
class SprayWallViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: SprayWallRepository,
    private val photos: SprayWallPhotoStore,
    private val preferences: UserPreferences,
    private val publisher: SprayWallPublisher,
    private val fetcher: SprayWallFetcher,
    private val profiles: NostrProfileManager,
) : ViewModel() {
    private val wallId: String = savedState[SprayWallRoutes.ARG_WALL] ?: ""
    private var ownerAsked: String? = null
    private val _state = MutableStateFlow(SprayWallUiState())
    val state: StateFlow<SprayWallUiState> = _state.asStateFlow()
    private var photoOf: String? = null

    init {
        viewModelScope.launch { repository.changes.collect { reload() } }
        viewModelScope.launch { preferences.gradeScale.collect { scale -> _state.update { it.copy(gradeScale = scale) } } }
        viewModelScope.launch {
            publisher.last.collect { outcome ->
                val problem = when (outcome) {
                    SprayWallPublisher.Outcome.NoIdentity -> SprayWallUiState.PublishProblem.NO_IDENTITY
                    is SprayWallPublisher.Outcome.Failed -> SprayWallUiState.PublishProblem.FAILED
                    else -> null
                }
                _state.update { it.copy(publishProblem = problem) }
            }
        }
        viewModelScope.launch {
            publisher.withdrawing.collect { walls -> _state.update { it.copy(withdrawing = wallId in walls) } }
        }
        // A public wall: climbs others set on it, new versions, withdrawals.
        viewModelScope.launch { fetcher.refresh(wallId) }
    }

    fun publishNow() {
        if (_state.value.publishing) return
        _state.update { it.copy(publishing = true) }
        viewModelScope.launch {
            // Asked for here: the signer is asked again also for what it declined before.
            publisher.publishPending(askAgainFor = wallId)
            _state.update { it.copy(publishing = false) }
        }
    }

    /** A withdrawal the relays took part of: tries the rest again; done, the wall turns private. */
    fun withdrawAgain() {
        if (_state.value.withdrawing || wallId in publisher.withdrawing.value) return
        _state.update { it.copy(withdrawing = true, withdrawFailed = null) }
        viewModelScope.launch {
            val result = publisher.withdrawWall(wallId)
            _state.update { it.copy(withdrawFailed = result.takeIf { r -> r != SprayWithdrawal.DONE }) }
        }
    }

    /** Someone else's wall: removes the copy here with my logbook on it, and takes my climbs on it back from Nostr. */
    fun removeFollowed(onDone: () -> Unit) {
        viewModelScope.launch {
            val wall = repository.wall(wallId)
            repository.myClimbs(wallId).forEach { publisher.withdrawClimbSoon(it, wall) }
            repository.deleteWall(wallId)
            onDone()
        }
    }

    private suspend fun reload() {
        val wall = repository.wall(wallId)
        if (wall == null) {
            _state.update { it.copy(loading = false, notFound = true) }
            return
        }
        val versions = repository.versions(wallId)
        val holds = repository.holds(wallId, wall.current.number)
        val climbs = repository.climbs(wallId)
        val sends = repository.sendCounts(wallId)
        _state.update {
            val loaded = it.copy(loading = false, wall = wall, versions = versions, holds = holds, climbs = climbs, sends = sends)
            // A restore or a hold edit can leave the selected filter with nothing to show.
            loaded.copy(filter = SprayClimbList.keep(loaded.filter, loaded.filters))
        }
        // Someone else's wall: their account name instead of the start of their key, once known.
        val owner = wall.ownerPubkey
        if (!wall.isMine && owner != null && ownerAsked != owner) {
            ownerAsked = owner
            viewModelScope.launch {
                profiles.sprayOwnerName(owner)?.let { name -> _state.update { it.copy(ownerName = name) } }
            }
        }
        // A reset brings a new photo.
        if (photoOf != wall.photoFile) {
            photoOf = wall.photoFile
            val bitmap = photos.load(wall.photoFile, PREVIEW_LONG_SIDE)?.asImageBitmap()
            _state.update { it.copy(photo = bitmap) }
        }
    }

    fun setFilter(filter: SprayClimbFilter) = _state.update { it.copy(filter = filter) }
    fun setSort(sort: SprayClimbSort) = _state.update { it.copy(sort = sort) }
    fun setQuery(query: String) = _state.update { it.copy(query = query) }
    fun discardUnfinishedReset() {
        viewModelScope.launch { repository.discardCurrentVersion(wallId) }
    }

    private companion object {
        const val PREVIEW_LONG_SIDE = 1600
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SprayWallScreen(
    onBack: () -> Unit,
    onEditWall: () -> Unit,
    onEditHolds: () -> Unit,
    onReset: () -> Unit,
    onVersions: () -> Unit,
    onNewClimb: () -> Unit,
    onOpenClimb: (String) -> Unit,
    onOpenAccount: () -> Unit = {},
    viewModel: SprayWallViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val wall = state.wall
    val hasHolds = state.holdsOnWall.isNotEmpty()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { SprayBarTitle(wall?.name ?: stringResource(R.string.spray_walls_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (wall != null) {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("spray_wall_menu")) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_more_options))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (wall.isMine) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.spray_wall_edit_holds)) },
                                    onClick = { menuOpen = false; onEditHolds() },
                                    leadingIcon = { Icon(Icons.Default.TouchApp, contentDescription = null) },
                                    modifier = Modifier.testTag("spray_menu_holds"),
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.spray_wall_reset)) },
                                    onClick = { menuOpen = false; onReset() },
                                    leadingIcon = { Icon(Icons.Default.PhotoCamera, contentDescription = null) },
                                    modifier = Modifier.testTag("spray_menu_reset"),
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.spray_versions_title)) },
                                onClick = { menuOpen = false; onVersions() },
                                leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
                                modifier = Modifier.testTag("spray_menu_versions"),
                            )
                            if (wall.isMine) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.spray_wall_edit_title)) },
                                    onClick = { menuOpen = false; onEditWall() },
                                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                    modifier = Modifier.testTag("spray_menu_edit"),
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.spray_followed_remove)) },
                                    onClick = { menuOpen = false; confirmRemove = true },
                                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                    modifier = Modifier.testTag("spray_menu_remove"),
                                )
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (hasHolds) {
                ExtendedFloatingActionButton(
                    onClick = onNewClimb,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.spray_climb_new)) },
                    // The design accent pair: the default container colours put orange text on orange.
                    containerColor = CruxCoachDesign.colors.brandAccent,
                    contentColor = CruxCoachDesign.colors.onBrandAccent,
                    modifier = Modifier.testTag("spray_climb_new"),
                )
            }
        },
    ) { padding ->
        if (state.notFound || wall == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (state.notFound) Text(stringResource(R.string.spray_not_found))
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                SprayWallCanvas(
                    photo = state.photo,
                    photoWidth = wall.photoWidth,
                    photoHeight = wall.photoHeight,
                    holds = state.holds.map { SprayCanvasHold(it.id, it.x, it.y, it.radius, SprayHoldLook.FAINT, outline = it.outline) },
                    interactive = false,
                    modifier = Modifier.sprayPhotoSize(wall.aspectRatio, screenHeightShare(0.45f)),
                )
            }
            val problem = state.publishProblem
            // While the card below says why the upload failed, the facts line does not say it again.
            item { WallFacts(wall, showPending = !(wall.needsPublish && problem != null)) }
            if (!wall.isMine) item { FollowedNote(wall, state.ownerName) }
            // Nothing of a wall the relays took back in part is published: finishing the withdrawal is what is left.
            if (wall.isMine && wall.withdrawStarted != null) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        WithdrawUnfinishedCard(withdrawing = state.withdrawing, enabled = true, onRetry = viewModel::withdrawAgain)
                        state.withdrawFailed?.let {
                            Text(withdrawFailureReason(it, started = true), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (wall.needsPublish && problem != null) {
                item { PublishCard(problem, state.publishing, viewModel::publishNow, onOpenAccount) }
            }
            if (state.unfinishedReset && wall.isMine) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("spray_reset_unfinished"),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.spray_reset_unfinished_text))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = onEditHolds) { Text(stringResource(R.string.spray_holds_title)) }
                                // In the card's own text colour: orange text or a grey outline would fade into the card.
                                OutlinedButton(
                                    onClick = viewModel::discardUnfinishedReset,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f)),
                                ) { Text(stringResource(R.string.spray_reset_discard)) }
                            }
                        }
                    }
                }
            } else if (!hasHolds && wall.isMine) {
                item {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.spray_wall_no_holds), textAlign = TextAlign.Center)
                        Button(onClick = onEditHolds, modifier = Modifier.padding(top = 12.dp).testTag("spray_wall_mark_holds")) {
                            Text(stringResource(R.string.spray_holds_title))
                        }
                    }
                }
            }
            if (state.climbs.isEmpty() && !hasHolds) return@LazyColumn
            // Search, filters and sorting only once there is something to search, filter and sort.
            if (state.climbs.isNotEmpty()) item { ListControls(state, viewModel) }
            val rows = state.rows
            if (rows.isEmpty()) {
                val empty = when {
                    state.climbs.isEmpty() -> R.string.spray_climbs_empty
                    // Right after a reset: the climbs so far are one chip further.
                    state.filter == SprayClimbFilter.ALL && state.query.isBlank() && SprayClimbFilter.EARLIER in state.filters -> R.string.spray_climbs_none_current
                    else -> R.string.spray_climbs_none_match
                }
                item {
                    Text(
                        stringResource(empty),
                        modifier = Modifier.fillMaxWidth().padding(24.dp).testTag("spray_climbs_empty"),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            items(rows, key = { it.climb.uuid }) { row ->
                ClimbRow(row, state.gradeScale) { onOpenClimb(row.climb.uuid) }
            }
        }
    }
    if (confirmRemove) {
        SprayConfirmDialog(
            title = stringResource(R.string.spray_followed_remove),
            text = stringResource(R.string.spray_followed_remove_text),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { confirmRemove = false; viewModel.removeFollowed(onBack) },
            onDismiss = { confirmRemove = false },
        )
    }
}

@Composable
private fun FollowedNote(wall: SprayWall, ownerName: String?) {
    val owner = wall.ownerPubkey.orEmpty()
    val text = if (wall.visibility == SprayWallVisibility.PUBLIC) {
        stringResource(R.string.spray_followed_note, ownerName ?: shortKey(owner))
    } else {
        stringResource(R.string.spray_followed_withdrawn)
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp).testTag("spray_followed_note"),
    )
}

@Composable
private fun PublishCard(problem: SprayWallUiState.PublishProblem, publishing: Boolean, onRetry: () -> Unit, onOpenAccount: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("spray_publish_card"),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 2.dp).size(18.dp))
                Text(
                    stringResource(
                        if (problem == SprayWallUiState.PublishProblem.NO_IDENTITY) R.string.spray_publish_no_identity_text else R.string.spray_publish_failed,
                    ),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            // The card's one way forward, as a filled button: an outlined one looks disabled on the card colour.
            if (problem == SprayWallUiState.PublishProblem.FAILED) {
                Button(onClick = onRetry, enabled = !publishing, modifier = Modifier.testTag("spray_publish_retry")) {
                    Text(stringResource(R.string.action_retry))
                }
            } else {
                Button(onClick = onOpenAccount, modifier = Modifier.testTag("spray_publish_setup_account")) {
                    Text(stringResource(R.string.spray_publish_setup_account))
                }
            }
        }
    }
}

/** The first characters of a public key, enough to tell people apart in a list. */
internal fun shortKey(hex: String): String = if (hex.length > 12) "${hex.take(8)}…" else hex

@Composable
private fun WallFacts(wall: SprayWall, showPending: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val public = wall.visibility == SprayWallVisibility.PUBLIC
        Icon(if (public) Icons.Default.Public else Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
        val parts = listOfNotNull(
            stringResource(if (public) R.string.spray_visibility_public else R.string.spray_visibility_private),
            stringResource(R.string.spray_publish_pending).takeIf { wall.needsPublish && showPending },
            wall.gym?.name?.takeIf { it.isNotBlank() },
            "${wall.angle}°",
            // A wall with one version has nothing to tell apart.
            stringResource(R.string.spray_version_label, wall.current.number).takeIf { wall.current.number > 1 },
        )
        // Non-breaking before each dot, so a wrapped line never starts with one.
        Text(parts.joinToString("\u00A0· "), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).testTag("spray_wall_facts"))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ListControls(state: SprayWallUiState, viewModel: SprayWallViewModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.climbs.size > SEARCH_FROM) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.spray_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("spray_search"),
            )
        }
        // Chips wrap instead of scrolling sideways; the sort is one chip with its choices in a menu.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // The holds filters only matter once a climb lost a hold, "earlier" once the wall was reset.
            val filters = state.filters
            if (filters.size > 1) {
                filters.forEach { filter ->
                    val label = when (filter) {
                        SprayClimbFilter.ALL -> R.string.spray_filter_all
                        SprayClimbFilter.INTACT -> R.string.spray_filter_intact
                        SprayClimbFilter.LOST_HOLDS -> R.string.spray_filter_lost
                        SprayClimbFilter.EARLIER -> R.string.spray_filter_earlier
                    }
                    FilterChip(
                        selected = state.filter == filter,
                        onClick = { viewModel.setFilter(filter) },
                        label = { Text(stringResource(label)) },
                        modifier = Modifier.testTag("spray_filter_${filter.name.lowercase()}"),
                    )
                }
            }
            SortChip(state.sort, viewModel::setSort)
        }
    }
}

private val SORT_LABELS = listOf(
    SprayClimbSort.NEWEST to R.string.spray_sort_newest,
    SprayClimbSort.NAME to R.string.spray_sort_name,
    SprayClimbSort.GRADE to R.string.spray_sort_grade,
)

@Composable
private fun SortChip(sort: SprayClimbSort, onSort: (SprayClimbSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(stringResource(SORT_LABELS.first { it.first == sort }.second)) },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.spray_sort_title)) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.testTag("spray_sort"),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SORT_LABELS.forEach { (option, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    onClick = { open = false; onSort(option) },
                    trailingIcon = { if (option == sort) Icon(Icons.Default.Check, contentDescription = null) },
                    modifier = Modifier.testTag("spray_sort_${option.name.lowercase()}"),
                )
            }
        }
    }
}

/** A climb in the board browser's card style: grade badge, name, then sends and what is special about it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClimbRow(row: SprayClimbRow, scale: GradeScale, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("spray_climb_row"),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SprayGradeBadge(row.climb.difficulty, scale)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(row.climb.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val small = MaterialTheme.typography.bodySmall
                    val muted = MaterialTheme.colorScheme.onSurfaceVariant
                    if (row.climb.difficulty == null) Text(stringResource(R.string.spray_climb_project), style = small, color = muted)
                    if (row.sends > 0) Text(pluralStringResource(R.plurals.spray_climb_sends, row.sends, row.sends), style = small, color = muted)
                    if (!row.climb.isMine) Text(stringResource(R.string.spray_climb_by_other), style = small, color = muted)
                    if (row.earlier) {
                        Text(stringResource(R.string.spray_climb_earlier), style = small, color = muted, modifier = Modifier.testTag("spray_earlier_label"))
                    } else if (row.lostHolds > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("spray_lost_chip")) {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            Text(
                                pluralStringResource(R.plurals.spray_climb_holds_gone, row.lostHolds, row.lostHolds),
                                style = small,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val SEARCH_FROM = 8
