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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.BuildConfig
import com.cruxcoach.android.R
import com.cruxcoach.android.competition.CompetitionRelayClient
import kotlinx.coroutines.delay

/**
 * One competition, from a participant's side.
 *
 * Ordered by what someone standing at a wall needs first: is it my turn, how
 * many people are ahead of me, how many attempts have I left, and only then the
 * leaderboard and the small print.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompetitionDetailScreen(
    onNavigateBack: () -> Unit,
    viewModel: CompetitionDetailViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val action by viewModel.action.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The countdown is the one thing that has to move without an event arriving.
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis() / 1000
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ui.snapshot.competition?.title ?: stringResource(R.string.comp_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = {
                    val link = viewModel.shareLink(BuildConfig.APP_LINK_HOST)
                    // Resolved here rather than inside the click handler: a
                    // Context read does not re-run when the configuration
                    // changes, so the chooser title could be in the previous
                    // locale after a language switch.
                    val shareLabel = stringResource(R.string.comp_share)
                    if (link != null) {
                        IconButton(
                            onClick = {
                                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_TEXT, link)
                                }
                                context.startActivity(
                                    android.content.Intent.createChooser(intent, shareLabel),
                                )
                            },
                            modifier = Modifier.testTag("competition_share"),
                        ) {
                            Icon(Icons.Default.Share, contentDescription = shareLabel)
                        }
                    }
                },
            )
        },
    ) { padding ->
        val snapshot = ui.snapshot
        if (snapshot.loading && snapshot.competition == null) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.comp_loading))
            }
            return@Scaffold
        }

        val problem = snapshot.problem
        if (snapshot.competition == null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text(
                    stringResource(
                        when (problem) {
                            CompetitionRelayClient.Problem.NEEDS_UPGRADE -> R.string.comp_needs_upgrade
                            CompetitionRelayClient.Problem.INVALID -> R.string.comp_invalid
                            CompetitionRelayClient.Problem.UNREACHABLE -> R.string.comp_unreachable
                            else -> R.string.comp_not_found
                        },
                    ),
                )
            }
            return@Scaffold
        }

        val competition = snapshot.competition!!
        val state = snapshot.state

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("competition_detail"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (snapshot.usesDevelopmentRelay) {
                item { WarningCard(stringResource(R.string.comp_dev_relay)) }
            }
            snapshot.chainBreakAt?.let { seq ->
                item { WarningCard(stringResource(R.string.comp_chain_break, seq)) }
            }
            if (state?.forkDetected == true) {
                item { WarningCard(stringResource(R.string.comp_fork)) }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(competition.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (competition.summary.isNotEmpty()) Text(competition.summary)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(competitionStatusLabel(state?.status ?: competition.status)),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        val me = ui.me
                        if (me != null) {
                            Text(stringResource(registrationLabel(me.registration)))
                            Text(stringResource(checkinLabel(me.checkin)))
                            if (competition.feeMsat > 0) Text(stringResource(paymentLabel(me.payment)))
                        }
                    }
                }
            }

            // ── the four questions, in order ──
            if (state != null && state.status in listOf("running", "paused")) {
                item { LivePanel(ui, now) }
            } else if (state != null) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.comp_waiting_to_start))
                        }
                    }
                }
            }

            // ── registration ──
            item { RegistrationPanel(ui, viewModel, action) }

            if (snapshot.standings.isNotEmpty()) {
                item { LeaderboardCard(ui) }
            }

            val announcements = state?.announcements.orEmpty()
            if (announcements.isNotEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.comp_announcements), fontWeight = FontWeight.Bold)
                            announcements.reversed().forEach { Text("• ${it.text}") }
                        }
                    }
                }
            }

            if (competition.raw["eligibility"] != null) {
                item { TextSection(stringResource(R.string.comp_eligibility), competition.rawText("eligibility")) }
            }
            if (competition.raw["participant_instructions"] != null) {
                item { TextSection(stringResource(R.string.comp_instructions), competition.rawText("participant_instructions")) }
            }
        }
    }
}

@Composable
private fun WarningCard(message: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            message,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun TextSection(title: String, body: String) {
    if (body.isBlank()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(body)
        }
    }
}

@Composable
private fun LivePanel(ui: CompetitionDetailViewModel.Ui, nowSeconds: Long) {
    val state = ui.snapshot.state ?: return
    val competition = ui.snapshot.competition ?: return
    val current = ui.currentClimber
    val currentName = current?.let { pubkey ->
        state.participants.firstOrNull { it.pubkey == pubkey }?.displayOrShort()
    }

    Card(Modifier.fillMaxWidth().testTag("competition_live")) {
        Column(Modifier.padding(16.dp)) {
            if (ui.isMyTurn) {
                Text(
                    stringResource(R.string.comp_your_turn),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
                Text(stringResource(R.string.comp_your_turn_hint))
                Spacer(Modifier.height(8.dp))
            }
            LabelledValue(
                stringResource(R.string.comp_current_climber),
                currentName ?: stringResource(R.string.comp_nobody_climbing),
            )
            LabelledValue(stringResource(R.string.comp_current_climb), climbLabel(ui, state.currentClimbId))
            LabelledValue(stringResource(R.string.comp_round), stringResource(R.string.comp_round, state.round))
            ui.climbersBefore?.let {
                LabelledValue(stringResource(R.string.comp_before_you), it.toString())
            }
            LabelledValue(stringResource(R.string.comp_attempts_left), ui.attemptsLeft.toString())
            LabelledValue(stringResource(R.string.comp_defers_left), ui.defersLeft.toString())
            ui.secondsToDeadline(nowSeconds)?.let { seconds ->
                LabelledValue(
                    stringResource(R.string.comp_deadline),
                    "%d:%02d".format(seconds / 60, seconds % 60),
                )
            }

            val myClimbs = ui.me?.climbs.orEmpty()
            if (myClimbs.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.comp_my_climbs), fontWeight = FontWeight.Bold)
                myClimbs.forEach { climb ->
                    Text("${climbLabel(ui, climb.climbId)} — ${climb.outcome} (${climb.attemptsUsed})")
                }
            }

            // What a deferral costs, stated where the decision is made. The
            // button itself lives in the actions card below — one control, not
            // a disabled twin of it here.
            if (ui.canDefer) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.comp_defer_hint, competition.rules.deferSlots),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (ui.isMyTurn && ui.defersLeft == 0) {
                Text(stringResource(R.string.comp_defer_none), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun RegistrationPanel(
    ui: CompetitionDetailViewModel.Ui,
    viewModel: CompetitionDetailViewModel,
    action: CompetitionDetailViewModel.Action,
) {
    val competition = ui.snapshot.competition ?: return
    val state = ui.snapshot.state ?: return
    val me = ui.me

    Card(Modifier.fillMaxWidth().testTag("competition_registration")) {
        Column(Modifier.padding(16.dp)) {
            when (action) {
                is CompetitionDetailViewModel.Action.Sent -> Text(
                    stringResource(R.string.comp_sent_relays, action.accepted, action.attempted),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                is CompetitionDetailViewModel.Action.Failed -> Text(
                    stringResource(
                        if (action.reason == "no_relay") R.string.comp_failed_no_relay
                        else R.string.comp_failed_generic,
                    ),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
                CompetitionDetailViewModel.Action.Working -> CircularProgressIndicator()
                CompetitionDetailViewModel.Action.Idle -> Unit
            }

            if (me != null) {
                if (me.registration == "accepted" && me.checkin == "none" &&
                    state.status in listOf("checkin_open", "running")
                ) {
                    Button(
                        onClick = { viewModel.requestCheckIn() },
                        modifier = Modifier.testTag("competition_checkin"),
                    ) { Text(stringResource(R.string.comp_checkin_action)) }
                }
                if (me.registration in listOf("pending", "accepted", "waitlisted") &&
                    state.status !in listOf("finished", "cancelled")
                ) {
                    OutlinedButton(
                        onClick = { viewModel.withdraw() },
                        modifier = Modifier.testTag("competition_withdraw"),
                    ) { Text(stringResource(R.string.comp_withdraw)) }
                }
                if (ui.canDefer) {
                    Button(
                        onClick = { viewModel.requestDefer() },
                        modifier = Modifier.testTag("competition_defer"),
                    ) { Text(stringResource(R.string.comp_defer)) }
                }
                return@Column
            }

            if (state.status != "registration_open") {
                Text(stringResource(R.string.comp_registration_closed))
                return@Column
            }

            val accepted = state.participants.count { it.registration == "accepted" }
            if (competition.capacity > 0 && accepted >= competition.capacity && !competition.waitlistEnabled) {
                Text(stringResource(R.string.comp_full))
                return@Column
            }

            var display by rememberSaveable { mutableStateOf("") }
            var waiver by rememberSaveable { mutableStateOf(false) }
            OutlinedTextField(
                value = display,
                onValueChange = { display = it.take(48) },
                label = { Text(stringResource(R.string.comp_display_name)) },
                supportingText = { Text(stringResource(R.string.comp_display_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("competition_display_name"),
            )
            if (competition.waiverRequired) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.comp_terms), fontWeight = FontWeight.Bold)
                Text(competition.rawText("waiver"), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = waiver,
                        onCheckedChange = { waiver = it },
                        modifier = Modifier.testTag("competition_waiver"),
                    )
                    Text(stringResource(R.string.comp_waiver_accept))
                }
            }
            Button(
                onClick = {
                    viewModel.register(
                        division = competition.divisions.firstOrNull()?.id ?: "open",
                        display = display.trim().ifEmpty { viewModel.myPubkey.take(8) },
                        waiverAccepted = !competition.waiverRequired || waiver,
                    )
                },
                enabled = !competition.waiverRequired || waiver,
                modifier = Modifier.fillMaxWidth().testTag("competition_register"),
            ) { Text(stringResource(R.string.comp_register)) }
            if (competition.waiverRequired && !waiver) {
                Text(stringResource(R.string.comp_waiver_required), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun LeaderboardCard(ui: CompetitionDetailViewModel.Ui) {
    Card(Modifier.fillMaxWidth().testTag("competition_leaderboard")) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.comp_leaderboard), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.comp_table_rank), Modifier.weight(0.15f), style = MaterialTheme.typography.labelSmall)
                Text(stringResource(R.string.comp_table_climber), Modifier.weight(0.45f), style = MaterialTheme.typography.labelSmall)
                Text(stringResource(R.string.comp_table_tops), Modifier.weight(0.2f), style = MaterialTheme.typography.labelSmall)
                Text(stringResource(R.string.comp_table_attempts), Modifier.weight(0.2f), style = MaterialTheme.typography.labelSmall)
            }
            HorizontalDivider()
            ui.snapshot.standings.forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(if (row.rank == 0) "—" else row.rank.toString(), Modifier.weight(0.15f))
                    Text(
                        row.display.ifBlank { row.pubkey.take(8) },
                        Modifier.weight(0.45f),
                        fontWeight = if (row.pubkey == ui.myPubkey) FontWeight.Bold else FontWeight.Normal,
                    )
                    Text(row.tops.toString(), Modifier.weight(0.2f))
                    Text(row.attempts.toString(), Modifier.weight(0.2f))
                }
            }
        }
    }
}

@Composable
private fun LabelledValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, fontWeight = FontWeight.Bold)
    }
}

private fun climbLabel(ui: CompetitionDetailViewModel.Ui, climbId: String): String {
    if (climbId.isEmpty()) return "—"
    return ui.snapshot.competition?.climb(climbId)?.label?.ifBlank { climbId } ?: climbId
}

private fun com.cruxcoach.domain.competition.Participant.displayOrShort(): String =
    display.ifBlank { pubkey.take(8) }

private fun com.cruxcoach.domain.competition.Competition.rawText(key: String): String =
    (raw[key] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()

fun registrationLabel(value: String): Int = when (value) {
    "accepted" -> R.string.comp_reg_accepted
    "waitlisted" -> R.string.comp_reg_waitlisted
    "rejected" -> R.string.comp_reg_rejected
    "withdrawn" -> R.string.comp_reg_withdrawn
    else -> R.string.comp_reg_pending
}

fun checkinLabel(value: String): Int = when (value) {
    "checked_in" -> R.string.comp_checkin_checked_in
    "no_show" -> R.string.comp_checkin_no_show
    else -> R.string.comp_checkin_none
}

fun paymentLabel(value: String): Int = when (value) {
    "pending" -> R.string.comp_pay_pending
    "settled" -> R.string.comp_pay_settled
    "failed" -> R.string.comp_pay_failed
    "expired" -> R.string.comp_pay_expired
    "refunded" -> R.string.comp_pay_refunded
    else -> R.string.comp_pay_not_required
}
