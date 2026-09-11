package com.cruxcoach.android.ui.sharing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.sharing.PeerIdParseError
import com.cruxcoach.android.sharing.SharingWriteError
import com.cruxcoach.domain.sharing.AccessDecision
import com.cruxcoach.domain.sharing.AccessEffect
import com.cruxcoach.domain.sharing.NativeSharingGateState
import com.cruxcoach.domain.sharing.SharingCircle

/**
 * The presentational pieces of the sharing screens.
 *
 * Split out from the screens so they take plain values instead of a ViewModel:
 * that is what lets the accessibility behaviour — the single spoken sentence
 * per row, the readable gate reasons — be asserted against the real production
 * composables rather than a copy of them in a test.
 */

/**
 * Renders the sentence a screen reader speaks for one category.
 *
 * The `mergeDescendants` semantics are deliberate: three separate `Text` nodes
 * would be announced as three disconnected fragments, and the whole point of
 * this row is that "what", "released or not" and "why" arrive together.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SharingCategoryRow(
    categoryLabel: String,
    released: Boolean,
    reason: String,
    allowSelected: Boolean = false,
    denySelected: Boolean = false,
    enabled: Boolean = true,
    onAllow: () -> Unit = {},
    onDeny: () -> Unit = {},
    onClear: () -> Unit = {},
) {
    val outcome = stringResource(
        if (released) R.string.sharing_released else R.string.sharing_not_released
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "$categoryLabel: $outcome. $reason"
            },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(categoryLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(outcome, style = MaterialTheme.typography.bodyMedium)
            Text(reason, style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            // Wrapping like the other permission pickers: "Kreis-Einstellung
            // verwenden" is a long label, and an action chip cut off at the
            // screen edge is one a user cannot press.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = allowSelected,
                    onClick = onAllow,
                    enabled = enabled,
                    label = { Text(stringResource(R.string.sharing_action_allow)) },
                )
                FilterChip(
                    selected = denySelected,
                    onClick = onDeny,
                    enabled = enabled,
                    label = { Text(stringResource(R.string.sharing_action_deny)) },
                )
                FilterChip(
                    selected = false,
                    onClick = onClear,
                    enabled = enabled,
                    label = { Text(stringResource(R.string.sharing_action_clear)) },
                )
            }
        }
    }
}

/**
 * The honest reason line.
 *
 * When something is released, the interesting rule is the owner's own; when it
 * is being held back, the gate that holds it is what the user needs to read.
 */
@Composable
fun sharingReasonText(
    policyDecision: AccessDecision,
    effectiveDecision: AccessDecision,
    peerCircle: SharingCircle,
): String {
    val decision = if (effectiveDecision.effect == AccessEffect.ALLOW) policyDecision else effectiveDecision
    return if (SharingLabels.sourceTakesCircle(decision.source)) {
        val circle = decision.inheritedFrom ?: peerCircle
        stringResource(SharingLabels.source(decision.source), stringResource(SharingLabels.circle(circle)))
    } else {
        stringResource(SharingLabels.source(decision.source))
    }
}

/** The "preview, live sync locked" banner with the evidenced reasons. */
@Composable
fun SharingGateBanner(gate: NativeSharingGateState) {
    val blocked = gate as? NativeSharingGateState.Blocked ?: return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.sharing_preview_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.sharing_preview_body), style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(R.string.sharing_preview_reasons_title),
                style = MaterialTheme.typography.labelLarge,
            )
            blocked.reasons.forEach { reason ->
                Text(
                    stringResource(SharingLabels.gateReason(reason)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (blocked.evidence.revision.isNotBlank()) {
                Text(
                    stringResource(R.string.sharing_gate_revision, blocked.evidence.revision.take(12)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** What a withdrawal cannot reach. Stated plainly, not softened. */
@Composable
fun SharingExternalCopiesCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.sharing_external_copies_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            HorizontalDivider()
            Text(
                stringResource(R.string.sharing_external_copies_body),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * The reason an invited identity was refused.
 *
 * Its own composable so the message is real production code rather than a
 * string a test asserts about in isolation, and so the announcement can be
 * checked: `isError` paints a border, which tells a screen-reader user nothing
 * about what actually went wrong.
 */
@Composable
fun SharingInviteError(error: PeerIdParseError) {
    val message = stringResource(SharingLabels.peerIdError(error))
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { contentDescription = message },
    )
}

/**
 * A write that did not happen.
 *
 * Shown rather than swallowed: a mutation that cannot be signed used to leave
 * the screen looking exactly like a successful one.
 */
/**
 * Says that a signature is being waited for, and that nothing else can be
 * changed until it arrives.
 *
 * With a local key there was nothing to show: signing finished before the
 * finger left the glass. An external signer answers in its own app and its own
 * time, so without this the screen simply goes quiet — which reads as a tap
 * that did not register, and says nothing at all to a screen reader.
 *
 * One banner rather than a spinner per control: what is blocked is *every*
 * mutation, because permission changes are taken one at a time.
 */
@Composable
fun SharingSigningBanner(signing: Boolean) {
    if (!signing) return
    val spoken = stringResource(R.string.sharing_cd_signing)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.sharing_signing_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(stringResource(R.string.sharing_signing_body), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun SharingWriteErrorMessage(error: SharingWriteError) {
    val message = stringResource(SharingLabels.writeError(error))
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { contentDescription = message },
    )
}

/**
 * The three exclusive circles as a wrapping chip row.
 *
 * Wrapping rather than a fixed [Row]: three German circle names do not fit
 * across a narrow screen, and a cut-off circle name in a permission picker is
 * worse than a second line. Each chip carries its own content description, so
 * the selection is reachable and announceable.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SharingCirclePicker(
    selected: SharingCircle,
    onSelect: (SharingCircle) -> Unit,
    enabled: Boolean = true,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SharingCircle.ordered.forEach { circle ->
            val label = stringResource(SharingLabels.circle(circle))
            FilterChip(
                selected = selected == circle,
                onClick = { onSelect(circle) },
                enabled = enabled,
                label = { Text(label) },
                modifier = Modifier.semantics { contentDescription = label },
            )
        }
    }
}
