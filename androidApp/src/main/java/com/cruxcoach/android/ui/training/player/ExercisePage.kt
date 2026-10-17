package com.cruxcoach.android.ui.training.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.android.ui.training.*
import com.cruxcoach.android.ui.training.workout.recordValueText
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.logic.SetOutcomes
import com.cruxcoach.athlete.logic.TargetOutcome
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.Grip
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.Side
import kotlin.math.roundToInt

/** What the page can do; one bundle so the page stays readable. */
class PageActions(
    val onUpdate: (ExerciseSet) -> Unit,
    val onFocus: (String) -> Unit,
    val onEdit: (String?) -> Unit,
    val onReopen: (String) -> Unit,
    val onReserve: (String, Int?) -> Unit,
    val onCompleteAs: (Double) -> Unit,
    val onOpenExercise: (String) -> Unit,
    val onPain: () -> Unit,
    val onSwap: () -> Unit,
    val onSwapTo: (String) -> Unit,
    val onSkipExercise: () -> Unit,
    val onEndExercise: () -> Unit,
    val onAddSet: () -> Unit,
    val onAddWarmups: () -> Unit,
    val onRemoveExercise: () -> Unit,
    val onAsPlanned: () -> Unit = {},
)

/**
 * One exercise with all its sets (owner 2026-10-17): done rows say what was done against the
 * target, the active row is open (load, result chips), planned rows can be tapped to do them next.
 * A circuit shows its rounds with every member's set. The rest is a bar below, not a screen.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ExercisePage(
    page: PlayerPage,
    state: PlayerState,
    language: String,
    actions: PageActions,
    canSwap: Boolean,
    warmupAvailable: Boolean,
    learningBadge: @Composable (slug: String, modifier: Modifier) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val active = state.current?.set?.takeIf { c -> !c.isCompleted && c.blockIndex in page.blockIndexes }
    val editing = state.editingId
    LaunchedEffect(page.key, active?.id) {
        // Keep the set just done (its reserve question) and the active one in view: on a small phone the
        // next set sat below the fold. Nothing moves while both are on screen already.
        val index = rowIndexOf(page, active?.id)
        if (index < 2) return@LaunchedEffect
        val info = listState.layoutInfo
        // The sticky header covers the top of the list: rows must sit below it.
        val header = info.visibleItemsInfo.firstOrNull { it.key == "header" }?.size ?: 0
        val before = info.visibleItemsInfo.firstOrNull { it.index == index - 1 }
        val item = info.visibleItemsInfo.firstOrNull { it.index == index }
        val shown = before != null && before.offset >= header && item != null &&
            item.offset + minOf(item.size, info.viewportEndOffset / 2) <= info.viewportEndOffset
        if (!shown) {
            listState.animateScrollToItem(index - 1)
            val covered = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "header" }?.size ?: 0
            if (covered > 0) listState.animateScrollBy(-covered.toFloat())
        }
    }
    LazyColumn(modifier.fillMaxSize().testTag("player_set_view"), state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        // The exercise (or circuit round) stays on top while the sets scroll: after the rest scrolled the
        // next set into view, the name had gone (render review 2026-10-17).
        stickyHeader(key = "header") {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
                if (page.circuit != null) CircuitHeader(page, state, actions)
                else ExerciseHeader(page, state, active, language, actions, canSwap, warmupAvailable)
            }
        }
        item(key = "intro") { PageIntro(page, state, active, language, learningBadge, actions.onAsPlanned) }
        if (page.circuit != null) {
            val rounds = page.sets.filter { it.setType != SetType.WARMUP }.map { it.setIndex }.distinct().sorted()
            val warmups = page.sets.filter { it.setType == SetType.WARMUP }
            if (warmups.isNotEmpty()) item(key = "warmups") { WarmupGroup(warmups, page, state, active, editing, language, actions) }
            rounds.forEachIndexed { r, setIndex ->
                val rows = page.sets.filter { it.setType != SetType.WARMUP && it.setIndex == setIndex }
                item(key = "round_$setIndex") {
                    Text(stringResource(R.string.trpg_round, r + 1), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp).testTag("player_round_${r + 1}"))
                }
                items(rows, key = { it.id }) { set ->
                    SetRowFor(set, page, state, active, editing, language, actions, withName = true)
                }
            }
        } else {
            val warmups = page.sets.filter { it.setType == SetType.WARMUP }
            if (warmups.isNotEmpty()) item(key = "warmups") { WarmupGroup(warmups, page, state, active, editing, language, actions) }
            items(page.sets.filter { it.setType != SetType.WARMUP }, key = { it.id }) { set ->
                SetRowFor(set, page, state, active, editing, language, actions, withName = false)
            }
        }
        item(key = "footer") { PageFooter(page, state, actions) }
    }
}

/** Row position of a set in the lazy list (header and intro first), for scrolling it into view. */
private fun rowIndexOf(page: PlayerPage, setId: String?): Int {
    if (setId == null) return 0
    val warmups = page.sets.filter { it.setType == SetType.WARMUP }
    val work = page.sets.filter { it.setType != SetType.WARMUP }
    if (warmups.any { it.id == setId }) return 2
    val offset = 2 + (if (warmups.isNotEmpty()) 1 else 0)
    if (page.circuit == null) return offset + work.indexOfFirst { it.id == setId }.coerceAtLeast(0)
    // Circuits: one label row per round before its sets.
    val rounds = work.map { it.setIndex }.distinct().sorted()
    var index = offset
    for (r in rounds) {
        index++
        for (s in work.filter { it.setIndex == r }) { if (s.id == setId) return index; index++ }
    }
    return 0
}

