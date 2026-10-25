package com.cruxcoach.android.ui.training.athlete.comeback

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.athlete.ComebackService
import com.cruxcoach.android.ui.training.SectionTitle
import com.cruxcoach.android.ui.training.TrainingScaffold
import com.cruxcoach.android.ui.training.catalogLanguage
import com.cruxcoach.android.ui.training.injurySideLabel
import com.cruxcoach.android.ui.training.regionLabel
import com.cruxcoach.athlete.logic.Comeback
import com.cruxcoach.athlete.logic.ComebackContent
import com.cruxcoach.athlete.logic.ComebackTexts
import com.cruxcoach.athlete.logic.DueSession
import com.cruxcoach.athlete.logic.DueStatus
import com.cruxcoach.athlete.logic.Ladder
import com.cruxcoach.athlete.logic.Prompt
import com.cruxcoach.athlete.logic.PromptKind
import com.cruxcoach.athlete.logic.Proposal
import com.cruxcoach.athlete.logic.SessionItemSpec
import com.cruxcoach.athlete.logic.SideTarget
import com.cruxcoach.athlete.logic.StageSpec
import com.cruxcoach.athlete.model.ComebackPlan
import com.cruxcoach.athlete.model.Grip
import com.cruxcoach.athlete.model.Injury
import com.cruxcoach.athlete.model.InjuryRegion
import com.cruxcoach.athlete.model.PlanSource
import com.cruxcoach.athlete.model.StageChangeReason
import com.cruxcoach.athlete.model.Urgency
import java.time.format.TextStyle
import java.util.Locale

/**
 * The comeback plan of one injury (owner 2026-10-20): set up from the injury questions, the plan's source and the
 * stage agreed with a doctor or physio; then today's sessions, the diary, the weekly look back and the information
 * behind it. The notice "kein Ersatz für den Arztbesuch" heads every state of the screen.
 */
@Composable
fun ComebackScreen(onBack: () -> Unit, onOpenPlayer: () -> Unit, viewModel: ComebackViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.cb_export)
    LaunchedEffect(Unit) {
        viewModel.events.collect { e ->
            when (e) {
                is ComebackEvent.WorkoutStarted -> onOpenPlayer()
                is ComebackEvent.Share -> runCatching {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, e.text)
                    }, shareTitle))
                }
            }
        }
    }
    var showCheck by rememberSaveable { mutableStateOf(false) }
    var alertOpen by rememberSaveable { mutableStateOf(false) }
    val injury = state.injury
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // A plan that just started opens at its top (device run 2026-10-20: the view stayed at "Stufe ändern").
    val started = injury?.activePlan?.history?.size
    LaunchedEffect(started) { if (started != null) listState.scrollToItem(0) }
    TrainingScaffold(title = stringResource(R.string.cb_title), onBack = onBack) { padding ->
        if (injury == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (state.loading) CircularProgressIndicator()
            }
            return@TrainingScaffold
        }
        val plan = injury.activePlan
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("comeback_list"), state = listState,
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ComebackNotice(onSigns = { showCheck = true }) }
            item { InjuryHeader(injury) }
            val signs = injury.check?.signs.orEmpty()
            if (signs.isNotEmpty()) item { SignsBanner(signs, injury.check?.signsAcknowledgedOn != null) { alertOpen = true } }
            if (state.under18) item {
                HintCard(null, txt(ComebackTexts.UNDER_18), "comeback_under18", caution = true)
            }
            if (plan == null) setupItems(state, injury, viewModel, onQuestions = { showCheck = true })
            else planItems(state, injury, plan, viewModel, onQuestions = { showCheck = true })
        }
    }
    if (showCheck && injury != null && state.today != null) {
        InjuryCheckSheet(injury, state.today!!, onDismiss = { showCheck = false }) { check ->
            viewModel.saveCheck(check)
            showCheck = false
            // New or unanswered warning signs: the alert for the most urgent one.
            if (Comeback.urgency(check.signs) != null && check.signsAcknowledgedOn == null) alertOpen = true
        }
    }
    val signs = injury?.check?.signs.orEmpty()
    val urgency = Comeback.urgency(signs)
    if (alertOpen && urgency != null) {
        SignsAlert(signs, urgency,
            onChecked = { viewModel.acknowledgeSigns(); alertOpen = false },
            onContinue = { viewModel.acknowledgeSigns(); alertOpen = false },
            onDismiss = { alertOpen = false })
    }
}

