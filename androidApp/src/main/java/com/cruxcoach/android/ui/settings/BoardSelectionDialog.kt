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
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.board.MoonBoardVariant

/** The brand the user is configuring in [BoardSelectionDialog] — tier 0 of the picker (FEAT-027). */
private enum class BoardBrandChoice { KILTER, MOONBOARD }

/**
 * Unified board picker (FEAT-027).
 *
 * Tier 0 — brand chooser (Kilter / MoonBoard).
 *  - Kilter branch: the product-size list plus the "find via gym" entry.
 *  - MoonBoard branch: a single-tier variant list. A MoonBoard "set up"
 *    is a fixed, standardised hold configuration — the variant fully
 *    determines the board, so there is no separate hold-set choice.
 *
 * @param initialBrand which brand tab to land on (the user's active brand).
 * @param productSizes Kilter product-size list (already layout-filtered by
 *        the caller); an empty list is handled gracefully by the Kilter tier.
 * @param selectedKilterSizeId currently-configured Kilter size.
 * @param selectedMoonBoardVariant currently-configured MoonBoard variant, or null.
 */
@Composable
internal fun BoardSelectionDialog(
    initialBrand: String,
    productSizes: List<BoardSize>,
    selectedKilterSizeId: Int,
    selectedMoonBoardVariant: MoonBoardVariant?,
    onConfirmKilter: (Int) -> Unit,
    onConfirmMoonBoard: (MoonBoardVariant) -> Unit,
    /** "Don't know your board? find it via your gym" — FEAT-007 gym
     *  search. Shown in the Kilter tier only; null hides it. */
    onFindViaGym: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    var brand by remember {
        mutableStateOf(
            if (initialBrand == "moonboard") BoardBrandChoice.MOONBOARD
            else BoardBrandChoice.KILTER
        )
    }
    var kilterSelection by remember { mutableIntStateOf(selectedKilterSizeId) }
    var mbVariant by remember {
        mutableStateOf(selectedMoonBoardVariant ?: MoonBoardVariant.entries.first())
    }

    val kilterEmpty = productSizes.isEmpty()

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
                // Tier 0 — brand chooser.
                Text(
                    stringResource(R.string.board_selection_brand_label),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = brand == BoardBrandChoice.KILTER,
                        onClick = { brand = BoardBrandChoice.KILTER },
                        label = { Text(stringResource(R.string.board_selection_brand_kilter)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = OrangeAccent.copy(alpha = 0.2f),
                            selectedLabelColor = OrangeAccent,
                        ),
                    )
                    FilterChip(
                        selected = brand == BoardBrandChoice.MOONBOARD,
                        onClick = { brand = BoardBrandChoice.MOONBOARD },
                        label = { Text(stringResource(R.string.board_selection_brand_moonboard)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = OrangeAccent.copy(alpha = 0.2f),
                            selectedLabelColor = OrangeAccent,
                        ),
                    )
                }

                HorizontalDivider()

                when (brand) {
                    BoardBrandChoice.KILTER -> {
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
                            productSizes.forEach { size ->
                                RadioRow(
                                    label = size.name,
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
                    }

                    BoardBrandChoice.MOONBOARD -> {
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
            }
        },
        confirmButton = {
            val confirmEnabled = brand == BoardBrandChoice.MOONBOARD || !kilterEmpty
            Button(
                onClick = {
                    when (brand) {
                        BoardBrandChoice.KILTER -> onConfirmKilter(kilterSelection)
                        BoardBrandChoice.MOONBOARD -> onConfirmMoonBoard(mbVariant)
                    }
                },
                enabled = confirmEnabled,
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
