package com.cruxcoach.android.ui.training.workouts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.android.ui.training.today.climbIntensityLabel
import com.cruxcoach.android.ui.training.today.climbingDayKindLabel
import com.cruxcoach.athlete.logic.BuiltinRoutines
import com.cruxcoach.athlete.logic.DayPlans
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.ClimbIntensity
import com.cruxcoach.athlete.model.ClimbingDayKind
import com.cruxcoach.athlete.catalog.LoadDomain
import com.cruxcoach.athlete.model.PLAN_BOARD
import com.cruxcoach.athlete.model.PLAN_FREE
import com.cruxcoach.athlete.model.PLAN_REST
import com.cruxcoach.athlete.model.PausePeriod
import com.cruxcoach.athlete.model.Routine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

/** How far ahead the planner shows days (owner 2026-10-19: "two days later", also over a weekend). */
internal const val PLANNER_DAYS = 14

data class DayPlannerState(
    val loading: Boolean = true,
    val today: LocalDate? = null,
    val profile: AthleteProfile = AthleteProfile(),
    val routines: List<Routine> = emptyList(),
    /** Today and the next days: the plan in force and how the coach fits the free days around the climbing. */
    val days: List<DayPlans.Day> = emptyList(),
    val warnings: List<DayPlans.Warning> = emptyList(),
    /** A training that gave way to the last change, with the free day the coach found for it. */
    val moveOffer: MoveOffer? = null,
    /** The last change, for "Rückgängig". */
    val lastChange: PlanChange? = null,
)

data class MoveOffer(val entry: String, val from: LocalDate, val to: LocalDate)

/** [entry] as written on [date] (null = back to the standard week); [before] is every date plan before it. */
data class PlanChange(val date: LocalDate, val entry: String?, val before: Map<String, String>)

/**
 * Plans single days (owner 2026-10-19): "today no time – rest; a volume boulder day tomorrow, a limit day two days
 * later". Each change is this date's own entry over the standard week; the coach shapes the free days around it.
 */