@Composable
private fun InjuryHeader(injury: Injury) {
    Column {
        Text(listOfNotNull(regionLabel(injury.region), injury.side?.let { injurySideLabel(it) }, injury.detail).joinToString(" · "),
            style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.tra_injury_since, injury.startedOn), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        injury.check?.finding?.let { Text(txt(ComebackTexts.finding(it)), style = MaterialTheme.typography.bodyMedium) }
    }
}

// ── Setup ─────────────────────────────────────────────────────────────

private fun LazyListScope.setupItems(state: ComebackUiState, injury: Injury, vm: ComebackViewModel, onQuestions: () -> Unit) {
    item(key = "setup") { Setup(state, injury, vm, onQuestions) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Setup(state: ComebackUiState, injury: Injury, vm: ComebackViewModel, onQuestions: () -> Unit) {
    val ladder = Comeback.ladder(injury)
    val finding = injury.check?.finding
    // No CruxCoach exercises for "something else", and findings that belong to the professional's plan: theirs first.
    var source by rememberSaveable(injury.id) {
        mutableStateOf(if (finding?.ownPlanOnly == true || ladder.id == com.cruxcoach.athlete.logic.LadderId.OTHER) PlanSource.OWN else PlanSource.CRUXCOACH)
    }
    var stage by rememberSaveable(injury.id) { mutableIntStateOf(0) }
    var agreed by rememberSaveable(injury.id) { mutableStateOf(false) }
    var dominant by rememberSaveable(injury.id) { mutableStateOf(false) }
    var half by rememberSaveable(injury.id) { mutableStateOf("") }
    var open by rememberSaveable(injury.id) { mutableStateOf("") }
    var routineId by rememberSaveable(injury.id) { mutableStateOf<String?>(null) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    val finger = injury.region == InjuryRegion.FINGER
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        injury.plan?.endedOn?.let { Text(stringResource(R.string.cb_end) + ": " + it, style = MaterialTheme.typography.bodySmall) }
        // 1 · About the injury
        StepCard(stringResource(R.string.cb_step_questions)) {
            Text(injury.check?.answeredOn?.let { stringResource(R.string.cb_step_questions_done, it) } ?: stringResource(R.string.cb_step_questions_open),
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onQuestions, modifier = Modifier.padding(top = 4.dp).testTag("comeback_open_questions")) {
                Text(stringResource(R.string.cb_questions))
            }
            finding?.let { FindingNotes(it) }
        }
        // 2 · Whose plan
        StepCard(stringResource(R.string.cb_step_source)) {
            SourceOption(PlanSource.CRUXCOACH, source, stringResource(R.string.cb_source_cruxcoach), stringResource(R.string.cb_source_cruxcoach_hint)) { source = it }
            SourceOption(PlanSource.OWN, source, stringResource(R.string.cb_source_own), stringResource(R.string.cb_source_own_hint)) { source = it }
            if (source == PlanSource.CRUXCOACH && finding?.ownPlanOnly == true) {
                Text(txt(ComebackTexts.OWN_PLAN_NOTE), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp).testTag("comeback_own_plan_note"))
            }
            if (source == PlanSource.OWN) {
                Text(stringResource(R.string.cb_own_routine), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                if (state.routines.isEmpty()) Text(stringResource(R.string.cb_own_routine_none), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.routines.take(20).forEach { r ->
                        FilterChip(selected = routineId == r.id, onClick = { routineId = if (routineId == r.id) null else r.id },
                            label = { Text(r.name) }, modifier = Modifier.testTag("comeback_own_routine_${r.id}"))
                    }
                }
            }
        }
        if (source == PlanSource.CRUXCOACH) {
            // 3 · The stage agreed with a doctor or physio
            StepCard(stringResource(R.string.cb_step_stage)) {
                Text(stringResource(R.string.cb_stage_pick_hint), style = MaterialTheme.typography.bodySmall)
                StageChoices(ladder, stage) { stage = it }
            }
            if (finger) {
                // 4 · Reference value
                StepCard(stringResource(R.string.cb_step_reference)) {
                    Text(stringResource(R.string.cb_reference_hint), style = MaterialTheme.typography.bodySmall)
                    ReferenceField(stringResource(R.string.cb_reference_half), half, "comeback_ref_half", state.suggestedRefs[Grip.HALF_CRIMP.name]) { half = it }
                    ReferenceField(stringResource(R.string.cb_reference_open), open, "comeback_ref_open", state.suggestedRefs[Grip.OPEN_HAND.name]) { open = it }
                    CheckRow(stringResource(R.string.cb_dominant), dominant, "comeback_dominant") { dominant = it }
                }
            }
        }
        CheckRow(stringResource(R.string.cb_agreed), agreed, "comeback_agreed") { agreed = it }
        Button(onClick = { confirming = true }, modifier = Modifier.fillMaxWidth().testTag("comeback_start")) {
            Text(stringResource(if (injury.plan != null) R.string.cb_restart else R.string.cb_start))
        }
        Text(stringResource(R.string.cb_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (confirming) {
        BeforeStartDialog(onDismiss = { confirming = false }) {
            confirming = false
            val refs = buildMap {
                half.toKgOrNull()?.let { put(Grip.HALF_CRIMP.name, it) }
                open.toKgOrNull()?.let { put(Grip.OPEN_HAND.name, it) }
            }
            vm.start(source, if (source == PlanSource.OWN) 0 else stage, agreed, if (finger) dominant else null, refs,
                routineId.takeIf { source == PlanSource.OWN })
        }
    }
}

private fun String.toKgOrNull(): Double? = replace(',', '.').trim().toDoubleOrNull()?.takeIf { it > 0 && it < 500 }

@Composable
private fun StepCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
private fun SourceOption(value: PlanSource, selected: PlanSource, title: String, hint: String, onSelect: (PlanSource) -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = value == selected, role = Role.RadioButton, onClick = { onSelect(value) })
            .padding(vertical = 4.dp).testTag("comeback_source_${value.name.lowercase()}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = value == selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StageChoices(ladder: Ladder, selected: Int, onSelect: (Int) -> Unit) {
    Column {
        ladder.stages.forEach { s ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = s.index == selected, role = Role.RadioButton, onClick = { onSelect(s.index) })
                    .padding(vertical = 6.dp).testTag("comeback_stage_${s.index}"),
                verticalAlignment = Alignment.Top,
            ) {
                RadioButton(selected = s.index == selected, onClick = null)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("${s.index} · ${txt(s.title)}", style = MaterialTheme.typography.bodyLarge)
                    Text(txt(s.goal), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.cb_climbing) + ": " + txt(s.climbingText), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ReferenceField(label: String, value: String, tag: String, suggestion: Pair<Double, Boolean>?, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        OutlinedTextField(value = value, onValueChange = { onChange(it.take(6)) }, label = { Text(label) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f).testTag(tag))
        suggestion?.let { (kg, before) ->
            val shown = "%.1f".format(kg)
            TextButton(onClick = { onChange(shown) }, modifier = Modifier.testTag("${tag}_use")) {
                Text(stringResource(if (before) R.string.cb_reference_before else R.string.cb_reference_other, shown) + "\n" +
                    stringResource(R.string.cb_reference_use), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** The whole row toggles, the text too (device run 2026-10-20: a tap on the text left the box empty). */
@Composable
internal fun CheckRow(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(vertical = 4.dp).testTag(tag),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun BeforeStartDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("comeback_before_dialog"),
        title = { Text(stringResource(R.string.cb_before_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // The intended purpose first (owner 2026-10-25): a training aid, not a medical device.
                Text(stringResource(R.string.cb_purpose), fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 8.dp).testTag("comeback_purpose"))
                Text(stringResource(R.string.cb_before_text))
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("comeback_understood")) { Text(stringResource(R.string.cb_understood)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.tr_action_cancel)) } },
    )
}

// ── Running plan ──────────────────────────────────────────────────────

private fun LazyListScope.planItems(state: ComebackUiState, injury: Injury, plan: ComebackPlan, vm: ComebackViewModel, onQuestions: () -> Unit) {
    val ladder = Comeback.ladder(injury)
    val stage = Comeback.stage(injury, plan)
    state.prompts.forEach { p -> item(key = "prompt-${p.key}") { PromptCard(p, injury, plan, ladder, vm) } }
    if (Comeback.notChecked(injury)) item(key = "not-checked") {
        HintCard(null, stringResource(R.string.cb_banner_not_checked), "comeback_not_checked",
            secondary = stringResource(R.string.cb_questions) to onQuestions)
    }
    if (!plan.agreedWithProfessional) item(key = "not-agreed") {
        HintCard(null, stringResource(R.string.cb_banner_not_agreed), "comeback_not_agreed",
            secondary = stringResource(R.string.cb_mark_agreed) to { vm.markAgreed() })
    }
    if (plan.source == PlanSource.CRUXCOACH) {
        item(key = "stage") { StageCard(injury, plan, ladder, stage, state, vm) }
        item(key = "today") { SectionTitle(stringResource(R.string.cb_today_sessions)) }
        state.due.filter { it.spec.items.isNotEmpty() }.forEach { d ->
            item(key = "session-${d.index}") { SessionCard(d, plan, injury, state) { name -> vm.startSession(d.index, name) } }
        }
    } else {
        item(key = "own") { OwnPlanCard(state, plan, vm) }
    }
    item(key = "diary") { DiaryCard(state, vm, onQuestions) }
    item(key = "chart") {
        SectionTitle(stringResource(R.string.cb_chart_title))
        ComebackChart(state.readings)
    }
    item(key = "info") { InfoCards(injury, ladder) }
    item(key = "actions") { Actions(state, injury, vm, onQuestions) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StageCard(injury: Injury, plan: ComebackPlan, ladder: Ladder, stage: StageSpec, state: ComebackUiState, vm: ComebackViewModel) {
    var picking by rememberSaveable { mutableStateOf(false) }
    var editingRefs by rememberSaveable { mutableStateOf(false) }
    val rule = Comeback.painRule(injury, stage)
    Card(Modifier.fillMaxWidth().testTag("comeback_stage_card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.cb_stage_header, stage.index, ladder.stages.lastIndex, txt(stage.title)),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            state.today?.let { Text(stringResource(R.string.cb_stage_day, Comeback.daysOnStage(plan, it) + 1), style = MaterialTheme.typography.bodySmall) }
            Text(txt(stage.goal), style = MaterialTheme.typography.bodyMedium)
            Label(stringResource(R.string.cb_do_this))
            stage.doThis.forEach { Bullet(txt(it)) }
            Label(stringResource(R.string.cb_climbing))
            Text(txt(stage.climbingText), style = MaterialTheme.typography.bodyMedium)
            if (stage.avoid.isNotEmpty()) {
                Label(stringResource(R.string.cb_avoid))
                stage.avoid.forEach { Bullet(txt(it)) }
            }
            Label(stringResource(R.string.cb_pain_rule))
            Text(stringResource(if (rule.overuse) R.string.cb_pain_rule_overuse else R.string.cb_pain_rule_acute, rule.greenMax),
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("comeback_pain_rule"))
            Label(stringResource(R.string.cb_next_when))
            stage.nextWhen.forEach { Bullet(txt(it)) }
            if (injury.region == InjuryRegion.FINGER) {
                Label(stringResource(R.string.cb_reference_label))
                val half = plan.referenceKg[Grip.HALF_CRIMP.name]
                val open = plan.referenceKg[Grip.OPEN_HAND.name]
                Text(if (half == null && open == null) stringResource(R.string.cb_reference_none)
                    else stringResource(R.string.cb_reference_values, half?.let { "%.1f".format(it) } ?: "–", open?.let { "%.1f".format(it) } ?: "–"),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("comeback_reference"))
                TextButton(onClick = { editingRefs = true }, modifier = Modifier.testTag("comeback_reference_edit")) {
                    Text(stringResource(R.string.cb_reference_edit))
                }
            }
            OutlinedButton(onClick = { picking = true }, modifier = Modifier.testTag("comeback_change_stage")) { Text(stringResource(R.string.cb_change_stage)) }
        }
    }
    if (editingRefs) {
        var half by remember { mutableStateOf(plan.referenceKg[Grip.HALF_CRIMP.name]?.let { "%.1f".format(it) }.orEmpty()) }
        var open by remember { mutableStateOf(plan.referenceKg[Grip.OPEN_HAND.name]?.let { "%.1f".format(it) }.orEmpty()) }
        var dominant by remember { mutableStateOf(plan.injuredDominant == true) }
        AlertDialog(
            onDismissRequest = { editingRefs = false },
            modifier = Modifier.testTag("comeback_reference_dialog"),
            title = { Text(stringResource(R.string.cb_reference_label)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.cb_reference_hint), style = MaterialTheme.typography.bodySmall)
                    ReferenceField(stringResource(R.string.cb_reference_half), half, "comeback_ref_half", state.suggestedRefs[Grip.HALF_CRIMP.name]) { half = it }
                    ReferenceField(stringResource(R.string.cb_reference_open), open, "comeback_ref_open", state.suggestedRefs[Grip.OPEN_HAND.name]) { open = it }
                    CheckRow(stringResource(R.string.cb_dominant), dominant, "comeback_dominant") { dominant = it }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editingRefs = false
                    vm.setReferences(buildMap {
                        half.toKgOrNull()?.let { put(Grip.HALF_CRIMP.name, it) }
                        open.toKgOrNull()?.let { put(Grip.OPEN_HAND.name, it) }
                    }, dominant)
                }, modifier = Modifier.testTag("comeback_reference_save")) { Text(stringResource(R.string.tr_action_save)) }
            },
            dismissButton = { TextButton(onClick = { editingRefs = false }) { Text(stringResource(R.string.tr_action_cancel)) } },
        )
    }
    if (picking) {
        var chosen by remember { mutableIntStateOf(plan.stage) }
        AlertDialog(
            onDismissRequest = { picking = false },
            modifier = Modifier.testTag("comeback_stage_dialog"),
            title = { Text(stringResource(R.string.cb_choose_stage)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.cb_stage_pick_hint), style = MaterialTheme.typography.bodySmall)
                    StageChoices(ladder, chosen) { chosen = it }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    picking = false
                    if (chosen != plan.stage) vm.moveTo(chosen, StageChangeReason.CHOSEN)
                }, modifier = Modifier.testTag("comeback_stage_confirm")) { Text(stringResource(R.string.tr_action_save)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.tr_action_cancel)) } },
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun Bullet(text: String) {
    Row { Text("•  ", style = MaterialTheme.typography.bodyMedium); Text(text, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun SessionCard(d: DueSession, plan: ComebackPlan, injury: Injury, state: ComebackUiState, onStart: (String) -> Unit) {
    val language = catalogLanguage()
    val title = sessionTitle(d.spec.kind)
    val name = stringResource(R.string.cb_session_name, title)
    Card(Modifier.fillMaxWidth().testTag("comeback_session_${d.index}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (d.spec.optional) AssistChip(onClick = {}, label = { Text(stringResource(R.string.cb_session_optional)) })
            }
            Text(
                if (d.spec.perDay > 1 || d.spec.minHoursBetween < 24) stringResource(R.string.cb_session_per_day, d.spec.minutes, d.spec.perDay, d.spec.minHoursBetween)
                else stringResource(R.string.cb_session_per_week, d.spec.minutes, d.spec.perWeek, d.spec.minHoursBetween),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            d.spec.items.filter { item -> item.side != SideTarget.HEALTHY || injury.side in setOf(com.cruxcoach.athlete.model.InjurySide.LEFT, com.cruxcoach.athlete.model.InjurySide.RIGHT) }
                .forEach { item -> Text(itemLine(item, plan, state, language), style = MaterialTheme.typography.bodyMedium) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(statusText(d), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f).testTag("comeback_session_status_${d.index}"))
                // Never locked: the status says what the stage suggests, the athlete decides.
                Button(onClick = { onStart(name) }, modifier = Modifier.testTag("comeback_session_start_${d.index}")) {
                    Text(stringResource(R.string.cb_session_start))
                }
            }
        }
    }
}

@Composable
private fun statusText(d: DueSession): String = when (d.status) {
    DueStatus.DUE -> stringResource(R.string.cb_session_due)
    DueStatus.DONE -> stringResource(R.string.cb_session_done)
    DueStatus.LATER -> stringResource(R.string.cb_session_later, d.nextAt?.let {
        java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0).toString()
    }.orEmpty())
    DueStatus.NOT_TODAY -> stringResource(R.string.cb_session_not_today)
}

@Composable
private fun itemLine(item: SessionItemSpec, plan: ComebackPlan, state: ComebackUiState, language: String): String {
    val name = state.catalog?.get(item.slug)?.name(language) ?: item.slug
    val dose = when {
        item.durationS != null -> "${item.sets} × ${item.durationS} s"
        item.repsMin != null -> "${item.sets} × ${item.repsMin}${item.repsMax?.takeIf { it != item.repsMin }?.let { "–$it" }.orEmpty()}"
        else -> "${item.sets} ×"
    }
    val parts = mutableListOf(name)
    // "Blockheben einarmig" says no grip: the stage's grip is part of the line.
    item.grip?.takeIf { !(it == Grip.OPEN_HAND && item.slug == "finger.open_hand_block_lift") }
        ?.let { parts += com.cruxcoach.android.ui.training.gripLabel(it) }
    parts += dose
    when (item.side) {
        SideTarget.INJURED -> parts += stringResource(R.string.cb_item_injured)
        SideTarget.HEALTHY -> parts += stringResource(R.string.cb_item_healthy)
        SideTarget.BOTH -> {}
    }
    val kg = Comeback.targetKg(plan, item, state.incrementKg)
    when {
        kg != null -> parts += stringResource(R.string.cb_item_target, "%.1f".format(kg.start), "%.1f".format(kg.endInclusive))
        item.loadPercent != null -> parts += stringResource(R.string.cb_item_percent, item.loadPercent!!.first, item.loadPercent!!.last)
    }
    item.effort?.let { parts += stringResource(R.string.cb_item_effort, it.first, it.last) }
    return parts.joinToString(" · ")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OwnPlanCard(state: ComebackUiState, plan: ComebackPlan, vm: ComebackViewModel) {
    val routine = state.routines.firstOrNull { it.id == plan.ownRoutineId }
    val name = stringResource(R.string.cb_session_name, routine?.name ?: stringResource(R.string.cb_session_own))
    Card(Modifier.fillMaxWidth().testTag("comeback_own_card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.cb_source_own), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.cb_source_own_hint), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.routines.take(20).forEach { r ->
                    FilterChip(selected = plan.ownRoutineId == r.id, onClick = { vm.setOwnRoutine(if (plan.ownRoutineId == r.id) null else r.id) },
                        label = { Text(r.name) }, modifier = Modifier.testTag("comeback_own_routine_${r.id}"))
                }
            }
            if (routine != null) {
                Button(onClick = { vm.startOwn(routine, name) }, modifier = Modifier.testTag("comeback_own_start")) { Text(stringResource(R.string.cb_session_start)) }
            } else {
                Text(stringResource(R.string.cb_own_routine_none), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DiaryCard(state: ComebackUiState, vm: ComebackViewModel, onQuestions: () -> Unit) {
    val entry = state.todayEntry
    var note by remember(entry?.day) { mutableStateOf(entry?.note.orEmpty()) }
    Card(Modifier.fillMaxWidth().testTag("comeback_diary")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.cb_diary), style = MaterialTheme.typography.titleMedium)
            PainScale(stringResource(R.string.cb_morning_pain), entry?.morningPain, "comeback_morning", { v -> vm.updateToday { it.copy(morningPain = v) } })
            PainScale(stringResource(R.string.cb_pressure_pain), entry?.pressurePain, "comeback_pressure", { v -> vm.updateToday { it.copy(pressurePain = v) } },
                hint = stringResource(R.string.cb_pressure_hint))
            CheckRow(stringResource(R.string.cb_swelling_today), entry?.swelling == true, "comeback_swelling_today") { on -> vm.updateToday { it.copy(swelling = on) } }
            CheckRow(stringResource(R.string.cb_new_sign), entry?.newSign == true, "comeback_new_sign") { on ->
                vm.updateToday { it.copy(newSign = on) }
                if (on) onQuestions()
            }
            OutlinedTextField(value = note, onValueChange = { v -> note = v.take(500); vm.updateToday { it.copy(note = note.ifBlank { null }) } },
                label = { Text(stringResource(R.string.cb_note)) }, modifier = Modifier.fillMaxWidth().testTag("comeback_note"))
            entry?.sessions?.forEach { s ->
                Text("${sessionTitle(s.kind)}: ${s.painMax?.let { "$it/10" } ?: "–"}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun FindingNotes(finding: com.cruxcoach.athlete.model.InjuryFinding) {
    val notes = ComebackTexts.findingNotes(finding)
    if (notes.isEmpty()) return
    Text(stringResource(R.string.cb_notes_finding), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    notes.forEach { Bullet(txt(it)) }
}

@Composable
private fun InfoCards(injury: Injury, ladder: Ladder) {
    var allSources by rememberSaveable { mutableStateOf(false) }
    val finding = injury.check?.finding
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        finding?.let { f ->
            ComebackTexts.typicalCourse(f)?.let { course ->
                Card(Modifier.fillMaxWidth().testTag("comeback_typical")) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.cb_typical), style = MaterialTheme.typography.titleMedium)
                        Text(txt(course), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                        Text(stringResource(R.string.cb_typical_hint), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        FindingNotes(f)
                    }
                }
            }
        }
        if (injury.region == InjuryRegion.FINGER) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.cb_nutrition), style = MaterialTheme.typography.titleMedium)
                    Text(txt(ComebackTexts.GELATIN), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        Card(Modifier.fillMaxWidth().testTag("comeback_sources")) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.cb_evidence), style = MaterialTheme.typography.titleMedium)
                Text(txt(ladder.evidence), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.cb_sources), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                val sources = ladder.sources + if (injury.region == InjuryRegion.FINGER) ComebackTexts.GELATIN_SOURCES else emptyList()
                (if (allSources) sources else sources.take(4)).forEach { s ->
                    Text("${s.citation} (${sourceKindLabel(s.kind)})", style = MaterialTheme.typography.bodySmall)
                }
                if (sources.size > 4) TextButton(onClick = { allSources = !allSources }, modifier = Modifier.testTag("comeback_sources_more")) {
                    Text(if (allSources) stringResource(R.string.cb_sources_less) else stringResource(R.string.cb_sources_more, sources.size - 4))
                }
            }
        }
    }
}

@Composable
private fun Actions(state: ComebackUiState, injury: Injury, vm: ComebackViewModel, onQuestions: () -> Unit) {
    var ending by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    val language = catalogLanguage()
    val labels = summaryLabels(injury)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.share(language, labels) }, modifier = Modifier.fillMaxWidth().testTag("comeback_export")) {
            Text(stringResource(R.string.cb_export))
        }
        OutlinedButton(onClick = onQuestions, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cb_questions)) }
        OutlinedButton(onClick = { ending = true }, modifier = Modifier.fillMaxWidth().testTag("comeback_end")) { Text(stringResource(R.string.cb_end)) }
        TextButton(onClick = { deleting = true }, modifier = Modifier.testTag("comeback_delete")) {
            Text(stringResource(R.string.cb_delete_all), color = MaterialTheme.colorScheme.error)
        }
        Text(stringResource(R.string.cb_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(48.dp))
    }
    if (ending) {
        AlertDialog(
            onDismissRequest = { ending = false },
            title = { Text(stringResource(R.string.cb_end_title)) },
            text = { Text(stringResource(R.string.cb_end_text)) },
            confirmButton = {
                TextButton(onClick = { ending = false; vm.end(resolved = true) }, modifier = Modifier.testTag("comeback_end_resolved")) {
                    Text(stringResource(R.string.cb_end_resolved))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { ending = false; vm.end(resolved = false) }, modifier = Modifier.testTag("comeback_end_keep")) {
                        Text(stringResource(R.string.cb_end_keep))
                    }
                    TextButton(onClick = { ending = false }) { Text(stringResource(R.string.tr_action_cancel)) }
                }
            },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.cb_delete_title)) },
            text = { Text(stringResource(R.string.cb_delete_text)) },
            confirmButton = {
                TextButton(onClick = { deleting = false; vm.deleteAll() }, modifier = Modifier.testTag("comeback_delete_confirm")) {
                    Text(stringResource(R.string.tr_action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.tr_action_cancel)) } },
        )
    }
}

