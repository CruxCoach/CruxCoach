package com.cruxcoach.android.ui.competition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
    val created = state as? CompetitionCreateViewModel.State.Created
    androidx.compose.runtime.LaunchedEffect(created) {
        if (created != null) onCreated(created.organizerPubkey, created.compId)
    }
    if (created != null) return
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
                        Field(stringResource(R.string.comp_create_field_starts), d.startsInHours, numeric = true) { viewModel.edit { x -> x.copy(startsInHours = it) } }
                        Field(stringResource(R.string.comp_create_field_duration), d.durationHours, numeric = true) { viewModel.edit { x -> x.copy(durationHours = it) } }
                    }
                    2 -> {
                        Text(stringResource(R.string.comp_create_climbs_hint))
                        OutlinedTextField(d.climbs, { value -> viewModel.edit { it.copy(climbs = value) } }, label = { Text(stringResource(R.string.comp_create_field_climbs)) }, minLines = 7, modifier = Modifier.fillMaxWidth().testTag("competition_create_climbs"))
                        Field(stringResource(R.string.comp_create_field_capacity), d.capacity, numeric = true) { viewModel.edit { x -> x.copy(capacity = it) } }
                        Field(stringResource(R.string.comp_create_field_division), d.division) { viewModel.edit { x -> x.copy(division = it) } }
                    }
                    else -> {
                        Text(stringResource(R.string.comp_create_review), fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.comp_create_review_summary, d.title, d.venue, d.boardModel, d.boardSize, d.angle))
                        Field(stringResource(R.string.comp_create_field_attempts), d.attempts, numeric = true) { viewModel.edit { x -> x.copy(attempts = it) } }
                        Field(stringResource(R.string.comp_create_field_turn_seconds), d.turnSeconds, numeric = true) { viewModel.edit { x -> x.copy(turnSeconds = it) } }
                        OutlinedTextField(d.rulesText, { value -> viewModel.edit { it.copy(rulesText = value) } }, label = { Text(stringResource(R.string.comp_create_field_rules)) }, minLines = 4, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(d.relays, { value -> viewModel.edit { it.copy(relays = value) } }, label = { Text(stringResource(R.string.comp_create_field_relays)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.comp_create_waiver), Modifier.weight(1f)); Switch(d.waiverRequired, { value -> viewModel.edit { it.copy(waiverRequired = value) } }) }
                    }
                }
                editing.error?.let { Text(competitionCreateError(it)) }
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
    "required_climb" -> stringResource(R.string.comp_create_error_climb)
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
