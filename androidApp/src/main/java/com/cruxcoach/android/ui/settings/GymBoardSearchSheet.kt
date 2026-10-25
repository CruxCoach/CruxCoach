package com.cruxcoach.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.android.ui.board.ZoomableBoardPreview
import com.cruxcoach.android.ui.common.LocateResult
import com.cruxcoach.android.ui.common.distanceLabel
import com.cruxcoach.android.ui.common.locateFailureText
import com.cruxcoach.android.ui.common.locateFixLabel
import com.cruxcoach.android.ui.common.openLocateFix
import com.cruxcoach.android.ui.common.rememberLocateMe
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
 *
 * "Gyms near me" lists the closest gyms with a supported board. The location
 * permission is asked for only when that button is tapped (or the host opened
 * the sheet from "Find a gym near you", [startNearby]); once it is granted,
 * the sheet shows the nearby gyms right away on every later open.
 */
@Composable
internal fun GymBoardSearchSheet(
    onClose: () -> Unit,
    onFallbackToDirect: () -> Unit,
    onDismiss: () -> Unit,
    vm: GymBoardPickerViewModel = hiltViewModel(),
    boardPickerViewModel: BoardPickerViewModel = hiltViewModel(),
    deferDownloads: Boolean = false,
    onBoardChosen: (BoardBrand) -> Unit = {},
    startNearby: Boolean = false,
    /** The rows of one gym to open directly (the board map's "Choose this
     *  board"); null starts with search / near me as usual. */
    startGym: List<BoardLocation>? = null,
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    var locateFailure by remember { mutableStateOf<LocateResult?>(null) }
    // True once the user (or the host's "Find a gym near you") asked for it;
    // a silent lookup on open never overrides typed text or shows errors.
    var nearbyAsked by remember { mutableStateOf(startNearby) }
    val locateMe = rememberLocateMe { result ->
        locateFailure = null
        when (result) {
            is LocateResult.Found ->
                if (nearbyAsked || vm.state.value.query.isBlank()) vm.showNearby(result.lat, result.lng)
            else -> if (nearbyAsked) locateFailure = result
        }
    }
    // Asked for explicitly by the host: a full search, prompt included.
    // Otherwise, only when already allowed, a short quiet lookup without a
    // prompt or a long GPS search; a tap on "near me" turns it into a full one.
    LaunchedEffect(Unit) {
        if (startGym != null) return@LaunchedEffect
        if (startNearby) locateMe.locate() else locateMe.locateQuietly()
    }
    BoardDownloadRequestHost(boardPickerViewModel, deferDownloads, onSelected = { brand ->
        vm.clearGymSelection()
        onBoardChosen(brand)
        onClose()
    }) { request ->

        // The VM is scoped to the HOST destination (Settings / board filter /
        // onboarding), so a previous visit's gym pick survives sheet close.
        // Reset on every sheet entry — otherwise the single-option auto-apply
        // below re-fires on reopen with the stale pick, silently rewriting the
        // board prefs and self-closing the sheet before the user can search.
        // Declared BEFORE the auto-apply effect: launch order guarantees the
        // clear runs first, and the auto-apply reads vm.state.value fresh.
        LaunchedEffect(Unit) {
            vm.clearGymSelection()
            startGym?.let(vm::openGym)
        }

        // Apply a chosen board option (routes every brand through the shared picker
        // VM) and close the sheet. Hoisted so a tapped card and the single-option
        // auto-apply below take the exact same path. Clears the gym selection
        // before closing so the consumed pick can never re-apply on reopen.
        val apply: (GymWallOption) -> Unit = { opt ->
            // Consume auto-apply before opening consent so cancellation cannot
            // re-trigger the same gym selection on recomposition.
            vm.clearGymSelection()
            request(opt.boardBrand) {
                when (opt.boardBrand) {
                    BoardBrand.QUANTUM ->
                        com.cruxcoach.domain.board.QuantumBoardModel.fromLayoutId(opt.layoutId.toLong())
                            ?.let { boardPickerViewModel.selectQuantum(it, deferDownloads) }
                    BoardBrand.MOONBOARD ->
                        MoonBoardVariant.fromLayoutId(opt.layoutId.toLong())
                            ?.let { boardPickerViewModel.selectMoonBoard(it) }
                    BoardBrand.SHINYWALL ->
                        com.cruxcoach.domain.board.ShinyWallVariant.fromLayoutId(opt.layoutId.toLong())
                            ?.let { boardPickerViewModel.selectShinyWall(it, deferDownloads) }
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
                            deferDownload = deferDownloads,
                        )
                }
            }
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
                    NearbyButton(
                        locating = locateMe.locating,
                        onClick = {
                            nearbyAsked = true
                            locateFailure = null
                            locateMe.locate()
                        },
                    )
                    if (nearbyAsked && locateMe.slow) {
                        StillLookingNote(onCancel = locateMe::cancel)
                    }
                    locateFailure?.let { failure ->
                        LocateFailureNote(failure) { locateFailure = null; locateMe.openFix(failure) }
                    }
                    Spacer(Modifier.height(10.dp))
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
                                        gymSubtitle(gym, s.brandsById[gym.id].orEmpty(), s.distanceById[gym.id]?.let { distanceLabel(it) }).takeIf { it.isNotEmpty() }?.let {
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
                                // "N boards — pick the one you're at" ONLY when the
                                // gym genuinely has several board TYPES (>1 brand,
                                // e.g. Kilter + MoonBoard). A single brand with many
                                // options is one board of unknown variant (e.g. the 5
                                // MoonBoard variants) — saying "5 boards" there was
                                // misleading, so ask which variant instead.
                                val brandCount = remember(s.wallOptions) {
                                    s.wallOptions.map { it.boardBrand }.distinct().size
                                }
                                Text(
                                    when {
                                        s.wallOptions.size == 1 ->
                                            stringResource(R.string.feat007_gym_one)
                                        brandCount > 1 ->
                                            stringResource(R.string.feat007_gym_categories)
                                        else ->
                                            stringResource(R.string.feat007_gym_variant)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.height(10.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    s.wallOptions.forEach { opt ->
                                        BoardOptionCard(opt) { apply(opt) }
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
                            if (s.nearby) {
                                Text(
                                    stringResource(R.string.gym_nearby_title),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                s.results.forEach { g ->
                                    val distance = s.distanceById[g.id]?.let { distanceLabel(it) }
                                    GymRow(
                                        g.name,
                                        gymSubtitle(g, s.brandsById[g.id].orEmpty(), distance),
                                        modifier = Modifier.testTag("gym_result_${g.id}"),
                                    ) { vm.selectGym(g) }
                                }
                            }
                        }

                        s.nearby -> {
                            Text(
                                stringResource(R.string.gym_nearby_none),
                                style = MaterialTheme.typography.bodyMedium,
                            )
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
}

/** Result subtitle: the gym's boards ("Kilter, MoonBoard"), its place and,
 *  for near-me results, the distance. Drops the placeholder "??" country
 *  (MoonBoard gyms have no country in the feed). Without the gym's brand list
 *  a MoonBoard row still says so, to tell it from a Kilter gym. */
private fun gymSubtitle(g: BoardLocation, brands: List<BoardBrand>, distance: String?): String {
    val place = buildList {
        g.city?.takeIf { it.isNotBlank() }?.let { add(it) }
        g.countryCode.takeIf { it.isNotBlank() && it != "??" }?.let { add(it) }
    }.joinToString(", ")
    val boards = when {
        brands.isNotEmpty() -> brands.joinToString(", ") { it.displayName }
        g.boardBrand == BoardBrand.MOONBOARD -> "MoonBoard"
        else -> ""
    }
    return listOf(boards, place, distance.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
}

/**
 * "Gyms near me" — the location prompt appears only after this tap. It stays
 * tappable while a lookup runs: a tap during the silent lookup on open (up to
 * 45 s for a cold GPS start) turns it into a search that looks until a
 * position arrives, instead of letting it end without a word.
 */
@Composable
internal fun NearbyButton(locating: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        FilledTonalButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().testTag("gym_nearby"),
        ) {
            if (locating) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (locating) R.string.locate_me_finding else R.string.gym_nearby_button))
        }
        Text(
            stringResource(R.string.locate_me_private),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, start = 4.dp),
        )
    }
}

/** A tap's search has found nothing for a while; it looks on until a position arrives (GPS indoors may need minutes). */
@Composable
private fun StillLookingNote(onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            stringResource(R.string.locate_me_still_looking),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onCancel, contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text(stringResource(R.string.action_cancel), color = OrangeAccent)
        }
    }
}

@Composable
private fun LocateFailureNote(failure: LocateResult, onFix: () -> Unit) {
    val text = locateFailureText(failure) ?: return
    // Stopping the search was the user's choice, not an error.
    val color = if (failure is LocateResult.Stopped) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
        locateFixLabel(failure)?.let { label ->
            TextButton(onClick = onFix, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text(label, color = OrangeAccent)
            }
        }
    }
}

/** Tappable gym search result — clearly a row you can pick (chevron + ripple). */

@Composable
private fun GymRow(name: String, sub: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedCard(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
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
private fun BoardOptionCard(option: GymWallOption, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = OrangeAccent.copy(alpha = 0.12f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Board-image thumbnail so the user can visually match their board
            // instead of decoding the size code; grid icon as fallback.
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                ZoomableBoardPreview(
                    brand = option.boardBrand,
                    sizeId = option.productSizeId.toLong(),
                    layoutId = option.layoutId.toLong(),
                    modifier = Modifier.fillMaxSize(),
                    fallback = {
                        Icon(Icons.Default.GridView, contentDescription = null, tint = OrangeAccent)
                    },
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    option.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null, tint = OrangeAccent,
            )
        }
    }
}
