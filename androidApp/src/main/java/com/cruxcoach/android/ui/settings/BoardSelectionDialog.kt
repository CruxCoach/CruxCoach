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
import com.cruxcoach.android.ui.board.BoardPreviewImage
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BoardSelectionDialog(
    initialBrand: String,
    productSizes: List<BoardSize>,
    selectedKilterSizeId: Int,
    selectedMoonBoardVariant: MoonBoardVariant?,
    /** FEAT-031: the active board's layout_id, used to seed the selected
     *  Aurora variant (e.g. Tension TB2 Mirror) when re-opening the picker. */
    selectedAuroraLayoutId: Int = 0,
    /** FEAT-031: the active board's product_size_id, used to seed the Aurora
     *  size tier so re-opening shows the current size selected. */
    selectedAuroraProductSizeId: Int = 0,
    /** FEAT-031: wire values of Aurora brands whose catalogue is already
     *  imported. Drives whether the "we'll download …" hint is shown — once a
     *  board is loaded the hint is misleading, so it's hidden. */
    loadedAuroraBrands: Set<String> = emptySet(),
    /** FEAT-031: picker-ready (deduped) product sizes per Aurora brand, keyed by
     *  wire value. Drives the post-sync size tier for EVERY interactive board —
     *  variant boards (Tension/Decoy) and single-layout boards (Grasshopper/So
     *  iLL); empty pre-sync so the tier stays hidden. */
    auroraBrandSizes: Map<String, List<BoardSize>> = emptyMap(),
    onConfirmKilter: (Int) -> Unit,
    onConfirmMoonBoard: (MoonBoardVariant) -> Unit,
    /** FEAT-031: confirm an Aurora-family board (Tension etc.) + the chosen
     *  variant (null for single-layout boards) + the chosen product size (null
     *  when no size tier is shown, i.e. pre-sync — the selector then uses the
     *  variant default). Defaults to a no-op so call sites that don't offer
     *  Aurora boards (onboarding) compile unchanged. */
    onConfirmAurora: (BoardBrand, BoardConstants.AuroraVariant?, Int?) -> Unit = { _, _, _ -> },
    /** FEAT-031: show the interactive Aurora-family boards as tier-0 picks.
     *  Off by default; the shared [BoardPickerDialog] wrapper turns it ON and
     *  wires [onConfirmAurora] + the catalogue-sync trigger, so both call sites
     *  that go through it (Settings AND onboarding) offer Aurora boards. Direct
     *  call sites that leave it off (e.g. the browse filter's quick board
     *  switch) stay Kilter/MoonBoard-only rather than showing a dead-end chip. */
    showAuroraBoards: Boolean = false,
    frequency: Map<Int, Long> = emptyMap(),
    /** "Don't know your board? find it via your gym" — FEAT-007 gym
     *  search. Shown in the Kilter categories only; null hides it. */
    onFindViaGym: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val activeBrand = remember(initialBrand) { BoardBrand.fromWire(initialBrand) }
    // Brand-guard the persisted size/layout seeds: the prefs hold the ACTIVE
    // board's ids, and the boards share one id space (Kilter size 7/8 vs
    // Tension TB2 7/8, So iLL 2 vs Decoy 2, Kilter layout 1 vs Decoy Dots
    // layout 1). Seeding another brand's tier from them would pre-select —
    // or, worse, CONFIRM — a nonexistent or wrong option, so each tier only
    // honours the prefs when the active brand owns them, else falls to that
    // tier's default. 0 = "no selection yet".
    val seedKilterSizeId = if (activeBrand == BoardBrand.KILTER) selectedKilterSizeId else 0
    val initialCategory = remember(initialBrand, selectedKilterSizeId, productSizes) {
        when {
            activeBrand == BoardBrand.MOONBOARD -> BoardCategory.MOONBOARD
            productSizes.firstOrNull { it.id.toInt() == seedKilterSizeId }
                ?.productId?.toInt() == BoardConstants.KILTER_HOMEWALL_PRODUCT_ID ->
                BoardCategory.KILTER_HOMEWALL
            else -> BoardCategory.KILTER_ORIGINAL
        }
    }
    var category by remember { mutableStateOf(initialCategory) }
    var kilterSelection by remember { mutableIntStateOf(seedKilterSizeId) }
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
    // FEAT-031: the chosen variant for a multi-layout Aurora board (Tension:
    // TB1 / TB2 Mirror / TB2 Spray). null for single-layout boards. Re-seeded
    // whenever the board changes — to the active layout's variant if re-opening
    // on that board, else the board's first variant.
    var auroraVariant by remember { mutableStateOf<BoardConstants.AuroraVariant?>(null) }
    LaunchedEffect(auroraBrand) {
        auroraVariant = auroraBrand?.let { b ->
            val variants = BoardConstants.auroraVariants(b)
            // Honour the persisted layout only when THIS brand is the active
            // one — layout ids collide across brands (Kilter Original layout 1
            // would otherwise pre-select Decoy Dots).
            variants.takeIf { b == activeBrand }
                ?.firstOrNull { it.layoutId == selectedAuroraLayoutId }
                ?: variants.firstOrNull()
        }
    }
    // FEAT-031: the chosen product size for the active Aurora variant. Seeded to
    // the active size when it belongs to this variant's product, else the
    // variant default; null when no size tier is shown (pre-sync / single-size).
    var auroraSizeId by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(auroraVariant, auroraBrand, auroraBrandSizes) {
        val brand = auroraBrand
        val variant = auroraVariant
        val sizes = if (brand == null) emptyList() else {
            val all = auroraBrandSizes[brand.wireValue].orEmpty()
            // Variant boards: only that variant's product. Single-layout
            // boards: the whole brand's sizes.
            variant?.let { v -> all.filter { it.productId.toInt() == v.productId } } ?: all
        }
        auroraSizeId = when {
            sizes.isEmpty() -> null
            // Honour the persisted size only when THIS brand is the active one
            // — size ids collide across brands (So iLL 2 vs Decoy 2), so a
            // stale foreign id must not mis-seed the tier.
            brand == activeBrand && sizes.any { it.id.toInt() == selectedAuroraProductSizeId } ->
                selectedAuroraProductSizeId
            variant != null -> variant.defaultSizeId
            // Single-layout board: default to the largest size, matching the
            // catalogue-derived default (getDefaultProductSizeForBrand).
            else -> sizes.maxByOrNull { (it.edgeRight - it.edgeLeft) * (it.edgeTop - it.edgeBottom) }?.id?.toInt()
        }
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

    // Switching to a Kilter category: if the current size pick doesn't belong
    // to its product, fall to that category's most common size. Keyed on
    // auroraBrand too — switching from an Aurora board to Kilter often leaves
    // [category] unchanged, but kilterSelection (0 or a foreign id) still
    // needs the same correction or Confirm would persist an invalid size.
    LaunchedEffect(category, auroraBrand) {
        if (isKilter && shownSizes.none { it.id.toInt() == kilterSelection }) {
            shownSizes.firstOrNull()?.let { kilterSelection = it.id.toInt() }
        }
    }

    // Current selection as (brand, sizeId, layoutId) for the image preview, so
    // the user can visually match their board instead of decoding a size code.
    val (previewBrand, previewSizeId, previewLayoutId) = when {
        isAurora -> Triple(
            auroraBrand!!,
            (auroraSizeId ?: auroraVariant?.defaultSizeId ?: 0).toLong(),
            auroraVariant?.layoutId?.toLong(),
        )
        category == BoardCategory.MOONBOARD ->
            Triple(BoardBrand.MOONBOARD, 0L, mbVariant.layoutId)
        else -> Triple(
            BoardBrand.KILTER,
            kilterSelection.toLong(),
            if (category == BoardCategory.KILTER_HOMEWALL) 8L else 1L,
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.board_selection_dialog_title),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                // FEAT-007 gym search — "Don't know your board?". Shown for every
                // board (moved out of the Kilter sub-block) so the escape hatch is
                // always one tap away regardless of the active category.
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
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Board-image preview of the current selection — a visual match
                // beats interpreting a cryptic size code (FEAT-007).
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .padding(8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        BoardPreviewImage(
                            brand = previewBrand,
                            sizeId = previewSizeId,
                            layoutId = previewLayoutId,
                            modifier = Modifier.fillMaxHeight(),
                        )
                    }
                }
                // Tier 0 — board category. A single dropdown: the labels are too
                // long to share one chip row on a narrow dialog, and the list grows
                // with each interactive Aurora board (FEAT-031).
                Text(
                    stringResource(R.string.board_category_label),
                    style = MaterialTheme.typography.bodyMedium,
                )
                // Each entry pairs its menu label with the state-write the old chip
                // did, in the same order. Aurora brands are appended only when
                // offered (Settings), so other call sites stay Kilter/MoonBoard.
                val kilterOriginalLabel = stringResource(R.string.board_category_kilter_original)
                val kilterHomewallLabel = stringResource(R.string.board_category_kilter_homewall)
                val moonBoardLabel = stringResource(R.string.board_category_moonboard)
                val boardOptions = buildList {
                    add(kilterOriginalLabel to {
                        category = BoardCategory.KILTER_ORIGINAL; auroraBrand = null
                    })
                    add(kilterHomewallLabel to {
                        category = BoardCategory.KILTER_HOMEWALL; auroraBrand = null
                    })
                    add(moonBoardLabel to {
                        category = BoardCategory.MOONBOARD; auroraBrand = null
                    })
                    if (showAuroraBoards) {
                        AURORA_PICK_BRANDS.forEach { brand ->
                            add(brand.displayName to { auroraBrand = brand })
                        }
                    }
                }
                // The collapsed field mirrors the active selection: an Aurora brand
                // wins over [category] (same precedence as the rest of the dialog).
                val selectedBoardLabel = when {
                    isAurora -> auroraBrand!!.displayName
                    category == BoardCategory.KILTER_ORIGINAL -> kilterOriginalLabel
                    category == BoardCategory.KILTER_HOMEWALL -> kilterHomewallLabel
                    else -> moonBoardLabel
                }
                var boardMenuExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = boardMenuExpanded,
                    onExpandedChange = { boardMenuExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedBoardLabel,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = boardMenuExpanded)
                        },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = boardMenuExpanded,
                        onDismissRequest = { boardMenuExpanded = false },
                        // Cap the height so the growing board list (FEAT-031)
                        // stays on-screen and scrolls instead of overflowing.
                        modifier = Modifier.heightIn(max = 280.dp),
                    ) {
                        boardOptions.forEach { (label, onSelect) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = { onSelect(); boardMenuExpanded = false },
                            )
                        }
                    }
                }

                HorizontalDivider()

                if (isAurora) {
                    // FEAT-031: variant tier for multi-layout boards (Tension:
                    // TB1 / TB2 Mirror / TB2 Spray). Single-layout boards have
                    // no entry in the catalog, so this is skipped for them.
                    val variants = BoardConstants.auroraVariants(auroraBrand!!)
                    if (variants.size > 1) {
                        Text(
                            stringResource(R.string.board_selection_variant_label),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        variants.forEach { v ->
                            RadioRow(
                                label = v.displayName,
                                selected = auroraVariant?.layoutId == v.layoutId,
                                onSelect = { auroraVariant = v },
                            )
                        }
                    }
                    // FEAT-031: size tier — once the board's catalogue is synced,
                    // let the user pick the exact product size (e.g. Grasshopper
                    // GrandMaster / Master / Ninja, or Tension TB2 12x12 / 10x12
                    // / 12x8 / 10x8) instead of being pinned to the largest. For
                    // a variant board it shows that variant's product; for a
                    // single-layout board (Grasshopper / So iLL) the whole
                    // brand. Hidden pre-sync and for single-size boards. Labels
                    // come from the synced product_sizes (sizeLabel is Kilter-only).
                    val brandSizes = auroraBrandSizes[auroraBrand!!.wireValue].orEmpty()
                    val auroraSizes = auroraVariant?.let { v ->
                        brandSizes.filter { it.productId.toInt() == v.productId }
                    } ?: brandSizes
                    if (auroraSizes.size > 1) {
                        Text(
                            stringResource(R.string.board_selection_aurora_size_label),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        auroraSizes.forEach { size ->
                            RadioRow(
                                label = BoardConstants.auroraSizeLabel(auroraBrand!!, size),
                                selected = auroraSizeId == size.id.toInt(),
                                onSelect = { auroraSizeId = size.id.toInt() },
                            )
                        }
                    }
                    // The download hint is only meaningful before the board is
                    // loaded — once its catalogue is imported it reads wrong
                    // ("we'll download …"), so hide it for loaded boards.
                    if (auroraBrand!!.wireValue !in loadedAuroraBrands) {
                        Text(
                            stringResource(
                                R.string.board_selection_aurora_download_hint,
                                auroraBrand!!.displayName,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
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
                        isAurora -> onConfirmAurora(auroraBrand!!, auroraVariant, auroraSizeId)
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
