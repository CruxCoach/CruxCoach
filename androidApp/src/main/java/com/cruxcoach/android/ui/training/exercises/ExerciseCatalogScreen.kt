package com.cruxcoach.android.ui.training.exercises

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.android.ui.training.*
import com.cruxcoach.athlete.catalog.CatalogFilter
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.catalog.ExerciseCategoryV2
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.logic.InjuryAdvice
import com.cruxcoach.athlete.logic.InjuryAdvisor
import com.cruxcoach.athlete.logic.InjuryVerdict
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.Injury
import com.cruxcoach.athlete.model.InjuryRegion
import com.cruxcoach.athlete.model.InjurySide
import com.cruxcoach.athlete.model.Side
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CatalogState(
    val loading: Boolean = true,
    val catalog: ExerciseCatalog = ExerciseCatalog.EMPTY,
    val profile: AthleteProfile = AthleteProfile(),
    val injuries: List<Injury> = emptyList(),
    val favorites: Set<String> = emptySet(),
    val query: String = "",
    val categories: Set<ExerciseCategoryV2> = emptySet(),
    val mineOnly: Boolean = false,
    val withoutClimbing: Boolean = false,
    val fingerFree: Boolean = false,
    val favoritesOnly: Boolean = false,
    /** True while the climbing/finger toggles still hold the injury defaults. */
    val injuryDefaultsApplied: Boolean = false,
    /** One-shot events for the screen. */
    val picked: Boolean = false,
    val pickRejected: Boolean = false,
) {
    val filter: CatalogFilter get() = CatalogFilter(
        categories = categories,
        ownedEquipment = if (mineOnly && profile.equipmentConfigured) profile.equipment + EquipmentV2.NONE else null,
        withoutClimbing = withoutClimbing,
        fingerFreeOnly = fingerFree,
        favoritesOnly = favoritesOnly,
        favorites = favorites,
    )
}

@HiltViewModel
class ExerciseCatalogViewModel @Inject constructor(private val service: AthleteService) : ViewModel() {

    private val _state = MutableStateFlow(CatalogState())
    val state: StateFlow<CatalogState> = _state.asStateFlow()
    private var userTouchedSafetyFilters = false