@Composable
private fun summaryLabels(injury: Injury) = ComebackService.SummaryLabels(
    title = stringResource(R.string.cb_export_title), injury = stringResource(R.string.cb_export_injury),
    region = regionLabel(injury.region), side = injury.side?.let { injurySideLabel(it) }, since = stringResource(R.string.cb_export_since),
    finding = stringResource(R.string.cb_export_finding), signs = stringResource(R.string.cb_export_signs), plan = stringResource(R.string.cb_export_plan),
    stage = stringResource(R.string.cb_export_stage), reference = stringResource(R.string.cb_export_reference),
    diaryHeader = stringResource(R.string.cb_export_diary), yes = stringResource(R.string.cb_export_yes),
)

// ── Prompts ───────────────────────────────────────────────────────────

@Composable
private fun PromptCard(p: Prompt, injury: Injury, plan: ComebackPlan, ladder: Ladder, vm: ComebackViewModel) {
    val tag = "comeback_prompt_${p.kind.name.lowercase()}"
    val canStepBack = plan.source == PlanSource.CRUXCOACH && plan.stage > 0
    val stepBack = stringResource(R.string.cb_step_back) to { vm.moveTo(plan.stage - 1, StageChangeReason.STEP_BACK, p.key) }
    val dismiss: () -> Unit = { vm.dismissPrompt(p.key) }
    when (p.kind) {
        PromptKind.REVIEW -> {
            val r = p.review ?: return
            val rule = Comeback.painRule(injury)
            when (r.proposal) {
                Proposal.MOVE_UP -> {
                    val next = ladder.stages.getOrNull(plan.stage + 1) ?: return
                    val text = stringResource(R.string.cb_review_up_text, r.green, r.loggedDays, "${next.index} – ${txt(next.title)}") +
                        if (next.index == ladder.stages.lastIndex) "\n\n" + stringResource(R.string.cb_review_up_check) else ""
                    HintCard(stringResource(R.string.cb_review_up_title), text, tag,
                        primary = stringResource(R.string.cb_move_up) to { vm.moveTo(plan.stage + 1, StageChangeReason.MOVE_UP, p.key) },
                        secondary = stringResource(R.string.cb_stay_week) to dismiss)
                }
                Proposal.REPEAT -> {
                    val worst = r.worst
                    val text = if (worst != null && worst.second > rule.greenMax) stringResource(R.string.cb_review_repeat_text,
                        java.time.DayOfWeek.of(worst.first.dayOfWeek.ordinal + 1).getDisplayName(TextStyle.FULL, Locale.getDefault()), worst.second, rule.greenMax)
                    else stringResource(R.string.cb_review_repeat_text_generic)
                    HintCard(stringResource(R.string.cb_review_repeat_title), text, tag, caution = true,
                        primary = stringResource(R.string.cb_repeat_week) to { vm.moveTo(plan.stage, StageChangeReason.REPEAT, p.key) },
                        secondary = if (canStepBack) stepBack else null, tertiary = stringResource(R.string.cb_keep_stage) to dismiss)
                }
                Proposal.STEP_BACK -> HintCard(stringResource(R.string.cb_review_back_title), stringResource(R.string.cb_review_back_text), tag, caution = true,
                    primary = if (canStepBack) stepBack else null, secondary = stringResource(R.string.cb_keep_stage) to dismiss)
                Proposal.STAY -> HintCard(null, stringResource(when (r.stayReason) {
                    com.cruxcoach.athlete.logic.StayReason.PRESSURE_RISING -> R.string.cb_review_stay_rising
                    com.cruxcoach.athlete.logic.StayReason.LAST_STAGE -> R.string.cb_review_stay_last
                    else -> R.string.cb_review_stay_text
                }), tag, secondary = stringResource(R.string.cb_ok) to dismiss)
            }
        }
        PromptKind.WORSENING -> HintCard(stringResource(R.string.cb_worse_title), stringResource(R.string.cb_worse_text, p.from ?: 0, p.to ?: 0), tag,
            caution = true, primary = if (canStepBack) stepBack else null, secondary = stringResource(R.string.cb_keep_stage) to dismiss)
        PromptKind.NOT_BETTER_14 -> HintCard(stringResource(R.string.cb_nb14_title), stringResource(R.string.cb_nb14_text), tag, caution = true,
            primary = stringResource(R.string.cb_ok) to dismiss, secondary = if (canStepBack) stepBack else null,
            tertiary = stringResource(R.string.cb_remind_week) to dismiss)
        PromptKind.NOT_BETTER_21 -> {
            val language = catalogLanguage()
            val labels = summaryLabels(injury)
            HintCard(stringResource(R.string.cb_nb21_title), stringResource(R.string.cb_nb21_text), tag, caution = true,
                primary = stringResource(R.string.cb_ok) to dismiss,
                secondary = stringResource(R.string.cb_export) to { vm.share(language, labels) })
        }
        PromptKind.NEW_SIGN -> HintCard(stringResource(R.string.cb_sign_title), stringResource(R.string.cb_sign_text), tag, caution = true,
            primary = stringResource(R.string.cb_ok) to dismiss)
    }
}
