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
 * The catalogues a sender offers, as it declares them. A current sender names only families
 * it holds catalogue climbs of — its CruxCoach community climbs never cross a share (they come
 * through Nostr), so a family it only has community climbs of is not on the list.
 */
internal fun offeredCatalogues(manifest: com.cruxcoach.android.util.LocalShareProtocol.Manifest): List<OfferedCatalogue> =
    offeredCatalogues(manifest.declaredCatalogues)

internal fun offeredCatalogues(declared: List<com.cruxcoach.android.util.LocalShareProtocol.BoardCatalogue>): List<OfferedCatalogue> =
    declared.mapNotNull { catalogue ->
        BoardBrand.fromWireOrNull(catalogue.boardBrand)
            ?.takeIf { it.isInteractive && catalogue.climbCount > 0 }
            ?.let { OfferedCatalogue(it, catalogue.climbCount) }
    }.distinctBy { it.brand }

/**
 * What the receiver starts with: everything the sender offers, ticked — a share is the cheap
 * way to get boards, and unticking one is one tap. From the internet: exactly what they had
 * chosen before that this sender cannot supply, and nothing new.
 */
internal fun initialShareSelection(
    offered: List<BoardBrand>,
    saved: Set<BoardBrand>?,
): Pair<Set<BoardBrand>, Set<BoardBrand>> =
    offered.toSet() to (saved.orEmpty() - offered.toSet())

/**
 * What is ticked in a share offer: the sender's families to take, and the other families to
 * fetch from the internet afterwards. Held as wire values so it survives rotation.
 */
internal class ShareChoice(
    val offered: List<OfferedCatalogue>,
    private val fromShare: androidx.compose.runtime.MutableState<List<String>>,
    private val fromInternet: androidx.compose.runtime.MutableState<List<String>>,
    val othersOpen: androidx.compose.runtime.MutableState<Boolean>,
) {
    val offeredBrands: List<BoardBrand> get() = offered.map { it.brand }
    val otherBrands: List<BoardBrand>
        get() = BoardBrand.entries.filter { it.isInteractive && it !in offeredBrands }
    val shareBrands: Set<BoardBrand> get() = offeredBrands.filter { it.wireValue in fromShare.value }.toSet()
    val onlineBrands: Set<BoardBrand> get() = otherBrands.filter { it.wireValue in fromInternet.value }.toSet()

    fun toggle(brand: BoardBrand) {
        val target = if (brand in offeredBrands) fromShare else fromInternet
        target.value = if (brand.wireValue in target.value) target.value - brand.wireValue
        else target.value + brand.wireValue
    }

    /** Make sure a family is wanted — from the sender if it has it, otherwise from the internet. */
    fun include(brand: BoardBrand) {
        if (!brand.isInteractive) return
        val target = if (brand in offeredBrands) fromShare else fromInternet
        if (brand.wireValue !in target.value) target.value = target.value + brand.wireValue
        if (brand !in offeredBrands) othersOpen.value = true
    }
}

@Composable
internal fun rememberShareChoice(
    key: String,
    offered: List<OfferedCatalogue>,
    savedSelection: Set<BoardBrand>?,
): ShareChoice {
    val initial = initialShareSelection(offered.map { it.brand }, savedSelection)
    val fromShare = rememberSaveable(key) { mutableStateOf(initial.first.map { it.wireValue }) }
    val fromInternet = rememberSaveable(key) { mutableStateOf(initial.second.map { it.wireValue }) }
    // Open from the start when it already holds a choice: a ticked box nobody can see is a
    // download nobody agreed to.
    val othersOpen = rememberSaveable(key) { mutableStateOf(initial.second.isNotEmpty()) }
    return androidx.compose.runtime.remember(offered, fromShare, fromInternet, othersOpen) {
        ShareChoice(offered, fromShare, fromInternet, othersOpen)
    }
}

/**
 * The two-source catalogue choice of a share offer, used by the offer dialog and by onboarding.
 *
 * A share is one database with whatever the sender happens to have. Listing all families as
 * if they could be downloaded would offer boards this peer cannot deliver; importing
 * everything it has would fill the phone with walls the receiver never climbs. So the
 * sender's catalogues are the choice, in the same rows as every other catalogue selection and
 * with their climb counts, and the remaining families sit one tap away under their real
 * condition — they need the internet, and are fetched after the share.
 */
@Composable
internal fun ShareCatalogueChoice(
    host: String,
    choice: ShareChoice,
    title: String = stringResource(R.string.board_sync_discovered_share_from),
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            // Named after the heading it sits beside, so a screen reader announces what the
            // information is about rather than the title of a dialog that may not be open.
            InfoButton(title, stringResource(R.string.board_sync_discovered_share_info, host))
        }
        // The one fact the consent rests on stays in plain sight (design.md: an essential
        // consent may not live in an info card alone); the address and the detail are behind
        // the info button.
        Text(
            stringResource(R.string.board_sync_discovered_share_unverified),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("discovered_share_unverified"),
        )
        CatalogueSelectionRows(
            selectedBrands = choice.shareBrands,
            brands = choice.offeredBrands,
            detail = { brand ->
                choice.offered.firstOrNull { it.brand == brand }?.climbCount?.let { count ->
                    // Grouped like every other climb count in the app: "284.253", not "284253".
                    pluralStringResource(
                        R.plurals.board_sync_share_climbs,
                        count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        java.text.NumberFormat.getIntegerInstance().format(count),
                    )
                }
            },
            onToggleBrand = choice::toggle,
        )
        if (choice.otherBrands.isNotEmpty()) {
            val online = choice.onlineBrands
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clickable(role = Role.Button) { choice.othersOpen.value = !choice.othersOpen.value }
                    .testTag("discovered_share_others"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.board_sync_discovered_share_others),
                        style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (online.isEmpty()) stringResource(R.string.board_sync_discovered_share_others_hint)
                        else online.joinToString(" · ") { it.displayName },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(if (choice.othersOpen.value) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null)
            }
            if (choice.othersOpen.value) {
                CatalogueSelectionRows(
                    selectedBrands = online, brands = choice.otherBrands, onToggleBrand = choice::toggle,
                )
            }
        }
    }
}

/** The nearby-share offer outside onboarding: still one question, but the answer says WHICH boards. */
@Composable
internal fun DiscoveredShareDialog(
    host: String,
    offered: List<OfferedCatalogue>,
    savedSelection: Set<BoardBrand>?,
    fallsBackOnline: Boolean,
    onConfirm: (shareBrands: Set<BoardBrand>, onlineBrands: Set<BoardBrand>) -> Unit,
    onDismiss: () -> Unit,
) {
    val choice = rememberShareChoice(host, offered, savedSelection)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(Icons.Default.NetworkWifi, contentDescription = null, tint = OrangeAccent,
                modifier = Modifier.size(40.dp))
        },
        title = { Text(stringResource(R.string.board_sync_discovered_share_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) { ShareCatalogueChoice(host, choice) }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(choice.shareBrands, choice.onlineBrands) },
                // Nothing from this peer is not an answer to this question; the other
                // button is.
                enabled = choice.shareBrands.isNotEmpty(),
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
