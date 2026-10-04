package com.cruxcoach.android.ui.training.workouts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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
import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.catalog.ExerciseCategoryV2
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.logic.BuiltinRoutines
import com.cruxcoach.athlete.logic.WorkoutPlanner
import com.cruxcoach.athlete.model.Routine
import com.cruxcoach.athlete.model.RoutineItem
import com.cruxcoach.athlete.model.SideMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

/** One editable row; [key] keeps list identity stable while items move. */
data class EditorItem(val key: Long, val item: RoutineItem)

data class RoutineEditorState(
    val loading: Boolean = true,
    val routineId: String? = null,
    val createdAt: Long = 0,
    val name: String = "",
    val notes: String = "",
    val items: List<EditorItem> = emptyList(),
    val dirty: Boolean = false,
    val catalog: ExerciseCatalog = ExerciseCatalog.EMPTY,
    val favorites: Set<String> = emptySet(),
    val saving: Boolean = false,
)

@HiltViewModel
class RoutineEditorViewModel @Inject constructor(private val service: AthleteService) : ViewModel() {
    private val _state = MutableStateFlow(RoutineEditorState())
    val state: StateFlow<RoutineEditorState> = _state.asStateFlow()
    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()
    private var loaded = false
    private var nextKey = 0L

    /** [defaultName] is the localized starter-routine name when copying one. */
    fun load(routineId: String?, from: String?, defaultName: String?) {
        if (loaded) return
        loaded = true
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val repo = service.repo
            val catalog = service.catalogStore.catalog.value
            val existing = routineId?.let { id -> repo.routines().firstOrNull { it.id == id } }
            val (name, notes, items) = when {
                existing != null -> Triple(existing.name, existing.notes.orEmpty(), existing.items)
                from != null && from.startsWith("builtin:") -> {
                    val builtin = BuiltinRoutines.byKey(from.removePrefix("builtin:"))
                    Triple(defaultName.orEmpty(), "", builtin?.items.orEmpty())
                }
                from != null && from.startsWith("ex:") -> {
                    val slugs = from.removePrefix("ex:").split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    Triple("", "", slugs.mapNotNull { slug -> catalog[slug]?.let { WorkoutPlanner.itemFor(it) } })
                }
                else -> Triple("", "", emptyList())
            }
            _state.value = RoutineEditorState(
                loading = false,
                routineId = existing?.id,
                createdAt = existing?.createdAt ?: 0L,
                name = name,
                notes = notes,
                items = items.map { EditorItem(nextKey++, it) },
                catalog = catalog,
                favorites = repo.favorites(),
            )
            repo.observeFavorites().collect { fav -> _state.update { it.copy(favorites = fav) } }
        }
    }

    fun setName(v: String) = _state.update { it.copy(name = v.take(80), dirty = true) }
    fun setNotes(v: String) = _state.update { it.copy(notes = v.take(500), dirty = true) }

    fun updateItem(key: Long, transform: (RoutineItem) -> RoutineItem) = _state.update { s ->
        s.copy(items = s.items.map { if (it.key == key) it.copy(item = transform(it.item)) else it }, dirty = true)
    }

    fun move(key: Long, delta: Int) = _state.update { s ->
        val index = s.items.indexOfFirst { it.key == key }
        val target = index + delta
        if (index < 0 || target !in s.items.indices) return@update s
        val list = s.items.toMutableList()
        val moved = list.removeAt(index)
        list.add(target, moved)
        s.copy(items = list, dirty = true)
    }

    fun remove(key: Long) = _state.update { s -> s.copy(items = s.items.filterNot { it.key == key }, dirty = true) }

    fun addSlugs(slugs: List<String>) = _state.update { s ->
        val added = slugs.mapNotNull { slug -> s.catalog[slug]?.let { EditorItem(nextKey++, WorkoutPlanner.itemFor(it)) } }
        s.copy(items = s.items + added, dirty = s.dirty || added.isNotEmpty())
    }

    fun toggleFavorite(slug: String) {
        val fav = slug !in _state.value.favorites
        viewModelScope.launch(Dispatchers.IO) { service.ensureReady(); service.repo.setFavorite(slug, fav) }
    }

    fun save() {
        val s = _state.value
        if (s.name.isBlank() || s.items.isEmpty() || s.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val now = System.currentTimeMillis()
            service.repo.saveRoutine(Routine(
                id = s.routineId ?: service.repo.newId(),
                name = s.name.trim(),
                notes = s.notes.trim().ifBlank { null },
                items = s.items.map { it.item },
                createdAt = if (s.routineId != null && s.createdAt > 0) s.createdAt else now,
                updatedAt = now,
            ))
            _state.update { it.copy(saving = false, dirty = false) }
            _saved.value = true
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoutineEditorScreen(
    routineId: String?,
    from: String?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: RoutineEditorViewModel = hiltViewModel(),
) {
    val builtinName = from?.takeIf { it.startsWith("builtin:") }
        ?.let { BuiltinRoutines.byKey(it.removePrefix("builtin:")) }?.let { routineName(it) }
    LaunchedEffect(routineId, from) { viewModel.load(routineId, from, builtinName) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    LaunchedEffect(saved) { if (saved) onSaved() }
    val lang = catalogLanguage()
    var picking by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    val leave = { if (state.dirty) confirmLeave = true else onBack() }
    BackHandler(enabled = state.dirty) { confirmLeave = true }

    TrainingScaffold(
        title = stringResource(if (routineId != null) R.string.trwo_editor_title_edit else R.string.trwo_editor_title_new),
        onBack = leave,
        actions = {
            TextButton(
                onClick = viewModel::save,
                enabled = !state.loading && state.name.isNotBlank() && state.items.isNotEmpty() && !state.saving,
                modifier = Modifier.testTag("routine_editor_save"),
            ) { Text(stringResource(R.string.tr_action_save)) }
        },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@TrainingScaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("routine_editor_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                OutlinedTextField(
                    value = state.name, onValueChange = viewModel::setName, singleLine = true,
                    label = { Text(stringResource(R.string.trwo_editor_name)) },
                    isError = state.name.isBlank(),
                    modifier = Modifier.fillMaxWidth().testTag("routine_editor_name"),
                )
            }
            item {
                OutlinedTextField(
                    value = state.notes, onValueChange = viewModel::setNotes,
                    label = { Text(stringResource(R.string.trwo_editor_notes)) },
                    minLines = 1, maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text(pluralStringResource(R.plurals.trwo_exercise_count, state.items.size, state.items.size),
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            }
            if (state.items.isEmpty()) item { EmptyHint(stringResource(R.string.trwo_editor_empty)) }
            items(state.items, key = { it.key }) { row ->
                val index = state.items.indexOf(row)
                ItemCard(
                    row = row,
                    def = state.catalog.fallbackFor(row.item.slug),
                    lang = lang,
                    isFirst = index == 0,
                    isLast = index == state.items.lastIndex,
                    onChange = { t -> viewModel.updateItem(row.key, t) },
                    onMove = { d -> viewModel.move(row.key, d) },
                    onRemove = { viewModel.remove(row.key) },
                )
            }
            item {
                OutlinedButton(
                    onClick = { picking = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("routine_editor_add"),
                ) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.trwo_editor_add))
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    if (picking) {
        ExercisePickerSheet(
            catalog = state.catalog,
            favorites = state.favorites,
            lang = lang,
            onToggleFavorite = viewModel::toggleFavorite,
            onDismiss = { picking = false },
            onConfirm = { slugs -> viewModel.addSlugs(slugs); picking = false },
        )
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.trwo_editor_unsaved_title)) },
            text = { Text(stringResource(R.string.trwo_editor_unsaved_text)) },
            confirmButton = {
                TextButton(onClick = { confirmLeave = false; onBack() }, modifier = Modifier.testTag("routine_editor_discard")) {
                    Text(stringResource(R.string.trwo_editor_discard))
                }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.tr_action_cancel)) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemCard(
    row: EditorItem,
    def: ExerciseDefinition,
    lang: String,
    isFirst: Boolean,
    isLast: Boolean,
    onChange: ((RoutineItem) -> RoutineItem) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val item = row.item
    Card(Modifier.fillMaxWidth().testTag("routine_item_${row.key}")) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(def.name(lang), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(categoryLabel(def.category), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { onMove(-1) }, enabled = !isFirst) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.trwo_move_up))
                }
                IconButton(onClick = { onMove(1) }, enabled = !isLast) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.trwo_move_down))
                }
                IconButton(onClick = onRemove, modifier = Modifier.testTag("routine_item_remove_${row.key}")) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.trwo_remove))
                }
            }
            Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                EditorStepper(stringResource(R.string.trwo_field_sets), item.sets, 1, 1, 20, "sets_${row.key}") { v ->
                    onChange { it.copy(sets = v) }
                }
                when (def.kind) {
                    ExerciseKind.REPS, ExerciseKind.LOAD_REPS -> {
                        EditorStepper(stringResource(R.string.trwo_field_reps_min), item.repsMin ?: item.repsMax ?: 5, 1, 1, 100, "reps_min_${row.key}") { v ->
                            onChange { it.copy(repsMin = v, repsMax = maxOf(v, it.repsMax ?: v)) }
                        }
                        EditorStepper(stringResource(R.string.trwo_field_reps_max), item.repsMax ?: item.repsMin ?: 8, 1, 1, 100, "reps_max_${row.key}") { v ->
                            onChange { it.copy(repsMax = v, repsMin = minOf(v, it.repsMin ?: v)) }
                        }
                    }
                    ExerciseKind.TIME, ExerciseKind.HANG -> {
                        val step = if (def.kind == ExerciseKind.TIME) 5 else 1
                        EditorStepper(stringResource(R.string.trwo_field_seconds), item.durationS ?: def.defaults.durationS ?: 10, step, 1, 3600, "seconds_${row.key}") { v ->
                            onChange { it.copy(durationS = v) }
                        }
                    }
                    ExerciseKind.INTERVAL -> {
                        EditorStepper(stringResource(R.string.trwo_field_work), item.workS ?: def.defaults.workS ?: 7, 1, 1, 120, "work_${row.key}") { v ->
                            onChange { it.copy(workS = v) }
                        }
                        EditorStepper(stringResource(R.string.trwo_field_rest_between), item.restBetweenS ?: def.defaults.restBetweenS ?: 3, 1, 1, 300, "rest_between_${row.key}") { v ->
                            onChange { it.copy(restBetweenS = v) }
                        }
                        EditorStepper(stringResource(R.string.trwo_field_reps_per_set), item.repsPerSet ?: def.defaults.repsPerSet ?: 6, 1, 1, 50, "reps_per_set_${row.key}") { v ->
                            onChange { it.copy(repsPerSet = v) }
                        }
                    }
                    ExerciseKind.CLIMB -> {
                        EditorStepper(stringResource(R.string.trwo_field_rounds), item.repsPerSet ?: def.defaults.rounds ?: 4, 1, 1, 100, "rounds_${row.key}") { v ->
                            onChange { it.copy(repsPerSet = v) }
                        }
                    }
                }
                EditorStepper(stringResource(R.string.trwo_field_rest), item.restS ?: def.defaults.restS ?: 90, 15, 0, 1800, "rest_${row.key}",
                    format = { formatClock(it) }) { v -> onChange { it.copy(restS = v) } }
                if ((def.kind == ExerciseKind.HANG || def.kind == ExerciseKind.INTERVAL) && (item.edgeMm != null || def.defaults.edgeMm != null)) {
                    EditorStepper(stringResource(R.string.trwo_field_edge), item.edgeMm ?: def.defaults.edgeMm ?: 20, 1, 3, 80, "edge_${row.key}") { v ->
                        onChange { it.copy(edgeMm = v) }
                    }
                }
                if (def.unilateral) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        SideMode.entries.forEach { mode ->
                            FilterChip(
                                selected = item.sides == mode,
                                onClick = { onChange { it.copy(sides = mode) } },
                                label = { Text(stringResource(when (mode) {
                                    SideMode.BOTH -> R.string.trwo_side_both
                                    SideMode.LEFT_ONLY -> R.string.trwo_side_left
                                    SideMode.RIGHT_ONLY -> R.string.trwo_side_right
                                })) },
                                modifier = Modifier.testTag("side_${mode.name.lowercase()}_${row.key}"),
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.trwo_field_warmup), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = item.warmup, onCheckedChange = { w -> onChange { it.copy(warmup = w) } },
                        modifier = Modifier.testTag("warmup_${row.key}"))
                }
            }
        }
    }
}

