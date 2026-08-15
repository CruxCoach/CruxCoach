package com.cruxcoach.android.ui.competition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.BuildConfig
import com.cruxcoach.android.R
import com.cruxcoach.android.competition.CompetitionClimbResolver
import com.cruxcoach.android.competition.CompetitionCataloguePolicy
import com.cruxcoach.android.competition.CompetitionRelayClient
import com.cruxcoach.android.data.LedHoldColors
import com.cruxcoach.android.ui.board.KilterBoardVisualization
import com.cruxcoach.android.ui.board.MoonBoardVisualization
import com.cruxcoach.android.ui.board.rememberMoonBoardAsset
import com.cruxcoach.android.util.GradeDisplayHelper
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant
import com.cruxcoach.domain.competition.Competition
import com.cruxcoach.domain.competition.CompetitionClimb
import com.cruxcoach.domain.competition.CompetitionProtocol
import com.cruxcoach.domain.competition.Nip19
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone

private enum class ParticipantRoute { REGISTRATION, CHECKIN, LIVE, CHOOSER, LEADERBOARD }
private enum class HostRoute { SETUP, ENTRANTS, LIVE }

/** Closed routing table: a new protocol intent cannot silently disappear. */
internal fun hostIntentDestination(op: String): String? = when (op) {
    "register", "checkin_request", "withdraw", "payment_claim", "prize_claim", "prize_receipt" ->
        HostRoute.ENTRANTS.name
    "defer_request", "attempt_report", "climb_choice" -> HostRoute.LIVE.name
    else -> null
}

