package com.cruxcoach.android.ui.board.sync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.NetworkWifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.common.InfoButton
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.domain.board.BoardBrand

/** One of the sender's catalogues, as its manifest declares it. */
internal data class OfferedCatalogue(val brand: BoardBrand, val climbCount: Long)

/**
 * What the receiver starts with.
 *
 * From the sender: what they already chose, where that overlaps — somebody set up for Kilter
 * did not ask for the sender's MoonBoard — and otherwise everything on offer, because a first
 * run with no choice yet is here to get boards. From the internet: exactly what they had
 * chosen that this sender cannot supply, and nothing new.
 */
internal fun initialShareSelection(
    offered: List<BoardBrand>,
    saved: Set<BoardBrand>?,
): Pair<Set<BoardBrand>, Set<BoardBrand>> {
    val chosen = saved.orEmpty()
    val fromShare = offered.filter { it in chosen }.ifEmpty { offered }.toSet()
    return fromShare to (chosen - offered.toSet())
}

/**
 * The nearby-share offer: still one question, but the answer now says WHICH boards.
 *
 * A share is one database with whatever the sender happens to have. Listing all families here
 * as if they could be downloaded would offer boards this peer cannot deliver; importing
 * everything it has would fill the phone with walls the receiver never climbs. So the
 * sender's catalogues are the choice, in the same rows as every other catalogue selection,
 * and the remaining families sit one tap away under their real condition — they need the
 * internet, and are fetched after the share.
 */
@Composable
internal fun DiscoveredShareDialog(
    host: String,
    offered: List<OfferedCatalogue>,
    savedSelection: Set<BoardBrand>?,
    fallsBackOnline: Boolean,
    onConfirm: (shareBrands: Set<BoardBrand>, onlineBrands: Set<BoardBrand>) -> Unit,
    onDismiss: () -> Unit,
) {
    val offeredBrands = offered.map { it.brand }
    val otherBrands = BoardBrand.entries.filter { it.isInteractive && it !in offeredBrands }
    val initial = initialShareSelection(offeredBrands, savedSelection)
    var fromShare by rememberSaveable(host) { mutableStateOf(initial.first.map { it.wireValue }) }
    var fromInternet by rememberSaveable(host) { mutableStateOf(initial.second.map { it.wireValue }) }
    // Open from the start when it already holds a choice: a ticked box nobody can see is
    // a download nobody agreed to.
    var othersOpen by rememberSaveable(host) { mutableStateOf(initial.second.isNotEmpty()) }
    val shareSet = offeredBrands.filter { it.wireValue in fromShare }.toSet()
    val onlineSet = otherBrands.filter { it.wireValue in fromInternet }.toSet()

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(Icons.Default.NetworkWifi, contentDescription = null, tint = OrangeAccent,
                modifier = Modifier.size(40.dp))
        },
        title = { Text(stringResource(R.string.board_sync_discovered_share_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.board_sync_discovered_share_from, host),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    InfoButton(
                        stringResource(R.string.board_sync_discovered_share_title),
                        stringResource(R.string.board_sync_discovered_share_info, host),
                    )
                }
                CatalogueSelectionRows(
                    selectedBrands = shareSet,
                    brands = offeredBrands,
                    detail = { brand ->
                        offered.firstOrNull { it.brand == brand }?.climbCount?.toInt()?.let {
                            pluralStringResource(R.plurals.board_sync_share_climbs, it, it)
                        }
                    },
                ) { brand ->
                    fromShare = if (brand.wireValue in fromShare) fromShare - brand.wireValue
                    else fromShare + brand.wireValue
                }
                if (otherBrands.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .clickable(role = Role.Button) { othersOpen = !othersOpen }
                            .testTag("discovered_share_others"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.board_sync_discovered_share_others),
                                style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (onlineSet.isEmpty()) stringResource(R.string.board_sync_discovered_share_others_hint)
                                else onlineSet.joinToString(" · ") { it.displayName },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(if (othersOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null)
                    }
                    if (othersOpen) {
                        CatalogueSelectionRows(selectedBrands = onlineSet, brands = otherBrands) { brand ->
                            fromInternet = if (brand.wireValue in fromInternet) fromInternet - brand.wireValue
                            else fromInternet + brand.wireValue
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(shareSet, onlineSet) },
                // Nothing from this peer is not an answer to this question; the other
                // button is.
                enabled = shareSet.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                modifier = Modifier.testTag("board_sync_discovered_share_confirm"),
            ) { Text(stringResource(R.string.board_sync_discovered_share_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss,
                modifier = Modifier.testTag("board_sync_discovered_share_internet")) {
                Text(stringResource(
                    if (fallsBackOnline) R.string.board_sync_discovered_share_internet
                    else R.string.action_cancel,
                ))
            }
        },
    )
}