/** ± stepper with a fixed-width value; tapping the value lets the athlete type it. */
@Composable
private fun EditorStepper(
    label: String,
    value: Int,
    step: Int,
    min: Int,
    max: Int,
    tag: String,
    format: (Int) -> String = { it.toString() },
    onChange: (Int) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        FilledTonalIconButton(onClick = { onChange((value - step).coerceIn(min, max)) }, modifier = Modifier.size(44.dp).testTag("${tag}_minus")) {
            Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.trwo_minus, label))
        }
        Text(
            format(value), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
            modifier = Modifier.width(76.dp).clickable(role = Role.Button) { editing = true }.testTag("${tag}_value"),
        )
        FilledTonalIconButton(onClick = { onChange((value + step).coerceIn(min, max)) }, modifier = Modifier.size(44.dp).testTag("${tag}_plus")) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.trwo_plus, label))
        }
    }
    if (editing) {
        var text by remember { mutableStateOf(value.toString()) }
        val parsed = parseDecimal(text)?.roundToInt()?.takeIf { it in min..max }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(label) },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it.take(6) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { Text(stringResource(R.string.trwo_range, min, max)) },
                    modifier = Modifier.testTag("${tag}_input"))
            },
            confirmButton = {
                TextButton(onClick = { parsed?.let(onChange); editing = false }, enabled = parsed != null) {
                    Text(stringResource(R.string.tr_action_save))
                }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.tr_action_cancel)) } },
        )
    }
}