    init {
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val repo = service.repo
            combine(
                service.catalogStore.catalog,
                repo.observeProfile(),
                repo.observeActiveInjuries(),
                repo.observeFavorites(),
            ) { catalog, profile, injuries, favorites -> Quad(catalog, profile, injuries, favorites) }
                .collect { (catalog, profile, injuries, favorites) ->
                    _state.update { s ->
                        // An injury adapts the filters once (MCI-style), until the athlete changes them.
                        val pauseClimbing = injuries.any { it.climbingPaused }
                        // Only a finger injury on both hands hides finger work: with one hand
                        // hurt, one-arm work on the healthy hand stays visible and the row
                        // says "healthy side only".
                        val fingerInjury = injuries.any {
                            it.region == InjuryRegion.FINGER && (it.side == null || it.side == InjurySide.BOTH)
                        }
                        val applyDefaults = !userTouchedSafetyFilters && (pauseClimbing || fingerInjury)
                        s.copy(
                            loading = false,
                            catalog = catalog,
                            profile = profile,
                            injuries = injuries,
                            favorites = favorites,
                            withoutClimbing = if (applyDefaults) pauseClimbing else s.withoutClimbing,
                            fingerFree = if (applyDefaults) fingerInjury else s.fingerFree,
                            injuryDefaultsApplied = applyDefaults || (s.injuryDefaultsApplied && !userTouchedSafetyFilters),
                        )
                    }
                }
        }
    }

    private data class Quad(val catalog: ExerciseCatalog, val profile: AthleteProfile, val injuries: List<Injury>, val favorites: Set<String>)

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    fun toggleCategory(c: ExerciseCategoryV2?) = _state.update { s ->
        s.copy(categories = when {
            c == null -> emptySet()
            c in s.categories -> s.categories - c
            else -> s.categories + c
        })
    }

    fun toggleMine() = _state.update { it.copy(mineOnly = !it.mineOnly) }

    /** Equipment set up from the "Meine Ausrüstung" hint; the filter then applies at once. */
    fun saveEquipment(equipment: Set<com.cruxcoach.athlete.catalog.EquipmentV2>) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            service.ensureReady()
            service.repo.updateProfile { it.copy(equipment = equipment, equipmentConfigured = true) }
            _state.update { it.copy(mineOnly = true) }
        }
    }
    fun toggleFavorites() = _state.update { it.copy(favoritesOnly = !it.favoritesOnly) }

    fun toggleWithoutClimbing() {
        userTouchedSafetyFilters = true
        _state.update { it.copy(withoutClimbing = !it.withoutClimbing, injuryDefaultsApplied = false) }
    }

    fun toggleFingerFree() {
        userTouchedSafetyFilters = true
        _state.update { it.copy(fingerFree = !it.fingerFree, injuryDefaultsApplied = false) }
    }

    fun toggleFavorite(slug: String) {
        val favorite = slug !in _state.value.favorites
        viewModelScope.launch(Dispatchers.IO) { service.ensureReady(); service.repo.setFavorite(slug, favorite) }
    }

    /** "Nie vorschlagen" — the exercise stays findable here. */
    fun toggleExcluded(slug: String) {
        val excluded = slug !in _state.value.profile.excludedExercises
        viewModelScope.launch(Dispatchers.IO) { service.ensureReady(); service.setExcluded(slug, excluded) }
    }

    fun pick(workoutId: String, slug: String) {
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val ok = service.addExercise(workoutId, slug)
            _state.update { if (ok) it.copy(picked = true) else it.copy(pickRejected = true) }
        }
    }

    fun consumeEvents() = _state.update { it.copy(picked = false, pickRejected = false) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExerciseCatalogScreen(
    pickForWorkout: String?,
    onBack: () -> Unit,
    onOpenExercise: (String) -> Unit,
    onExercisePicked: () -> Unit,
    onCreateCustom: () -> Unit,
    viewModel: ExerciseCatalogViewModel = hiltViewModel(),
    tabBar: @Composable () -> Unit = {},
    onTrainingStarted: () -> Unit = {},
    onCreateRoutine: (from: String) -> Unit = {},
    /** The Workouts | Exercises switch of the Training tab (not while picking). */
    header: @Composable () -> Unit = {},
) {
    var addSlug by remember { mutableStateOf<String?>(null) }
    var equipmentOpen by remember { mutableStateOf(false) }
    val setUpText = stringResource(R.string.trt_sugg_equipment_action)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val language = catalogLanguage()
    val snackbar = remember { SnackbarHostState() }
    val rejectedText = stringResource(R.string.trx_pick_rejected)
    val mineHint = stringResource(R.string.trx_filter_mine_hint)
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.picked, state.pickRejected) {
        when {
            state.picked -> { viewModel.consumeEvents(); onExercisePicked() }
            state.pickRejected -> { viewModel.consumeEvents(); snackbar.showSnackbar(rejectedText) }
        }
    }

    val results = remember(state.catalog, state.query, state.filter, language) {
        state.catalog.search(state.query, language, state.filter)
    }

    TrainingScaffold(
        title = stringResource(if (pickForWorkout != null) R.string.trx_title_pick else R.string.tr_tab_training),
        onBack = onBack,
        bottomBar = { if (pickForWorkout == null) tabBar() },
        subHeader = { if (pickForWorkout == null) header() },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (pickForWorkout == null) {
                ExtendedFloatingActionButton(
                    onClick = onCreateCustom,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.trx_create_custom)) },
                    modifier = Modifier.testTag("exercise_create_custom"),
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                placeholder = { Text(stringResource(R.string.trx_search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setQuery("") }) {
                            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.trx_search_clear))
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("exercise_search"),
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.categories.isEmpty(),
                    onClick = { viewModel.toggleCategory(null) },
                    label = { Text(stringResource(R.string.trx_filter_all_categories)) },
                    modifier = Modifier.testTag("exercise_category_all"),
                )
                ExerciseCategoryV2.entries.forEach { c ->
                    FilterChip(
                        selected = c in state.categories,
                        onClick = { viewModel.toggleCategory(c) },
                        label = { Text(categoryLabel(c)) },
                        modifier = Modifier.testTag("exercise_category_${c.name.lowercase()}"),
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.mineOnly && state.profile.equipmentConfigured,
                    onClick = {
                        if (state.profile.equipmentConfigured) viewModel.toggleMine()
                        // Not set up yet: say so and offer the equipment right here.
                        else scope.launch {
                            if (snackbar.showSnackbar(mineHint, setUpText, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                                equipmentOpen = true
                            }
                        }
                    },
                    label = { Text(stringResource(R.string.trx_filter_mine)) },
                    modifier = Modifier.testTag("exercise_filter_mine"),
                )
                FilterChip(
                    selected = state.withoutClimbing,
                    onClick = viewModel::toggleWithoutClimbing,
                    label = { Text(stringResource(R.string.trx_filter_no_climbing)) },
                    modifier = Modifier.testTag("exercise_filter_no_climbing"),
                )
                FilterChip(
                    selected = state.fingerFree,
                    onClick = viewModel::toggleFingerFree,
                    label = { Text(stringResource(R.string.trx_filter_finger_free)) },
                    modifier = Modifier.testTag("exercise_filter_finger_free"),
                )
                FilterChip(
                    selected = state.favoritesOnly,
                    onClick = viewModel::toggleFavorites,
                    label = { Text(stringResource(R.string.trx_filter_favorites)) },
                    modifier = Modifier.testTag("exercise_filter_favorites"),
                )
            }
            if (state.injuryDefaultsApplied) {
                Text(
                    stringResource(R.string.trx_filters_injury_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = CruxCoachDesign.colors.caution,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }
            Text(
                pluralStringResource(R.plurals.trx_count, results.size, results.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("exercise_count"),
            )
            if (results.isEmpty()) {
                EmptyHint(stringResource(R.string.trx_no_results))
                return@Column
            }
            LazyColumn(
                Modifier.fillMaxSize().testTag("exercise_list"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Favourites first: the exercises the athlete actually uses are one tap away.
                // Safety and equipment filters do not hide the athlete's own picks; the row
                // still shows the injury advice. Category chips do apply.
                val favs = if (state.query.isBlank() && !state.favoritesOnly) {
                    state.catalog.search("", language, CatalogFilter(categories = state.categories))
                        .filter { it.slug in state.favorites }
                } else emptyList()
                val showSections = favs.isNotEmpty()
                val rest = if (showSections) results.filterNot { it.slug in state.favorites } else results
                @Composable
                fun row(def: ExerciseDefinition) = ExerciseRow(
                    def = def,
                    language = language,
                    advice = InjuryAdvisor.assess(def, state.injuries),
                    favorite = def.slug in state.favorites,
                    pickMode = pickForWorkout != null,
                    onClick = {
                        if (pickForWorkout != null) viewModel.pick(pickForWorkout, def.slug) else onOpenExercise(def.slug)
                    },
                    onOpenDetail = { onOpenExercise(def.slug) },
                    onToggleFavorite = { viewModel.toggleFavorite(def.slug) },
                    onAddToWorkout = if (pickForWorkout == null) ({ addSlug = def.slug }) else null,
                    excluded = def.slug in state.profile.excludedExercises,
                    onToggleExcluded = { viewModel.toggleExcluded(def.slug) },
                )
                if (showSections) {
                    item(key = "h-fav") { SectionTitle(stringResource(R.string.trx_section_favorites)) }
                    items(favs, key = { "fav-" + it.slug }) { row(it) }
                    item(key = "h-all") { SectionTitle(stringResource(R.string.trx_section_all)) }
                }
                items(rest, key = { it.slug }) { row(it) }
            }
        }
    }
    addSlug?.let { slug ->
        com.cruxcoach.android.ui.training.workouts.AddToWorkoutSheet(
            slug = slug,
            onDismiss = { addSlug = null },
            onTrainingStarted = { addSlug = null; onTrainingStarted() },
            onCreateRoutine = { from -> addSlug = null; onCreateRoutine(from) },
        )
    }
    if (equipmentOpen) {
        com.cruxcoach.android.ui.training.common.EquipmentSheet(
            initial = emptySet(),
            onDismiss = { equipmentOpen = false },
            onSave = { set -> viewModel.saveEquipment(set); equipmentOpen = false },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExerciseRow(
    def: ExerciseDefinition,
    language: String,
    advice: InjuryAdvice,
    favorite: Boolean,
    pickMode: Boolean,
    onClick: () -> Unit,
    onOpenDetail: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToWorkout: (() -> Unit)? = null,
    excluded: Boolean = false,
    onToggleExcluded: (() -> Unit)? = null,
) {
    val name = def.name(language)
    // Long press: details or "Nie vorschlagen" (without the toggle it opens the details as before).
    var menu by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = { if (onToggleExcluded != null) menu = true else onOpenDetail() })
            .testTag("exercise_row_${def.slug}"),
    ) {
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.tre_menu_details)) }, onClick = { menu = false; onOpenDetail() })
            onToggleExcluded?.let { toggle ->
                DropdownMenuItem(
                    text = { Text(stringResource(if (excluded) R.string.tre_include else R.string.tre_exclude)) },
                    onClick = { menu = false; toggle() },
                    modifier = Modifier.testTag("exercise_exclude_${def.slug}"),
                )
            }
        }
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            com.cruxcoach.android.ui.training.bodymap.ExerciseThumb(def, Modifier.testTag("exercise_thumb_${def.slug}"))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (def.custom) {
                        Spacer(Modifier.width(6.dp))
                        Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                            Text(stringResource(R.string.trx_custom_badge))
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        categoryLabel(def.category) + " · ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    DifficultyDots(def.difficulty)
                }
                Text(
                    equipmentSummary(def),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                InjuryBadge(advice)
                if (excluded) {
                    Badge(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp).testTag("exercise_excluded_${def.slug}")) {
                        Text(stringResource(R.string.tre_excluded_badge))
                    }
                }
            }
            if (pickMode) {
                IconButton(onClick = onOpenDetail, modifier = Modifier.testTag("exercise_info_${def.slug}")) {
                    Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.trx_open_detail, name))
                }
            }
            if (onAddToWorkout != null) {
                IconButton(onClick = onAddToWorkout, modifier = Modifier.testTag("exercise_add_${def.slug}")) {
                    Icon(Icons.Default.PlaylistAdd, contentDescription = stringResource(R.string.trx_add_to_workout, name))
                }
            }
            IconButton(onClick = onToggleFavorite, modifier = Modifier.testTag("exercise_fav_${def.slug}")) {
                Icon(
                    if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = stringResource(if (favorite) R.string.trx_favorite_remove else R.string.trx_favorite_add),
                    tint = if (favorite) CruxCoachDesign.colors.brandAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun DifficultyDots(level: Int, modifier: Modifier = Modifier) {
    val clamped = level.coerceIn(1, 5)
    val description = stringResource(R.string.trx_difficulty, clamped)
    Text(
        "●".repeat(clamped) + "○".repeat(5 - clamped),
        style = MaterialTheme.typography.bodySmall,
        color = CruxCoachDesign.colors.brandAccent,
        modifier = modifier.semantics { contentDescription = description },
    )
}

@Composable
internal fun equipmentSummary(def: ExerciseDefinition): String {
    val needed = def.equipment.filterNot { it == EquipmentV2.NONE || it == EquipmentV2.MAT }
    return if (needed.isEmpty()) equipmentLabel(EquipmentV2.NONE) else needed.map { equipmentLabel(it) }.joinToString(", ")
}

/** Small coloured label for the injury filter verdict; nothing for OK. */
@Composable
internal fun InjuryBadge(advice: InjuryAdvice, modifier: Modifier = Modifier) {
    val text = when (advice.verdict) {
        InjuryVerdict.OK -> return
        InjuryVerdict.AVOID -> stringResource(R.string.trx_badge_avoid)
        InjuryVerdict.CAUTION -> stringResource(R.string.trx_badge_caution)
        InjuryVerdict.ONE_SIDE_ONLY -> stringResource(
            if (advice.allowedSide == Side.LEFT) R.string.trx_badge_only_left else R.string.trx_badge_only_right,
        )
    }
    val avoid = advice.verdict == InjuryVerdict.AVOID
    Surface(
        color = if (avoid) MaterialTheme.colorScheme.errorContainer else CruxCoachDesign.colors.cautionContainer,
        contentColor = if (avoid) MaterialTheme.colorScheme.onErrorContainer else CruxCoachDesign.colors.onCautionContainer,
        shape = CruxCoachDesign.shapes.small,
        modifier = modifier.padding(top = 4.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}