@HiltViewModel
class DayPlannerViewModel @Inject constructor(private val service: AthleteService) : ViewModel() {
    private val _state = MutableStateFlow(DayPlannerState())
    val state: StateFlow<DayPlannerState> = _state.asStateFlow()
    private val pausesNow = MutableStateFlow<List<PausePeriod>>(emptyList())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            service.ensureReady()
            val repo = service.repo
            val from = service.today().minus(DatePeriod(days = 3)).toString()
            combine(repo.observeProfile(), repo.observeRoutines(), repo.observePauses(), service.observeActivityChanges(from)) { p, r, pauses, _ ->
                Triple(p, r, pauses)
            }.collect { (profile, routines, pauses) ->
                pausesNow.value = pauses
                runCatching { compute(profile, routines, pauses) }
            }
        }
    }

    private fun compute(profile: AthleteProfile, routines: List<Routine>, pauses: List<PausePeriod>) {
        val today = service.today()
        val logged = service.activities(days = 3).mapNotNull { (day, a) ->
            if (a.climbingMinutes <= 0 && a.climbingEfforts <= 0) null
            else runCatching { LocalDate.parse(day) }.getOrNull()?.let { it to a.climbIntensity }
        }.toMap()
        val days = DayPlans.preview(profile, today, PLANNER_DAYS, today, pauses, logged)
        val warnings = DayPlans.warnings(days, today) { fingerWork(it, routines) }
        _state.update { it.copy(loading = false, today = today, profile = profile, routines = routines, days = days, warnings = warnings) }
    }

    /** A planned training trains the fingers (a hangboard or block exercise beyond the warm-up). */
    private fun fingerWork(entry: String, routines: List<Routine>): Boolean {
        val routine = when {
            entry.startsWith("builtin:") -> BuiltinRoutines.byKey(entry.removePrefix("builtin:"))
            else -> routines.firstOrNull { it.id == entry }
        } ?: return false
        return routine.items.any { !it.warmup && LoadDomain.FINGER in service.catalog.fallbackFor(it.slug).domains }
    }

    private fun isTraining(entry: String?) =
        entry != null && entry != PLAN_REST && entry != PLAN_BOARD && entry != PLAN_FREE && !DayPlans.isClimb(entry)

    /** Plans [date] as [entry] ([PLAN_FREE] = nothing, null = as in the standard week). */
    fun set(date: LocalDate, entry: String?) = io {
        val today = service.today()
        var before: Map<String, String> = emptyMap()
        var displaced: String? = null
        var after: AthleteProfile? = null
        service.repo.updateProfile { p ->
            before = p.datePlan
            displaced = DayPlans.entry(p, date)
            DayPlans.withDay(p, date, entry, today).also { after = it }
        }
        val now = after ?: return@io
        val gone = displaced?.takeIf { isTraining(it) && DayPlans.entry(now, date) != it }
        // The training that had to give way: the coach offers the next free day that fits it; nothing moves unasked.
        val offer = gone?.let { e ->
            DayPlans.freeDayFor(now, date, today, fingerWork(e, _state.value.routines), pausesNow.value)?.let { MoveOffer(e, date, it) }
        }
        _state.update { it.copy(lastChange = PlanChange(date, entry, before), moveOffer = offer) }
    }

    /** Swaps what [a] and [b] plan ("move this day"). */
    fun swap(a: LocalDate, b: LocalDate) = io {
        val today = service.today()
        var before: Map<String, String> = emptyMap()
        service.repo.updateProfile { p -> before = p.datePlan; DayPlans.swap(p, a, b, today) }
        _state.update { it.copy(lastChange = PlanChange(b, null, before), moveOffer = null) }
    }

    fun acceptMove() {
        val offer = _state.value.moveOffer ?: return
        io {
            val today = service.today()
            var before: Map<String, String> = emptyMap()
            service.repo.updateProfile { p -> before = p.datePlan; DayPlans.withDay(p, offer.to, offer.entry, today) }
            _state.update { it.copy(lastChange = PlanChange(offer.to, offer.entry, before), moveOffer = null) }
        }
    }

    fun dismissMove() = _state.update { it.copy(moveOffer = null) }

    fun undo() {
        val change = _state.value.lastChange ?: return
        io {
            service.repo.updateProfile { it.copy(datePlan = change.before) }
            _state.update { it.copy(lastChange = null, moveOffer = null) }
        }
    }

    private fun io(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { service.ensureReady(); block() }
    }
}

/** "Heute", "Morgen", else the short weekday and date ("Mi., 22. Okt."). */
@Composable
internal fun plannerDayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> stringResource(R.string.trdp_today)
    today.plus(DatePeriod(days = 1)) -> stringResource(R.string.trdp_tomorrow)
    else -> {
        val locale = Locale.getDefault()
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEdMMM")
        java.time.LocalDate.parse(date.toString()).format(java.time.format.DateTimeFormatter.ofPattern(pattern, locale))
    }
}

@Composable
private fun hintText(day: DayPlans.Day, profile: AthleteProfile): String? = when (day.hint) {
    DayPlans.Hint.AFTER_HARD -> stringResource(R.string.trdp_hint_after_hard)
    DayPlans.Hint.BEFORE_LIMIT -> stringResource(R.string.trdp_hint_before_limit)
    DayPlans.Hint.BEFORE_HARD -> stringResource(R.string.trdp_hint_before_hard)
    DayPlans.Hint.OPEN -> stringResource(R.string.trdp_hint_open)
    DayPlans.Hint.PAUSED -> stringResource(R.string.trdp_hint_paused)
    DayPlans.Hint.CLIMB, DayPlans.Hint.BOARD -> stringResource(
        if (profile.coach.addOnAfterClimbing == false) R.string.trdp_hint_climb_only else R.string.trdp_hint_climb)
    DayPlans.Hint.REST, DayPlans.Hint.ROUTINE -> null
}