/** Multi-select exercise picker: favourites first, search over German and English names and aliases. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExercisePickerSheet(
    catalog: ExerciseCatalog,
    favorites: Set<String>,
    lang: String,
    onToggleFavorite: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<ExerciseCategoryV2?>(null) }
    val selected = remember { mutableStateListOf<String>() }
    val filter = CatalogFilter(categories = setOfNotNull(category))
    val results = remember(query, category, catalog) { catalog.search(query, lang, filter) }
    val favDefs = remember(favorites, catalog, query, category) {
        if (query.isNotBlank()) emptyList()
        else favorites.mapNotNull { catalog[it] }.filter { filter.matches(it) }.sortedBy { it.name(lang) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("routine_picker")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.trwo_editor_add), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                label = { Text(stringResource(R.string.trwo_picker_search)) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("routine_picker_search"),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = category == null, onClick = { category = null }, label = { Text(stringResource(R.string.trwo_picker_all)) })
                ExerciseCategoryV2.entries.forEach { c ->
                    FilterChip(selected = category == c, onClick = { category = if (category == c) null else c }, label = { Text(categoryLabel(c)) })
                }
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 520.dp)) {
            if (favDefs.isNotEmpty()) {
                item { Text(stringResource(R.string.trwo_picker_favorites), style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                items(favDefs, key = { "f-" + it.slug }) { def ->
                    PickerRow(def, lang, def.slug in selected, true, onToggleFavorite) { toggle(selected, def.slug) }
                }
                item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
            }
            items(results, key = { it.slug }) { def ->
                PickerRow(def, lang, def.slug in selected, def.slug in favorites, onToggleFavorite) { toggle(selected, def.slug) }
            }
        }
        Button(
            onClick = { onConfirm(selected.toList()) },
            enabled = selected.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 52.dp).testTag("routine_picker_confirm"),
        ) { Text(pluralStringResource(R.plurals.trwo_picker_add_n, selected.size, selected.size)) }
    }
}

private fun toggle(list: MutableList<String>, slug: String) {
    if (slug in list) list.remove(slug) else list.add(slug)
}

@Composable
private fun PickerRow(
    def: ExerciseDefinition,
    lang: String,
    selected: Boolean,
    favorite: Boolean,
    onToggleFavorite: (String) -> Unit,
    onToggle: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(def.name(lang)) },
        supportingContent = { Text(categoryLabel(def.category)) },
        leadingContent = { Checkbox(checked = selected, onCheckedChange = { onToggle() }) },
        trailingContent = {
            IconButton(onClick = { onToggleFavorite(def.slug) }) {
                Icon(Icons.Default.Star,
                    contentDescription = stringResource(if (favorite) R.string.trwo_fav_remove else R.string.trwo_fav_add),
                    tint = if (favorite) CruxCoachDesign.colors.brandAccent else MaterialTheme.colorScheme.outline)
            }
        },
        modifier = Modifier.clickable(onClick = onToggle).testTag("routine_picker_row_${def.slug}"),
    )
}