// ── Header ───────────────────────────────────────────────────────────

/** "It hurts" in the header, compact so the name keeps its room. */
@Composable
private fun PainChip(actions: PageActions) {
    AssistChip(
        onClick = actions.onPain,
        label = { Text(stringResource(R.string.trp2_pain_button), maxLines = 1) },
        leadingIcon = { Icon(Icons.Default.Healing, null, Modifier.size(18.dp)) },
        modifier = Modifier.testTag("player_pain"),
    )
}

/** Sticky: thumbnail, name, "Pull · set 2 of 4", side; "it hurts" and the exercise menu. */
@Composable
private fun ExerciseHeader(
    page: PlayerPage,
    state: PlayerState,
    active: ExerciseSet?,
    language: String,
    actions: PageActions,
    canSwap: Boolean,
    warmupAvailable: Boolean,
) {
    val first = page.sets.firstOrNull() ?: return
    val def = state.def(first.exerciseSlug) ?: return
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            com.cruxcoach.android.ui.training.bodymap.ExerciseThumb(def, Modifier.size(40.dp))
            Spacer(Modifier.width(10.dp))
            Text(def.name(language), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).clickable(role = Role.Button) { actions.onOpenExercise(def.slug) }
                    .testTag("player_exercise_name"))
            ExerciseMenu(def, page, state, language, actions, canSwap, warmupAvailable)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Set x of y counts work sets only (warm-ups have their own row).
            val position = active?.let { set ->
                if (set.setType == SetType.WARMUP) stringResource(R.string.trpg_warmup_row,
                    page.sets.filter { it.setType == SetType.WARMUP }.indexOfFirst { it.id == set.id } + 1,
                    page.sets.count { it.setType == SetType.WARMUP })
                else stringResource(R.string.trp_set_of, workNumberOf(page, set) ?: 1,
                    page.sets.filter { it.setType != SetType.WARMUP && it.blockIndex == set.blockIndex }.map { it.setIndex }.distinct().size)
            }
            // "Warm-up · Warm-up · 1 of 2" on a warm-up exercise: the category goes when the row says it already.
            val category = categoryLabel(def.category).takeIf {
                !(def.category == com.cruxcoach.athlete.catalog.ExerciseCategoryV2.WARMUP && active?.setType == SetType.WARMUP)
            }
            Text(listOfNotNull(category, position).joinToString(" · "), style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag("player_set_of"))
            active?.side?.let { side ->
                Surface(color = CruxCoachDesign.colors.brandAccent, shape = MaterialTheme.shapes.small) {
                    Text(sideLabel(side).uppercase(), color = CruxCoachDesign.colors.onBrandAccent,
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp).testTag("player_side"))
                }
                Spacer(Modifier.width(8.dp))
            }
            if (active != null && active.setType != SetType.WORK &&
                !(active.setType == SetType.WARMUP && def.category == com.cruxcoach.athlete.catalog.ExerciseCategoryV2.WARMUP)) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                    Text(setTypeLabel(active.setType), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).testTag("player_badge"))
                }
                Spacer(Modifier.width(8.dp))
            }
            if (page.open) PainChip(actions)
        }
    }
}