/**
 * "Tage planen": today and the next two weeks, one tap per day to plan or swap it; the free days show how the
 * coach fits them around the climbing, and what to watch out for (advice, nothing locked).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayPlannerSheet(onDismiss: () -> Unit, viewModel: DayPlannerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var choosing by remember { mutableStateOf<LocalDate?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("day_planner")) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.trdp_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.trdp_intro), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            val today = state.today
            if (state.loading || today == null) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }
            state.lastChange?.let { change ->
                Row(Modifier.fillMaxWidth().testTag("day_planner_changed"), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.trdp_changed), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = viewModel::undo, modifier = Modifier.testTag("day_planner_undo")) { Text(stringResource(R.string.trpg_undo)) }
                }
            }
            state.moveOffer?.let { offer ->
                OutlinedCard(Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("day_planner_move")) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.trdp_move_offer, planEntryLabel(offer.entry, state.routines),
                            plannerDayLabel(offer.from, today), plannerDayLabel(offer.to, today)), style = MaterialTheme.typography.bodyMedium)
                        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = viewModel::acceptMove, modifier = Modifier.testTag("day_planner_move_yes")) {
                                Text(stringResource(R.string.trdp_move_yes))
                            }
                            TextButton(onClick = viewModel::dismissMove, modifier = Modifier.testTag("day_planner_move_no")) {
                                Text(stringResource(R.string.trdp_move_no))
                            }
                        }
                    }
                }
            }
            state.warnings.forEach { w ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("day_planner_warning"), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.WarningAmber, null, Modifier.size(18.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text(when (w.kind) {
                        DayPlans.WarningKind.HARD_BACK_TO_BACK ->
                            stringResource(R.string.trdp_warn_back_to_back, plannerDayLabel(w.other, today), plannerDayLabel(w.date, today))
                        DayPlans.WarningKind.FINGERS_BEFORE_HARD ->
                            stringResource(R.string.trdp_warn_fingers_before, plannerDayLabel(w.other, today), plannerDayLabel(w.date, today))
                    }, style = MaterialTheme.typography.bodySmall)
                }
            }
            state.days.forEach { day ->
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().clickable { choosing = day.date }.padding(vertical = 10.dp)
                        .testTag("day_planner_day_${day.date}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(plannerDayLabel(day.date, today), style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (day.date == today) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.width(112.dp))
                    Column(Modifier.weight(1f)) {
                        val label = when {
                            day.entry != null -> planEntryLabel(day.entry, state.routines)
                            day.hint == DayPlans.Hint.CLIMB -> stringResource(R.string.trdp_usual_climb)
                            else -> stringResource(R.string.trdp_free)
                        }
                        Text(label, style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (day.entry != null || day.hint == DayPlans.Hint.CLIMB) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (day.entry == null && day.hint != DayPlans.Hint.CLIMB) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurface)
                        val hint = hintText(day, state.profile)
                        val own = if (day.overridden) stringResource(R.string.trdp_only_this_day) else null
                        listOfNotNull(own, hint).takeIf { it.isNotEmpty() }?.let {
                            Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                                color = if (day.hint == DayPlans.Hint.OPEN || day.hint == DayPlans.Hint.PAUSED) MaterialTheme.colorScheme.onSurfaceVariant
                                    else CruxCoachDesign.colors.brandAccent)
                        }
                    }
                }
            }
        }
    }
    choosing?.let { date ->
        val today = state.today ?: return@let
        val day = state.days.firstOrNull { it.date == date }
        val standard = state.profile.weekPlan[date.dayOfWeek.isoDayNumber]
        PlanChooserDialog(
            title = plannerDayLabel(date, today),
            current = day?.entry,
            routines = state.routines,
            onDismiss = { choosing = null },
            onChoose = { entry -> viewModel.set(date, entry ?: PLAN_FREE); choosing = null },
            freeLabel = stringResource(R.string.trdp_free),
            standardLabel = if (day?.overridden == true) stringResource(R.string.trdp_standard, planEntryLabel(standard, state.routines)) else null,
            onStandard = { viewModel.set(date, null); choosing = null },
            swapTargets = state.days.map { it.date }.filter { it != date },
            swapLabel = { plannerDayLabel(it, today) },
            onSwap = { other -> viewModel.swap(date, other); choosing = null },
        )
    }
}

/**
 * What a day can be: free, rest, a climbing day (where, how hard), a board session or a training. Used for the
 * standard week's weekdays and for single dates (those also go back to the standard week or swap with another day).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlanChooserDialog(
    title: String,
    /** The entry in force; null = free. */
    current: String?,
    routines: List<Routine>,
    onDismiss: () -> Unit,
    /** null = free. */
    onChoose: (String?) -> Unit,
    freeLabel: String = stringResource(R.string.trwo_plan_free),
    standardLabel: String? = null,
    onStandard: () -> Unit = {},
    swapTargets: List<LocalDate> = emptyList(),
    swapLabel: @Composable (LocalDate) -> String = { it.toString() },
    onSwap: (LocalDate) -> Unit = {},
) {
    val trainings: List<String> = routines.map { it.id } + BuiltinRoutines.all.map { "builtin:" + it.builtinKey }
    val currentClimb = DayPlans.climbOf(current)
    var kind by rememberSaveable { mutableStateOf(currentClimb?.kind ?: ClimbingDayKind.GYM_BOULDER) }
    var intensity by rememberSaveable { mutableStateOf(currentClimb?.intensity ?: ClimbIntensity.VOLUME) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp).testTag("week_plan_chooser")) {
                if (standardLabel != null) {
                    item(key = "standard") { ChooserRow(standardLabel, false, "week_plan_option_standard", onStandard) }
                }
                item(key = "free") { ChooserRow(freeLabel, current == null, "week_plan_option_free") { onChoose(null) } }
                item(key = PLAN_REST) { ChooserRow(planEntryLabel(PLAN_REST, routines), current == PLAN_REST, "week_plan_option_$PLAN_REST") { onChoose(PLAN_REST) } }
                item(key = "climb") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("week_plan_climb")) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.trdp_climb), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            if (currentClimb != null) Icon(Icons.Default.Check, contentDescription = null)
                        }
                        Text(stringResource(R.string.trdp_climb_where), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(ClimbingDayKind.GYM_BOULDER, ClimbingDayKind.GYM_ROPE, ClimbingDayKind.OUTDOOR, ClimbingDayKind.OTHER).forEach { k ->
                                FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(climbingDayKindLabel(k)) },
                                    modifier = Modifier.testTag("week_plan_climb_kind_${k.name.lowercase()}"))
                            }
                        }
                        Text(stringResource(R.string.trdp_climb_how), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ClimbIntensity.entries.forEach { i ->
                                FilterChip(selected = intensity == i, onClick = { intensity = i }, label = { Text(climbIntensityLabel(i)) },
                                    modifier = Modifier.testTag("week_plan_climb_intensity_${i.name.lowercase()}"))
                            }
                        }
                        FilledTonalButton(onClick = { onChoose(DayPlans.Climb(kind, intensity).entry) },
                            modifier = Modifier.padding(top = 4.dp).testTag("week_plan_climb_apply")) {
                            Text(stringResource(R.string.trdp_climb_apply))
                        }
                    }
                }
                item(key = PLAN_BOARD) { ChooserRow(planEntryLabel(PLAN_BOARD, routines), current == PLAN_BOARD, "week_plan_option_$PLAN_BOARD") { onChoose(PLAN_BOARD) } }
                items(trainings, key = { it }) { entry ->
                    ChooserRow(planEntryLabel(entry, routines), entry == current, "week_plan_option_$entry") { onChoose(entry) }
                }
                if (swapTargets.isNotEmpty()) {
                    item(key = "swap") {
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("week_plan_swap")) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.SwapHoriz, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.trdp_swap), style = MaterialTheme.typography.bodyLarge)
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                swapTargets.forEach { d ->
                                    AssistChip(onClick = { onSwap(d) }, label = { Text(swapLabel(d)) },
                                        modifier = Modifier.testTag("week_plan_swap_$d"))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.tr_action_cancel)) } },
    )
}

@Composable
private fun ChooserRow(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Default.Check, contentDescription = null)
    }
}

/** A climbing day as the plan shows it: "Halle Bouldern · Limit". */
@Composable
internal fun climbPlanLabel(climb: DayPlans.Climb): String =
    climbingDayKindLabel(climb.kind) + " · " + climbIntensityLabel(climb.intensity)

/** Short weekday name for the plan's labels. */
internal fun shortWeekday(date: LocalDate): String =
    java.time.DayOfWeek.of(date.dayOfWeek.isoDayNumber).getDisplayName(TextStyle.SHORT, Locale.getDefault())
