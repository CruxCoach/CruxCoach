package com.cruxcoach.android.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant

/**
 * Tier-0 board category of the unified picker (FEAT-027 follow-up).
 *
 * Replaces the old 2-way brand chooser so onboarding and Settings present
 * the *same* three-way choice. Kilter Original and Kilter Homewall are one
 * brand but physically distinct boards (own layout, own hold geometry),
 * so they are first-class categories rather than a buried sub-segment.
 */
private enum class BoardCategory { KILTER_ORIGINAL, KILTER_HOMEWALL, MOONBOARD }

/** The interactive Aurora-family boards offered as tier-0 picks alongside
 *  Kilter + MoonBoard (FEAT-031). Data-driven over [BoardBrand] so a board
 *  promoted to interactive (usesAuroraProtocol, excl. Kilter itself) appears
 *  automatically with no further picker wiring. */
private val AURORA_PICK_BRANDS: List<BoardBrand> =
    BoardBrand.entries.filter { it.usesAuroraProtocol && it != BoardBrand.KILTER }

/**
 * Unified board picker — used by both onboarding and Settings.
 *
 * Tier 0: Kilter Original / Kilter Homewall / MoonBoard.
 * Tier 1: the Kilter categories show their product-size list (common
 * first); MoonBoard shows its variant list. A MoonBoard variant is a
 * fixed, standardised hold configuration, so it fully determines the
 * board — no separate size choice.
 *
 * @param initialBrand the active brand ("kilter" | "moonboard") — picks
 *        the landing category together with [selectedKilterSizeId].
 * @param productSizes the FULL Kilter size list (both products); the
 *        dialog filters it per category. Empty is handled gracefully.
 * @param frequency optional product-size-id → popularity, for "common
 *        boards first" ordering of the Kilter lists.
 */