/** Below the header, scrolling away: the learning line, per-block load, interval rhythm, circuit members. */
@Composable
private fun PageIntro(
    page: PlayerPage,
    state: PlayerState,
    active: ExerciseSet?,
    language: String,
    learningBadge: @Composable (slug: String, modifier: Modifier) -> Unit,
    onAsPlanned: () -> Unit = {},
) {
    val first = page.sets.firstOrNull() ?: return
    val units = state.profile.units
    Column(Modifier.fillMaxWidth()) {
        if (page.circuit != null) {
            Text(page.blockIndexes.mapNotNull { b -> page.sets.firstOrNull { it.blockIndex == b } }
                .joinToString(" → ") { state.def(it.exerciseSlug)?.name(language) ?: it.exerciseSlug },
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 2.dp))
        }
        val set = active ?: first
        val def = state.def(set.exerciseSlug) ?: return
        state.dayForm?.let { m -> DayFormLine(page, state, m, onAsPlanned) }
        if (active?.setType == SetType.WORK) learningBadge(def.slug, Modifier.padding(bottom = 4.dp))
        val details = listOfNotNull(
            perBlockText(set, def, units),
            if (def.kind == ExerciseKind.INTERVAL) stringResource(R.string.trw_interval_format,
                (set.workS ?: def.defaults.workS?.toDouble() ?: 7.0).roundToInt(),
                (set.restBetweenS ?: def.defaults.restBetweenS?.toDouble() ?: 3.0).roundToInt(),
                set.repsPerSet ?: def.defaults.repsPerSet ?: 6) else null,
        )
        if (details.isNotEmpty()) {
            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp).testTag("player_target_detail"))
        }
    }
}

/**
 * Why this exercise is lighter today (comparison review #5): the check-in answer or the hard climbing that
 * trimmed sets or load, with "As planned" to undo it in one tap. Only on pages it changed.
 */
@Composable
private fun DayFormLine(page: PlayerPage, state: PlayerState, m: com.cruxcoach.athlete.logic.ReadinessModifier, onAsPlanned: () -> Unit) {
    val defs = page.sets.filter { it.setType == SetType.WORK }.mapNotNull { state.def(it.exerciseSlug) }.distinctBy { it.slug }
    val touched = defs.filter { com.cruxcoach.athlete.logic.ReadinessModifiers.affects(it, m) }
    if (touched.isEmpty()) return
    val parts = buildList {
        if (m.setsDelta < 0) add(stringResource(if (page.circuit != null) R.string.trpg_dayform_round_less else R.string.trpg_dayform_set_less))
        if (m.dropMaxFinger && touched.any { com.cruxcoach.athlete.logic.ReadinessModifiers.isMaxFinger(it) }) add(stringResource(R.string.trpg_dayform_finger_cap))
        touched.maxOf { com.cruxcoach.athlete.logic.ReadinessModifiers.loadCutPercent(it, m) }.takeIf { it > 0 }
            ?.let { add(stringResource(R.string.trpg_dayform_load, it)) }
    }
    if (parts.isEmpty()) return
    val why = m.reason?.let { com.cruxcoach.android.ui.training.today.readinessReasonShort(it) }
        ?: stringResource(R.string.trpg_dayform_climbed)
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).testTag("player_dayform"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
            Text(stringResource(R.string.trpg_dayform, why, parts.joinToString(" · ")),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            TextButton(onClick = onAsPlanned, modifier = Modifier.testTag("player_dayform_as_planned")) {
                Text(stringResource(R.string.trpg_dayform_as_planned))
            }
        }
    }
}

/** Sticky for circuits: the round, the rests, "it hurts". */
@Composable
private fun CircuitHeader(page: PlayerPage, state: PlayerState, actions: PageActions) {
    val work = page.sets.filter { it.setType != SetType.WARMUP }
    val rounds = work.map { it.setIndex }.distinct().sorted()
    val active = state.current?.set?.takeIf { c -> !c.isCompleted && c.blockIndex in page.blockIndexes }
    val round = (active?.let { a -> rounds.indexOf(a.setIndex) } ?: rounds.indexOfLast { r -> work.any { it.setIndex == r && it.isCompleted } })
        .coerceAtLeast(0) + 1
    val firstWork = work.firstOrNull()
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        Text(stringResource(R.string.trpg_round_header, round, rounds.size.coerceAtLeast(1)),
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag("player_round_header"))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(firstWork?.let { stringResource(R.string.trpg_circuit_rests, restText(it.restS ?: 0), restText(it.roundRestS ?: 0)) } ?: "",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).testTag("player_circuit_rests"))
            if (page.open) PainChip(actions)
        }
    }
}

