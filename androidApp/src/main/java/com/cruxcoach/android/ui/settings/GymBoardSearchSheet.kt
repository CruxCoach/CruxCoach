package com.cruxcoach.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.data.repository.BoardLocation
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant

/**
 * FEAT-007 Path B sheet — "I don't know my board → find my gym".
 * Local offline search; pick the gym, then the board in front of you
 * (1 wall = one tap, multi-board gyms = choose). Covers every brand
 * (FEAT-031): the pick is routed through the shared [BoardPickerViewModel]
 * — the single selection source for Kilter, MoonBoard and the Aurora
 * family — then [onClose] closes the sheet.
 */
@Composable
internal fun GymBoardSearchSheet(
    onClose: () -> Unit,
    onFallbackToDirect: () -> Unit,
    onDismiss: () -> Unit,
    vm: GymBoardPickerViewModel = hiltViewModel(),
    boardPickerViewModel: BoardPickerViewModel = hiltViewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()

    // The VM is scoped to the HOST destination (Settings / board filter /
    // onboarding), so a previous visit's gym pick survives sheet close.
    // Reset on every sheet entry — otherwise the single-option auto-apply
    // below re-fires on reopen with the stale pick, silently rewriting the
    // board prefs and self-closing the sheet before the user can search.
    // Declared BEFORE the auto-apply effect: launch order guarantees the
    // clear runs first, and the auto-apply reads vm.state.value fresh.
    LaunchedEffect(Unit) { vm.clearGymSelection() }

    // Apply a chosen board option (routes every brand through the shared picker
    // VM) and close the sheet. Hoisted so a tapped card and the single-option
    // auto-apply below take the exact same path. Clears the gym selection
    // before closing so the consumed pick can never re-apply on reopen.
    val apply: (GymWallOption) -> Unit = { opt ->
        when (opt.boardBrand) {
            BoardBrand.MOONBOARD ->
                MoonBoardVariant.fromLayoutId(opt.layoutId.toLong())
                    ?.let { boardPickerViewModel.selectMoonBoard(it) }
            BoardBrand.KILTER ->
                // fixedAngle is non-null only for a fixed-angle wall → seeds the
                // browse angle; adjustable walls leave it to the user.
                boardPickerViewModel.selectKilter(opt.productSizeId, opt.fixedAngle)
            else ->
                boardPickerViewModel.selectAurora(
                    opt.boardBrand,
                    BoardConstants.auroraVariant(opt.boardBrand, opt.layoutId),
                    // 0 = no explicit size → selector derives the default.
                    opt.productSizeId.takeIf { it > 0 },
                )
        }
        vm.clearGymSelection()
        onClose()
    }

    // A gym that resolves to exactly ONE board (97% of Kilter gyms, Touchstone's
    // single size, any cron-resolved MoonBoard/Decoy variant) needs no
    // confirmation tap — apply it the moment the gym is picked. Reads
    // vm.state.value (not the composition snapshot `s`) so the entry-reset
    // above is already visible and a stale pick can't slip through.
    LaunchedEffect(s.selectedGym?.id, s.wallOptions.size) {
        val cur = vm.state.value
        if (cur.selectedGym != null && cur.wallOptions.size == 1) {
            apply(cur.wallOptions.first())
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.feat007_gym_search_title), fontWeight = FontWeight.Bold)
        },
        text = {
            // Single scroll container for the whole dialog body (search
            // field scrolls with content) — same pattern as
            // BoardSelectionDialog. Inner lists are plain Columns; a
            // nested verticalScroll of the same orientation would crash.
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = s.query,
                    onValueChange = vm::onQueryChange,
                    label = { Text(stringResource(R.string.feat007_gym_search_field)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))

                val gym = s.selectedGym
                when {
                    gym != null -> {
                        // Selected-gym header card
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Place, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(gym.name, fontWeight = FontWeight.Bold)
                                    gymSubtitle(gym).takeIf { it.isNotEmpty() }?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))

                        if (s.wallOptions.isEmpty()) {
                            Text(
                                stringResource(R.string.feat007_gym_no_walls),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else {
                            Text(
                                if (s.wallOptions.size == 1)
                                    stringResource(R.string.feat007_gym_one)
                                else
                                    stringResource(R.string.feat007_gym_many, s.wallOptions.size),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(10.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                s.wallOptions.forEach { opt ->
                                    BoardOptionCard(opt.label, opt.isRecommended) { apply(opt) }
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = vm::clearGymSelection) {
                            Text(stringResource(R.string.feat007_back), color = OrangeAccent)
                        }
                    }

                    s.searching -> {
                        Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(28.dp), color = OrangeAccent)
                        }
                    }

                    // Real results ALWAYS win — even when `enabled` (a snapshot
                    // taken at VM init, possibly stale until a search self-heals
                    // it) still says false: a successful search proves the data
                    // is there, so it must never be masked by the no-data text.
                    s.results.isNotEmpty() -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            s.results.forEach { g ->
                                GymRow(g.name, gymSubtitle(g)) { vm.selectGym(g) }
                            }
                        }
                    }

                    // No gym/wall data on the device (fresh install before the
                    // location chunk arrives): searching would hit an empty DB,
                    // so say so instead of silently returning "no gym found".
                    // The persistent fallback below still leads to the manual
                    // picker.
                    !s.enabled -> {
                        Text(
                            stringResource(R.string.feat007_gym_loading),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    s.query.trim().length < 2 -> {
                        Text(
                            stringResource(R.string.feat007_gym_search_min),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> {
                        Text(
                            stringResource(R.string.feat007_gym_search_none),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                // Persistent escape hatch: the manual board picker is always one
                // tap away, not only when a search returns nothing.
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onFallbackToDirect) {
                    Text(
                        stringResource(R.string.settings_board_find_via_gym_fallback),
                        color = OrangeAccent,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}

/** Result subtitle. Drops the placeholder "??" country (MoonBoard gyms have
 *  no country in the feed) and tags MoonBoard gyms with the brand so they're
 *  distinguishable from Kilter gyms in a mixed result list. */
private fun gymSubtitle(g: BoardLocation): String {
    val parts = buildList {
        g.city?.takeIf { it.isNotBlank() }?.let { add(it) }
        g.countryCode.takeIf { it.isNotBlank() && it != "??" }?.let { add(it) }
    }
    return if (g.boardBrand == BoardBrand.MOONBOARD) {
        (listOf("MoonBoard") + parts).joinToString(" · ")
    } else {
        parts.joinToString(", ")
    }
}

/** Tappable gym search result — clearly a row you can pick (chevron + ripple). */
@Composable
private fun GymRow(name: String, sub: String, onClick: () -> Unit) {
    OutlinedCard(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Place, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                if (sub.isNotEmpty()) Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A board choice rendered as a prominent, obviously-tappable CTA card
 * (accent tint + icon + chevron) so it's unmistakable that tapping it
 * selects that board immediately.
 */
@Composable
private fun BoardOptionCard(label: String, recommended: Boolean = false, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = OrangeAccent.copy(alpha = 0.12f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.GridView, contentDescription = null, tint = OrangeAccent)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (recommended) {
                    Text(
                        stringResource(R.string.feat007_gym_recommended),
                        style = MaterialTheme.typography.labelSmall,
                        color = OrangeAccent,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null, tint = OrangeAccent,
            )
        }
    }
}
