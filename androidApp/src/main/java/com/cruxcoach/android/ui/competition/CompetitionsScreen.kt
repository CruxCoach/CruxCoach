package com.cruxcoach.android.ui.competition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.competition.CompetitionDiscovery
import com.cruxcoach.android.competition.CompetitionShareLink

/**
 * The competition list.
 *
 * Three ways in, in the order people actually arrive: a link somebody sent
 * them, the code on the gym wall, and a list of what is on. The phone's own
 * camera still works — the App Link filter brings a scanned link here — but at
 * a wall with the app already open, leaving it to come back is a worse version
 * of the same thing, so there is a scanner here too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompetitionsScreen(
    onOpenCompetition: (CompetitionShareLink.Ref) -> Unit,
    onScan: () -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: CompetitionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.comp_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::refresh,
                        modifier = Modifier.testTag("competitions_refresh"),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.comp_refresh))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("competitions_list"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.comp_open_link_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            stringResource(R.string.comp_open_link_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = state.linkInput,
                            onValueChange = viewModel::onLinkChange,
                            label = { Text(stringResource(R.string.comp_open_link_label)) },
                            isError = state.linkError,
                            singleLine = true,
                            supportingText = if (state.linkError) {
                                { Text(stringResource(R.string.comp_open_link_error)) }
                            } else {
                                null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("competition_link_input"),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(
                                onClick = { viewModel.openLink()?.let(onOpenCompetition) },
                                modifier = Modifier.weight(1f).testTag("competition_link_open"),
                            ) { Text(stringResource(R.string.comp_open_action)) }
                            OutlinedButton(
                                onClick = onScan,
                                modifier = Modifier.weight(1f).testTag("competition_scan_open"),
                            ) { Text(stringResource(R.string.comp_scan_action)) }
                        }
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChange,
                    label = { Text(stringResource(R.string.comp_search_hint)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("competition_search"),
                )
            }

            item {
                Text(
                    stringResource(R.string.comp_discover_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            if (state.loading && !state.loaded) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator() }
                }
            } else if (state.visible.isEmpty()) {
                // "Nothing is on" and "nothing answered" are different, and only
                // one of them is news. `loaded` is what tells them apart.
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                stringResource(if (state.loaded) R.string.comp_empty_title else R.string.comp_unreachable),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            if (state.loaded) Text(
                                stringResource(R.string.comp_empty),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            items(state.visible, key = { it.eventId }) { listing ->
                CompetitionRow(listing, onOpenCompetition)
            }
        }
    }
}

@Composable
private fun CompetitionRow(
    listing: CompetitionDiscovery.Listing,
    onOpen: (CompetitionShareLink.Ref) -> Unit,
) {
    val competition = listing.competition
    Card(
        onClick = {
            onOpen(
                CompetitionShareLink.Ref(
                    organizerPubkey = listing.organizerPubkey,
                    compId = competition.compId,
                    naddr = CompetitionNaddr.encode(listing.organizerPubkey, competition.compId),
                ),
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .testTag("competition_row"),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(competition.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (competition.summary.isNotEmpty()) {
                Text(competition.summary, style = MaterialTheme.typography.bodySmall)
            }
            val venue = (competition.raw["venue"] as? kotlinx.serialization.json.JsonObject)
                ?.get("name")?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content.orEmpty()
            val board = (competition.raw["board"] as? kotlinx.serialization.json.JsonObject)
                ?.get("model")?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content.orEmpty()
            if (venue.isNotBlank() || board.isNotBlank()) {
                Text(
                    listOfNotNull(venue.takeIf { it.isNotBlank() }, board.takeIf { it.isNotBlank() })
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(competitionStatusLabel(competition.status)),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    if (competition.feeMsat == 0L) {
                        stringResource(R.string.comp_pay_not_required)
                    } else {
                        stringResource(R.string.comp_fee, stringResource(R.string.comp_sats, (competition.feeMsat / 1000).toInt()))
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(
                stringResource(R.string.comp_open_details),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** Lifecycle status → its label. Unknown values cannot occur: the reducer rejects them. */
fun competitionStatusLabel(status: String): Int = when (status) {
    "draft" -> R.string.comp_status_draft
    "published" -> R.string.comp_status_published
    "registration_open" -> R.string.comp_status_registration_open
    "registration_closed" -> R.string.comp_status_registration_closed
    "checkin_open" -> R.string.comp_status_checkin_open
    "running" -> R.string.comp_status_running
    "paused" -> R.string.comp_status_paused
    "finished" -> R.string.comp_status_finished
    else -> R.string.comp_status_cancelled
}