@Composable
private fun restText(seconds: Int): String =
    if (seconds >= 60) formatClock(seconds) else stringResource(R.string.tr_format_seconds, seconds)

@Composable
private fun ExerciseMenu(
    def: ExerciseDefinition,
    page: PlayerPage,
    state: PlayerState,
    language: String,
    actions: PageActions,
    canSwap: Boolean,
    warmupAvailable: Boolean,
) {
    var menu by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val catalog = state.defs
    Box {
        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("player_exercise_menu")) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.trp_exercise_menu))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (canSwap) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.trp_swap_exercise)) },
                    leadingIcon = { Icon(Icons.Default.SwapHoriz, null) },
                    onClick = { menu = false; actions.onSwap() },
                    modifier = Modifier.testTag("player_swap_exercise"),
                )
            }
            if (page.open && page.sets.none { it.isCompleted }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.trp_skip_exercise)) },
                    leadingIcon = { Icon(Icons.Default.SkipNext, null) },
                    onClick = { menu = false; actions.onSkipExercise() },
                    modifier = Modifier.testTag("player_skip_exercise"),
                )
            }
            if (warmupAvailable) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.trw_add_warmup)) },
                    leadingIcon = { Icon(Icons.Default.Whatshot, null) },
                    onClick = { menu = false; actions.onAddWarmups() },
                    modifier = Modifier.testTag("player_add_warmup"),
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.trw_remove_exercise)) },
                leadingIcon = { Icon(Icons.Default.Delete, null) },
                onClick = { menu = false; confirmRemove = true },
                modifier = Modifier.testTag("player_remove_exercise"),
            )
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.trw_remove_exercise)) },
            text = { Text(stringResource(R.string.trw_remove_exercise_confirm, def.name(language))) },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; actions.onRemoveExercise() }, modifier = Modifier.testTag("player_remove_confirm")) {
                    Text(stringResource(R.string.tr_action_delete))
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.tr_action_cancel)) } },
        )
    }
}

// ── Rows ─────────────────────────────────────────────────────────────

/** "1", "2 L", "A" for warm-ups; work sets numbered by their round within the block. */
@Composable
private fun rowLabel(page: PlayerPage, set: ExerciseSet): String {
    val side = set.side?.let { stringResource(if (it == Side.LEFT) R.string.trw_side_short_left else R.string.trw_side_short_right) }
    val base = when {
        set.setType == SetType.WARMUP -> stringResource(R.string.trw_warmup_short)
        page.circuit != null -> null  // the round label above says which round
        else -> (workNumberOf(page, set) ?: 1).toString()
    }
    return listOfNotNull(base, side).joinToString(" ")
}

private fun workNumberOf(page: PlayerPage, set: ExerciseSet): Int? {
    if (set.setType == SetType.WARMUP) return null
    val indexes = page.sets.filter { it.blockIndex == set.blockIndex && it.setType != SetType.WARMUP }.map { it.setIndex }.distinct().sorted()
    return indexes.indexOf(set.setIndex).takeIf { it >= 0 }?.plus(1)
}

@Composable
private fun SetRowFor(
    set: ExerciseSet,
    page: PlayerPage,
    state: PlayerState,
    active: ExerciseSet?,
    editing: String?,
    language: String,
    actions: PageActions,
    withName: Boolean,
) {
    val def = state.def(set.exerciseSlug) ?: return
    val name = if (withName) def.name(language) else null
    when {
        set.isCompleted && editing == set.id -> EditingRow(set, def, page, state, name, actions)
        set.isCompleted -> DoneRow(set, def, page, state, name, actions)
        set.id == active?.id && editing == null -> ActiveRow(set, def, page, state, name, actions)
        else -> PlannedRow(set, def, page, state, name, actions)
    }
}

/** Done warm-ups fold into one row; while one is due they show one by one. */
@Composable
private fun WarmupGroup(
    warmups: List<ExerciseSet>,
    page: PlayerPage,
    state: PlayerState,
    active: ExerciseSet?,
    editing: String?,
    language: String,
    actions: PageActions,
) {
    var open by rememberSaveable(page.key) { mutableStateOf(false) }
    val allDone = warmups.all { it.isCompleted }
    if (allDone && !open && warmups.none { it.id == editing }) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.small)
                .clickable { open = true }.padding(horizontal = 4.dp).testTag("player_warmups_done"),
        ) {
            Text(stringResource(R.string.trw_warmup_short), style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(44.dp))
            Text(stringResource(R.string.trpg_warmup_row, warmups.size, warmups.size), style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f))
            Icon(Icons.Default.Check, null, tint = CruxCoachDesign.colors.positive, modifier = Modifier.size(20.dp))
            Icon(Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(20.dp))
        }
    } else {
        Column {
            warmups.forEach { set ->
                SetRowFor(set, page, state, active, editing, language, actions, withName = page.circuit != null)
            }
        }
    }
}