@Composable
internal fun BoardSelectionDialog(
    initialBrand: String,
    productSizes: List<BoardSize>,
    selectedKilterSizeId: Int,
    selectedMoonBoardVariant: MoonBoardVariant?,
    onConfirmKilter: (Int) -> Unit,
    onConfirmMoonBoard: (MoonBoardVariant) -> Unit,
    /** FEAT-031: confirm an Aurora-family board (Tension etc.). Defaults to a
     *  no-op so call sites that don't yet offer Aurora boards (onboarding)
     *  compile unchanged. */
    onConfirmAurora: (BoardBrand) -> Unit = {},
    /** FEAT-031: show the interactive Aurora-family boards as tier-0 picks.
     *  Off by default — only the Settings board picker wires [onConfirmAurora]
     *  + the catalogue-sync trigger, so other call sites (filter, onboarding)
     *  stay Kilter/MoonBoard-only rather than offering a dead-end chip. */
    showAuroraBoards: Boolean = false,
    frequency: Map<Int, Long> = emptyMap(),
    /** "Don't know your board? find it via your gym" — FEAT-007 gym
     *  search. Shown in the Kilter categories only; null hides it. */
    onFindViaGym: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val initialCategory = remember(initialBrand, selectedKilterSizeId, productSizes) {
        when {
            BoardBrand.fromWire(initialBrand) == BoardBrand.MOONBOARD -> BoardCategory.MOONBOARD
            productSizes.firstOrNull { it.id.toInt() == selectedKilterSizeId }
                ?.productId?.toInt() == BoardConstants.KILTER_HOMEWALL_PRODUCT_ID ->
                BoardCategory.KILTER_HOMEWALL
            else -> BoardCategory.KILTER_ORIGINAL
        }
    }
    var category by remember { mutableStateOf(initialCategory) }
    var kilterSelection by remember { mutableIntStateOf(selectedKilterSizeId) }
    var mbVariant by remember {
        mutableStateOf(selectedMoonBoardVariant ?: MoonBoardVariant.entries.first())
    }
    // FEAT-031: an Aurora-family board (Tension etc.) as the active pick. When
    // non-null it takes precedence over [category]; selecting a Kilter/MoonBoard
    // chip clears it. Seeded from the active brand so re-opening lands on it.
    var auroraBrand by remember {
        mutableStateOf(
            BoardBrand.fromWire(initialBrand)
                .takeIf { showAuroraBoards && it.usesAuroraProtocol && it != BoardBrand.KILTER }
        )
    }

    val isAurora = auroraBrand != null
    val isKilter = !isAurora && category != BoardCategory.MOONBOARD
    val kilterProductId = if (category == BoardCategory.KILTER_HOMEWALL) {
        BoardConstants.KILTER_HOMEWALL_PRODUCT_ID
    } else {
        BoardConstants.KILTER_PRODUCT_ID
    }
    val shownSizes = remember(category, productSizes, frequency) {
        productSizes
            .filter { it.productId.toInt() == kilterProductId }
            .sortedByDescending { frequency[it.id.toInt()] ?: 0L }
    }
    val kilterEmpty = isKilter && shownSizes.isEmpty()

    // Switching category: if the current size pick doesn't belong to the
    // new category's product, fall to that category's most common size.
    LaunchedEffect(category) {
        if (isKilter && shownSizes.none { it.id.toInt() == kilterSelection }) {
            shownSizes.firstOrNull()?.let { kilterSelection = it.id.toInt() }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.board_selection_dialog_title),
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Tier 0 — board category. Stacked chips: the three labels
                // are too long to share one row on a narrow dialog.
                Text(
                    stringResource(R.string.board_category_label),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CategoryChip(
                        label = stringResource(R.string.board_category_kilter_original),
                        selected = !isAurora && category == BoardCategory.KILTER_ORIGINAL,
                        onSelect = { category = BoardCategory.KILTER_ORIGINAL; auroraBrand = null },
                    )
                    CategoryChip(
                        label = stringResource(R.string.board_category_kilter_homewall),
                        selected = !isAurora && category == BoardCategory.KILTER_HOMEWALL,
                        onSelect = { category = BoardCategory.KILTER_HOMEWALL; auroraBrand = null },
                    )
                    CategoryChip(
                        label = stringResource(R.string.board_category_moonboard),
                        selected = !isAurora && category == BoardCategory.MOONBOARD,
                        onSelect = { category = BoardCategory.MOONBOARD; auroraBrand = null },
                    )
                    // FEAT-031: interactive Aurora-family boards, data-driven so a
                    // newly-promoted board appears with no further picker wiring.
                    // Gated to call sites that wire onConfirmAurora (Settings).
                    if (showAuroraBoards) {
                        AURORA_PICK_BRANDS.forEach { brand ->
                            CategoryChip(
                                label = brand.displayName,
                                selected = auroraBrand == brand,
                                onSelect = { auroraBrand = brand },
                            )
                        }
                    }
                }

                HorizontalDivider()

                if (isAurora) {
                    Text(
                        stringResource(
                            R.string.board_selection_aurora_download_hint,
                            auroraBrand!!.displayName,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else if (isKilter) {
                    if (kilterEmpty) {
                        Text(
                            stringResource(R.string.board_model_dialog_body_missing_inline),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        Text(
                            stringResource(R.string.board_model_dialog_title_pick),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        shownSizes.forEach { size ->
                            RadioRow(
                                label = BoardConstants.sizeLabel(size.id, size.name),
                                selected = kilterSelection == size.id.toInt(),
                                onSelect = { kilterSelection = size.id.toInt() },
                            )
                        }
                        Text(
                            stringResource(R.string.board_model_dialog_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (onFindViaGym != null) {
                            TextButton(
                                onClick = onFindViaGym,
                                contentPadding = PaddingValues(horizontal = 0.dp),
                            ) {
                                Text(
                                    stringResource(R.string.settings_board_find_via_gym),
                                    color = OrangeAccent,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        stringResource(R.string.board_selection_moonboard_variant_label),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    MoonBoardVariant.entries.forEach { variant ->
                        RadioRow(
                            label = variant.displayName,
                            selected = mbVariant == variant,
                            onSelect = { mbVariant = variant },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when {
                        isAurora -> onConfirmAurora(auroraBrand!!)
                        isKilter -> onConfirmKilter(kilterSelection)
                        else -> onConfirmMoonBoard(mbVariant)
                    }
                },
                enabled = !kilterEmpty,
                colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    stringResource(R.string.board_model_dialog_confirm),
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.board_model_dialog_cancel))
            }
        },
    )
}

/** Tier-0 category chip — full-width so the three stack cleanly. */
@Composable
private fun CategoryChip(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onSelect,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = OrangeAccent.copy(alpha = 0.2f),
            selectedLabelColor = OrangeAccent,
        ),
    )
}

/** A single radio-selectable row — shared by the Kilter + MoonBoard tiers. */
@Composable
private fun RadioRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onSelect,
                role = Role.RadioButton,
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = OrangeAccent),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
