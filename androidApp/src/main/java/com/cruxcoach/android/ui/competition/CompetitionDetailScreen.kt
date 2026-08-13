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
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.BuildConfig
import com.cruxcoach.android.R
import com.cruxcoach.android.competition.CompetitionClimbResolver
import com.cruxcoach.android.competition.CompetitionRelayClient
import com.cruxcoach.domain.competition.Competition
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone

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
    onOpenClimb: (climbUuid: String, angle: Int) -> Unit = { _, _ -> },
    viewModel: CompetitionDetailViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val action by viewModel.action.collectAsStateWithLifecycle()
    val climbOpen by viewModel.climbOpen.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The board screen is only reached once the climb resolves against what
    // this phone actually holds; everything else stays here with a reason.
    var lastAsked by remember { mutableStateOf("") }
    LaunchedEffect(climbOpen) {
        val ready = climbOpen as? CompetitionClimbResolver.Result.Ready ?: return@LaunchedEffect
        viewModel.clearClimbOpen()
        onOpenClimb(ready.climbUuid, ready.angle)
    }

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
                        Text(
                            stringResource(
                                R.string.comp_identity_active,
                                ui.suggestedDisplayName.ifBlank { "${ui.myPubkey.take(12)}…" },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

            item { CompetitionEssentials(competition) }

            // ── the four questions, in order ──
            if (state != null && state.status in listOf("running", "paused")) {
                item { LivePanel(ui, now, viewModel) { id, _ -> lastAsked = id; viewModel.openClimb(id) } }
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
            item { RegistrationPanel(ui, viewModel, action) { id, _ -> lastAsked = id; viewModel.openClimb(id) } }
            item { ClimbOpenProblem(climbOpen, lastAsked, viewModel) }

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

/** The wall and schedule belong before registration, not in small print. */
@Composable
private fun CompetitionEssentials(competition: com.cruxcoach.domain.competition.Competition) {
    val board = listOfNotNull(
        competition.rawNestedText("board", "model").takeIf { it.isNotBlank() }?.let(::boardModelLabel),
        competition.rawNestedText("board", "size").takeIf { it.isNotBlank() },
        competition.rawNestedInt("board", "angle")?.let { "$it°" },
    ).joinToString(" · ").ifBlank { stringResource(R.string.comp_detail_not_set) }
    val venue = competition.rawNestedText("venue", "name")
        .ifBlank { stringResource(R.string.comp_detail_online) }
    val formatter = remember(competition.timezone) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .apply { timeZone = TimeZone.getTimeZone(competition.timezone) }
    }
    val starts = remember(competition.startsAt, formatter) {
        formatter.format(Date(competition.startsAt * 1000))
    }
    val registrationCloses = remember(competition.registrationClosesAt, formatter) {
        formatter.format(Date(competition.registrationClosesAt * 1000))
    }
    Card(Modifier.fillMaxWidth().testTag("competition_essentials")) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.comp_detail_essentials), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            LabelledValue(
                stringResource(R.string.comp_detail_starts),
                stringResource(R.string.comp_detail_time_value, starts, competition.timezone),
            )
            LabelledValue(stringResource(R.string.comp_detail_registration_closes), registrationCloses)
            LabelledValue(stringResource(R.string.comp_detail_venue), venue)
            LabelledValue(stringResource(R.string.comp_detail_board), board)
            LabelledValue(
                stringResource(R.string.comp_detail_format),
                stringResource(
                    R.string.comp_detail_format_value,
                    competition.rules.climbCount,
                    competition.rules.attemptsPerClimb,
                ),
            )
            LabelledValue(
                stringResource(R.string.comp_detail_fee),
                if (competition.feeMsat > 0) {
                    stringResource(R.string.comp_pay_amount, competition.feeMsat / 1000)
                } else {
                    stringResource(R.string.comp_detail_free)
                },
            )
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
private fun LivePanel(
    ui: CompetitionDetailViewModel.Ui,
    nowSeconds: Long,
    viewModel: CompetitionDetailViewModel,
    onOpenClimb: (String, Int) -> Unit,
) {
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

            if (competition.rules.progression == "asynchronous_turns" && ui.me != null) {
                Spacer(Modifier.height(12.dp))
                NextClimbSection(ui, nowSeconds, viewModel, onOpenClimb)
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
    onOpenClimb: (String, Int) -> Unit,
) {
    val competition = ui.snapshot.competition ?: return
    val state = ui.snapshot.state ?: return
    val me = ui.me
    val now = System.currentTimeMillis() / 1000
    val registrationOpen = registrationWindowOpen(competition, state.status, now)
    val checkinOpen = checkinWindowOpen(competition, state.status, now)

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
                var confirmWithdraw by rememberSaveable { mutableStateOf(false) }
                // Not only `pending`. A payment the organizer recorded as
                // failed or expired is precisely the state somebody has to be
                // able to leave, and showing the badge without the button
                // strands them.
                if (competition.feeMsat > 0 && me.payment in PAYABLE_STATES) {
                    PaymentSection(ui, viewModel)
                    Spacer(Modifier.height(8.dp))
                }
                if (ui.picksOwnClimbs) {
                    ClaimStatusSection(ui, viewModel, onOpenClimb)
                    Spacer(Modifier.height(8.dp))
                }
                if (me.registration == "accepted" && me.checkin == "none" && checkinOpen) {
                    Button(
                        onClick = { viewModel.requestCheckIn() },
                        modifier = Modifier.testTag("competition_checkin"),
                    ) { Text(stringResource(R.string.comp_checkin_action)) }
                }
                if (me.registration in listOf("pending", "accepted", "waitlisted") &&
                    state.status !in listOf("finished", "cancelled")
                ) {
                    OutlinedButton(
                        onClick = { confirmWithdraw = true },
                        modifier = Modifier.testTag("competition_withdraw"),
                    ) { Text(stringResource(R.string.comp_withdraw)) }
                    if (confirmWithdraw) {
                        AlertDialog(
                            onDismissRequest = { confirmWithdraw = false },
                            title = { Text(stringResource(R.string.comp_withdraw_confirm_title)) },
                            text = { Text(stringResource(R.string.comp_withdraw_confirm_body)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    confirmWithdraw = false
                                    viewModel.withdraw()
                                }) { Text(stringResource(R.string.comp_withdraw_confirm_action)) }
                            },
                            dismissButton = {
                                TextButton(onClick = { confirmWithdraw = false }) {
                                    Text(stringResource(R.string.action_cancel))
                                }
                            },
                        )
                    }
                }
                // Withdrawing is not meant to be a door that locks behind you.
                // While registration is open, asking again replaces the
                // withdrawal rather than adding a second request.
                if (me.registration in listOf("withdrawn", "rejected") && registrationOpen) {
                    Text(
                        stringResource(R.string.comp_register_again_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = {
                            viewModel.register(
                                division = me.division.ifEmpty {
                                    competition.divisions.firstOrNull()?.id ?: "open"
                                },
                                display = me.display,
                                waiverAccepted = true,
                                selections = me.selections,
                            )
                        },
                        modifier = Modifier.testTag("competition_register_again"),
                    ) { Text(stringResource(R.string.comp_register_again)) }
                }
                if (ui.canDefer) {
                    Button(
                        onClick = { viewModel.requestDefer() },
                        modifier = Modifier.testTag("competition_defer"),
                    ) { Text(stringResource(R.string.comp_defer)) }
                }
                return@Column
            }

            if (!registrationOpen) {
                Text(stringResource(R.string.comp_registration_closed))
                return@Column
            }

            val accepted = state.participants.count { it.registration == "accepted" }
            if (competition.capacity > 0 && accepted >= competition.capacity && !competition.waitlistEnabled) {
                Text(stringResource(R.string.comp_full))
                return@Column
            }

            var display by rememberSaveable(ui.myPubkey) { mutableStateOf(ui.suggestedDisplayName) }
            LaunchedEffect(ui.suggestedDisplayName) {
                if (display.isBlank()) display = ui.suggestedDisplayName
            }
            var waiver by rememberSaveable { mutableStateOf(false) }
            var selectedDivision by rememberSaveable(competition.compId) {
                mutableStateOf(competition.divisions.firstOrNull()?.id ?: "open")
            }
            OutlinedTextField(
                value = display,
                onValueChange = { display = it.take(48) },
                label = { Text(stringResource(R.string.comp_display_name)) },
                supportingText = { Text(stringResource(R.string.comp_display_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("competition_display_name"),
            )
            if (competition.divisions.size > 1) {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.comp_division_required), fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.comp_division_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                competition.divisions.forEach { division ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedDivision = division.id }
                            .testTag("competition_division_${division.id}"),
                    ) {
                        RadioButton(
                            selected = selectedDivision == division.id,
                            onClick = { selectedDivision = division.id },
                        )
                        Text(division.label)
                    }
                }
            }
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
            val picked = remember { mutableStateListOf<String>() }
            if (ui.picksOwnClimbs) {
                Spacer(Modifier.height(12.dp))
                ClimbPicker(
                    ui = ui,
                    picked = picked,
                    needed = competition.rules.climbCount,
                    onOpenClimb = onOpenClimb,
                )
            }
            val picksComplete = !ui.picksOwnClimbs || picked.size == competition.rules.climbCount
            Button(
                onClick = {
                    viewModel.register(
                        division = selectedDivision,
                        display = display.trim(),
                        waiverAccepted = !competition.waiverRequired || waiver,
                        selections = picked.toList(),
                    )
                },
                enabled = display.isNotBlank() && (!competition.waiverRequired || waiver) && picksComplete,
                modifier = Modifier.fillMaxWidth().testTag("competition_register"),
            ) { Text(stringResource(R.string.comp_register)) }
            if (!picksComplete) {
                Text(
                    stringResource(R.string.comp_pick_incomplete, competition.rules.climbCount),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (competition.waiverRequired && !waiver) {
                Text(stringResource(R.string.comp_waiver_required), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Payment states an entrant can still act on. */
private val PAYABLE_STATES = setOf("pending", "failed", "expired")

/**
 * Paying the entry fee.
 *
 * The app used to render `payment == pending` and stop there, which left an
 * entrant looking at a state with no way out of it. Three steps, each of which
 * can fail in a way they have to be told about: resolve the organizer's
 * endpoint, ask it for an invoice bound to this person and this registration,
 * then show that invoice with what it costs and when it dies.
 *
 * The invoice is checked before it is shown — an invoice for a different amount
 * is refused rather than displayed with a warning, because the number on the
 * screen and the number a wallet would send have to be the same number.
 */
@Composable
private fun PaymentSection(
    ui: CompetitionDetailViewModel.Ui,
    viewModel: CompetitionDetailViewModel,
) {
    val competition = ui.snapshot.competition ?: return
    val payment by viewModel.payment.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Text(stringResource(R.string.comp_pay_title), fontWeight = FontWeight.Bold)
    Text(
        stringResource(R.string.comp_pay_amount, competition.feeMsat / 1000),
        style = MaterialTheme.typography.bodyMedium,
    )

    if (competition.feeLnurl.isNullOrBlank()) {
        // A fee with nowhere to send it is the organizer's problem to fix, but
        // the entrant still needs to know why there is no button.
        Text(stringResource(R.string.comp_pay_no_endpoint), style = MaterialTheme.typography.bodySmall)
        return
    }

    when (val state = payment) {
        CompetitionDetailViewModel.Payment.Working -> CircularProgressIndicator()

        is CompetitionDetailViewModel.Payment.Ready -> {
            val invoice = state.invoice
            Text(
                invoice.bolt11,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().testTag("competition_pay_invoice"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        // A device with no Lightning wallet must not crash; the
                        // invoice is on screen and copyable either way.
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(invoice.walletUri)),
                            )
                        }
                    },
                    modifier = Modifier.testTag("competition_pay_open"),
                ) { Text(stringResource(R.string.comp_pay_open_wallet)) }
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText("bolt11", invoice.bolt11))
                    },
                    modifier = Modifier.testTag("competition_pay_copy"),
                ) { Text(stringResource(R.string.comp_pay_copy)) }
            }
            val minutes = (invoice.secondsLeft(System.currentTimeMillis() / 1000) + 59) / 60
            Text(
                if (minutes > 0) stringResource(R.string.comp_pay_expires_in, minutes)
                else stringResource(R.string.comp_pay_invoice_expired),
                style = MaterialTheme.typography.bodySmall,
            )
            // Said before they pay, not after: what the organizer will be able
            // to check, and what they will have to take on trust.
            Text(
                if (invoice.verifiable) stringResource(R.string.comp_pay_will_verify)
                else stringResource(R.string.comp_pay_manual_confirm),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(
                onClick = { viewModel.requestInvoice() },
                modifier = Modifier.testTag("competition_pay_retry"),
            ) { Text(stringResource(R.string.comp_pay_new_invoice)) }
        }

        is CompetitionDetailViewModel.Payment.Failed -> {
            Text(
                payErrorMessage(state.code, state.amountSats),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .testTag("competition_pay_error")
                    .semantics { liveRegion = LiveRegionMode.Assertive },
            )
            Button(
                onClick = { viewModel.requestInvoice() },
                modifier = Modifier.testTag("competition_pay_retry"),
            ) { Text(stringResource(R.string.comp_pay_try_again)) }
        }

        CompetitionDetailViewModel.Payment.Idle -> Button(
            onClick = { viewModel.requestInvoice() },
            modifier = Modifier.fillMaxWidth().testTag("competition_pay_start"),
        ) { Text(stringResource(R.string.comp_pay_get_invoice)) }
    }

    Text(stringResource(R.string.comp_pay_settle_hint), style = MaterialTheme.typography.bodySmall)
}

/** One sentence per way this can fail, rather than a code on screen. */
@Composable
private fun payErrorMessage(code: String, amountSats: Long): String = when (code) {
    "empty", "no_fee" -> stringResource(R.string.comp_pay_no_endpoint)
    "bad_address", "bad_domain", "bad_url", "unrecognised" ->
        stringResource(R.string.comp_pay_err_bad_address)
    "onion" -> stringResource(R.string.comp_pay_err_onion)
    "not_https" -> stringResource(R.string.comp_pay_err_not_https)
    "bad_lnurl" -> stringResource(R.string.comp_pay_err_bad_lnurl)
    "not_a_pay_request" -> stringResource(R.string.comp_pay_err_not_a_pay_request)
    "bad_callback" -> stringResource(R.string.comp_pay_err_bad_callback)
    "below_minimum" -> stringResource(R.string.comp_pay_err_below_minimum, amountSats)
    "above_maximum" -> stringResource(R.string.comp_pay_err_above_maximum, amountSats)
    "wrong_amount" -> stringResource(R.string.comp_pay_err_wrong_amount, amountSats)
    "expired" -> stringResource(R.string.comp_pay_invoice_expired)
    "unreachable" -> stringResource(R.string.comp_pay_err_unreachable)
    "signing_failed" -> stringResource(R.string.comp_pay_err_signing)
    else -> stringResource(R.string.comp_pay_err_provider)
}

/**
 * Choosing climbs at registration, when the organizer let entrants choose.
 *
 * A climb somebody already holds is shown as taken and cannot be ticked, so the
 * race is visible before it is lost rather than after. Each one can be opened
 * on the board first — picking a problem you have not seen is how you find out
 * at the wall that it is not for you.
 */
@Composable
private fun ClimbPicker(
    ui: CompetitionDetailViewModel.Ui,
    picked: SnapshotStateList<String>,
    needed: Int,
    onOpenClimb: (String, Int) -> Unit,
) {
    val competition = ui.snapshot.competition ?: return
    val state = ui.snapshot.state ?: return
    val unique = competition.rules.selectionUniqueness == "unique_per_competition"

    Text(stringResource(R.string.comp_pick_title), fontWeight = FontWeight.Bold)
    Text(stringResource(R.string.comp_pick_hint, needed), style = MaterialTheme.typography.bodySmall)
    if (unique) {
        Text(stringResource(R.string.comp_pick_unique_hint), style = MaterialTheme.typography.bodySmall)
    }
    Text(
        stringResource(R.string.comp_pick_count, picked.size, needed),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    competition.climbPool.forEach { climb ->
        val takenBy = if (unique) state.claims[climb.id] else null
        val taken = takenBy != null && takenBy != ui.myPubkey
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(
                checked = picked.contains(climb.id),
                enabled = !taken,
                onCheckedChange = { checked ->
                    if (checked) {
                        if (picked.size < needed) picked.add(climb.id)
                    } else {
                        picked.remove(climb.id)
                    }
                },
                modifier = Modifier.testTag("competition_pick_${climb.id}"),
            )
            Column(Modifier.weight(1f)) {
                Text(climb.label)
                Text(
                    if (taken) stringResource(R.string.comp_pick_taken)
                    else stringResource(R.string.comp_climb_angle, climb.angle),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OpenOnBoardButton(climb, onOpenClimb)
        }
    }
}

/**
 * What happened to an entrant's picks.
 *
 * Losing a race silently is the worst version of it, so this names the climbs
 * that were granted, says plainly when one was taken first, and offers what is
 * still free. Re-registering replaces the earlier request rather than adding a
 * second one, because an intent reuses its nonce.
 */
@Composable
private fun ClaimStatusSection(
    ui: CompetitionDetailViewModel.Ui,
    viewModel: CompetitionDetailViewModel,
    onOpenClimb: (String, Int) -> Unit,
) {
    val competition = ui.snapshot.competition ?: return
    val state = ui.snapshot.state ?: return
    val me = ui.me ?: return

    Text(stringResource(R.string.comp_your_climbs), fontWeight = FontWeight.Bold)
    me.selections.forEach { id ->
        val climb = competition.climb(id)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(climb?.label ?: id, modifier = Modifier.weight(1f))
            if (climb != null) OpenOnBoardButton(climb, onOpenClimb)
        }
    }

    if (ui.climbsStillToPick == 0) {
        Text(stringResource(R.string.comp_picks_confirmed), style = MaterialTheme.typography.bodySmall)
        return
    }
    if (!registrationWindowOpen(competition, state.status, System.currentTimeMillis() / 1000)) {
        Text(stringResource(R.string.comp_picks_pending), style = MaterialTheme.typography.bodySmall)
        return
    }
    val free = ui.freePoolClimbs.filter { it.id !in me.selections }
    if (free.isEmpty()) {
        Text(stringResource(R.string.comp_picks_none_left), style = MaterialTheme.typography.bodySmall)
        return
    }

    Text(
        stringResource(R.string.comp_picks_lost, ui.climbsStillToPick),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    val repick = remember(me.selections) { mutableStateListOf<String>().apply { addAll(me.selections) } }
    free.forEach { climb ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(
                checked = repick.contains(climb.id),
                onCheckedChange = { checked ->
                    if (checked) {
                        if (repick.size < competition.rules.climbCount) repick.add(climb.id)
                    } else {
                        repick.remove(climb.id)
                    }
                },
                modifier = Modifier.testTag("competition_repick_${climb.id}"),
            )
            Text(climb.label, modifier = Modifier.weight(1f))
            OpenOnBoardButton(climb, onOpenClimb)
        }
    }
    Button(
        onClick = {
            viewModel.register(
                division = me.division.ifEmpty { competition.divisions.firstOrNull()?.id ?: "open" },
                display = me.display,
                waiverAccepted = true,
                selections = repick.toList(),
            )
        },
        enabled = repick.size == competition.rules.climbCount,
        modifier = Modifier.fillMaxWidth().testTag("competition_repick"),
    ) { Text(stringResource(R.string.comp_pick_again)) }
}

private fun registrationWindowOpen(competition: Competition, status: String, at: Long): Boolean =
    at <= competition.registrationClosesAt && (
        status == "registration_open" || status == "checkin_open" ||
            (status == "running" && competition.rules.lateEntryAllowed)
        )

private fun checkinWindowOpen(competition: Competition, status: String, at: Long): Boolean =
    at <= competition.checkinClosesAt && (
        status == "checkin_open" || (status == "running" && competition.rules.lateEntryAllowed)
        )

/**
 * Asynchronous turns: which of my climbs I go to next.
 *
 * The control exists only while this climber may actually act. Every reason
 * they cannot gets its own sentence instead — a disabled button teaches nobody
 * anything, and one that publishes a report the reducer then rejects is worse,
 * because they would walk away believing the attempt counted.
 */
@Composable
private fun NextClimbSection(
    ui: CompetitionDetailViewModel.Ui,
    nowSeconds: Long,
    viewModel: CompetitionDetailViewModel,
    onOpenClimb: (String, Int) -> Unit,
) {
    val remaining = ui.remainingClimbs
    Text(stringResource(R.string.comp_next_title), fontWeight = FontWeight.Bold)
    if (remaining.isEmpty()) {
        Text(stringResource(R.string.comp_next_none_left), style = MaterialTheme.typography.bodySmall)
        return
    }
    if (!ui.mayAct(nowSeconds)) {
        Text(whyNotYet(ui, nowSeconds), style = MaterialTheme.typography.bodySmall)
        return
    }

    var chosen by rememberSaveable { mutableStateOf(remaining.first().climb.id) }
    if (remaining.none { it.climb.id == chosen }) chosen = remaining.first().climb.id

    remaining.forEach { entry ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            RadioButton(
                selected = chosen == entry.climb.id,
                onClick = { chosen = entry.climb.id },
                modifier = Modifier.testTag("competition_next_${entry.climb.id}"),
            )
            Column(Modifier.weight(1f)) {
                Text(entry.climb.label)
                Text(
                    stringResource(R.string.comp_next_attempts, entry.attemptsLeft),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OpenOnBoardButton(entry.climb, onOpenClimb)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("top" to R.string.comp_outcome_top, "zone" to R.string.comp_outcome_zone,
            "fall" to R.string.comp_outcome_fall).forEach { (outcome, label) ->
            Button(
                onClick = { viewModel.reportAttempt(chosen, outcome) },
                modifier = Modifier.testTag("competition_report_$outcome"),
            ) { Text(stringResource(label)) }
        }
    }
    Text(stringResource(R.string.comp_next_report_hint), style = MaterialTheme.typography.bodySmall)
}

/** The one sentence that says why the chooser is not there. */
@Composable
private fun whyNotYet(ui: CompetitionDetailViewModel.Ui, nowSeconds: Long): String {
    val state = ui.snapshot.state
    val competition = ui.snapshot.competition
    val me = ui.me
    val rest = ui.restSecondsLeft(nowSeconds)
    return when {
        state?.paused == true -> stringResource(R.string.comp_next_paused)
        me == null -> stringResource(R.string.comp_next_not_entered)
        me.result != "active" -> stringResource(R.string.comp_next_out)
        me.checkin != "checked_in" -> stringResource(R.string.comp_next_not_checked_in)
        competition != null && competition.feeMsat > 0 && me.payment != "settled" ->
            stringResource(R.string.comp_next_unpaid)
        rest > 0 -> stringResource(R.string.comp_next_resting, rest)
        else -> stringResource(R.string.comp_next_not_your_turn)
    }
}

/**
 * Why a climb did not open.
 *
 * Two situations, two different fixes, and neither is the app being broken:
 * the board has not been downloaded on this phone, or it has and nothing we
 * hold can draw this climb. Retry is offered for the first, because a sync
 * between now and then changes the answer.
 */
@Composable
private fun ClimbOpenProblem(
    result: CompetitionClimbResolver.Result?,
    lastAsked: String,
    viewModel: CompetitionDetailViewModel,
) {
    if (result == null || result is CompetitionClimbResolver.Result.Ready) return
    Card(
        Modifier.fillMaxWidth().testTag("competition_climb_problem"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                when (result) {
                    is CompetitionClimbResolver.Result.NotInCatalogue ->
                        stringResource(R.string.comp_climb_not_downloaded, result.brand)
                    is CompetitionClimbResolver.Result.WrongBoard ->
                        stringResource(R.string.comp_climb_wrong_board, result.brand)
                    else -> stringResource(R.string.comp_climb_unusable)
                },
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (result is CompetitionClimbResolver.Result.NotInCatalogue && lastAsked.isNotEmpty()) {
                    Button(
                        onClick = { viewModel.retryOpenClimb(lastAsked) },
                        modifier = Modifier.testTag("competition_climb_retry"),
                    ) { Text(stringResource(R.string.comp_climb_retry)) }
                }
                OutlinedButton(
                    onClick = { viewModel.clearClimbOpen() },
                    modifier = Modifier.testTag("competition_climb_dismiss"),
                ) { Text(stringResource(R.string.comp_climb_dismiss)) }
            }
        }
    }
}

/**
 * Put this climb on the wall.
 *
 * The whole reason a competition climb carries a real board uuid: the existing
 * board screen takes it from here, at the competition's angle.
 */
@Composable
private fun OpenOnBoardButton(
    climb: com.cruxcoach.domain.competition.CompetitionClimb,
    onOpenClimb: (String, Int) -> Unit,
) {
    if (climb.climbUuid.isBlank()) return
    OutlinedButton(
        onClick = { onOpenClimb(climb.id, climb.angle) },
        modifier = Modifier.testTag("competition_open_${climb.id}"),
    ) { Text(stringResource(R.string.comp_open_on_board)) }
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
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.38f),
        )
        Text(
            value,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.62f),
        )
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

private fun com.cruxcoach.domain.competition.Competition.rawNestedText(parent: String, key: String): String =
    (raw[parent] as? kotlinx.serialization.json.JsonObject)
        ?.get(key)?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content.orEmpty()

private fun com.cruxcoach.domain.competition.Competition.rawNestedInt(parent: String, key: String): Int? =
    rawNestedText(parent, key).toIntOrNull()

/** Protocol model ids stay stable; people see the board names used in the picker. */
private fun boardModelLabel(model: String): String = when (model) {
    "kilterboard-og" -> "Kilter Board Original"
    "kilterboard-homewall" -> "Kilter Board Homewall"
    "moonboard-2016" -> "MoonBoard 2016"
    "moonboard-masters-2017" -> "MoonBoard Masters 2017"
    "moonboard-masters-2019" -> "MoonBoard Masters 2019"
    "mini-moonboard-2020" -> "Mini MoonBoard 2020"
    "moonboard-2024" -> "MoonBoard 2024"
    "mini-moonboard-2025" -> "Mini MoonBoard 2025"
    "moonboard-2010" -> "MoonBoard 2010"
    "tension-board-1" -> "Tension Board"
    "tension-board-2-mirror" -> "Tension Board 2 (Mirror)"
    "tension-board-2-spray" -> "Tension Board 2 (Spray)"
    "grasshopper-board" -> "Grasshopper Board"
    "soill-board" -> "So iLL Board"
    "touchstone-board" -> "Touchstone Board"
    else -> model.split('-').joinToString(" ") { part ->
        part.replaceFirstChar { character -> character.uppercase() }
    }
}

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