/** The outcome of a done set: met ✓, under ↓ (caution, never red), over ↑. */
@Composable
private fun OutcomeIcon(outcome: TargetOutcome?, tag: String) {
    val (icon, tint, cd) = when (outcome) {
        TargetOutcome.MET -> Triple(Icons.Default.Check, CruxCoachDesign.colors.positive, stringResource(R.string.trpg_outcome_met))
        TargetOutcome.UNDER -> Triple(Icons.Default.ArrowDownward, CruxCoachDesign.colors.caution, stringResource(R.string.trpg_outcome_under))
        TargetOutcome.OVER -> Triple(Icons.Default.ArrowUpward, CruxCoachDesign.colors.positive, stringResource(R.string.trpg_outcome_over))
        null -> Triple(Icons.Default.Check, CruxCoachDesign.colors.positive, stringResource(R.string.trp_set_done_label))
    }
    Icon(icon, contentDescription = cd, tint = tint, modifier = Modifier.size(20.dp).testTag(tag))
}

/** "8/8", "7/10 s", "8" without a target. */
@Composable
private fun resultText(set: ExerciseSet, def: ExerciseDefinition): String? = when (def.kind) {
    ExerciseKind.REPS, ExerciseKind.LOAD_REPS -> set.reps?.let { reps ->
        set.targetReps?.takeIf { set.setType != SetType.TEST }?.let { "$reps/$it" } ?: reps.toString()
    }
    ExerciseKind.TIME, ExerciseKind.HANG -> set.durationS?.roundToInt()?.let { held ->
        val target = set.targetDurationS?.roundToInt()?.takeIf { set.setType != SetType.TEST }
        if (target != null) stringResource(R.string.trpg_seconds_of, held, target) else stringResource(R.string.tr_format_seconds, held)
    }
    ExerciseKind.CLIMB -> set.reps?.let { pluralStringResource(R.plurals.trp_rounds, it, it) }
    ExerciseKind.INTERVAL -> null
}

@Composable
private fun RowLabel(text: String, emphasis: Boolean = false) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = if (emphasis) FontWeight.Bold else FontWeight.Normal,
        color = if (emphasis) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1, modifier = Modifier.width(if (text.isEmpty()) 8.dp else 44.dp))
}

