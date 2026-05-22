package com.cruxcoach.android.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.data.repository.AccessType
import com.cruxcoach.data.repository.Adjustability

/**
 * Bottom sheet that exposes every map-side filter dimension. Stays
 * reactively bound to [MapViewModel.state] so chips update immediately
 * when toggled (DataStore round-trip is sub-frame).
 *
 * The footer carries a live "showing N / M" counter and a "reset all"
 * action so the user can always escape an over-constrained filter set.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MapFilterSheet(
    state: MapState,
    onDismiss: () -> Unit,
    onSelectAllLayouts: () -> Unit,
    onToggleShowOriginal: () -> Unit,
    onToggleShowHomewalls: () -> Unit,
    onToggleMatchesMyBoard: () -> Unit,
    onToggleCountry: (String) -> Unit,
    onToggleAccessType: (AccessType) -> Unit,
    onToggleAdjustability: (Adjustability) -> Unit,
    onToggleSizeId: (Int) -> Unit,
    onResetAll: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        // The "footer" sticks below a scrolling content column. LazyColumn
        // gives us free recycler behaviour for the (potentially long)
        // country list; the others are short enough that they share the
        // same lazy list as static items rather than splitting into
        // separate Columns.
        Column(modifier = Modifier.fillMaxWidth()) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Text(
                        stringResource(R.string.map_filter_sheet_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                item {
                    Section(stringResource(R.string.map_filter_section_layout)) {
                        FilterChip(
                            selected = state.filters.showOriginal && state.filters.showHomewalls,
                            onClick = onSelectAllLayouts,
                            label = { Text(stringResource(R.string.map_filter_show_all)) },
                        )
                        FilterChip(
                            selected = state.filters.showOriginal,
                            onClick = onToggleShowOriginal,
                            label = { Text(stringResource(R.string.map_filter_show_original)) },
                        )
                        FilterChip(
                            selected = state.filters.showHomewalls,
                            onClick = onToggleShowHomewalls,
                            label = { Text(stringResource(R.string.map_filter_show_homewalls)) },
                        )
                        FilterChip(
                            selected = state.filters.matchesMyBoard && state.canFilterByMyBoard,
                            enabled = state.canFilterByMyBoard,
                            onClick = onToggleMatchesMyBoard,
                            label = { Text(stringResource(R.string.map_filter_matches_my_board)) },
                        )
                    }
                }

                item {
                    Section(stringResource(R.string.map_filter_section_access)) {
                        AccessType.entries.forEach { type ->
                            FilterChip(
                                selected = type in state.filters.accessTypes,
                                onClick = { onToggleAccessType(type) },
                                label = { Text(accessTypeLabel(type)) },
                            )
                        }
                    }
                }

                item {
                    Section(stringResource(R.string.map_filter_section_adjustability)) {
                        // FULL/LIMITED almost never appear in source data; collapse
                        // them into the "Adjustable" bucket via Adjustability.UNKNOWN
                        // staying separate.
                        listOf(
                            Adjustability.ADJUSTABLE,
                            Adjustability.FIXED,
                            Adjustability.UNKNOWN,
                        ).forEach { adj ->
                            FilterChip(
                                selected = adj in state.filters.adjustabilities,
                                onClick = { onToggleAdjustability(adj) },
                                label = { Text(adjustabilityLabel(adj)) },
                            )
                        }
                    }
                }

                item {
                    val sizeOptions = state.unfilteredStats.bySize
                    if (sizeOptions.isNotEmpty()) {
                        Section(stringResource(R.string.map_filter_section_size)) {
                            sizeOptions.forEach { (label, count) ->
                                val sizeId = state.unfilteredLocations
                                    .firstOrNull { it.sizeLabel == label }?.productSizeId
                                if (sizeId != null) {
                                    FilterChip(
                                        selected = sizeId in state.filters.sizeIds,
                                        onClick = { onToggleSizeId(sizeId) },
                                        label = { Text("${BoardConstants.sizeLabel(sizeId.toLong(), label)} ($count)") },
                                    )
                                }
                            }
                        }
                    }
                }

                // Country chips can grow long — render in their own
                // FlowRow with all entries (sorted by frequency desc).
                item {
                    val countries = state.unfilteredStats.byCountry
                    if (countries.isNotEmpty()) {
                        Section(stringResource(R.string.map_filter_section_country)) {
                            countries.forEach { (code, count) ->
                                FilterChip(
                                    selected = code in state.filters.countries,
                                    onClick = { onToggleCountry(code) },
                                    label = { Text("$code ($count)") },
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        R.string.map_filter_count_template,
                        state.filteredLocations.size,
                        state.unfilteredLocations.size,
                    ),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = onResetAll,
                    enabled = !state.filters.isAtDefault,
                ) {
                    Text(stringResource(R.string.map_filter_reset))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun accessTypeLabel(type: AccessType): String = when (type) {
    AccessType.PUBLIC -> stringResource(R.string.map_access_public)
    AccessType.PRIVATE -> stringResource(R.string.map_access_private)
    AccessType.MEMBERS -> stringResource(R.string.map_access_members)
    AccessType.UNKNOWN -> stringResource(R.string.map_marker_field_unknown)
}

@Composable
private fun adjustabilityLabel(adj: Adjustability): String = when (adj) {
    Adjustability.ADJUSTABLE, Adjustability.FULL, Adjustability.LIMITED ->
        stringResource(R.string.map_adjustability_adjustable)
    Adjustability.FIXED -> stringResource(R.string.map_adjustability_fixed)
    Adjustability.UNKNOWN -> stringResource(R.string.map_marker_field_unknown)
}
