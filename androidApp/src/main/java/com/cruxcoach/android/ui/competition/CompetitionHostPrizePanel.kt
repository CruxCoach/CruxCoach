package com.cruxcoach.android.ui.competition

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.domain.competition.CompetitionPrizeClaim
import kotlinx.serialization.json.JsonPrimitive

/** Organizer prize fulfilment. Private destinations exist only in ViewModel memory. */
@Composable
internal fun CompetitionHostPrizePanel(
    ui: CompetitionDetailViewModel.Ui,
    viewModel: CompetitionDetailViewModel,
    action: CompetitionDetailViewModel.Action,
) {
    val competition = ui.snapshot.competition ?: return
    val state = ui.snapshot.state ?: return
    if (competition.prizes.isEmpty()) return
    val reviews by viewModel.hostPrizeReviews.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var paidPrizeId by rememberSaveable { mutableStateOf<String?>(null) }
    var paidPubkey by rememberSaveable { mutableStateOf<String?>(null) }
    var paidReason by rememberSaveable { mutableStateOf("") }
    var rejectedPrizeId by rememberSaveable { mutableStateOf<String?>(null) }
    var rejectedPubkey by rememberSaveable { mutableStateOf<String?>(null) }

    if (paidPrizeId != null && paidPubkey != null) AlertDialog(
        onDismissRequest = { paidPrizeId = null; paidPubkey = null; paidReason = "" },
        title = { Text(stringResource(R.string.comp_host_prize_paid_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.comp_host_prize_paid_body))
                OutlinedTextField(
                    paidReason,
                    { paidReason = it },
                    label = { Text(stringResource(R.string.comp_host_prize_paid_reason)) },
                    modifier = Modifier.fillMaxWidth().testTag("competition_host_prize_paid_reason"),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    viewModel.hostPrizeDecision(paidPrizeId!!, paidPubkey!!, "paid", paidReason)
                    paidPrizeId = null; paidPubkey = null; paidReason = ""
                },
                enabled = paidReason.isNotBlank() && action !is CompetitionDetailViewModel.Action.Working,
                modifier = Modifier.testTag("competition_host_prize_paid_confirm"),
            ) { Text(stringResource(R.string.comp_host_prize_mark_paid)) }
        },
        dismissButton = {
            TextButton({ paidPrizeId = null; paidPubkey = null; paidReason = "" }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )

    if (rejectedPrizeId != null && rejectedPubkey != null) AlertDialog(
        onDismissRequest = { rejectedPrizeId = null; rejectedPubkey = null },
        title = { Text(stringResource(R.string.comp_host_prize_reject_title)) },
        text = { Text(stringResource(R.string.comp_host_prize_reject_body)) },
        confirmButton = {
            Button({
                viewModel.hostPrizeDecision(rejectedPrizeId!!, rejectedPubkey!!, "rejected")
                rejectedPrizeId = null; rejectedPubkey = null
            }) { Text(stringResource(R.string.comp_host_prize_reject)) }
        },
        dismissButton = {
            TextButton({ rejectedPrizeId = null; rejectedPubkey = null }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )

    Card(Modifier.fillMaxWidth().testTag("competition_host_prizes")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.comp_host_prize_title), fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.comp_money_no_custody), style = MaterialTheme.typography.bodySmall)
            if (state.status != "finished") {
                Text(stringResource(R.string.comp_host_prize_not_final), style = MaterialTheme.typography.bodySmall)
                return@Column
            }

            val currentHash = state.stateHash()
            competition.prizes.forEach { prize ->
                val winner = CompetitionPrizeClaim.eligibleWinner(ui.snapshot.standings, prize)
                Text(prize.label, fontWeight = FontWeight.SemiBold)
                if (winner == null) {
                    Text(stringResource(R.string.comp_host_prize_no_winner), style = MaterialTheme.typography.bodySmall)
                    return@forEach
                }
                Text(
                    stringResource(
                        R.string.comp_host_prize_winner,
                        winner.display.ifBlank { "${winner.pubkey.take(12)}…" },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                state.prizes[prize.id]?.let { status ->
                    Text(hostPrizeStateText(status.state), style = MaterialTheme.typography.bodySmall)
                }
                val claim = ui.snapshot.pendingIntents.firstOrNull { intent ->
                    intent.op == "prize_claim" && intent.pubkey == winner.pubkey &&
                        (intent.data["prize_id"] as? JsonPrimitive)?.content == prize.id
                }
                val receipt = ui.snapshot.pendingIntents.firstOrNull { intent ->
                    intent.op == "prize_receipt" && intent.pubkey == winner.pubkey &&
                        (intent.data["prize_id"] as? JsonPrimitive)?.content == prize.id &&
                        (intent.data["received"] as? JsonPrimitive)?.content == "true"
                }
                if (claim == null) {
                    Text(stringResource(R.string.comp_host_prize_no_claim), style = MaterialTheme.typography.bodySmall)
                } else when (val review = reviews[claim.eventId]) {
                    null -> Button(
                        onClick = { viewModel.reviewPrizeClaim(claim) },
                        modifier = Modifier.testTag("competition_host_prize_review_${prize.id}"),
                    ) { Text(stringResource(R.string.comp_host_prize_review)) }
                    is CompetitionDetailViewModel.HostPrizeReview.Working -> CircularProgressIndicator()
                    is CompetitionDetailViewModel.HostPrizeReview.Failed -> {
                        Text(
                            hostPrizeErrorText(review.error),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton({ viewModel.reviewPrizeClaim(claim) }) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                    is CompetitionDetailViewModel.HostPrizeReview.Valid -> {
                        if (review.resultsHash != currentHash) {
                            Text(
                                stringResource(R.string.comp_host_prize_stale),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedButton({ viewModel.reviewPrizeClaim(claim) }) {
                                Text(stringResource(R.string.comp_host_prize_review_again))
                            }
                        } else {
                            Text(stringResource(R.string.comp_host_prize_destination), style = MaterialTheme.typography.bodySmall)
                            Text(review.destination, modifier = Modifier.testTag("competition_host_prize_destination_${prize.id}"))
                            if (prize.isCash) Button({
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("lightning:${review.destination}")))
                                }
                            }) { Text(stringResource(R.string.comp_host_prize_pay)) }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton({
                                    viewModel.hostPrizeDecision(prize.id, winner.pubkey, "approved")
                                }) { Text(stringResource(R.string.comp_host_prize_approve)) }
                                Button({ paidPrizeId = prize.id; paidPubkey = winner.pubkey }) {
                                    Text(stringResource(R.string.comp_host_prize_mark_paid))
                                }
                                TextButton({ rejectedPrizeId = prize.id; rejectedPubkey = winner.pubkey }) {
                                    Text(stringResource(R.string.comp_host_prize_reject))
                                }
                            }
                            Text(
                                stringResource(R.string.comp_host_prize_paid_assertion),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                if (state.prizes[prize.id]?.state == "paid") {
                    Text(
                        stringResource(
                            if (receipt != null) R.string.comp_host_prize_receipt_yes
                            else R.string.comp_host_prize_receipt_no,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun hostPrizeStateText(state: String): String = stringResource(when (state) {
    "claimed" -> R.string.comp_prize_state_claimed
    "approved" -> R.string.comp_prize_state_approved
    "paid" -> R.string.comp_prize_state_paid
    "rejected" -> R.string.comp_prize_state_rejected
    else -> R.string.comp_prize_state_expired
})

@Composable
private fun hostPrizeErrorText(error: String): String = stringResource(when (error) {
    "unreadable" -> R.string.comp_host_prize_error_unreadable
    "unknown_schema" -> R.string.comp_host_prize_error_schema
    "wrong_competition" -> R.string.comp_host_prize_error_competition
    "unknown_prize" -> R.string.comp_host_prize_error_unknown
    "stale_results" -> R.string.comp_host_prize_error_stale
    "already_awarded" -> R.string.comp_host_prize_error_awarded
    "nobody_is_eligible" -> R.string.comp_host_prize_error_no_winner
    "not_the_winner" -> R.string.comp_host_prize_error_not_winner
    "deadline_passed" -> R.string.comp_host_prize_error_deadline
    else -> R.string.comp_host_prize_error_destination
})