@Composable
private fun DoneRow(set: ExerciseSet, def: ExerciseDefinition, page: PlayerPage, state: PlayerState, name: String?, actions: PageActions) {
    val units = state.profile.units
    val outcome = SetOutcomes.of(def, set)
    val label = rowLabel(page, set)
    val result = resultText(set, def)
    val outcomeText = when (outcome.main) {
        TargetOutcome.MET -> stringResource(R.string.trpg_outcome_met)
        TargetOutcome.UNDER -> stringResource(R.string.trpg_outcome_under)
        TargetOutcome.OVER -> stringResource(R.string.trpg_outcome_over)
        null -> stringResource(R.string.trp_set_done_label)
    }
    val load = loadText(set, def, units)?.takeIf { def.load != LoadMode.BODYWEIGHT_PLUS || (set.loadKg ?: 0.0) != 0.0 }
    val rowDescription = stringResource(R.string.trpg_row_cd, label, listOfNotNull(name, result, outcomeText, load).joinToString(", "))
    val latest = state.lastDone?.set?.id == set.id
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.small)
                .clickable(onClickLabel = stringResource(R.string.tr_action_edit)) { actions.onEdit(set.id) }
                .semantics(mergeDescendants = true) { contentDescription = rowDescription }
                .padding(horizontal = 4.dp).testTag("player_row_${set.id}"),
        ) {
            RowLabel(label)
            Column(Modifier.weight(1f)) {
                name?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    result?.let { Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                    Spacer(Modifier.width(6.dp))
                    OutcomeIcon(outcome.main, "player_outcome_${set.id}")
                    load?.let {
                        Spacer(Modifier.width(10.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
            set.rir?.let {
                Text(stringResource(R.string.trw_rir_short, it), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (latest) LatestDoneExtras(set, def, state, actions)
    }
}

/** Under the set done last: the reserve question, a new best, and what autoregulation changed. */
@Composable
private fun LatestDoneExtras(set: ExerciseSet, def: ExerciseDefinition, state: PlayerState, actions: PageActions) {
    val units = state.profile.units
    val asks = set.setType == SetType.WORK && def.kind in setOf(ExerciseKind.REPS, ExerciseKind.LOAD_REPS, ExerciseKind.HANG)
    Column(Modifier.padding(start = 48.dp, bottom = 4.dp)) {
        if (asks) ReserveChoice(set, actions, "player_rir_", Modifier.padding(bottom = 4.dp))
        state.feedback?.takeIf { it.def.slug == def.slug }?.let { fb ->
            val resources = LocalResources.current
            Column(Modifier.testTag("player_feedback")) {
                fb.record?.let { record ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.EmojiEvents, null, modifier = Modifier.size(18.dp), tint = CruxCoachDesign.colors.positive)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.trp_record, recordValueText(resources, fb.def, record, units)),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
                fb.raisedCapacity?.let { capacity ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.TrendingUp, null, modifier = Modifier.size(18.dp), tint = CruxCoachDesign.colors.positive)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(if (fb.firstValue) R.string.trp_benchmark_first else R.string.trp_benchmark_raised,
                            capacityText(fb.def, capacity, units)), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        state.adjustment?.let { adj ->
            Text(adjustmentText(adj, units, set.side), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("player_adjustment"))
        }
    }
}

/** A done set opened to correct it: its values, the reserve answer, "not done after all". */
@Composable
private fun EditingRow(set: ExerciseSet, def: ExerciseDefinition, page: PlayerPage, state: PlayerState, name: String?, actions: PageActions) {
    val units = state.profile.units
    OutlinedCard(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("player_row_editing")) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RowLabel(rowLabel(page, set), emphasis = true)
                Text(name ?: def.name(com.cruxcoach.android.ui.training.catalogLanguage()), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            SetSteppers(set, def, units, state.profile.smallestIncrementKg, actions.onUpdate, Modifier.padding(top = 8.dp))
            if (set.setType != SetType.WARMUP && def.kind in setOf(ExerciseKind.REPS, ExerciseKind.LOAD_REPS, ExerciseKind.HANG)) {
                ReserveChoice(set, actions, "player_review_rir_", Modifier.padding(top = 12.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { actions.onReopen(set.id) }, modifier = Modifier.heightIn(min = 48.dp).testTag("player_reopen")) {
                    Text(stringResource(R.string.trp_reopen))
                }
                Button(onClick = { actions.onEdit(null) }, modifier = Modifier.heightIn(min = 48.dp).testTag("player_edit_ok")) {
                    Text(stringResource(R.string.tr_action_done))
                }
            }
        }
    }
}

/**
 * The set that is due: its target, the load (and edge, grip) to set before, and the result chips
 * for "did fewer / more" – one tap logs the set with that number.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActiveRow(set: ExerciseSet, def: ExerciseDefinition, page: PlayerPage, state: PlayerState, name: String?, actions: PageActions) {
    val units = state.profile.units
    val accent = CruxCoachDesign.colors.brandAccent
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("player_row_active"),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RowLabel(rowLabel(page, set), emphasis = true)
                    Column(Modifier.weight(1f)) {
                        name?.let { Text(it, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        targetLine(set, def)?.let {
                            Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.testTag("player_target"))
                        }
                    }
                    percentText(set, def, state.bodyweight)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    MovedMarker(set, state)
                }
                ActiveValues(set, def, state, actions)
                // Fewer or more than planned: one tap logs the set with that number (owner 2026-10-17).
                val chips = resultChips(set, def)
                if (chips.isNotEmpty()) {
                    Text(stringResource(R.string.trpg_other_result), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                    var typing by remember { mutableStateOf(false) }
                    // Equal widths in one row: buttons kept a minimum width and pushed "…" into a second line.
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        chips.forEach { value ->
                            NumberChip(value.toString(), "player_result_$value", Modifier.weight(1f)) { actions.onCompleteAs(value.toDouble()) }
                        }
                        NumberChip("…", "player_result_more", Modifier.weight(1f), description = stringResource(R.string.trpg_other_number)) { typing = true }
                    }
                    if (typing) {
                        NumberEntryDialog(
                            title = stringResource(R.string.trpg_other_number),
                            initial = (set.reps ?: set.targetReps ?: 0).toString(),
                            tag = "player_result_input",
                            onDismiss = { typing = false },
                            onValue = { v -> typing = false; actions.onCompleteAs(v.roundToInt().coerceIn(0, 999).toDouble()) },
                        )
                    }
                }
            }
        }
    }
}

/** A big, equal-width choice: result numbers, reserve answers, how hard it was. */
@Composable
internal fun NumberChip(
    text: String,
    tag: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    description: String? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp,
            if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline),
        modifier = modifier.height(48.dp).semantics {
            this.selected = selected
            description?.let { contentDescription = it }
        }.testTag(tag),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** "How many more?" with 0, 1, 2, 3+ in one row; a second tap takes the answer back. */
@Composable
private fun ReserveChoice(set: ExerciseSet, actions: PageActions, tagPrefix: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(stringResource(R.string.trpg_reserve_question), style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(0, 1, 2, 3).forEach { value ->
                NumberChip(if (value == 3) stringResource(R.string.trw_rir_3plus) else value.toString(), "$tagPrefix$value",
                    Modifier.weight(1f), selected = set.rir == value) {
                    actions.onReserve(set.id, if (set.rir == value) null else value)
                }
            }
        }
    }
}

/** "Target 8 reps", "Target 10 s", or the climbing rounds. */
@Composable
private fun targetLine(set: ExerciseSet, def: ExerciseDefinition): String? = when (def.kind) {
    ExerciseKind.REPS, ExerciseKind.LOAD_REPS -> if (set.setType == SetType.TEST) null
        else (set.targetReps ?: set.reps)?.let { stringResource(R.string.trpg_target, pluralStringResource(R.plurals.trp_reps_big, it, it)) }
    ExerciseKind.TIME, ExerciseKind.HANG -> if (set.setType == SetType.TEST) null
        else (set.targetDurationS ?: set.durationS ?: def.defaults.durationS?.toDouble())?.roundToInt()?.let { stringResource(R.string.trw_target_seconds, it) }
    ExerciseKind.CLIMB, ExerciseKind.INTERVAL -> null
}

/** Rep results around the plan, for "6 instead of 8"; none for tests, holds, intervals and climbing. */
private fun resultChips(set: ExerciseSet, def: ExerciseDefinition): List<Int> {
    if (set.setType == SetType.TEST) return emptyList()
    if (def.kind != ExerciseKind.REPS && def.kind != ExerciseKind.LOAD_REPS) return emptyList()
    val target = set.reps ?: set.targetReps ?: return emptyList()
    return listOf(target - 2, target - 1, target + 1, target + 2).filter { it in 0..999 }
}

/** The values set before the set: load (carries over), seconds and edge for holds, grip, rounds for climbing, reps for tests. */
@Composable
private fun ActiveValues(set: ExerciseSet, def: ExerciseDefinition, state: PlayerState, actions: PageActions) {
    val units = state.profile.units
    val loaded = def.load == LoadMode.BODYWEIGHT_PLUS || def.load == LoadMode.EXTERNAL
    val timed = def.kind == ExerciseKind.TIME || def.kind == ExerciseKind.HANG
    val needsSteppers = loaded || timed || def.kind == ExerciseKind.CLIMB || set.setType == SetType.TEST ||
        def.kind == ExerciseKind.INTERVAL && set.edgeMm != null
    if (!needsSteppers) return
    SetSteppers(
        set = set,
        def = def,
        units = units,
        incrementKg = state.profile.smallestIncrementKg,
        onUpdate = actions.onUpdate,
        modifier = Modifier.padding(top = 8.dp),
        // Reps are not set before a set: the main button logs the plan, the chips log the rest.
        showMain = set.setType == SetType.TEST || timed || def.kind == ExerciseKind.CLIMB,
    ) {
        // The athlete's own load carries over (the view model moves the later sets with it).
        val carried = if (set.loadKg == set.targetLoadKg) 0 else state.sets.count {
            !it.isCompleted && it.blockIndex == set.blockIndex && it.side == set.side && it.setType == set.setType &&
                it.setIndex > set.setIndex && it.loadKg == set.loadKg
        }
        if (carried > 0) {
            Text(pluralStringResource(R.plurals.trp_load_carried, carried, carried), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().testTag("player_load_carried"))
        }
    }
    if (def.kind == ExerciseKind.HANG || def.kind == ExerciseKind.INTERVAL) GripChoice(set, actions)
}

@Composable
private fun GripChoice(set: ExerciseSet, actions: PageActions) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(top = 4.dp)) {
        AssistChip(
            onClick = { open = true },
            label = { Text(set.grip?.let { gripLabel(it) } ?: stringResource(R.string.trw_grip_none), maxLines = 1) },
            trailingIcon = { Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(18.dp)) },
            modifier = Modifier.heightIn(min = 48.dp).testTag("player_grip"),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Grip.entries.forEach { g ->
                DropdownMenuItem(text = { Text(gripLabel(g)) }, onClick = { open = false; actions.onUpdate(set.copy(grip = g)) })
            }
        }
    }
}

/** A set still to come: what is planned, marked when autoregulation moved it; a tap makes it the next one. */
@Composable
private fun PlannedRow(set: ExerciseSet, def: ExerciseDefinition, page: PlayerPage, state: PlayerState, name: String?, actions: PageActions) {
    val units = state.profile.units
    val summary = targetSummary(set, def, null, units)
    val label = rowLabel(page, set)
    val planned = stringResource(R.string.trpg_planned_cd)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.small)
            .clickable(onClickLabel = stringResource(R.string.trp_next_set)) { actions.onFocus(set.id) }
            .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(label, name, summary, planned).joinToString(", ") }
            .padding(horizontal = 4.dp).testTag("player_row_${set.id}"),
    ) {
        RowLabel(label)
        Column(Modifier.weight(1f)) {
            name?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            summary?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        MovedMarker(set, state)
    }
}

/** ↓ / ↑ on a set whose plan an earlier set of this session moved (autoregulation). */
@Composable
private fun MovedMarker(set: ExerciseSet, state: PlayerState) {
    when (movedBy(set, state)) {
        -1 -> Icon(Icons.Default.ArrowDownward, null, tint = CruxCoachDesign.colors.caution, modifier = Modifier.size(18.dp).testTag("player_moved_${set.id}"))
        1 -> Icon(Icons.Default.ArrowUpward, null, tint = CruxCoachDesign.colors.positive, modifier = Modifier.size(18.dp).testTag("player_moved_${set.id}"))
        else -> Unit
    }
}

/** −1/+1 when an earlier set of this session moved this one's plan (same block and side). */
private fun movedBy(set: ExerciseSet, state: PlayerState): Int {
    val cause = state.sets.filter { d ->
        d.isCompleted && d.blockIndex == set.blockIndex && d.setIndex < set.setIndex && (d.side == null || d.side == set.side) &&
            state.adjustments.containsKey(d.id)
    }.maxByOrNull { it.setIndex } ?: return 0
    val adj = state.adjustments[cause.id] ?: return 0
    val sum = adj.loadDeltaKg + adj.repsDelta + adj.durationDeltaS
    return when { sum < -1e-9 -> -1; sum > 1e-9 -> 1; else -> 0 }
}

// ── Footer ───────────────────────────────────────────────────────────

/** "+ set" / "+ round" and "end exercise" (after at least one set) – one tap each. */
@Composable
private fun PageFooter(page: PlayerPage, state: PlayerState, actions: PageActions) {
    val anyDone = page.sets.any { it.isCompleted && it.setType != SetType.WARMUP }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = actions.onAddSet, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("player_add_set")) {
            Text(stringResource(if (page.circuit != null) R.string.trpg_add_round else R.string.trw_add_set), maxLines = 1)
        }
        if (page.open && anyDone) {
            OutlinedButton(onClick = actions.onEndExercise, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("player_end_exercise")) {
                Text(stringResource(if (page.circuit != null) R.string.trpg_end_circuit else R.string.trpg_end_exercise), maxLines = 1)
            }
        } else if (page.open && page.circuit != null) {
            OutlinedButton(onClick = actions.onSkipExercise, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("player_skip_exercise")) {
                Text(stringResource(R.string.trp_skip_exercise), maxLines = 1)
            }
        }
    }
}

/** Type a number (reps done, a load) – for jumps the chips do not cover. */
@Composable
internal fun NumberEntryDialog(title: String, initial: String, tag: String, onDismiss: () -> Unit, onValue: (Double) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val parsed = parseDecimal(text)?.takeIf { it >= 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it.take(6) }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                modifier = Modifier.testTag(tag))
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onValue) }, enabled = parsed != null, modifier = Modifier.testTag("${tag}_ok")) {
                Text(stringResource(R.string.tr_action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.tr_action_cancel)) } },
    )
}