/**
 * One competition, with focused participant and host destinations.
 *
 * Ordered by what someone standing at a wall needs first: is it my turn, how
 * many people are ahead of me, how many attempts have I left, and only then the
 * leaderboard and the small print.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompetitionDetailScreen(
    onNavigateBack: () -> Unit,
    onManageIdentity: () -> Unit = {},
    onOpenClimb: (climbUuid: String, angle: Int) -> Unit = { _, _ -> },
    viewModel: CompetitionDetailViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val action by viewModel.action.collectAsStateWithLifecycle()
    val climbOpen by viewModel.climbOpen.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val competitionKey = ui.snapshot.competition?.compId
    var participantRouteName by rememberSaveable(competitionKey) { mutableStateOf("") }
    var participantPrimaryName by rememberSaveable(competitionKey) { mutableStateOf("") }
    val participantRoute = ParticipantRoute.entries.firstOrNull { it.name == participantRouteName }
    var hostRouteName by rememberSaveable(competitionKey) { mutableStateOf("") }
    var hostPrimaryName by rememberSaveable(competitionKey) { mutableStateOf("") }
    val hostRoute = HostRoute.entries.firstOrNull { it.name == hostRouteName }

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
    val preparedClimbId = ui.preparedClimbId
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
                    IconButton(onClick = {
                        if (viewModel.isAuthority && hostRouteName != HostRoute.SETUP.name &&
                            ui.snapshot.state != null
                        ) {
                            hostRouteName = HostRoute.SETUP.name
                        } else if (!viewModel.isAuthority && participantRoute in listOf(
                                ParticipantRoute.CHOOSER,
                                ParticipantRoute.LEADERBOARD,
                            )
                        ) participantRouteName = ParticipantRoute.LIVE.name
                        else onNavigateBack()
                    }) {
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
        bottomBar = {
            if (ui.snapshot.trustworthy && !viewModel.isAuthority && participantRoute == ParticipantRoute.LIVE) ParticipantActionBar(
                ui = ui,
                nowSeconds = now,
                viewModel = viewModel,
                preparedClimbId = preparedClimbId,
                onOpenClimb = { id -> lastAsked = id; viewModel.openClimb(id) },
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
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = viewModel::reload,
                    modifier = Modifier.testTag("competition_retry_load"),
                ) { Text(stringResource(R.string.action_retry)) }
            }
            return@Scaffold
        }

        val competition = snapshot.competition
        val state = snapshot.state

        // A definition without its complete authority chain is not evidence of
        // registration, queue position, results, or safe host state. Block all
        // role-specific controls until exact-d-tag backfill is trustworthy.
        if (snapshot.loading || !snapshot.trustworthy) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp)
                    .testTag("competition_history_loading"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (snapshot.loading) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    stringResource(
                        when {
                            snapshot.loading -> R.string.comp_history_loading
                            state?.forkDetected == true -> R.string.comp_fork
                            state?.chainComplete == false -> R.string.comp_history_gap_recovering
                            else -> R.string.comp_history_untrusted
                        },
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = viewModel::reload,
                    modifier = Modifier.testTag("competition_retry_history"),
                ) { Text(stringResource(R.string.action_retry)) }
            }
            return@Scaffold
        }

        if (!viewModel.isAuthority && state != null) {
            val runningNow = CompetitionProtocol.competitionRunning(competition, state.status, now)
            val terminal = state.status in listOf("finished", "cancelled")
            val primaryRoute = when {
                runningNow || state.status == "paused" || terminal -> ParticipantRoute.LIVE
                ui.me?.registration == "accepted" -> ParticipantRoute.CHECKIN
                else -> ParticipantRoute.REGISTRATION
            }
            val allowedRoutes = buildSet {
                add(ParticipantRoute.REGISTRATION)
                if (ui.me?.registration == "accepted") add(ParticipantRoute.CHECKIN)
                if (primaryRoute == ParticipantRoute.LIVE) {
                    add(ParticipantRoute.LIVE)
                    add(ParticipantRoute.LEADERBOARD)
                    if (ui.picksOwnClimbs) add(ParticipantRoute.CHOOSER)
                }
            }
            LaunchedEffect(primaryRoute, participantRouteName) {
                val followedPreviousPrimary = participantRouteName.isBlank() ||
                    participantRouteName == participantPrimaryName
                if (participantRoute !in allowedRoutes ||
                    (participantPrimaryName != primaryRoute.name && followedPreviousPrimary)
                ) participantRouteName = primaryRoute.name
                participantPrimaryName = primaryRoute.name
            }
            val effectiveRoute = participantRoute?.takeIf { it in allowedRoutes } ?: primaryRoute
            BackHandler(effectiveRoute in listOf(ParticipantRoute.CHOOSER, ParticipantRoute.LEADERBOARD)) {
                participantRouteName = ParticipantRoute.LIVE.name
            }
            ParticipantPhaseScreen(
                route = effectiveRoute,
                ui = ui,
                action = action,
                climbOpen = climbOpen,
                lastAsked = lastAsked,
                nowSeconds = now,
                preparedClimbId = preparedClimbId,
                viewModel = viewModel,
                contentPadding = padding,
                onRoute = { participantRouteName = it.name },
                onPreparedClimb = { id ->
                    if (id != null && ui.picksOwnClimbs) viewModel.chooseClimb(id)
                },
                onOpenClimb = { id -> lastAsked = id; viewModel.openClimb(id) },
                onManageIdentity = onManageIdentity,
            )
            return@Scaffold
        }

        if (viewModel.isAuthority && state != null) {
            val primaryRoute = when {
                CompetitionProtocol.competitionRunning(competition, state.status, now) ||
                    state.status == "paused" -> HostRoute.LIVE
                CompetitionProtocol.registrationWindowOpen(competition, state.status, now) ||
                    CompetitionProtocol.checkinWindowOpen(competition, state.status, now) ||
                    ui.snapshot.pendingIntents.isNotEmpty() -> HostRoute.ENTRANTS
                else -> HostRoute.SETUP
            }
            LaunchedEffect(primaryRoute, hostRouteName) {
                val followedPreviousPrimary = hostRouteName.isBlank() || hostRouteName == hostPrimaryName
                if (hostPrimaryName != primaryRoute.name && followedPreviousPrimary) {
                    hostRouteName = primaryRoute.name
                }
                hostPrimaryName = primaryRoute.name
            }
            val effectiveRoute = hostRoute ?: primaryRoute
            BackHandler(effectiveRoute != HostRoute.SETUP) { hostRouteName = HostRoute.SETUP.name }
            OrganizerPhaseScreen(
                route = effectiveRoute,
                ui = ui,
                viewModel = viewModel,
                action = action,
                nowSeconds = now,
                contentPadding = padding,
                onRoute = { hostRouteName = it.name },
            )
            return@Scaffold
        }

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
                        val visibleStatus = when {
                            state == null -> competition.status
                            state.status in listOf("finished", "cancelled", "paused") -> state.status
                            CompetitionProtocol.competitionRunning(competition, state.status, now) -> "running"
                            CompetitionProtocol.checkinWindowOpen(competition, state.status, now) -> "checkin_open"
                            CompetitionProtocol.registrationWindowOpen(competition, state.status, now) -> "registration_open"
                            else -> "published"
                        }
                        Text(
                            stringResource(competitionStatusLabel(visibleStatus)),
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

            item { ClimbOpenProblem(climbOpen, lastAsked, viewModel) }

            item { CompetitionEssentials(competition) }
            item { CompetitionScoringCard(competition) }
            item { CompetitionCatalogueOverview(ui) { id, _ -> lastAsked = id; viewModel.openClimb(id) } }

            if (snapshot.trustworthy && snapshot.standings.isNotEmpty()) {
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

/** Focused participant destinations. */
@Composable
private fun ParticipantPhaseScreen(
    route: ParticipantRoute,
    ui: CompetitionDetailViewModel.Ui,
    action: CompetitionDetailViewModel.Action,
    climbOpen: CompetitionClimbResolver.Result?,
    lastAsked: String,
    nowSeconds: Long,
    preparedClimbId: String?,
    viewModel: CompetitionDetailViewModel,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    onRoute: (ParticipantRoute) -> Unit,
    onPreparedClimb: (String?) -> Unit,
    onOpenClimb: (String) -> Unit,
    onManageIdentity: () -> Unit,
) {
    val competition = ui.snapshot.competition ?: return
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding)
            .testTag("competition_participant_${route.name.lowercase()}"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (ui.snapshot.usesDevelopmentRelay) item { WarningCard(stringResource(R.string.comp_dev_relay)) }
        ui.snapshot.chainBreakAt?.let { seq -> item { WarningCard(stringResource(R.string.comp_chain_break, seq)) } }
        if (ui.snapshot.state?.forkDetected == true) item { WarningCard(stringResource(R.string.comp_fork)) }

        item {
            Text(
                stringResource(
                    when (route) {
                        ParticipantRoute.REGISTRATION -> R.string.comp_participant_screen_registration
                        ParticipantRoute.CHECKIN -> R.string.comp_participant_screen_checkin
                        ParticipantRoute.LIVE -> R.string.comp_participant_screen_live
                        ParticipantRoute.CHOOSER -> R.string.comp_participant_screen_chooser
                        ParticipantRoute.LEADERBOARD -> R.string.comp_leaderboard
                    },
                ),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        item { ParticipantIdentityCard(ui, onManageIdentity) }
        item { ParticipantPhaseNavigation(route, ui, nowSeconds, onRoute) }

        when (route) {
            ParticipantRoute.REGISTRATION -> item { RegistrationPanel(ui, viewModel, action) }
            ParticipantRoute.CHECKIN -> item { CheckInPanel(ui, viewModel, action, nowSeconds) }
            ParticipantRoute.LIVE -> {
                if (ui.picksOwnClimbs && ui.me != null) item {
                    Button(
                        onClick = { onRoute(ParticipantRoute.CHOOSER) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .testTag("competition_choose_next_boulder"),
                    ) {
                        Text(
                            if (preparedClimbId == null) stringResource(R.string.comp_choose_next_boulder)
                            else stringResource(
                                R.string.comp_change_prepared_boulder,
                                climbLabel(ui, preparedClimbId),
                            ),
                        )
                    }
                }
                item {
                    LivePanel(
                        ui = ui,
                        nowSeconds = nowSeconds,
                        action = action,
                        preparedClimbId = preparedClimbId,
                    )
                }
                item {
                    OutlinedButton(
                        onClick = { onRoute(ParticipantRoute.LEADERBOARD) },
                        modifier = Modifier.fillMaxWidth().testTag("competition_open_leaderboard"),
                    ) { Text(stringResource(R.string.comp_open_leaderboard)) }
                }
                item { PrizeClaimPanel(ui, viewModel) }
            }
            ParticipantRoute.CHOOSER -> {
                item {
                    Text(
                        stringResource(
                            R.string.comp_journey_pool_best,
                            competition.rules.countedClimbCount,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    NextClimbSection(
                        ui,
                        nowSeconds,
                        viewModel,
                        preparedClimbId,
                        onPreparedClimb,
                    ) { id, _ -> onOpenClimb(id) }
                }
            }
            ParticipantRoute.LEADERBOARD -> item { LeaderboardCard(ui) }
        }
        item { ClimbOpenProblem(climbOpen, lastAsked, viewModel) }
    }
}

internal fun competitionIdentityNpub(pubkey: String): String = runCatching {
    Nip19.encodeNpub(pubkey).let {
        if (it.length > 24) "${it.take(12)}…${it.takeLast(6)}" else it
    }
}.getOrElse { "${pubkey.take(12)}…${pubkey.takeLast(6)}" }

@Composable
private fun ParticipantIdentityCard(
    ui: CompetitionDetailViewModel.Ui,
    onManageIdentity: () -> Unit,
) {
    val npub = remember(ui.myPubkey) { competitionIdentityNpub(ui.myPubkey) }
    Card(Modifier.fillMaxWidth().testTag("competition_participant_identity")) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(
                    R.string.comp_identity_signed_in,
                    ui.suggestedDisplayName.ifBlank {
                        stringResource(R.string.comp_identity_unknown_name)
                    },
                    npub,
                ),
                fontWeight = FontWeight.Bold,
            )
            val me = ui.me
            Text(
                if (me == null) stringResource(R.string.comp_identity_not_registered)
                else stringResource(registrationLabel(me.registration)),
                color = if (me == null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (me == null) {
                Text(
                    stringResource(R.string.comp_identity_registration_owner),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = onManageIdentity,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("competition_manage_identity"),
            ) { Text(stringResource(R.string.comp_manage_identity)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParticipantPhaseNavigation(
    route: ParticipantRoute,
    ui: CompetitionDetailViewModel.Ui,
    nowSeconds: Long,
    onRoute: (ParticipantRoute) -> Unit,
) {
    val competition = ui.snapshot.competition ?: return
    val state = ui.snapshot.state ?: return
    val live = CompetitionProtocol.competitionRunning(competition, state.status, nowSeconds) ||
        state.status in listOf("paused", "finished", "cancelled")
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 3,
    ) {
        listOfNotNull(
            ParticipantRoute.REGISTRATION to R.string.comp_journey_registration,
            (ParticipantRoute.CHECKIN to R.string.comp_journey_checkin)
                .takeIf { ui.me?.registration == "accepted" },
            (ParticipantRoute.LIVE to R.string.comp_journey_live).takeIf { live },
        ).forEach { (target, label) ->
            OutlinedButton(
                onClick = { onRoute(target) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    .semantics { selected = route == target; role = Role.Tab }
                    .testTag("competition_phase_${target.name.lowercase()}"),
            ) { Text(stringResource(label), textAlign = TextAlign.Center) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OrganizerPhaseScreen(
    route: HostRoute,
    ui: CompetitionDetailViewModel.Ui,
    viewModel: CompetitionDetailViewModel,
    action: CompetitionDetailViewModel.Action,
    nowSeconds: Long,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    onRoute: (HostRoute) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding)
            .testTag("competition_host_${route.name.lowercase()}"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (ui.snapshot.usesDevelopmentRelay) item { WarningCard(stringResource(R.string.comp_dev_relay)) }
        item {
            Text(
                stringResource(
                    when (route) {
                        HostRoute.SETUP -> R.string.comp_host_screen_setup
                        HostRoute.ENTRANTS -> R.string.comp_host_screen_entrants
                        HostRoute.LIVE -> R.string.comp_host_screen_live
                    },
                ),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        item {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 3,
            ) {
                listOf(
                    HostRoute.SETUP to R.string.comp_host_nav_setup,
                    HostRoute.ENTRANTS to R.string.comp_host_nav_entrants,
                    HostRoute.LIVE to R.string.comp_host_nav_live,
                ).forEach { (target, label) ->
                    OutlinedButton(
                        onClick = { onRoute(target) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            .semantics { selected = route == target; role = Role.Tab }
                            .testTag("competition_host_nav_${target.name.lowercase()}"),
                    ) { Text(stringResource(label), textAlign = TextAlign.Center) }
                }
            }
        }
        item { OrganizerConsole(route, ui, viewModel, action, nowSeconds) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OrganizerConsole(
    route: HostRoute,
    ui: CompetitionDetailViewModel.Ui,
    viewModel: CompetitionDetailViewModel,
    action: CompetitionDetailViewModel.Action,
    nowSeconds: Long,
) {
    val competition = ui.snapshot.competition ?: return
    val state = ui.snapshot.state ?: return
    val cleanup by viewModel.cleanup.collectAsStateWithLifecycle()
    val paymentReviews by viewModel.hostPaymentReviews.collectAsStateWithLifecycle()
    var announcement by rememberSaveable { mutableStateOf("") }
    var confirmCancel by rememberSaveable { mutableStateOf(false) }
    var confirmFinish by rememberSaveable { mutableStateOf(false) }
    var confirmSkip by rememberSaveable { mutableStateOf(false) }
    var confirmCleanup by rememberSaveable { mutableStateOf(false) }
    var paymentPubkey by rememberSaveable { mutableStateOf<String?>(null) }
    var paymentReason by rememberSaveable { mutableStateOf("") }
    var retirePubkey by rememberSaveable { mutableStateOf<String?>(null) }
    var retireReason by rememberSaveable { mutableStateOf("") }
    var disqualifyPubkey by rememberSaveable { mutableStateOf<String?>(null) }
    var disqualifyReason by rememberSaveable { mutableStateOf("") }
    var editCompetition by rememberSaveable { mutableStateOf(false) }
    var editSubmitted by rememberSaveable { mutableStateOf(false) }
    var correctionOpen by rememberSaveable { mutableStateOf(false) }
    var correctionSeq by rememberSaveable { mutableStateOf<Int?>(null) }
    var correctionOutcome by rememberSaveable { mutableStateOf("") }
    var correctionReason by rememberSaveable { mutableStateOf("") }
    var correctionSubmitted by rememberSaveable { mutableStateOf(false) }
    val rejectedCorrections = state.rejected.filter { it.op == "correction" }.mapTo(mutableSetOf()) { it.seq }
    val latestCorrections = ui.snapshot.logEntries.filter {
        it.op == "correction" && it.seq !in rejectedCorrections
    }.mapNotNull { correction ->
        val target = (correction.data["supersedes_seq"] as? JsonPrimitive)?.content?.toIntOrNull()
        val replacement = correction.data["replacement"] as? JsonObject
        target?.let { it to replacement }
    }.toMap()
    val correctableAttempts = ui.snapshot.logEntries.filter {
        it.op in setOf("attempt_result", "complete_turn")
    }.map { entry ->
        val replacement = latestCorrections[entry.seq]
        if ((replacement?.get("op") as? JsonPrimitive)?.content == entry.op) {
            entry.copy(data = replacement.get("data") as? JsonObject ?: entry.data)
        } else entry
    }.sortedByDescending { it.seq }
    LaunchedEffect(editCompetition, editSubmitted, action) {
        if (editCompetition && editSubmitted && action is CompetitionDetailViewModel.Action.Sent) {
            editCompetition = false
            editSubmitted = false
        }
    }
    LaunchedEffect(correctionOpen, correctionSubmitted, action) {
        if (correctionOpen && correctionSubmitted && action is CompetitionDetailViewModel.Action.Sent) {
            correctionOpen = false
            correctionSubmitted = false
            correctionReason = ""
        }
    }
    if (editCompetition) HostCompetitionEditDialog(
        competition = competition,
        revision = state.configRevision + 1,
        working = action is CompetitionDetailViewModel.Action.Working,
        error = (action as? CompetitionDetailViewModel.Action.Failed)?.reason,
        impactOf = viewModel::configUpdateImpact,
        onDismiss = {
            if (action !is CompetitionDetailViewModel.Action.Working) {
                editCompetition = false
                editSubmitted = false
            }
        },
        onPublish = { patch, reason ->
            editSubmitted = true
            viewModel.hostUpdateConfig(patch, reason)
        },
    )
    if (confirmCancel) AlertDialog(
        onDismissRequest = { confirmCancel = false },
        title = { Text(stringResource(R.string.comp_host_cancel_title)) },
        text = { Text(stringResource(R.string.comp_host_cancel_body)) },
        confirmButton = {
            Button({ confirmCancel = false; viewModel.hostLifecycle("cancelled") }) {
                Text(stringResource(R.string.comp_host_cancel_confirm))
            }
        },
        dismissButton = { TextButton({ confirmCancel = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
    if (confirmFinish) AlertDialog(
        onDismissRequest = { confirmFinish = false },
        title = { Text(stringResource(R.string.comp_host_finish_title)) },
        text = { Text(stringResource(R.string.comp_host_finish_body)) },
        confirmButton = {
            Button({ confirmFinish = false; viewModel.hostLifecycle("finished") }) {
                Text(stringResource(R.string.comp_host_finish_confirm))
            }
        },
        dismissButton = { TextButton({ confirmFinish = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
    if (confirmSkip) AlertDialog(
        onDismissRequest = { confirmSkip = false },
        title = { Text(stringResource(R.string.comp_host_skip_title)) },
        text = { Text(stringResource(R.string.comp_host_skip_body)) },
        confirmButton = {
            Button({ confirmSkip = false; viewModel.hostSkipTurn() }) {
                Text(stringResource(R.string.comp_host_skip_confirm))
            }
        },
        dismissButton = { TextButton({ confirmSkip = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
    if (confirmCleanup) AlertDialog(
        onDismissRequest = { confirmCleanup = false },
        title = { Text(stringResource(R.string.comp_host_cleanup_confirm_title)) },
        text = { Text(stringResource(R.string.comp_host_cleanup_confirm_body)) },
        confirmButton = {
            Button({ confirmCleanup = false; viewModel.cleanupCompetition() }) {
                Text(stringResource(R.string.comp_host_cleanup_confirm))
            }
        },
        dismissButton = { TextButton({ confirmCleanup = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
    if (correctionOpen) {
        val selected = correctableAttempts.firstOrNull { it.seq == correctionSeq }
            ?: correctableAttempts.firstOrNull()
        val originalOutcome = (selected?.data?.get("outcome") as? JsonPrimitive)?.content.orEmpty()
        val chosenOutcome = correctionOutcome.ifBlank { originalOutcome }
        AlertDialog(
            onDismissRequest = {
                if (action !is CompetitionDetailViewModel.Action.Working) {
                    correctionOpen = false
                    correctionSubmitted = false
                    correctionReason = ""
                }
            },
            title = { Text(stringResource(R.string.comp_host_correction_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.comp_host_correction_body))
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
                        items(correctableAttempts, key = { it.seq }) { entry ->
                            val pubkey = (entry.data["pubkey"] as? JsonPrimitive)?.content.orEmpty()
                            val climbId = (entry.data["climb_id"] as? JsonPrimitive)?.content.orEmpty()
                            val attemptNo = (entry.data["attempt_no"] as? JsonPrimitive)?.content.orEmpty()
                            val entryOutcome = (entry.data["outcome"] as? JsonPrimitive)?.content.orEmpty()
                            val outcomeLabel = stringResource(attemptOutcomeLabel(entryOutcome))
                            val displayedOutcome = if (latestCorrections.containsKey(entry.seq)) {
                                stringResource(R.string.comp_host_correction_amended, outcomeLabel)
                            } else outcomeLabel
                            FilterChip(
                                selected = entry.seq == selected?.seq,
                                onClick = {
                                    correctionSeq = entry.seq
                                    correctionOutcome = entryOutcome
                                },
                                label = {
                                    Text(stringResource(
                                        R.string.comp_host_correction_attempt,
                                        state.participant(pubkey)?.displayOrShort() ?: pubkey.take(8),
                                        competition.climb(climbId)?.label ?: climbId,
                                        attemptNo,
                                        displayedOutcome,
                                    ))
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    Text(stringResource(R.string.comp_host_correction_outcome), fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CompetitionProtocol.ATTEMPT_OUTCOMES.forEach { outcome ->
                            FilterChip(
                                selected = chosenOutcome == outcome,
                                onClick = { correctionOutcome = outcome },
                                label = { Text(stringResource(attemptOutcomeLabel(outcome))) },
                            )
                        }
                    }
                    OutlinedTextField(
                        correctionReason,
                        { correctionReason = it },
                        label = { Text(stringResource(R.string.comp_host_correction_reason)) },
                        modifier = Modifier.fillMaxWidth().testTag("competition_host_correction_reason"),
                    )
                    if (state.status == "finished") Text(
                        stringResource(R.string.comp_host_correction_final_warning),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        correctionSubmitted = true
                        viewModel.hostCorrectResult(selected!!.seq, chosenOutcome, correctionReason)
                    },
                    enabled = selected != null && correctionReason.isNotBlank() &&
                        chosenOutcome != originalOutcome &&
                        action !is CompetitionDetailViewModel.Action.Working,
                    modifier = Modifier.testTag("competition_host_correction_publish"),
                ) { Text(stringResource(R.string.comp_host_correction_publish)) }
            },
            dismissButton = {
                TextButton(onClick = { correctionOpen = false; correctionReason = "" }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
    paymentPubkey?.let { pubkey ->
        val label = state.participant(pubkey)?.displayOrShort() ?: pubkey.take(12)
        AlertDialog(
            onDismissRequest = { paymentPubkey = null; paymentReason = "" },
            title = { Text(stringResource(R.string.comp_host_payment_title, label)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.comp_host_payment_body))
                    OutlinedTextField(
                        paymentReason,
                        { paymentReason = it },
                        label = { Text(stringResource(R.string.comp_host_payment_reason)) },
                        modifier = Modifier.fillMaxWidth().testTag("competition_host_payment_reason"),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.hostConfirmPayment(pubkey, paymentReason)
                        paymentPubkey = null
                        paymentReason = ""
                    },
                    enabled = paymentReason.isNotBlank() && action !is CompetitionDetailViewModel.Action.Working,
                    modifier = Modifier.testTag("competition_host_payment_confirm"),
                ) { Text(stringResource(R.string.comp_host_payment_confirm)) }
            },
            dismissButton = {
                TextButton({ paymentPubkey = null; paymentReason = "" }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
    retirePubkey?.let { pubkey ->
        val label = state.participant(pubkey)?.displayOrShort() ?: pubkey.take(12)
        AlertDialog(
            onDismissRequest = { retirePubkey = null; retireReason = "" },
            title = { Text(stringResource(R.string.comp_host_retire_title, label)) },
            text = {
                OutlinedTextField(
                    value = retireReason,
                    onValueChange = { retireReason = it },
                    label = { Text(stringResource(R.string.comp_host_retire_reason)) },
                    modifier = Modifier.fillMaxWidth().testTag("competition_host_retire_reason"),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.hostRetire(pubkey, retireReason)
                        retirePubkey = null
                        retireReason = ""
                    },
                    enabled = retireReason.isNotBlank() && action !is CompetitionDetailViewModel.Action.Working,
                    modifier = Modifier.testTag("competition_host_retire_confirm"),
                ) { Text(stringResource(R.string.comp_host_retire_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { retirePubkey = null; retireReason = "" }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
    disqualifyPubkey?.let { pubkey ->
        val label = state.participant(pubkey)?.displayOrShort() ?: pubkey.take(12)
        AlertDialog(
            onDismissRequest = { disqualifyPubkey = null; disqualifyReason = "" },
            title = { Text(stringResource(R.string.comp_host_disqualify_title, label)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.comp_host_disqualify_hint))
                    OutlinedTextField(
                        value = disqualifyReason,
                        onValueChange = { disqualifyReason = it },
                        label = { Text(stringResource(R.string.comp_host_disqualify_reason)) },
                        modifier = Modifier.fillMaxWidth().testTag("competition_host_disqualify_reason"),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.hostDisqualify(pubkey, disqualifyReason)
                        disqualifyPubkey = null
                        disqualifyReason = ""
                    },
                    enabled = disqualifyReason.isNotBlank() && action !is CompetitionDetailViewModel.Action.Working,
                    modifier = Modifier.testTag("competition_host_disqualify_confirm"),
                ) { Text(stringResource(R.string.comp_host_disqualify_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { disqualifyPubkey = null; disqualifyReason = "" }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
    val runningNow = CompetitionProtocol.competitionRunning(competition, state.status, nowSeconds)
    Card(Modifier.fillMaxWidth().testTag("competition_host_console")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (route == HostRoute.SETUP) {
            HostScheduleOverview(competition, state.status, nowSeconds)
            Text(
                stringResource(R.string.comp_host_automatic_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (ui.connectedRelays > 0) {
                    stringResource(R.string.comp_host_transport_live, ui.connectedRelays, state.seq)
                } else stringResource(R.string.comp_host_transport_offline, state.seq),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(
                    R.string.comp_host_counts,
                    state.participants.count { it.registration == "accepted" },
                    state.participants.count { it.checkin == "checked_in" },
                    ui.snapshot.pendingIntents.size,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = {
                    viewModel.clearAction()
                    editSubmitted = false
                    editCompetition = true
                },
                enabled = action !is CompetitionDetailViewModel.Action.Working,
                modifier = Modifier.fillMaxWidth().testTag("competition_host_edit"),
            ) { Text(stringResource(R.string.comp_edit_action)) }
            if (state.status !in listOf("finished", "cancelled")) {
                TextButton({ confirmCancel = true }) { Text(stringResource(R.string.comp_host_cancel_action)) }
            }
            if (state.status == "cancelled") {
                Text(
                    stringResource(R.string.comp_host_cleanup_explanation),
                    style = MaterialTheme.typography.bodySmall,
                )
                if ((cleanup as? CompetitionDetailViewModel.Cleanup.Sent)?.complete != true) {
                    Button(
                        onClick = { confirmCleanup = true },
                        enabled = cleanup !is CompetitionDetailViewModel.Cleanup.Working,
                        modifier = Modifier.fillMaxWidth().testTag("competition_host_cleanup"),
                    ) {
                        Text(stringResource(if (cleanup is CompetitionDetailViewModel.Cleanup.Idle) R.string.comp_host_cleanup_action else R.string.comp_host_cleanup_retry))
                    }
                }
                when (val result = cleanup) {
                    CompetitionDetailViewModel.Cleanup.Working -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    is CompetitionDetailViewModel.Cleanup.Sent -> Text(
                        stringResource(
                            if (result.complete) R.string.comp_host_cleanup_result_complete else R.string.comp_host_cleanup_result,
                            result.tombstoneAccepted,
                            result.deletionAccepted,
                            result.attempted,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    is CompetitionDetailViewModel.Cleanup.Failed -> Text(
                        stringResource(R.string.comp_host_cleanup_failed, result.reason),
                        color = MaterialTheme.colorScheme.error,
                    )
                    CompetitionDetailViewModel.Cleanup.Idle -> Unit
                }
            }
            }

            if (route != HostRoute.SETUP && !ui.snapshot.intentHistoryComplete) {
                Text(
                    stringResource(R.string.comp_intents_untrusted_title),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(stringResource(R.string.comp_intents_untrusted))
                Button(
                    onClick = viewModel::reload,
                    modifier = Modifier.fillMaxWidth().testTag("competition_host_retry_intents"),
                ) { Text(stringResource(R.string.action_retry)) }
                return@Column
            }

            if (route == HostRoute.LIVE) {
                val next = if (state.status == "paused") "running" else null
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    next?.let { target ->
                        Button(
                            { viewModel.hostLifecycle(target) },
                            Modifier.testTag("competition_host_lifecycle"),
                            enabled = action !is CompetitionDetailViewModel.Action.Working,
                        ) { Text(hostLifecycleLabel(target)) }
                    }
                    if (runningNow) OutlinedButton({ viewModel.hostLifecycle("paused") }) {
                        Text(stringResource(R.string.comp_host_pause))
                    }
                    if (runningNow || state.status == "paused") {
                        OutlinedButton({ confirmFinish = true }) {
                            Text(stringResource(R.string.comp_host_finish))
                        }
                    }
                }
            }

            if (route == HostRoute.ENTRANTS) {
            Text(stringResource(R.string.comp_host_entrants_title), fontWeight = FontWeight.Bold)
            ui.snapshot.pendingIntents.filter { it.op == "register" }.forEach { intent ->
                val display = (intent.data["display"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                val division = (intent.data["division"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                Column(Modifier.fillMaxWidth()) {
                    Text(display.ifBlank { "${intent.pubkey.take(12)}…" }, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.comp_host_new_registration, division), style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button({ viewModel.hostRegistration(intent.pubkey, "accepted", intent.eventId, division, display) }) { Text(stringResource(R.string.comp_host_accept)) }
                        OutlinedButton({ viewModel.hostRegistration(intent.pubkey, "waitlisted", intent.eventId, division, display) }) { Text(stringResource(R.string.comp_host_waitlist)) }
                        OutlinedButton({ viewModel.hostRegistration(intent.pubkey, "rejected", intent.eventId, division, display) }) { Text(stringResource(R.string.comp_host_reject)) }
                    }
                }
            }
            if (state.participants.isEmpty()) Text(stringResource(R.string.comp_host_no_entrants))
            state.participants.forEach { participant ->
                Column(Modifier.fillMaxWidth()) {
                    Text(participant.display.ifBlank { "${participant.pubkey.take(12)}…" }, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${stringResource(registrationLabel(participant.registration))} · " +
                            stringResource(checkinLabel(participant.checkin)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (competition.feeMsat > 0) {
                        Text(
                            stringResource(paymentLabel(participant.payment)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (participant.registration in listOf("pending", "waitlisted")) {
                            Button({ viewModel.hostRegistration(participant.pubkey, "accepted") }) { Text(stringResource(R.string.comp_host_accept)) }
                            OutlinedButton({ viewModel.hostRegistration(participant.pubkey, "rejected") }) { Text(stringResource(R.string.comp_host_reject)) }
                        }
                        if (participant.registration == "accepted" && participant.checkin == "none") {
                            Button({ viewModel.hostCheckIn(participant.pubkey, true) }) { Text(stringResource(R.string.comp_host_check_in)) }
                            OutlinedButton({ viewModel.hostCheckIn(participant.pubkey, false) }) { Text(stringResource(R.string.comp_host_no_show)) }
                        }
                        if (participant.result == "active" && participant.registration == "accepted") {
                            if (competition.feeMsat > 0 && participant.payment != "settled") {
                                val paymentClaim = ui.snapshot.pendingIntents.firstOrNull { intent ->
                                    intent.op == "payment_claim" && intent.pubkey == participant.pubkey
                                }
                                val receiptId = (paymentClaim?.data?.get("zap_receipt_id") as? JsonPrimitive)
                                    ?.content.orEmpty()
                                if (paymentClaim != null && receiptId.isNotBlank()) {
                                    Button(
                                        onClick = { viewModel.hostVerifyPayment(paymentClaim) },
                                        enabled = paymentReviews[participant.pubkey] !is
                                            CompetitionDetailViewModel.HostPaymentReview.Working,
                                        modifier = Modifier.testTag(
                                            "competition_host_payment_verify_${participant.pubkey.take(8)}",
                                        ),
                                    ) { Text(stringResource(R.string.comp_host_payment_verify)) }
                                }
                                OutlinedButton(
                                    onClick = { paymentPubkey = participant.pubkey },
                                    modifier = Modifier.testTag("competition_host_payment_${participant.pubkey.take(8)}"),
                                ) { Text(stringResource(R.string.comp_host_payment_manual)) }
                                when (val review = paymentReviews[participant.pubkey]) {
                                    CompetitionDetailViewModel.HostPaymentReview.Working ->
                                        CircularProgressIndicator(Modifier.size(24.dp))
                                    is CompetitionDetailViewModel.HostPaymentReview.Verified -> Text(
                                        stringResource(
                                            if (review.weaklyBound) R.string.comp_host_payment_verified_weak
                                            else R.string.comp_host_payment_verified,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    is CompetitionDetailViewModel.HostPaymentReview.Failed -> Text(
                                        paymentReceiptErrorText(review.error),
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    null -> Unit
                                }
                            }
                            OutlinedButton(
                                onClick = { retirePubkey = participant.pubkey },
                                modifier = Modifier.testTag("competition_host_retire_${participant.pubkey.take(8)}"),
                            ) { Text(stringResource(R.string.comp_host_retire)) }
                            OutlinedButton(
                                onClick = { disqualifyPubkey = participant.pubkey },
                                modifier = Modifier.testTag("competition_host_disqualify_${participant.pubkey.take(8)}"),
                            ) { Text(stringResource(R.string.comp_host_disqualify)) }
                        }
                    }
                }
            }
            }

            if (route != HostRoute.SETUP) ui.snapshot.pendingIntents.filter { intent ->
                hostIntentDestination(intent.op) == route.name && intent.op !in setOf(
                    "register", "payment_claim", "prize_claim", "prize_receipt",
                )
            }.forEach { intent ->
                val participant = state.participant(intent.pubkey)
                val label = participant?.display?.ifBlank { null } ?: "${intent.pubkey.take(12)}…"
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("$label · ${hostRequestLabel(intent.op)}")
                    when (intent.op) {
                        "checkin_request" -> Button({ viewModel.hostCheckIn(intent.pubkey, true, intent.eventId) }) { Text(stringResource(R.string.comp_host_confirm)) }
                        "withdraw" -> Button({ viewModel.hostWithdraw(intent.pubkey, intent.eventId) }) { Text(stringResource(R.string.comp_host_confirm_withdrawal)) }
                        "defer_request" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Button({ viewModel.hostDefer(intent.pubkey, "granted", intent.eventId) }) { Text(stringResource(R.string.comp_host_grant)) }
                            OutlinedButton({ viewModel.hostDefer(intent.pubkey, "denied", intent.eventId) }) { Text(stringResource(R.string.comp_host_reject)) }
                        }
                        "attempt_report" -> {
                            val climbId = (intent.data["climb_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                            val outcome = (intent.data["outcome"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                            Button({ viewModel.hostAttempt(intent.pubkey, climbId, outcome, intent.eventId) }) { Text(stringResource(R.string.comp_host_record)) }
                        }
                        "climb_choice" -> {
                            val climbId = (intent.data["climb_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                            val climb = competition.climbPool.firstOrNull { it.id == climbId }
                            val progress = participant?.climb(climbId)
                            val remaining = participant?.registration == "accepted" &&
                                participant.checkin == "checked_in" && participant.result == "active" &&
                                (competition.feeMsat == 0L || participant.payment == "settled") &&
                                climb != null && progress?.outcome != "top" &&
                                (progress?.attemptsUsed ?: 0) < competition.rules.attemptsPerClimb
                            Text(
                                if (remaining) stringResource(R.string.comp_host_prepared_choice, climb?.label.orEmpty())
                                else stringResource(R.string.comp_host_choice_unavailable),
                                color = if (remaining) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.testTag("competition_host_choice_${intent.pubkey.take(8)}"),
                            )
                        }
                    }
                }
            }

            if (route == HostRoute.ENTRANTS) {
                CompetitionHostPrizePanel(ui, viewModel, action)
            }

            if (route == HostRoute.LIVE) {
            if (CompetitionProtocol.checkinWindowOpen(competition, state.status, nowSeconds) || runningNow || state.status == "paused") {
                Text(stringResource(R.string.comp_host_live_controls), fontWeight = FontWeight.Bold)
                if (state.order.isEmpty()) Button(viewModel::hostSeed, Modifier.testTag("competition_host_seed")) { Text(stringResource(R.string.comp_host_seed_queue)) }
                else {
                    val current = state.order.getOrNull(state.cursor)
                    val currentParticipant = current?.let(state::participant)
                    val currentChoice = ui.snapshot.pendingIntents.firstOrNull {
                        it.op == "climb_choice" && it.pubkey == current
                    }
                    val chosenClimbId =
                        (currentChoice?.data?.get("climb_id") as? kotlinx.serialization.json.JsonPrimitive)
                            ?.content.orEmpty()
                    val chosenClimb = chosenClimbId.takeIf(String::isNotBlank)?.let(competition::climb)
                    val chosenProgress = chosenClimbId.takeIf(String::isNotBlank)
                        ?.let { currentParticipant?.climb(it) }
                    val choiceAvailable = chosenClimb != null && currentParticipant?.registration == "accepted" &&
                        currentParticipant.checkin == "checked_in" && currentParticipant.result == "active" &&
                        (competition.feeMsat == 0L || currentParticipant.payment == "settled") &&
                        chosenProgress?.outcome != "top" &&
                        (chosenProgress?.attemptsUsed ?: 0) < competition.rules.attemptsPerClimb
                    val activeClimbId = if (competition.rules.climbSource == "participant_choice") {
                        chosenClimbId.takeIf { choiceAvailable }.orEmpty()
                    } else state.currentClimbId
                    val activeClimb = activeClimbId.takeIf(String::isNotBlank)?.let(competition::climb)
                    Text(
                        stringResource(
                            R.string.comp_host_turn,
                            current?.let { key -> state.participant(key)?.display?.ifBlank { key.take(12) } }
                                ?: stringResource(R.string.comp_host_turn_not_open),
                        ),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (state.cursor < 0) Button({ viewModel.hostQueue("open_turn", 0) }) { Text(stringResource(R.string.comp_host_open_first_turn)) }
                        else Button({ confirmSkip = true }, Modifier.testTag("competition_host_skip_turn")) {
                            Text(stringResource(R.string.comp_host_skip_turn))
                        }
                    }
                    if (competition.rules.climbSource == "participant_choice") {
                        Text(
                            when {
                                activeClimb != null -> stringResource(R.string.comp_host_prepared_choice, activeClimb.label)
                                currentChoice != null -> stringResource(R.string.comp_host_choice_unavailable)
                                else -> stringResource(R.string.comp_host_awaiting_current_choice)
                            },
                            color = if (activeClimb == null) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (runningNow && current != null && activeClimbId.isNotBlank()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button({ viewModel.hostAttempt(current, activeClimbId, "top", currentChoice?.eventId) }) { Text(stringResource(R.string.comp_host_result_top)) }
                            OutlinedButton({ viewModel.hostAttempt(current, activeClimbId, "zone", currentChoice?.eventId) }) { Text(stringResource(R.string.comp_host_result_zone)) }
                            OutlinedButton({ viewModel.hostAttempt(current, activeClimbId, "fall", currentChoice?.eventId) }) { Text(stringResource(R.string.comp_host_result_fall)) }
                            if (ui.secondsToDeadline(nowSeconds) == 0L) {
                                OutlinedButton({ viewModel.hostAttempt(current, activeClimbId, "timeout", currentChoice?.eventId) }) { Text(stringResource(R.string.comp_host_result_timeout)) }
                            }
                        }
                    }
                    if (state.currentClimbId.isBlank() && competition.climbs.isNotEmpty()) {
                        Button({ viewModel.hostQueue("next_climb", climbId = competition.climbs.first().id) }) {
                            Text(stringResource(R.string.comp_host_start_climb, competition.climbs.first().label))
                        }
                    }
                }
            }

            OutlinedTextField(announcement, { announcement = it }, label = { Text(stringResource(R.string.comp_host_announcement)) }, modifier = Modifier.fillMaxWidth())
            OutlinedButton({ viewModel.hostAnnounce(announcement); announcement = "" }, enabled = announcement.isNotBlank()) { Text(stringResource(R.string.comp_host_publish)) }
            if (state.audit.any { it.supersedesResults }) WarningCard(stringResource(R.string.comp_results_amended))
            if (correctableAttempts.isNotEmpty()) {
                OutlinedButton(
                    onClick = {
                        correctionSeq = correctableAttempts.first().seq
                        correctionOutcome = (correctableAttempts.first().data["outcome"] as? JsonPrimitive)
                            ?.content.orEmpty()
                        correctionOpen = true
                    },
                    modifier = Modifier.fillMaxWidth().testTag("competition_host_correction_open"),
                ) { Text(stringResource(R.string.comp_host_correction_open)) }
            }
            }
            when (action) {
                is CompetitionDetailViewModel.Action.Failed -> Text(
                    competitionActionError(action.reason), color = MaterialTheme.colorScheme.error,
                )
                is CompetitionDetailViewModel.Action.Sent -> Text(stringResource(R.string.comp_host_published_relays, action.accepted))
                is CompetitionDetailViewModel.Action.Working -> LinearProgressIndicator(Modifier.fillMaxWidth())
                else -> Unit
            }
        }
    }
}

@Composable
private fun HostCompetitionEditDialog(
    competition: Competition,
    revision: Int,
    working: Boolean,
    error: String?,
    impactOf: (JsonObject) -> String?,
    onDismiss: () -> Unit,
    onPublish: (JsonObject, String) -> Unit,
) {
    val venue = (competition.raw["venue"] as? JsonObject)
        ?.get("name")?.let { it as? JsonPrimitive }?.content.orEmpty()
    val initialClimbs = competitionClimbsEditorText(
        if (competition.rules.climbSource == "participant_choice") competition.climbPool else competition.climbs,
    )
    var title by rememberSaveable(competition.revision) { mutableStateOf(competition.title) }
    var summary by rememberSaveable(competition.revision) { mutableStateOf(competition.summary) }
    var venueName by rememberSaveable(competition.revision) { mutableStateOf(venue) }
    var capacity by rememberSaveable(competition.revision) { mutableStateOf(competition.capacity.toString()) }
    var divisions by rememberSaveable(competition.revision) { mutableStateOf(competition.divisions.joinToString("\n") { "${it.id} | ${it.label}" }) }
    var feeSats by rememberSaveable(competition.revision) { mutableStateOf((competition.feeMsat / 1_000L).toString()) }
    var feeLnurl by rememberSaveable(competition.revision) { mutableStateOf(competition.feeLnurl.orEmpty()) }
    var eligibility by rememberSaveable(competition.revision) { mutableStateOf((competition.raw["eligibility"] as? JsonPrimitive)?.content.orEmpty()) }
    var waiver by rememberSaveable(competition.revision) { mutableStateOf((competition.raw["waiver"] as? JsonPrimitive)?.content.orEmpty()) }
    var participantInstructions by rememberSaveable(competition.revision) { mutableStateOf((competition.raw["participant_instructions"] as? JsonPrimitive)?.content.orEmpty()) }
    var spectatorInfo by rememberSaveable(competition.revision) { mutableStateOf((competition.raw["spectator_info"] as? JsonPrimitive)?.content.orEmpty()) }
    var refundPolicy by rememberSaveable(competition.revision) { mutableStateOf((competition.raw["refund_policy"] as? JsonPrimitive)?.content.orEmpty()) }
    var prizes by rememberSaveable(competition.revision) { mutableStateOf(competition.prizes.joinToString("\n") {
        listOf(it.id, it.rank.toString(), it.kind, it.label, (it.valueMsat / 1_000L).toString(), it.division.orEmpty()).joinToString(" | ")
    }) }
    var prizeClaimDays by rememberSaveable(competition.revision) { mutableStateOf(competition.prizeClaimDays.toString()) }
    var timezone by rememberSaveable(competition.revision) { mutableStateOf(competition.timezone) }
    var registrationOpensAt by rememberSaveable(competition.revision) { mutableStateOf(CompetitionCreateDraftPolicy.formatLocalMinute(competition.registrationOpensAt, competition.timezone).orEmpty()) }
    var registrationClosesAt by rememberSaveable(competition.revision) { mutableStateOf(CompetitionCreateDraftPolicy.formatLocalMinute(competition.registrationClosesAt, competition.timezone).orEmpty()) }
    var checkinOpensAt by rememberSaveable(competition.revision) { mutableStateOf(CompetitionCreateDraftPolicy.formatLocalMinute(competition.checkinOpensAt, competition.timezone).orEmpty()) }
    var checkinClosesAt by rememberSaveable(competition.revision) { mutableStateOf(CompetitionCreateDraftPolicy.formatLocalMinute(competition.checkinClosesAt, competition.timezone).orEmpty()) }
    var startsAt by rememberSaveable(competition.revision) { mutableStateOf(CompetitionCreateDraftPolicy.formatLocalMinute(competition.startsAt, competition.timezone).orEmpty()) }
    var endsAt by rememberSaveable(competition.revision) { mutableStateOf(CompetitionCreateDraftPolicy.formatLocalMinute(competition.endsAt, competition.timezone).orEmpty()) }
    var attempts by rememberSaveable(competition.revision) { mutableStateOf(competition.rules.attemptsPerClimb.toString()) }
    var climbCount by rememberSaveable(competition.revision) { mutableStateOf(competition.rules.climbCount.toString()) }
    var countedClimbCount by rememberSaveable(competition.revision) { mutableStateOf(competition.rules.countedClimbCount.toString()) }
    var progression by rememberSaveable(competition.revision) { mutableStateOf(competition.rules.progression) }
    var scoring by rememberSaveable(competition.revision) { mutableStateOf(competition.rules.scoring) }
    var zonePoints by rememberSaveable(competition.revision) { mutableStateOf((competition.rules.scorePoints?.zone ?: 10).toString()) }
    var topPoints by rememberSaveable(competition.revision) { mutableStateOf((competition.rules.scorePoints?.top ?: 25).toString()) }
    var flashPoints by rememberSaveable(competition.revision) { mutableStateOf((competition.rules.scorePoints?.flash ?: 5).toString()) }
    var waitlistEnabled by rememberSaveable(competition.revision) { mutableStateOf(competition.waitlistEnabled) }
    var lateEntryAllowed by rememberSaveable(competition.revision) { mutableStateOf(competition.rules.lateEntryAllowed) }
    var climbText by rememberSaveable(competition.revision) { mutableStateOf(initialClimbs) }
    var reason by rememberSaveable(competition.revision) { mutableStateOf("") }

    val schedule = CompetitionCreateDraftPolicy.schedule(
        timezone, registrationOpensAt, registrationClosesAt,
        checkinOpensAt, checkinClosesAt, startsAt, endsAt,
    )
    val parsedDivisions = CompetitionCreateDraftPolicy.parseDivisions(
        CompetitionCreateViewModel.Draft(divisions = divisions),
    )
    val parsedFeeSats = feeSats.toLongOrNull()
    val parsedPrizes = CompetitionCreateDraftPolicy.parsePrizes(
        CompetitionCreateViewModel.Draft(divisions = divisions, prizes = prizes),
        parsedDivisions.orEmpty(),
    )?.map { candidate ->
        val id = (candidate["id"] as JsonPrimitive).content
        val previous = (competition.raw["prizes"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { (it["id"] as? JsonPrimitive)?.content == id }
        JsonObject(previous.orEmpty() + candidate)
    }
    val parsedPrizeClaimDays = prizeClaimDays.toIntOrNull()

    val climbPatch = if (climbText == initialClimbs) JsonObject(emptyMap())
    else competitionClimbsEditorPatch(
        competition, climbText, countedClimbCount.toIntOrNull() ?: competition.rules.countedClimbCount,
    )

    val patch = JsonObject(buildMap {
        title.trim().takeIf { it != competition.title }?.let { put("title", JsonPrimitive(it)) }
        summary.trim().takeIf { it != competition.summary }?.let { put("summary", JsonPrimitive(it)) }
        venueName.trim().takeIf { it != venue }?.let {
            put("venue", JsonObject(mapOf("name" to JsonPrimitive(it))))
        }
        capacity.toIntOrNull()?.takeIf { it != competition.capacity }?.let { put("capacity", JsonPrimitive(it)) }
        parsedDivisions?.takeIf { candidate ->
            candidate.map { (it["id"] as JsonPrimitive).content to (it["label"] as JsonPrimitive).content } !=
                competition.divisions.map { it.id to it.label }
        }?.let { put("divisions", JsonArray(it)) }
        parsedFeeSats?.times(1_000L)?.takeIf { it != competition.feeMsat }?.let {
            put("fee_msat", JsonPrimitive(it))
            if (it == 0L) put("fee_lnurl", kotlinx.serialization.json.JsonNull)
        }
        if ((parsedFeeSats ?: 0L) > 0 && feeLnurl.trim() != competition.feeLnurl.orEmpty()) {
            put("fee_lnurl", JsonPrimitive(feeLnurl.trim()))
        }
        parsedPrizes?.takeIf { it != (competition.raw["prizes"] as? JsonArray)?.toList().orEmpty() }
            ?.let { put("prizes", JsonArray(it)) }
        parsedPrizeClaimDays?.takeIf { it != competition.prizeClaimDays }
            ?.let { put("prize_claim_days", JsonPrimitive(it)) }
        listOf(
            "eligibility" to eligibility, "waiver" to waiver,
            "participant_instructions" to participantInstructions,
            "spectator_info" to spectatorInfo, "refund_policy" to refundPolicy,
        ).forEach { (field, value) ->
            val previous = (competition.raw[field] as? JsonPrimitive)?.content.orEmpty()
            value.trim().takeIf { it != previous }?.let { put(field, JsonPrimitive(it)) }
        }
        schedule?.let { value ->
            value.timezone.takeIf { it != competition.timezone }?.let { put("timezone", JsonPrimitive(it)) }
            listOf(
                "registration_opens_at" to value.registrationOpensAt,
                "registration_closes_at" to value.registrationClosesAt,
                "checkin_opens_at" to value.checkinOpensAt,
                "checkin_closes_at" to value.checkinClosesAt,
                "starts_at" to value.startsAt,
                "ends_at" to value.endsAt,
            ).forEach { (field, epoch) ->
                val previous = when (field) {
                    "registration_opens_at" -> competition.registrationOpensAt
                    "registration_closes_at" -> competition.registrationClosesAt
                    "checkin_opens_at" -> competition.checkinOpensAt
                    "checkin_closes_at" -> competition.checkinClosesAt
                    "starts_at" -> competition.startsAt
                    else -> competition.endsAt
                }
                if (epoch != previous) put(field, JsonPrimitive(epoch))
            }
        }
        if (waitlistEnabled != competition.waitlistEnabled) put("waitlist_enabled", JsonPrimitive(waitlistEnabled))
        attempts.toIntOrNull()?.takeIf { it != competition.rules.attemptsPerClimb }?.let {
            put("rules", JsonObject(mapOf("attempts_per_climb" to JsonPrimitive(it))))
        }
        val rules = (get("rules") as? JsonObject).orEmpty().toMutableMap()
        climbCount.toIntOrNull()?.takeIf { it != competition.rules.climbCount }
            ?.let { rules["climb_count"] = JsonPrimitive(it) }
        countedClimbCount.toIntOrNull()?.takeIf { it != competition.rules.countedClimbCount }
            ?.let { rules["counted_climb_count"] = JsonPrimitive(it) }
        progression.takeIf { it != competition.rules.progression }
            ?.let { rules["progression"] = JsonPrimitive(it) }
        scoring.takeIf { it != competition.rules.scoring }
            ?.let { rules["scoring"] = JsonPrimitive(it) }
        if (scoring == "achievement_points") {
            val points = JsonObject(mapOf(
                "zone" to JsonPrimitive(zonePoints.toIntOrNull() ?: -1),
                "top" to JsonPrimitive(topPoints.toIntOrNull() ?: -1),
                "flash" to JsonPrimitive(flashPoints.toIntOrNull() ?: -1),
            ))
            val previous = competition.raw["rules"]?.let { it as? JsonObject }?.get("score_points")
            if (points != previous) rules["score_points"] = points
        }
        lateEntryAllowed.takeIf { it != competition.rules.lateEntryAllowed }
            ?.let { rules["late_entry_allowed"] = JsonPrimitive(it) }
        if (rules.isNotEmpty()) put("rules", JsonObject(rules))
        climbPatch?.forEach { (key, value) ->
            if (key == "rules" && value is JsonObject) {
                val current = get("rules") as? JsonObject
                put("rules", JsonObject(current.orEmpty() + value))
            } else {
                put(key, value)
            }
        }
    })
    val impact = impactOf(patch)
    val availableClimbs = climbText.lineSequence().count(String::isNotBlank)
    val parsedClimbCount = climbCount.toIntOrNull()
    val parsedCounted = countedClimbCount.toIntOrNull()
    val parsedScorePoints = listOf(zonePoints, topPoints, flashPoints).map(String::toIntOrNull)
    val valid = title.isNotBlank() && schedule != null && parsedDivisions != null && parsedPrizes != null &&
        parsedPrizeClaimDays in 1..365 &&
        parsedFeeSats in 0L..1_000_000L && (parsedFeeSats == 0L || feeLnurl.isNotBlank()) &&
        (!competition.waiverRequired || waiver.isNotBlank()) &&
        listOf(eligibility, waiver, participantInstructions, spectatorInfo, refundPolicy).none { it.length > 2_000 } &&
        capacity.toIntOrNull() in 0..500 &&
        attempts.toIntOrNull() in 1..20 && parsedClimbCount in 1..minOf(availableClimbs, 40) &&
        parsedCounted in 1..(parsedClimbCount ?: 0) &&
        !(competition.rules.climbSource == "participant_choice" && scoring in setOf("points_sum", "hardest_n")) &&
        (scoring != "achievement_points" || (parsedScorePoints.all { it != null && it in 0..10_000 } &&
            parsedScorePoints.filterNotNull().any { it > 0 })) &&
        reason.isNotBlank() && impact != null && climbPatch != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.comp_edit_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.comp_edit_revision, revision), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.comp_edit_field_title)) }, singleLine = true)
                OutlinedTextField(summary, { summary = it }, label = { Text(stringResource(R.string.comp_edit_field_summary)) })
                OutlinedTextField(venueName, { venueName = it }, label = { Text(stringResource(R.string.comp_edit_field_venue)) }, singleLine = true)
                OutlinedTextField(capacity, { capacity = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.comp_edit_field_capacity)) }, singleLine = true)
                OutlinedTextField(divisions, { divisions = it }, label = { Text(stringResource(R.string.comp_create_field_divisions)) }, supportingText = { Text(stringResource(R.string.comp_create_divisions_hint)) }, minLines = 2)
                OutlinedTextField(feeSats, { feeSats = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.comp_create_field_fee)) }, singleLine = true)
                if ((parsedFeeSats ?: 0L) > 0) {
                    OutlinedTextField(feeLnurl, { feeLnurl = it }, label = { Text(stringResource(R.string.comp_create_field_fee_lnurl)) }, singleLine = true)
                    Text(stringResource(R.string.comp_money_no_custody_organizer), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(prizes, { prizes = it }, label = { Text(stringResource(R.string.comp_create_field_prizes)) }, supportingText = { Text(stringResource(R.string.comp_create_prizes_hint)) }, minLines = 3)
                OutlinedTextField(prizeClaimDays, { prizeClaimDays = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.comp_create_field_prize_claim_days)) }, singleLine = true)
                if (prizes.isNotBlank()) Text(stringResource(R.string.comp_prize_not_funded), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(eligibility, { eligibility = it }, label = { Text(stringResource(R.string.comp_create_field_eligibility)) })
                OutlinedTextField(participantInstructions, { participantInstructions = it }, label = { Text(stringResource(R.string.comp_create_field_instructions)) })
                OutlinedTextField(spectatorInfo, { spectatorInfo = it }, label = { Text(stringResource(R.string.comp_create_field_spectator)) })
                OutlinedTextField(refundPolicy, { refundPolicy = it }, label = { Text(stringResource(R.string.comp_create_field_refund)) })
                if (competition.waiverRequired) OutlinedTextField(waiver, { waiver = it }, label = { Text(stringResource(R.string.comp_create_field_waiver)) })
                Text(stringResource(R.string.comp_create_schedule_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(timezone, { timezone = it }, label = { Text(stringResource(R.string.comp_create_field_timezone)) }, singleLine = true)
                OutlinedTextField(registrationOpensAt, { registrationOpensAt = it }, label = { Text(stringResource(R.string.comp_create_field_registration_opens)) }, singleLine = true)
                OutlinedTextField(registrationClosesAt, { registrationClosesAt = it }, label = { Text(stringResource(R.string.comp_create_field_registration_closes)) }, singleLine = true)
                OutlinedTextField(checkinOpensAt, { checkinOpensAt = it }, label = { Text(stringResource(R.string.comp_create_field_checkin_opens)) }, singleLine = true)
                OutlinedTextField(checkinClosesAt, { checkinClosesAt = it }, label = { Text(stringResource(R.string.comp_create_field_checkin_closes)) }, singleLine = true)
                OutlinedTextField(startsAt, { startsAt = it }, label = { Text(stringResource(R.string.comp_create_field_starts_at)) }, singleLine = true)
                OutlinedTextField(endsAt, { endsAt = it }, label = { Text(stringResource(R.string.comp_create_field_ends_at)) }, singleLine = true, isError = schedule == null)
                OutlinedTextField(attempts, { attempts = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.comp_edit_field_attempts)) }, singleLine = true)
                OutlinedTextField(climbCount, { climbCount = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.comp_create_field_climb_count)) }, singleLine = true)
                OutlinedTextField(countedClimbCount, { countedClimbCount = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.comp_create_field_counted_climbs)) }, singleLine = true)
                EditChoice(
                    stringResource(R.string.comp_create_field_progression), progression,
                    listOf(
                        "asynchronous_turns" to stringResource(R.string.comp_create_progression_async),
                        "synchronous_rounds" to stringResource(R.string.comp_create_progression_sync),
                    ),
                ) { progression = it }
                EditChoice(
                    stringResource(R.string.comp_create_field_scoring), scoring,
                    buildList {
                        add("tops_then_attempts" to stringResource(R.string.comp_create_scoring_tops))
                        add("achievement_points" to stringResource(R.string.comp_create_scoring_achievement))
                        if (competition.rules.climbSource == "organizer_set") {
                            add("points_sum" to stringResource(R.string.comp_create_scoring_points))
                            add("hardest_n" to stringResource(R.string.comp_create_scoring_hardest))
                        }
                    },
                ) { scoring = it }
                if (scoring == "achievement_points") {
                    OutlinedTextField(zonePoints, { zonePoints = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.comp_create_field_zone_points)) }, singleLine = true)
                    OutlinedTextField(topPoints, { topPoints = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.comp_create_field_top_points)) }, singleLine = true)
                    OutlinedTextField(flashPoints, { flashPoints = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.comp_create_field_flash_points)) }, singleLine = true)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.comp_create_waitlist), Modifier.weight(1f))
                    Checkbox(waitlistEnabled, { waitlistEnabled = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.comp_create_late_entry), Modifier.weight(1f))
                    Checkbox(lateEntryAllowed, { lateEntryAllowed = it })
                }
                OutlinedTextField(
                    climbText, { climbText = it },
                    label = { Text(stringResource(R.string.comp_edit_field_climbs)) },
                    supportingText = {
                        Text(stringResource(if (climbPatch == null) R.string.comp_edit_climbs_invalid else R.string.comp_edit_climbs_hint))
                    },
                    minLines = 5,
                    isError = climbPatch == null,
                    modifier = Modifier.fillMaxWidth().testTag("competition_edit_climbs"),
                )
                Card(colors = CardDefaults.cardColors(
                    containerColor = if (impact == "scoring") MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.secondaryContainer,
                )) {
                    Text(
                        stringResource(when (impact) {
                            "scoring" -> R.string.comp_edit_impact_scoring
                            "safe" -> R.string.comp_edit_impact_safe
                            else -> R.string.comp_edit_impact_none
                        }),
                        Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(
                    reason, { reason = it }, label = { Text(stringResource(R.string.comp_edit_reason)) },
                    supportingText = { Text(stringResource(R.string.comp_edit_reason_hint)) },
                )
                if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error != null) {
                    Text(
                        competitionActionError(error),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                }
            }
        },
        confirmButton = {
            Button({ onPublish(patch, reason.trim()) }, enabled = valid && !working) {
                Text(stringResource(R.string.comp_edit_publish))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !working) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

internal fun competitionClimbsEditorText(climbs: List<CompetitionClimb>): String = climbs.joinToString("\n") { climb ->
    listOfNotNull(climb.climbUuid, climb.label, climb.points.toString(), climb.zoneHold?.toString()).joinToString(" | ")
}

/** Parse the compact editor while retaining stable IDs and unknown signed metadata for existing rows. */
internal fun competitionClimbsEditorPatch(
    competition: Competition,
    text: String,
    countedClimbCount: Int = competition.rules.countedClimbCount,
): JsonObject? {
    val participantChoice = competition.rules.climbSource == "participant_choice"
    val existing = if (participantChoice) competition.climbPool else competition.climbs
    val rawEntries = if (participantChoice) {
        ((competition.raw["climb_pool"] as? JsonObject)?.get("options") as? JsonArray).orEmpty()
    } else {
        (competition.raw["climbs"] as? JsonArray).orEmpty()
    }.mapNotNull { it as? JsonObject }
    val boardAngle = ((competition.raw["board"] as? JsonObject)?.get("angle") as? JsonPrimitive)
        ?.content?.toIntOrNull() ?: return null
    val usedIds = existing.mapTo(mutableSetOf()) { it.id }
    var nextId = 1
    fun freshId(): String {
        while ("edit$nextId" in usedIds) nextId += 1
        return "edit${nextId++}".also(usedIds::add)
    }
    val seen = mutableSetOf<String>()
    val entries = text.lineSequence().filter(String::isNotBlank).toList().map { line ->
        val parts = line.split('|').map(String::trim)
        val rawUuid = parts.getOrNull(0).orEmpty().lowercase()
        val uuidMatch = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
            .find(rawUuid)?.value
        val uuid = uuidMatch ?: rawUuid
        if (!CompetitionProtocol.isClimbUuid(uuid) || !seen.add(uuid)) return null
        val prior = existing.firstOrNull { it.climbUuid.equals(uuid, ignoreCase = true) }
        val label = parts.getOrNull(1)?.takeIf(String::isNotBlank) ?: prior?.label ?: uuid.take(8)
        val pointsText = parts.getOrNull(2).orEmpty()
        val points = pointsText.takeIf(String::isNotBlank)?.toIntOrNull()
        if (pointsText.isNotBlank() && (points == null || points < 0)) return null
        val zoneText = parts.getOrNull(3).orEmpty()
        val zone = zoneText.takeIf(String::isNotBlank)?.toIntOrNull()
        if (zoneText.isNotBlank() && (zone == null || zone <= 0)) return null
        val raw = rawEntries.firstOrNull { entry ->
            (entry["id"] as? JsonPrimitive)?.content == prior?.id
        }?.toMutableMap() ?: mutableMapOf()
        raw["id"] = JsonPrimitive(prior?.id ?: freshId())
        raw["climb_uuid"] = JsonPrimitive(uuid)
        raw["angle"] = JsonPrimitive(prior?.angle ?: boardAngle)
        raw["label"] = JsonPrimitive(label)
        raw["points"] = JsonPrimitive(points ?: prior?.points ?: 0)
        if (zone == null) raw.remove("zone_hold") else raw["zone_hold"] = JsonPrimitive(zone)
        JsonObject(raw)
    }.toList()
    if (entries.isEmpty() || entries.size > 40 || entries.size < countedClimbCount) return null

    return if (participantChoice) {
        JsonObject(mapOf("climb_pool" to JsonObject(mapOf("options" to JsonArray(entries)))))
    } else {
        JsonObject(mapOf("climbs" to JsonArray(entries)))
    }
}

@Composable
private fun EditChoice(
    label: String,
    selected: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) = Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(label, fontWeight = FontWeight.SemiBold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (value, text) ->
            FilterChip(selected == value, { onSelect(value) }, label = { Text(text) })
        }
    }
}

@Composable
private fun competitionActionError(reason: String): String = when (reason) {
    "reason_required" -> stringResource(R.string.comp_host_error_reason)
    "immutable_or_empty_patch", "config_empty_patch" -> stringResource(R.string.comp_host_error_empty)
    "config_referenced_climb" -> stringResource(R.string.comp_host_error_climb_used)
    "config_referenced_division" -> stringResource(R.string.comp_host_error_division_used)
    "config_invalid" -> stringResource(R.string.comp_host_error_invalid)
    "config_bad_revision" -> stringResource(R.string.comp_host_error_revision)
    "correction_bad_target" -> stringResource(R.string.comp_host_error_correction_target)
    "correction_invalid_replacement" -> stringResource(R.string.comp_host_error_correction_invalid)
    "correction_no_change" -> stringResource(R.string.comp_host_error_correction_no_change)
    "config_immutable_field" -> stringResource(R.string.comp_host_error_immutable)
    "config_impact_mismatch" -> stringResource(R.string.comp_host_error_impact)
    "no_relay" -> stringResource(R.string.comp_host_error_no_relay)
    else -> stringResource(R.string.comp_host_error_generic, reason)
}

@Composable
private fun paymentReceiptErrorText(code: String): String = when (code) {
    "no_receipt", "receipt_not_found" -> stringResource(R.string.comp_host_payment_receipt_missing)
    "incomplete_receipt_history", "unreachable", "not_loaded" ->
        stringResource(R.string.comp_host_payment_receipt_unavailable)
    "no_provider_key" -> stringResource(R.string.comp_host_payment_receipt_unverifiable)
    "no_relay" -> stringResource(R.string.comp_host_payment_receipt_record_failed)
    else -> stringResource(R.string.comp_host_payment_receipt_invalid)
}

@Composable
private fun hostRequestLabel(op: String): String = when (op) {
    "checkin_request" -> stringResource(R.string.comp_host_request_checkin)
    "withdraw" -> stringResource(R.string.comp_host_request_withdraw)
    "defer_request" -> stringResource(R.string.comp_host_request_defer)
    "attempt_report" -> stringResource(R.string.comp_host_request_attempt)
    "climb_choice" -> stringResource(R.string.comp_host_request_choice)
    else -> op
}

@Composable
private fun hostLifecycleLabel(status: String) = when (status) {
    "running" -> stringResource(R.string.comp_host_resume)
    "finished" -> stringResource(R.string.comp_host_finish)
    else -> status
}

@Composable
private fun HostScheduleOverview(competition: Competition, status: String, nowSeconds: Long) {
    val registration = windowState(
        competition,
        nowSeconds,
        competition.registrationOpensAt,
        competition.registrationClosesAt,
        CompetitionProtocol.registrationWindowOpen(competition, status, nowSeconds),
    )
    val checkin = windowState(
        competition,
        nowSeconds,
        competition.checkinOpensAt,
        competition.checkinClosesAt,
        CompetitionProtocol.checkinWindowOpen(competition, status, nowSeconds),
    )
    val live = when {
        status == "cancelled" -> stringResource(R.string.comp_journey_cancelled)
        status == "finished" -> stringResource(R.string.comp_journey_finished)
        status == "paused" -> stringResource(R.string.comp_live_paused)
        CompetitionProtocol.competitionRunning(competition, status, nowSeconds) ->
            stringResource(R.string.comp_journey_live_now)
        nowSeconds < competition.startsAt -> stringResource(
            R.string.comp_journey_starts,
            formatCompetitionTime(competition, competition.startsAt),
        )
        else -> stringResource(R.string.comp_journey_finished)
    }
    JourneyRow(stringResource(R.string.comp_journey_registration), registration)
    JourneyRow(stringResource(R.string.comp_journey_checkin), checkin)
    JourneyRow(stringResource(R.string.comp_journey_live), live)
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
private fun CompetitionScoringCard(competition: Competition) {
    val rules = competition.rules
    val explanation = when (rules.scoring) {
        "achievement_points" -> {
            val points = rules.scorePoints ?: return
            stringResource(
                R.string.comp_scoring_achievement,
                points.zone,
                points.top,
                points.flash,
                points.zone + points.top,
                points.zone + points.top + points.flash,
            )
        }
        "points_sum" -> stringResource(R.string.comp_scoring_points_sum)
        "hardest_n" -> stringResource(R.string.comp_scoring_hardest_n)
        else -> stringResource(R.string.comp_scoring_ifsc)
    }
    Card(Modifier.fillMaxWidth().testTag("competition_scoring")) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.comp_scoring_title), fontWeight = FontWeight.Bold)
            Text(explanation)
        }
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
    action: CompetitionDetailViewModel.Action,
    preparedClimbId: String?,
) {
    val state = ui.snapshot.state ?: return
    val competition = ui.snapshot.competition ?: return
    val current = ui.currentClimber
    val currentName = current?.let { pubkey ->
        state.participants.firstOrNull { it.pubkey == pubkey }?.displayOrShort()
    }
    val nextName = ui.nextClimber?.let { pubkey -> state.participant(pubkey)?.displayOrShort() }
    val cue = CompetitionLivePolicy.personalCue(
        state,
        ui.myPubkey,
        CompetitionProtocol.competitionRunning(competition, state.status, nowSeconds),
    )
    val sync = CompetitionLivePolicy.syncHealth(
        hasState = true,
        connectedRelays = ui.connectedRelays,
        lastSyncedAt = ui.snapshot.lastSyncedAt,
        nowSeconds = nowSeconds,
    )

    Card(
        Modifier.fillMaxWidth().testTag("competition_live"),
        colors = CardDefaults.cardColors(
            containerColor = when (cue.kind) {
                CompetitionLivePolicy.Cue.CURRENT -> MaterialTheme.colorScheme.primaryContainer
                CompetitionLivePolicy.Cue.PAUSED -> MaterialTheme.colorScheme.tertiaryContainer
                CompetitionLivePolicy.Cue.CANCELLED -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            when (action) {
                CompetitionDetailViewModel.Action.Working -> CircularProgressIndicator(
                    modifier = Modifier.testTag("competition_live_action_working"),
                )
                is CompetitionDetailViewModel.Action.Sent -> Text(
                    stringResource(R.string.comp_sent_relays, action.accepted, action.attempted),
                    color = MaterialTheme.colorScheme.primary,
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
                CompetitionDetailViewModel.Action.Idle -> Unit
            }
            Text(
                personalCueText(cue),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = if (cue.kind == CompetitionLivePolicy.Cue.CURRENT) {
                    Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
                } else Modifier,
            )
            val nextTask = preparedClimbId?.let(competition::climb)
                ?: if (!ui.picksOwnClimbs) ui.rotation.entries.firstOrNull()?.climb else null
            Text(
                if (nextTask != null) stringResource(R.string.comp_live_next_task, nextTask.label)
                else stringResource(
                    if (ui.picksOwnClimbs) R.string.comp_live_no_prepared_boulder
                    else R.string.comp_live_waiting_shared_boulder,
                ),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            LabelledValue(
                stringResource(R.string.comp_current_climber),
                currentName ?: stringResource(R.string.comp_nobody_climbing),
            )
            if (!ui.picksOwnClimbs) {
                LabelledValue(stringResource(R.string.comp_current_climb), climbLabel(ui, state.currentClimbId))
            }
            LabelledValue(
                stringResource(
                    if (state.cursor == state.order.lastIndex && state.cursor >= 0) R.string.comp_next_climber_next_round
                    else R.string.comp_next_climber,
                ),
                nextName ?: "—",
            )
            LabelledValue(stringResource(R.string.comp_round), stringResource(R.string.comp_round, state.round))
            if (CompetitionProtocol.competitionRunning(competition, state.status, nowSeconds)) ui.secondsToDeadline(nowSeconds)?.let { seconds ->
                LabelledValue(
                    stringResource(R.string.comp_deadline),
                    "%d:%02d".format(seconds / 60, seconds % 60),
                )
            }
            CompetitionLivePolicy.etaSeconds(
                state,
                ui.myPubkey,
                nowSeconds,
                CompetitionProtocol.competitionRunning(competition, state.status, nowSeconds),
            )?.takeIf { it > 0 }?.let { seconds ->
                LabelledValue(
                    stringResource(R.string.comp_live_estimated_turn),
                    stringResource(R.string.comp_live_about_minutes, ((seconds + 59) / 60).coerceAtLeast(1)),
                )
            }
            if (cue.roundOffset > 0) {
                LabelledValue(
                    stringResource(R.string.comp_live_your_next_round),
                    stringResource(R.string.comp_round, state.round + cue.roundOffset),
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiveMetric(
                    ui.climbersBefore?.toString() ?: "—",
                    stringResource(R.string.comp_before_you),
                    Modifier.weight(1f),
                )
                LiveMetric(
                    nextTask?.let { ui.attemptsLeftFor(it.id).toString() } ?: "—",
                    stringResource(R.string.comp_attempts_left),
                    Modifier.weight(1f),
                )
                LiveMetric(ui.defersLeft.toString(), stringResource(R.string.comp_defers_left), Modifier.weight(1f))
            }

            if (ui.queue.entries.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.comp_live_queue), fontWeight = FontWeight.Bold)
                ui.queue.entries.forEach { entry ->
                    val label = entry.participant?.displayOrShort() ?: entry.pubkey.take(8)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            if (entry.current) stringResource(R.string.comp_live_now_short)
                            else if (entry.roundOffset > 0) stringResource(R.string.comp_live_next_round_short)
                            else (entry.queuePosition + 1).toString(),
                            Modifier.width(44.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            label,
                            fontWeight = if (entry.pubkey == ui.myPubkey || entry.current) FontWeight.Bold else FontWeight.Normal,
                            color = if (entry.pubkey == ui.myPubkey) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                if (ui.queue.hidden > 0) {
                    Text(
                        stringResource(R.string.comp_live_more, ui.queue.hidden),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (sync.kind != CompetitionLivePolicy.Sync.LIVE) {
                Spacer(Modifier.height(12.dp))
                Text(
                    syncHealthText(sync),
                    color = if (sync.kind == CompetitionLivePolicy.Sync.STALE) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            val myClimbs = ui.me?.climbs.orEmpty()
            if (myClimbs.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.comp_my_results), fontWeight = FontWeight.Bold)
                myClimbs.forEach { climb ->
                    Text(stringResource(
                        R.string.comp_result_summary,
                        climbLabel(ui, climb.climbId),
                        stringResource(attemptOutcomeLabel(climb.outcome)),
                        pluralStringResource(
                            R.plurals.comp_attempt_count,
                            climb.attemptsUsed,
                            climb.attemptsUsed,
                        ),
                    ))
                }
            }

        }
    }
}

@Composable
private fun LiveMetric(value: String, label: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(10.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun personalCueText(cue: CompetitionLivePolicy.PersonalCue): String = when (cue.kind) {
    CompetitionLivePolicy.Cue.SPECTATOR -> stringResource(R.string.comp_live_follow)
    CompetitionLivePolicy.Cue.WAITING -> stringResource(R.string.comp_live_get_ready)
    CompetitionLivePolicy.Cue.NOT_QUEUED -> stringResource(R.string.comp_live_not_queued)
    CompetitionLivePolicy.Cue.CURRENT -> stringResource(R.string.comp_your_turn)
    CompetitionLivePolicy.Cue.NEXT -> stringResource(R.string.comp_live_you_are_next)
    CompetitionLivePolicy.Cue.QUEUED -> stringResource(R.string.comp_live_ahead, cue.ahead ?: 0)
    CompetitionLivePolicy.Cue.PAUSED -> stringResource(R.string.comp_live_paused)
    CompetitionLivePolicy.Cue.FINISHED -> stringResource(R.string.comp_live_final_result)
    CompetitionLivePolicy.Cue.CANCELLED -> stringResource(R.string.comp_live_cancelled)
}

@Composable
private fun syncHealthText(health: CompetitionLivePolicy.SyncHealth): String = when (health.kind) {
    CompetitionLivePolicy.Sync.CONNECTING -> stringResource(R.string.comp_live_connecting)
    CompetitionLivePolicy.Sync.LIVE -> stringResource(R.string.comp_live_synced, health.connectedRelays)
    CompetitionLivePolicy.Sync.OFFLINE -> stringResource(R.string.comp_live_offline)
    CompetitionLivePolicy.Sync.STALE -> stringResource(R.string.comp_live_stale, health.ageSeconds ?: 0)
}

/** One-hand action region: the next valid participant action stays reachable. */
@Composable
private fun ParticipantActionBar(
    ui: CompetitionDetailViewModel.Ui,
    nowSeconds: Long,
    viewModel: CompetitionDetailViewModel,
    preparedClimbId: String?,
    onOpenClimb: (String) -> Unit,
) {
    val state = ui.snapshot.state ?: return
    val competition = ui.snapshot.competition ?: return
    val me = ui.me ?: return
    // Never guess a participant-choice boulder. The sticky action must use
    // the selection made in the live dashboard and retained by the screen.
    val activeClimb = preparedClimbId?.let(competition::climb)
        ?: if (!ui.picksOwnClimbs) {
            competition.climb(state.currentClimbId) ?: ui.rotation.entries.firstOrNull()?.climb
        } else null
    val runningNow = CompetitionProtocol.competitionRunning(competition, state.status, nowSeconds)
    val deferAvailability = ui.deferAvailability(nowSeconds)
    val canCheckIn = me.registration == "accepted" && me.checkin == "none" &&
        checkinWindowOpen(competition, state.status, nowSeconds)
    if (!runningNow && state.status != "paused" && !canCheckIn) return

    Card(
        Modifier.fillMaxWidth().navigationBarsPadding().testTag("competition_actions"),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                if (ui.isMyTurn) stringResource(R.string.comp_your_turn)
                else stringResource(R.string.comp_live_next_action),
                fontWeight = FontWeight.Bold,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canCheckIn) {
                    Button(
                        onClick = { viewModel.requestCheckIn() },
                        modifier = Modifier.weight(1f).testTag("competition_checkin_sticky"),
                    ) { Text(stringResource(R.string.comp_checkin_action)) }
                }
                if (activeClimb != null && runningNow) {
                    Button(
                        onClick = { onOpenClimb(activeClimb.id) },
                        modifier = Modifier.weight(1f).testTag("competition_open_live"),
                    ) {
                        Text(
                            if (ui.isMyTurn) stringResource(R.string.comp_live_open_now)
                            else stringResource(R.string.comp_live_prepare),
                        )
                    }
                } else if (ui.isMyTurn && ui.picksOwnClimbs && runningNow) {
                    Text(
                        stringResource(R.string.comp_live_choose_required_short),
                        modifier = Modifier.weight(1f).testTag("competition_choose_required_sticky"),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (deferAvailability.allowed) {
                    OutlinedButton(
                        onClick = { viewModel.requestDefer() },
                        modifier = Modifier.testTag("competition_defer"),
                    ) { Text(stringResource(R.string.comp_defer)) }
                }
            }
            if (ui.isMyTurn && !deferAvailability.allowed) {
                val reason = when (deferAvailability.reason) {
                    CompetitionLivePolicy.DeferReason.PAUSED -> R.string.comp_next_paused
                    CompetitionLivePolicy.DeferReason.BUDGET -> R.string.comp_defer_none
                    CompetitionLivePolicy.DeferReason.CONSECUTIVE -> R.string.comp_live_defer_consecutive
                    else -> null
                }
                reason?.let {
                    Text(
                        stringResource(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (deferAvailability.allowed) {
                Text(
                    stringResource(R.string.comp_defer_hint, competition.rules.deferSlots),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A single glance answers where this entrant is in each independent window. */
@Composable
private fun ParticipantJourneyCard(ui: CompetitionDetailViewModel.Ui, nowSeconds: Long) {
    val competition = ui.snapshot.competition ?: return
    val state = ui.snapshot.state ?: return
    val me = ui.me
    val pendingRegistration = ui.snapshot.pendingIntents.any {
        it.pubkey == ui.myPubkey && it.op == "register"
    }
    val pendingCheckin = ui.snapshot.pendingIntents.any {
        it.pubkey == ui.myPubkey && it.op == "checkin_request"
    }
    val registration = when {
        me?.registration == "accepted" -> stringResource(R.string.comp_journey_registration_accepted)
        me?.registration == "rejected" -> stringResource(R.string.comp_journey_registration_rejected)
        me?.registration == "waitlisted" -> stringResource(R.string.comp_reg_waitlisted)
        me?.registration == "withdrawn" -> stringResource(R.string.comp_reg_withdrawn)
        pendingRegistration || me?.registration == "pending" ->
            stringResource(R.string.comp_journey_registration_sent)
        CompetitionProtocol.registrationWindowOpen(competition, state.status, nowSeconds) ->
            stringResource(R.string.comp_journey_open_now)
        nowSeconds < competition.registrationOpensAt -> stringResource(
            R.string.comp_journey_opens,
            formatCompetitionTime(competition, competition.registrationOpensAt),
        )
        else -> stringResource(R.string.comp_journey_closed)
    }
    val checkin = when {
        me?.checkin == "checked_in" -> stringResource(R.string.comp_checkin_checked_in)
        me?.checkin == "no_show" -> stringResource(R.string.comp_checkin_no_show)
        pendingCheckin -> stringResource(R.string.comp_journey_request_sent)
        me?.registration != "accepted" -> stringResource(R.string.comp_journey_after_acceptance)
        CompetitionProtocol.checkinWindowOpen(competition, state.status, nowSeconds) ->
            stringResource(R.string.comp_journey_open_now)
        nowSeconds < competition.checkinOpensAt -> stringResource(
            R.string.comp_journey_opens,
            formatCompetitionTime(competition, competition.checkinOpensAt),
        )
        else -> stringResource(R.string.comp_journey_closed)
    }
    val live = when {
        state.status == "cancelled" -> stringResource(R.string.comp_journey_cancelled)
        state.status == "finished" -> stringResource(R.string.comp_journey_finished)
        state.status == "paused" -> stringResource(R.string.comp_live_paused)
        CompetitionProtocol.competitionRunning(competition, state.status, nowSeconds) ->
            stringResource(R.string.comp_journey_live_now)
        nowSeconds < competition.startsAt -> stringResource(
            R.string.comp_journey_starts,
            formatCompetitionTime(competition, competition.startsAt),
        )
        else -> stringResource(R.string.comp_journey_finished)
    }

    Card(Modifier.fillMaxWidth().testTag("competition_journey")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.comp_journey_title), fontWeight = FontWeight.Bold)
            JourneyRow(stringResource(R.string.comp_journey_registration), registration)
            JourneyRow(stringResource(R.string.comp_journey_checkin), checkin)
            JourneyRow(stringResource(R.string.comp_journey_live), live)
        }
    }
}

@Composable
private fun JourneyRow(label: String, status: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.35f))
        Text(status, textAlign = TextAlign.End, modifier = Modifier.weight(0.65f))
    }
}

@Composable
private fun CheckInPanel(
    ui: CompetitionDetailViewModel.Ui,
    viewModel: CompetitionDetailViewModel,
    action: CompetitionDetailViewModel.Action,
    nowSeconds: Long,
) {
    val competition = ui.snapshot.competition ?: return
    val state = ui.snapshot.state ?: return
    val me = ui.me ?: return
    val pending = ui.snapshot.pendingIntents.any {
        it.pubkey == ui.myPubkey && it.op == "checkin_request"
    }
    val open = CompetitionProtocol.checkinWindowOpen(competition, state.status, nowSeconds)
    Card(Modifier.fillMaxWidth().testTag("competition_checkin")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.comp_journey_checkin), fontWeight = FontWeight.Bold)
            when {
                me.checkin == "checked_in" -> Text(stringResource(R.string.comp_journey_checkin_done))
                me.checkin == "no_show" -> Text(stringResource(R.string.comp_checkin_no_show))
                pending -> {
                    Text(
                        stringResource(R.string.comp_journey_request_sent),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    Text(stringResource(R.string.comp_journey_checkin_waiting))
                }
                open -> {
                    Text(stringResource(R.string.comp_journey_checkin_open))
                    Button(
                        onClick = viewModel::requestCheckIn,
                        enabled = action !is CompetitionDetailViewModel.Action.Working,
                        modifier = Modifier.fillMaxWidth().testTag("competition_checkin_main"),
                    ) { Text(stringResource(R.string.comp_checkin_action)) }
                }
                nowSeconds < competition.checkinOpensAt -> Text(
                    stringResource(
                        R.string.comp_journey_checkin_opens,
                        formatCompetitionTime(competition, competition.checkinOpensAt),
                    ),
                )
                else -> Text(stringResource(R.string.comp_journey_checkin_closed))
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
    val now = System.currentTimeMillis() / 1000
    val registrationOpen = registrationWindowOpen(competition, state.status, now)

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
                Text(
                    stringResource(registrationLabel(me.registration)),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                // Not only `pending`. A payment the organizer recorded as
                // failed or expired is precisely the state somebody has to be
                // able to leave, and showing the badge without the button
                // strands them.
                if (competition.feeMsat > 0 && me.payment in PAYABLE_STATES) {
                    PaymentSection(ui, viewModel)
                    Spacer(Modifier.height(8.dp))
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
                                selections = emptyList(),
                            )
                        },
                        modifier = Modifier.testTag("competition_register_again"),
                    ) { Text(stringResource(R.string.comp_register_again)) }
                }
                return@Column
            }

            val pendingRegistration = ui.snapshot.pendingIntents.any {
                it.pubkey == ui.myPubkey && it.op == "register"
            }
            if (pendingRegistration) {
                Text(
                    stringResource(R.string.comp_journey_registration_sent),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Text(stringResource(R.string.comp_journey_registration_waiting))
                return@Column
            }

            if (!registrationOpen) {
                Text(
                    if (now < competition.registrationOpensAt) {
                        stringResource(
                            R.string.comp_journey_registration_opens,
                            formatCompetitionTime(competition, competition.registrationOpensAt),
                        )
                    } else stringResource(R.string.comp_registration_closed),
                )
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
            if (ui.picksOwnClimbs) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(
                        R.string.comp_journey_choose_live,
                        competition.rules.countedClimbCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = {
                    viewModel.register(
                        division = selectedDivision,
                        display = display.trim(),
                        waiverAccepted = !competition.waiverRequired || waiver,
                        selections = emptyList(),
                    )
                },
                enabled = display.isNotBlank() && (!competition.waiverRequired || waiver),
                modifier = Modifier.fillMaxWidth().testTag("competition_register"),
            ) { Text(stringResource(R.string.comp_register)) }
            val registerProblem = when {
                display.isBlank() -> stringResource(R.string.comp_display_required)
                competition.waiverRequired && !waiver -> stringResource(R.string.comp_waiver_required)
                else -> null
            }
            if (registerProblem != null) {
                Text(
                    registerProblem,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * Claiming a prize you won.
 *
 * Only after the results are final, only for the prize the standings put you
 * at, and only through an encrypted channel — the payout destination goes to
 * the organizer and nowhere else. It says whose money this is before asking
 * for a wallet address: the organizer's, paid from their own wallet, with
 * CruxCoach holding nothing and guaranteeing nothing.
 */
@Composable
private fun PrizeClaimPanel(
    ui: CompetitionDetailViewModel.Ui,
    viewModel: CompetitionDetailViewModel,
) {
    val competition = ui.snapshot.competition ?: return
    val state = ui.snapshot.state ?: return
    if (state.status != "finished") return

    val claimable = remember(state.seq, ui.myPubkey) { viewModel.claimablePrizes() }
    if (claimable.isEmpty()) return

    val claim by viewModel.prizeClaim.collectAsStateWithLifecycle()

    Card(Modifier.fillMaxWidth().testTag("competition_prizes")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.comp_prize_title), fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.comp_money_prize_not_funded),
                style = MaterialTheme.typography.bodySmall,
            )

            for (prize in claimable) {
                val status = state.prizes[prize.id]
                Text(
                    if (prize.isCash) {
                        stringResource(R.string.comp_prize_won_cash, prize.label, prize.valueMsat / 1000)
                    } else {
                        prize.label
                    },
                    fontWeight = FontWeight.Bold,
                )

                if (status != null && status.pubkey == ui.myPubkey &&
                    status.state in listOf("approved", "paid")
                ) {
                    Text(prizeStateText(status.state))
                    if (status.state == "paid") {
                        // The only word about a payout that comes from the side
                        // that was paid. Optional, and the organizer's screen
                        // says its absence proves nothing.
                        Button(
                            onClick = { viewModel.acknowledgePrize(prize.id) },
                            modifier = Modifier.testTag("competition_prize_ack_${prize.id}"),
                        ) { Text(stringResource(R.string.comp_prize_acknowledge)) }
                    }
                    continue
                }
                if (status != null && status.state == "expired") {
                    Text(prizeStateText("expired"))
                    continue
                }
                if (status != null) Text(prizeStateText(status.state))

                var payoutKind by rememberSaveable(prize.id) {
                    mutableStateOf(if (prize.isCash) "lightning_address" else "non_cash")
                }
                var destination by rememberSaveable(prize.id) { mutableStateOf("") }

                if (prize.isCash) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        listOf(
                            "lightning_address" to R.string.comp_prize_kind_address,
                            "bolt11" to R.string.comp_prize_kind_invoice,
                        ).forEach { (kind, label) ->
                            RadioButton(
                                selected = payoutKind == kind,
                                onClick = { payoutKind = kind },
                                modifier = Modifier.testTag("competition_prize_kind_$kind"),
                            )
                            Text(stringResource(label))
                        }
                    }
                }

                OutlinedTextField(
                    value = destination,
                    onValueChange = { destination = it.take(600) },
                    label = { Text(stringResource(R.string.comp_prize_dest)) },
                    supportingText = { Text(stringResource(R.string.comp_prize_dest_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("competition_prize_dest_${prize.id}"),
                )

                when (val current = claim) {
                    CompetitionDetailViewModel.PrizeClaim.Working -> CircularProgressIndicator()
                    CompetitionDetailViewModel.PrizeClaim.Sent -> Text(
                        stringResource(R.string.comp_prize_sent),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    is CompetitionDetailViewModel.PrizeClaim.Failed -> Text(
                        prizeErrorText(current.code),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .testTag("competition_prize_error")
                            .semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                    CompetitionDetailViewModel.PrizeClaim.Idle -> Unit
                }

                Button(
                    onClick = { viewModel.claimPrize(prize.id, payoutKind, destination) },
                    enabled = destination.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().testTag("competition_prize_claim_${prize.id}"),
                ) { Text(stringResource(R.string.comp_prize_claim)) }
            }
        }
    }
}

@Composable
private fun prizeStateText(state: String): String = when (state) {
    "claimed" -> stringResource(R.string.comp_prize_state_claimed)
    "approved" -> stringResource(R.string.comp_prize_state_approved)
    "paid" -> stringResource(R.string.comp_prize_state_paid)
    "rejected" -> stringResource(R.string.comp_prize_state_rejected)
    else -> stringResource(R.string.comp_prize_state_expired)
}

/** One sentence per way a claim can be refused, rather than a code on screen. */
@Composable
private fun prizeErrorText(code: String): String = when (code) {
    "no_destination" -> stringResource(R.string.comp_prize_err_no_destination)
    "not_a_cash_prize" -> stringResource(R.string.comp_prize_err_not_cash)
    "cash_prize_needs_a_wallet" -> stringResource(R.string.comp_prize_err_needs_wallet)
    "destination_wrong_amount" -> stringResource(R.string.comp_prize_err_wrong_amount)
    "destination_expired" -> stringResource(R.string.comp_prize_err_expired)
    "destination_unreadable_invoice" -> stringResource(R.string.comp_prize_err_unreadable)
    "destination_onion" -> stringResource(R.string.comp_prize_err_onion)
    "destination_not_https" -> stringResource(R.string.comp_prize_err_not_https)
    "no_encryption" -> stringResource(R.string.comp_prize_err_no_encryption)
    else -> stringResource(R.string.comp_prize_err_bad_destination)
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
    // Before they pay, not after: where the money goes, and who cannot get it
    // back for them.
    Text(
        stringResource(R.string.comp_money_no_custody),
        style = MaterialTheme.typography.bodySmall,
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

@Composable
private fun CompetitionCatalogueCard(
    entry: CompetitionDetailViewModel.CatalogueEntry,
    checked: Boolean,
    enabled: Boolean,
    taken: Boolean,
    zoneRelevant: Boolean,
    gradeScale: GradeScale,
    onCheckedChange: (Boolean) -> Unit,
    onOpenClimb: (String, Int) -> Unit,
    showSelection: Boolean = true,
) {
    val option = entry.option
    val climb = entry.climb
    val validZoneHold = CompetitionCataloguePolicy.validZoneHold(option, entry.holds)
    Card(
        modifier = Modifier.fillMaxWidth().testTag("competition_catalog_${option.id}"),
        colors = CardDefaults.cardColors(
            containerColor = if (checked) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        ),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.width(104.dp)) {
                if (climb.boardBrand == BoardBrand.MOONBOARD.wireValue) {
                    MoonBoardVisualization(
                        frames = climb.frames,
                        assetState = rememberMoonBoardAsset(climb.layoutId),
                        variant = MoonBoardVariant.fromLayoutId(climb.layoutId),
                        highlightedHoldId = validZoneHold,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    KilterBoardVisualization(
                        holds = entry.holds,
                        placements = entry.placements,
                        boardSize = entry.boardSize,
                        boardImages = entry.boardImages,
                        ledColors = LedHoldColors.standardFor(BoardBrand.fromWire(climb.boardBrand)),
                        selectedHolds = validZoneHold?.let(::setOf).orEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showSelection) {
                        Checkbox(
                            checked = checked,
                            enabled = enabled,
                            onCheckedChange = onCheckedChange,
                            modifier = Modifier.testTag("competition_pick_${option.id}"),
                        )
                    }
                    Text(climb.name.ifBlank { option.label }, fontWeight = FontWeight.Bold)
                }
                val grade = climb.difficultyAverage?.let {
                    GradeDisplayHelper.formatDifficulty(it, gradeScale)
                } ?: stringResource(R.string.comp_catalog_ungraded)
                Text(
                    stringResource(
                        R.string.comp_catalog_details,
                        grade,
                        climb.ascensionistCount ?: 0,
                        option.angle,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                climb.setterUsername?.takeIf { it.isNotBlank() }?.let {
                    Text(stringResource(R.string.comp_catalog_setter, it), style = MaterialTheme.typography.bodySmall)
                }
                climb.qualityAverage?.let {
                    Text(stringResource(R.string.comp_catalog_quality, it), style = MaterialTheme.typography.bodySmall)
                }
                if (taken) {
                    Text(stringResource(R.string.comp_pick_taken), color = MaterialTheme.colorScheme.error)
                } else if (!enabled && !checked) {
                    Text(stringResource(R.string.comp_pick_limit_reached), style = MaterialTheme.typography.bodySmall)
                }
                if (zoneRelevant && validZoneHold != null) {
                    Text(
                        stringResource(
                            R.string.comp_catalog_zone_marked,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                OpenOnBoardButton(option, onOpenClimb)
            }
        }
    }
}

@Composable
private fun CompetitionCatalogueOverview(
    ui: CompetitionDetailViewModel.Ui,
    onOpenClimb: (String, Int) -> Unit,
) {
    val competition = ui.snapshot.competition ?: return
    val ready = ui.catalogue as? CompetitionDetailViewModel.CatalogueState.Ready
    Card(Modifier.fillMaxWidth().testTag("competition_organizer_climbs")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.comp_catalog_comp_climbs), fontWeight = FontWeight.Bold)
            Text(
                if (competition.rules.climbSource == "participant_choice") {
                    stringResource(
                        R.string.comp_journey_pool_best,
                        competition.rules.countedClimbCount,
                    )
                } else stringResource(R.string.comp_catalog_comp_climbs_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            if (ready == null) {
                Text(
                    stringResource(
                        if (ui.catalogue is CompetitionDetailViewModel.CatalogueState.Loading) {
                            R.string.comp_catalog_loading
                        } else {
                            R.string.comp_catalog_read_only_unavailable
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                return@Column
            }
            competition.climbsFor(emptyList()).mapNotNull { ready.entries[it.id] }.forEach { entry ->
                CompetitionCatalogueCard(
                    entry = entry,
                    checked = false,
                    enabled = false,
                    taken = false,
                    zoneRelevant = competition.zoneScoringActive(),
                    gradeScale = ui.gradeScale,
                    onCheckedChange = {},
                    onOpenClimb = onOpenClimb,
                    showSelection = false,
                )
            }
        }
    }
}

private fun Competition.zoneScoringActive(): Boolean =
    rules.scoring == "tops_then_attempts" ||
        (rules.scoring == "achievement_points" && (rules.scorePoints?.zone ?: 0) > 0) ||
        "most_zones" in rules.tiebreaks || "fewest_zone_attempts" in rules.tiebreaks

private fun registrationWindowOpen(competition: Competition, status: String, at: Long): Boolean =
    CompetitionProtocol.registrationWindowOpen(competition, status, at)

private fun checkinWindowOpen(competition: Competition, status: String, at: Long): Boolean =
    CompetitionProtocol.checkinWindowOpen(competition, status, at)

@Composable
private fun windowState(
    competition: Competition,
    nowSeconds: Long,
    opensAt: Long,
    closesAt: Long,
    open: Boolean,
): String = when {
    open -> stringResource(R.string.comp_journey_open_now)
    nowSeconds < opensAt -> stringResource(
        R.string.comp_journey_opens_short,
        formatCompetitionTime(competition, opensAt),
    )
    nowSeconds > closesAt -> stringResource(R.string.comp_journey_closed)
    else -> stringResource(R.string.comp_journey_closed)
}

@Composable
private fun formatCompetitionTime(competition: Competition, epochSeconds: Long): String {
    val formatter = remember(competition.timezone) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).apply {
            timeZone = TimeZone.getTimeZone(competition.timezone)
        }
    }
    return remember(epochSeconds, formatter) { formatter.format(Date(epochSeconds * 1000)) }
}

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
    preparedClimbId: String?,
    onPreparedClimb: (String?) -> Unit,
    onOpenClimb: (String, Int) -> Unit,
) {
    val remaining = ui.remainingClimbs
    Text(stringResource(R.string.comp_next_title), fontWeight = FontWeight.Bold)
    when (ui.preparedChoice) {
        CompetitionDetailViewModel.PreparedChoice.Loading -> {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.comp_choice_loading), style = MaterialTheme.typography.bodySmall)
            return
        }
        CompetitionDetailViewModel.PreparedChoice.Untrusted -> {
            WarningCard(stringResource(R.string.comp_choice_untrusted))
            OutlinedButton(
                onClick = viewModel::retryPreparedChoice,
                modifier = Modifier.fillMaxWidth().testTag("competition_choice_retry"),
            ) { Text(stringResource(R.string.action_retry)) }
            return
        }
        else -> Unit
    }
    if (remaining.isEmpty()) {
        Text(stringResource(R.string.comp_next_none_left), style = MaterialTheme.typography.bodySmall)
        return
    }
    val mayAct = ui.mayAct(nowSeconds)
    val chosen = preparedClimbId?.takeIf { id -> remaining.any { it.climb.id == id } }
    if (mayAct && chosen == null) {
        Card(
            Modifier.fillMaxWidth().testTag("competition_choose_required"),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    stringResource(R.string.comp_live_choose_required),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
                Text(stringResource(R.string.comp_live_choose_required_hint))
            }
        }
        Spacer(Modifier.height(8.dp))
    } else if (!mayAct) {
        Text(whyNotYet(ui, nowSeconds), style = MaterialTheme.typography.bodySmall)
        Text(
            stringResource(R.string.comp_live_prepare_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    remaining.forEach { entry ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable(
                enabled = ui.preparedChoice !is CompetitionDetailViewModel.PreparedChoice.Working,
            ) { onPreparedClimb(entry.climb.id) }
                .testTag("competition_choice_${entry.climb.id}"),
        ) {
            RadioButton(
                selected = chosen == entry.climb.id,
                onClick = { onPreparedClimb(entry.climb.id) },
                enabled = ui.preparedChoice !is CompetitionDetailViewModel.PreparedChoice.Working,
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
    if (chosen != null) {
        Text(
            stringResource(R.string.comp_live_prepared, climbLabel(ui, chosen)),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("competition_prepared_climb"),
        )
    }
    if (ui.preparedChoice is CompetitionDetailViewModel.PreparedChoice.Working) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    if (mayAct && chosen != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("top" to R.string.comp_outcome_top, "zone" to R.string.comp_outcome_zone,
            "fall" to R.string.comp_outcome_fall).forEach { (outcome, label) ->
            Button(
                onClick = { viewModel.reportAttempt(chosen, outcome) },
                modifier = Modifier.testTag("competition_report_$outcome"),
            ) { Text(stringResource(label)) }
        }
    }
    if (mayAct && chosen != null) {
        Text(stringResource(R.string.comp_next_report_hint), style = MaterialTheme.typography.bodySmall)
    }
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
    val showPoints = ui.snapshot.competition?.rules?.scoring != "tops_then_attempts"
    Card(Modifier.fillMaxWidth().testTag("competition_leaderboard")) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.comp_leaderboard), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (!ui.snapshot.trustworthy) {
                Text(
                    stringResource(R.string.comp_leaderboard_untrusted),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("competition_leaderboard_untrusted")
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                return@Column
            }
            if (ui.snapshot.standings.isEmpty()) {
                Text(
                    stringResource(R.string.comp_leaderboard_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("competition_leaderboard_empty"),
                )
                return@Column
            }
            if (ui.snapshot.state?.audit?.any { it.supersedesResults } == true) {
                WarningCard(stringResource(R.string.comp_results_amended))
            }
            ui.snapshot.standings.firstOrNull { it.pubkey == ui.myPubkey }?.let { mine ->
                Text(
                    if (mine.rank > 0) stringResource(R.string.comp_leaderboard_your_rank, mine.rank)
                    else stringResource(R.string.comp_leaderboard_your_rank_pending),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("competition_leaderboard_my_rank"),
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.comp_table_rank), Modifier.weight(0.15f), style = MaterialTheme.typography.labelSmall)
                Text(stringResource(R.string.comp_table_climber), Modifier.weight(0.4f), style = MaterialTheme.typography.labelSmall)
                if (showPoints) Text(stringResource(R.string.comp_table_points), Modifier.weight(0.2f), style = MaterialTheme.typography.labelSmall)
                Text(stringResource(R.string.comp_table_tops), Modifier.weight(0.16f), style = MaterialTheme.typography.labelSmall)
                Text(stringResource(R.string.comp_table_zones), Modifier.weight(0.16f), style = MaterialTheme.typography.labelSmall)
                Text(stringResource(R.string.comp_table_attempts), Modifier.weight(0.16f), style = MaterialTheme.typography.labelSmall)
            }
            HorizontalDivider()
            ui.snapshot.standings.forEachIndexed { index, row ->
                val tied = row.rank > 0 && ui.snapshot.standings.anyIndexed { otherIndex, other ->
                    otherIndex != index && other.rank == row.rank
                }
                val active = row.pubkey == ui.currentClimber
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(
                        if (row.rank == 0) "—" else if (tied) "${row.rank}=" else row.rank.toString(),
                        Modifier.weight(0.15f),
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        (if (active) "● " else "") + row.display.ifBlank { row.pubkey.take(8) },
                        Modifier.weight(0.4f),
                        fontWeight = if (row.pubkey == ui.myPubkey || active) FontWeight.Bold else FontWeight.Normal,
                        color = if (row.pubkey == ui.myPubkey || active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    if (showPoints) Text(row.points.toString(), Modifier.weight(0.2f))
                    Text(row.tops.toString(), Modifier.weight(0.16f))
                    Text(row.zones.toString(), Modifier.weight(0.16f))
                    Text(row.attempts.toString(), Modifier.weight(0.16f))
                }
            }
        }
    }
}

private fun attemptOutcomeLabel(outcome: String): Int = when (outcome) {
    "top" -> R.string.comp_host_result_top
    "zone" -> R.string.comp_host_result_zone
    "fall" -> R.string.comp_host_result_fall
    "pass" -> R.string.comp_host_result_pass
    "timeout" -> R.string.comp_host_result_timeout
    else -> R.string.comp_host_result_unknown
}

private inline fun <T> List<T>.anyIndexed(predicate: (Int, T) -> Boolean): Boolean {
    forEachIndexed { index, value -> if (predicate(index, value)) return true }
    return false
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
