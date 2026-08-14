package com.cruxcoach.android.ui.competition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompetitionCreateScreen(
    onNavigateBack: () -> Unit,
    onCreated: (String, String) -> Unit,
    viewModel: CompetitionCreateViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDiscard by remember { mutableStateOf(false) }
    val created = state as? CompetitionCreateViewModel.State.Created
    androidx.compose.runtime.LaunchedEffect(created) {
        if (created != null) onCreated(created.organizerPubkey, created.compId)
    }
    if (created != null) return
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text(stringResource(R.string.comp_create_discard_title)) },
        text = { Text(stringResource(R.string.comp_create_discard_hint)) },
        confirmButton = {
            Button(onClick = { viewModel.discardDraft(); confirmDiscard = false }) {
                Text(stringResource(R.string.comp_create_discard_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.action_cancel)) }
        },
    )
    Scaffold(topBar = { TopAppBar(
        title = { Text(stringResource(R.string.comp_create_title)) },
        navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back)) } },
    ) }) { padding ->
        if (state is CompetitionCreateViewModel.State.Publishing) {
            Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator(); Text(stringResource(R.string.comp_create_publishing))
            }
        } else {
            val editing = state as CompetitionCreateViewModel.State.Editing
            val d = editing.draft
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()).testTag("competition_create"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.comp_create_step, editing.step + 1, 4), fontWeight = FontWeight.Bold)
                when (editing.step) {
                    0 -> {
                        Field(stringResource(R.string.comp_create_field_title), d.title) { viewModel.edit { x -> x.copy(title = it) } }
                        Field(stringResource(R.string.comp_create_field_summary), d.summary) { viewModel.edit { x -> x.copy(summary = it) } }
                        Field(stringResource(R.string.comp_create_field_venue), d.venue) { viewModel.edit { x -> x.copy(venue = it) } }
                        Field(stringResource(R.string.comp_create_field_address), d.address) { viewModel.edit { x -> x.copy(address = it) } }
                        Field(stringResource(R.string.comp_create_field_organizer), d.organizer) { viewModel.edit { x -> x.copy(organizer = it) } }
                        Field(stringResource(R.string.comp_create_field_contact), d.contact) { viewModel.edit { x -> x.copy(contact = it) } }
                        Row(verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.comp_create_public), Modifier.weight(1f)); Switch(d.public, { value -> viewModel.edit { it.copy(public = value) } }) }
                    }
                    1 -> {
                        Text(stringResource(R.string.comp_create_board_hint))
                        Field(stringResource(R.string.comp_create_field_brand), d.boardBrand) { viewModel.edit { x -> x.copy(boardBrand = it) } }
                        Field(stringResource(R.string.comp_create_field_model), d.boardModel) { viewModel.edit { x -> x.copy(boardModel = it) } }
                        Field(stringResource(R.string.comp_create_field_size), d.boardSize) { viewModel.edit { x -> x.copy(boardSize = it) } }
                        Field(stringResource(R.string.comp_create_field_layout), d.layoutId, numeric = true) { viewModel.edit { x -> x.copy(layoutId = it) } }
                        Field(stringResource(R.string.comp_create_field_angle), d.angle, numeric = true) { viewModel.edit { x -> x.copy(angle = it) } }
                        Text(stringResource(R.string.comp_create_schedule_hint))
                        Field(stringResource(R.string.comp_create_field_timezone), d.timezone) { viewModel.edit { x -> x.copy(timezone = it) } }
                        Field(stringResource(R.string.comp_create_field_registration_opens), d.registrationOpensAt) { viewModel.edit { x -> x.copy(registrationOpensAt = it) } }
                        Field(stringResource(R.string.comp_create_field_registration_closes), d.registrationClosesAt) { viewModel.edit { x -> x.copy(registrationClosesAt = it) } }
                        Field(stringResource(R.string.comp_create_field_checkin_opens), d.checkinOpensAt) { viewModel.edit { x -> x.copy(checkinOpensAt = it) } }
                        Field(stringResource(R.string.comp_create_field_checkin_closes), d.checkinClosesAt) { viewModel.edit { x -> x.copy(checkinClosesAt = it) } }
                        Field(stringResource(R.string.comp_create_field_starts_at), d.startsAt) { viewModel.edit { x -> x.copy(startsAt = it) } }
                        Field(stringResource(R.string.comp_create_field_ends_at), d.endsAt) { viewModel.edit { x -> x.copy(endsAt = it) } }
                    }
                    2 -> {
                        Choice(
                            stringResource(R.string.comp_create_field_climb_source),
                            d.climbSource,
                            listOf(
                                "organizer_set" to stringResource(R.string.comp_create_climb_source_organizer),
                                "participant_choice" to stringResource(R.string.comp_create_climb_source_participant),
                            ),
                        ) { selected ->
                            viewModel.edit { current ->
                                current.copy(
                                    climbSource = selected,
                                    scoring = if (selected == "participant_choice" && current.scoring in setOf("points_sum", "hardest_n")) "tops_then_attempts" else current.scoring,
                                )
                            }
                        }
                        Text(stringResource(if (d.climbSource == "participant_choice") R.string.comp_create_pool_hint else R.string.comp_create_climbs_hint))
                        OutlinedTextField(d.climbs, { value -> viewModel.edit { it.copy(climbs = value) } }, label = { Text(stringResource(R.string.comp_create_field_climbs)) }, minLines = 7, modifier = Modifier.fillMaxWidth().testTag("competition_create_climbs"))
                        Field(stringResource(R.string.comp_create_field_climb_count), d.climbCount, numeric = true) { viewModel.edit { x -> x.copy(climbCount = it) } }
                        Field(stringResource(R.string.comp_create_field_counted_climbs), d.countedClimbCount, numeric = true) { viewModel.edit { x -> x.copy(countedClimbCount = it) } }
                        if (d.climbSource == "participant_choice") Choice(
                            stringResource(R.string.comp_create_field_uniqueness),
                            d.selectionUniqueness,
                            listOf(
                                "none" to stringResource(R.string.comp_create_uniqueness_shared),
                                "unique_per_competition" to stringResource(R.string.comp_create_uniqueness_exclusive),
                            ),
                        ) { value -> viewModel.edit { it.copy(selectionUniqueness = value) } }
                        Field(stringResource(R.string.comp_create_field_capacity), d.capacity, numeric = true) { viewModel.edit { x -> x.copy(capacity = it) } }
                        Field(stringResource(R.string.comp_create_field_division), d.division) { viewModel.edit { x -> x.copy(division = it) } }
                        Row(verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.comp_create_waitlist), Modifier.weight(1f)); Switch(d.waitlistEnabled, { value -> viewModel.edit { it.copy(waitlistEnabled = value) } }) }
                        Row(verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.comp_create_late_entry), Modifier.weight(1f)); Switch(d.lateEntryAllowed, { value -> viewModel.edit { it.copy(lateEntryAllowed = value) } }) }
                    }
                    else -> {
                        Text(stringResource(R.string.comp_create_review), fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.comp_create_review_summary, d.title, d.venue, d.boardModel, d.boardSize, d.angle))
                        Text(stringResource(
                            R.string.comp_create_review_schedule,
                            d.registrationOpensAt, d.registrationClosesAt,
                            d.checkinOpensAt, d.checkinClosesAt,
                            d.startsAt, d.endsAt, d.timezone,
                        ))
                        Choice(
                            stringResource(R.string.comp_create_field_progression), d.progression,
                            listOf(
                                "asynchronous_turns" to stringResource(R.string.comp_create_progression_async),
                                "synchronous_rounds" to stringResource(R.string.comp_create_progression_sync),
                            ),
                        ) { value -> viewModel.edit { it.copy(progression = value) } }
                        val scoringChoices = buildList {
                            add("tops_then_attempts" to stringResource(R.string.comp_create_scoring_tops))
                            add("achievement_points" to stringResource(R.string.comp_create_scoring_achievement))
                            if (d.climbSource == "organizer_set") {
                                add("points_sum" to stringResource(R.string.comp_create_scoring_points))
                                add("hardest_n" to stringResource(R.string.comp_create_scoring_hardest))
                            }
                        }
                        Choice(stringResource(R.string.comp_create_field_scoring), d.scoring, scoringChoices) { value -> viewModel.edit { it.copy(scoring = value) } }
                        if (d.scoring == "achievement_points") {
                            Field(stringResource(R.string.comp_create_field_zone_points), d.zonePoints, numeric = true) { viewModel.edit { x -> x.copy(zonePoints = it) } }
                            Field(stringResource(R.string.comp_create_field_top_points), d.topPoints, numeric = true) { viewModel.edit { x -> x.copy(topPoints = it) } }
                            Field(stringResource(R.string.comp_create_field_flash_points), d.flashPoints, numeric = true) { viewModel.edit { x -> x.copy(flashPoints = it) } }
                        }
                        Field(stringResource(R.string.comp_create_field_attempts), d.attempts, numeric = true) { viewModel.edit { x -> x.copy(attempts = it) } }
                        Field(stringResource(R.string.comp_create_field_turn_seconds), d.turnSeconds, numeric = true) { viewModel.edit { x -> x.copy(turnSeconds = it) } }
                        OutlinedTextField(d.rulesText, { value -> viewModel.edit { it.copy(rulesText = value) } }, label = { Text(stringResource(R.string.comp_create_field_rules)) }, minLines = 4, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(d.relays, { value -> viewModel.edit { it.copy(relays = value) } }, label = { Text(stringResource(R.string.comp_create_field_relays)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.comp_create_waiver), Modifier.weight(1f)); Switch(d.waiverRequired, { value -> viewModel.edit { it.copy(waiverRequired = value) } }) }
                    }
                }
                editing.error?.let { Text(competitionCreateError(it)) }
                TextButton(
                    onClick = { confirmDiscard = true },
                    modifier = Modifier.testTag("competition_create_discard"),
                ) { Text(stringResource(R.string.comp_create_discard)) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (editing.step > 0) OutlinedButton(viewModel::back, Modifier.weight(1f)) { Text(stringResource(R.string.action_back)) }
                    Button(if (editing.step == 3) viewModel::publish else viewModel::next, Modifier.weight(1f).testTag(if (editing.step == 3) "competition_create_publish" else "competition_create_next")) {
                        Text(stringResource(if (editing.step == 3) R.string.comp_create_publish else R.string.action_next))
                    }
                }
            }
        }
    }
}

@Composable
private fun competitionCreateError(code: String): String = when (code) {
    "required_basics" -> stringResource(R.string.comp_create_error_basics)
    "invalid_board" -> stringResource(R.string.comp_create_error_board)
    "invalid_schedule" -> stringResource(R.string.comp_create_error_schedule)
    "required_climb" -> stringResource(R.string.comp_create_error_climb)
    "invalid_climb_count" -> stringResource(R.string.comp_create_error_climb_count)
    "invalid_counted_climb_count" -> stringResource(R.string.comp_create_error_counted_climb_count)
    "invalid_choice_scoring" -> stringResource(R.string.comp_create_error_choice_scoring)
    else -> stringResource(R.string.comp_create_error_publish, code)
}

@Composable private fun Field(
    label: String,
    value: String,
    numeric: Boolean = false,
    onChange: (String) -> Unit,
) = OutlinedTextField(
    value, onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
    keyboardOptions = KeyboardOptions(
        keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
        imeAction = ImeAction.Next,
    ),
)

@Composable private fun Choice(
    label: String,
    selected: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) = Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(label, fontWeight = FontWeight.SemiBold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, text) ->
            FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(text) })
        }
    }
}
