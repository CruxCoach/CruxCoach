package com.cruxcoach.android.ui.competition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
        title = { Text("Wettkampf erstellen") },
        navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
    ) }) { padding ->
        if (state is CompetitionCreateViewModel.State.Publishing) {
            Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator(); Text("Entwurf wird veröffentlicht …")
            }
        } else {
            val editing = state as CompetitionCreateViewModel.State.Editing
            val d = editing.draft
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()).testTag("competition_create"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Schritt ${editing.step + 1} von 4", fontWeight = FontWeight.Bold)
                when (editing.step) {
                    0 -> {
                        Field("Titel", d.title) { viewModel.edit { x -> x.copy(title = it) } }
                        Field("Kurzbeschreibung", d.summary) { viewModel.edit { x -> x.copy(summary = it) } }
                        Field("Ort / Halle", d.venue) { viewModel.edit { x -> x.copy(venue = it) } }
                        Field("Adresse", d.address) { viewModel.edit { x -> x.copy(address = it) } }
                        Field("Veranstalter", d.organizer) { viewModel.edit { x -> x.copy(organizer = it) } }
                        Field("Kontakt", d.contact) { viewModel.edit { x -> x.copy(contact = it) } }
                        Row(verticalAlignment = Alignment.CenterVertically) { Text("Öffentlich", Modifier.weight(1f)); Switch(d.public, { value -> viewModel.edit { it.copy(public = value) } }) }
                    }
                    1 -> {
                        Text("Das aktuell in CruxCoach ausgewählte Board ist vorausgefüllt.")
                        Field("Board-Marke", d.boardBrand) { viewModel.edit { x -> x.copy(boardBrand = it) } }
                        Field("Board-Modell", d.boardModel) { viewModel.edit { x -> x.copy(boardModel = it) } }
                        Field("Boardgröße", d.boardSize) { viewModel.edit { x -> x.copy(boardSize = it) } }
                        Field("Layout-ID", d.layoutId) { viewModel.edit { x -> x.copy(layoutId = it) } }
                        Field("Winkel", d.angle) { viewModel.edit { x -> x.copy(angle = it) } }
                        Field("Start in Stunden", d.startsInHours) { viewModel.edit { x -> x.copy(startsInHours = it) } }
                        Field("Dauer in Stunden", d.durationHours) { viewModel.edit { x -> x.copy(durationHours = it) } }
                    }
                    2 -> {
                        Text("Ein Boulder pro Zeile: CruxCoach-Link oder UUID | Name | optionale Zone-Hold-ID")
                        OutlinedTextField(d.climbs, { value -> viewModel.edit { it.copy(climbs = value) } }, label = { Text("Boulder") }, minLines = 7, modifier = Modifier.fillMaxWidth().testTag("competition_create_climbs"))
                        Field("Teilnehmerlimit", d.capacity) { viewModel.edit { x -> x.copy(capacity = it) } }
                        Field("Division", d.division) { viewModel.edit { x -> x.copy(division = it) } }
                    }
                    else -> {
                        Text("Review", fontWeight = FontWeight.Bold)
                        Text("${d.title}\n${d.venue} · ${d.boardModel} ${d.boardSize} @ ${d.angle}°")
                        Field("Versuche pro Boulder", d.attempts) { viewModel.edit { x -> x.copy(attempts = it) } }
                        Field("Zeit pro Zug (Sekunden)", d.turnSeconds) { viewModel.edit { x -> x.copy(turnSeconds = it) } }
                        OutlinedTextField(d.rulesText, { value -> viewModel.edit { it.copy(rulesText = value) } }, label = { Text("Regeln / Beschreibung") }, minLines = 4, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(d.relays, { value -> viewModel.edit { it.copy(relays = value) } }, label = { Text("Relays (eine URL pro Zeile)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically) { Text("Teilnahmebedingungen bestätigen lassen", Modifier.weight(1f)); Switch(d.waiverRequired, { value -> viewModel.edit { it.copy(waiverRequired = value) } }) }
                    }
                }
                editing.error?.let { Text(it) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (editing.step > 0) OutlinedButton(viewModel::back, Modifier.weight(1f)) { Text("Zurück") }
                    Button(if (editing.step == 3) viewModel::publish else viewModel::next, Modifier.weight(1f).testTag(if (editing.step == 3) "competition_create_publish" else "competition_create_next")) {
                        Text(if (editing.step == 3) "Entwurf erstellen" else "Weiter")
                    }
                }
            }
        }
    }
}

@Composable private fun Field(label: String, value: String, onChange: (String) -> Unit) =
    OutlinedTextField(value, onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
