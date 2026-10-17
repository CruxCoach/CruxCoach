package com.cruxcoach.android.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.BottomSheetScaffoldState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedFilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.domain.board.BoardBrand
import java.text.NumberFormat
import java.util.Locale

/** The search field's text and what the offline index found for it. */
data class MapSearchUi(
    val query: String = "",
    val results: List<MapSearchResult> = emptyList(),
    /** True while the typed text has not been looked up yet. */
    val pending: Boolean = false,
)

/**
 * The board map's screen layout without the map engine: the map fills the
 * screen, a floating search bar with the brand and filter chips sits on top,
 * and a sheet at the bottom lists the places in view or shows the selected
 * one. [map] is MapLibre in the app and a picture in screenshot tests.
 *
 * [onPeekHeight] reports the collapsed sheet's height in px together with the
 * venue it was measured for (null for the list), so the camera can keep a
 * selected marker above the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreenLayout(
    state: MapState,
    inView: VenuesInView,
    selectedVenue: MapVenue?,
    search: MapSearchUi,
    scaffoldState: BottomSheetScaffoldState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearchResult: (MapSearchResult) -> Unit,
    onOpenStats: () -> Unit,
    onOpenFilters: () -> Unit,
    onSelectAllBrands: () -> Unit,
    onToggleBrand: (BoardBrand) -> Unit,
    onSelectVenue: (MapVenue) -> Unit,
    onCloseVenue: () -> Unit,
    onBrowseClimbs: (brand: BoardBrand, layoutId: Int, productSizeId: Int?) -> Unit,
    onResetFilters: () -> Unit,
    onShowNearest: () -> Unit,
    onOverlayHeight: (px: Int) -> Unit,
    onPeekHeight: (px: Int, venueId: String?) -> Unit,
    /** The user's position once "My location" found it; rows then show distances. */
    userPosition: UserPosition? = null,
    locating: Boolean = false,
    onLocateMe: () -> Unit = {},
    /** "Choose this board" on a place whose board the map cannot open directly. */
    onChooseBoardAt: (MapVenue) -> Unit = {},
    map: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    // The screen sits inside the app scaffold, which already keeps it off the
    // status and navigation bars: measure the real layout, never raw insets.
    var peekContentPx by remember { mutableIntStateOf(0) }
    val peek = with(density) { peekContentPx.toDp() }.coerceAtLeast(96.dp)
    var screenHeightPx by remember { mutableIntStateOf(0) }
    var overlayBottomPx by remember { mutableIntStateOf(0) }
    // Pulled all the way up, the sheet stops below the search bar so search,
    // board choice and filters stay reachable.
    val sheetMax = if (overlayBottomPx > 0 && screenHeightPx > overlayBottomPx) {
        with(density) { (screenHeightPx - overlayBottomPx).toDp() - 8.dp }
    } else androidx.compose.ui.unit.Dp.Unspecified
    val screenTitle = stringResource(R.string.map_screen_title)

    // The search bar, chips and search results are drawn above the sheet, so
    // results stay readable while the keyboard pushes the sheet up.
    Box(
        Modifier
            .fillMaxSize()
            .semantics { paneTitle = screenTitle }
            .onSizeChanged { screenHeightPx = it.height }
    ) {
        BottomSheetScaffold(
            scaffoldState = scaffoldState,
            sheetPeekHeight = peek,
            sheetShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
            sheetContainerColor = MaterialTheme.colorScheme.surface,
            sheetShadowElevation = 10.dp,
            sheetDragHandle = null,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            sheetContent = {
                if (selectedVenue != null) {
                    // Keyed by venue: switching straight to another place resets
                    // the scroll and re-reports the summary height for it.
                    key(selectedVenue.id) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = sheetMax)
                                .verticalScroll(rememberScrollState())
                                .navigationBarsPadding()
                                .padding(bottom = 24.dp)
                                .testTag("map_sheet_venue"),
                        ) {
                            Column(
                                Modifier.onSizeChanged {
                                    peekContentPx = it.height
                                    onPeekHeight(it.height, selectedVenue.id)
                                }
                            ) {
                                SheetHandle()
                                VenueSummary(
                                    selectedVenue,
                                    onClose = onCloseVenue,
                                    distanceMeters = userPosition?.distanceTo(selectedVenue),
                                )
                            }
                            VenueDetails(
                                selectedVenue, state.boardInfo, onBrowseClimbs,
                                onChooseBoard = { onChooseBoardAt(selectedVenue) },
                            )
                        }
                    }
                } else {
                    VenueListSheet(
                        modifier = Modifier.heightIn(max = sheetMax),
                        state = state,
                        inView = inView,
                        userPosition = userPosition,
                        onSelectVenue = onSelectVenue,
                        onResetFilters = onResetFilters,
                        onShowNearest = onShowNearest,
                        onPeekContent = { px ->
                            peekContentPx = px
                            onPeekHeight(px, null)
                        },
                    )
                }
            },
        ) {
            Box(Modifier.fillMaxSize()) {
                map()
                if (state.isLoading || state.locationsLoading) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(16.dp))
                            .padding(16.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp), color = MaterialTheme.colorScheme.primary)
                    }
                }
                // Under the sheet: hidden when the sheet is pulled up.
                LocateMeButton(
                    locating = locating,
                    active = userPosition != null,
                    onClick = onLocateMe,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = peek + 16.dp),
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Column(Modifier.onGloballyPositioned { coords ->
                // Bottom edge of the chips in the map's own coordinates.
                overlayBottomPx = (coords.positionInParent().y + coords.size.height).toInt() +
                    with(density) { 8.dp.roundToPx() }
                onOverlayHeight(overlayBottomPx)
            }) {
                SearchPill(
                    search = search,
                    onBack = onBack,
                    onQueryChange = onQueryChange,
                    onSubmit = { search.results.firstOrNull()?.let(onSearchResult) },
                    onOpenStats = onOpenStats,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BrandChip(state, onSelectAllBrands, onToggleBrand)
                    FiltersChip(state.filters.activeCountExceptBrands, onOpenFilters)
                }
            }
            // Results cover the chips while the user is searching.
            if (search.query.trim().length >= 2) {
                SearchResults(search, onSearchResult, Modifier.padding(top = 60.dp))
            }
        }
    }
}

@Composable
private fun LocateMeButton(locating: Boolean, active: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val label = stringResource(if (locating) R.string.locate_me_finding else R.string.locate_me)
    SmallFloatingActionButton(
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .size(48.dp)
            .testTag("map_locate_me")
            .semantics { contentDescription = label },
    ) {
        if (locating) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(if (active) Icons.Filled.MyLocation else Icons.Filled.LocationSearching, contentDescription = null)
        }
    }
}

@Composable
private fun SheetHandle() {
    Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .width(36.dp)
                .height(4.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
        )
    }
}

@Composable
private fun VenueListSheet(
    modifier: Modifier,
    state: MapState,
    inView: VenuesInView,
    userPosition: UserPosition?,
    onSelectVenue: (MapVenue) -> Unit,
    onResetFilters: () -> Unit,
    onShowNearest: () -> Unit,
    onPeekContent: (px: Int) -> Unit,
) {
    val density = LocalDensity.current
    // The collapsed sheet is the header plus a fixed 60 dp — most of the
    // nearest place, or the reset / "show nearest" button. Fixed on purpose:
    // the list depends on the visible area, which depends on this height, so
    // a height that followed the list could flip back and forth.
    val belowHeader = with(density) { 60.dp.roundToPx() }
    val filteredEmpty = !state.isLoading && state.filteredVenues.isEmpty() && state.unfilteredVenues.isNotEmpty()
    Column(modifier.fillMaxWidth().testTag("map_sheet_list")) {
        Column(Modifier.onSizeChanged { onPeekContent(it.height + belowHeader) }) {
            SheetHandle()
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)) {
                val title = when {
                    state.isLoading || state.locationsLoading -> stringResource(R.string.map_loading_locations)
                    filteredEmpty -> stringResource(R.string.map_in_view_filtered_title)
                    inView.total == 0 -> stringResource(R.string.map_in_view_empty_title)
                    else -> pluralStringResource(R.plurals.map_in_view_title, inView.total, inView.total)
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("map_in_view_title"),
                )
                val subtitle = when {
                    state.isLoading || state.locationsLoading || filteredEmpty -> ""
                    inView.total == 0 -> stringResource(R.string.map_in_view_empty_hint)
                    inView.total > inView.nearest.size -> stringResource(R.string.map_in_view_capped, inView.nearest.size)
                    userPosition != null -> stringResource(R.string.map_in_view_nearest_you)
                    else -> stringResource(R.string.map_in_view_nearest)
                }
                // Always one line, empty or not, so the header keeps its height.
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            filteredEmpty -> OutlinedButton(
                onClick = onResetFilters,
                modifier = Modifier.padding(horizontal = 20.dp),
            ) { Text(stringResource(R.string.map_filter_reset)) }
            // An empty stretch of map (open sea, a country without boards):
            // one tap brings the closest boards into view.
            inView.total == 0 && !state.isLoading && state.filteredVenues.isNotEmpty() -> OutlinedButton(
                onClick = onShowNearest,
                modifier = Modifier.padding(horizontal = 20.dp).testTag("map_show_nearest"),
            ) { Text(stringResource(R.string.map_in_view_show_nearest)) }
            inView.nearest.isNotEmpty() -> LazyColumn(Modifier.fillMaxWidth().testTag("map_venue_list")) {
                items(inView.nearest, key = { it.id }) { venue ->
                    VenueRow(venue, onClick = { onSelectVenue(venue) }, distanceMeters = userPosition?.distanceTo(venue))
                }
                if (inView.total > inView.nearest.size) {
                    item {
                        Text(
                            stringResource(R.string.map_in_view_more, inView.total - inView.nearest.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        )
                    }
                }
                item { Spacer(Modifier.navigationBarsPadding().height(12.dp)) }
            }
        }
    }
}

@Composable
private fun SearchPill(
    search: MapSearchUi,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onOpenStats: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            val textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
            BasicTextField(
                value = search.query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = textStyle,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.weight(1f).testTag("map_search_field"),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (search.query.isEmpty()) {
                            Text(
                                stringResource(R.string.map_search_hint),
                                style = textStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )
            if (search.query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.map_search_clear))
                }
            }
            IconButton(onClick = onOpenStats, modifier = Modifier.testTag("map_open_stats")) {
                Icon(Icons.Outlined.BarChart, contentDescription = stringResource(R.string.map_open_stats))
            }
        }
    }
}

@Composable
private fun SearchResults(search: MapSearchUi, onSearchResult: (MapSearchResult) -> Unit, modifier: Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 460.dp)
            .testTag("map_search_results"),
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        when {
            search.pending && search.results.isEmpty() -> Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.map_search_searching), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            search.results.isEmpty() -> Text(
                stringResource(R.string.map_search_no_results),
                modifier = Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                search.results.forEach { result -> MapSearchResultRow(result) { onSearchResult(result) } }
            }
        }
    }
}

@Composable
private fun MapSearchResultRow(result: MapSearchResult, onClick: () -> Unit) {
    when (result) {
        is MapSearchResult.Venue -> VenueRow(result.venue, onClick)
        is MapSearchResult.Place -> {
            val locale = Locale.getDefault()
            val title = if (locale.language == "de") result.place.germanName ?: result.place.name else result.place.name
            val subtitle = listOfNotNull(
                result.place.region,
                Locale("", result.place.countryCode).getDisplayCountry(locale).takeIf(String::isNotBlank),
            ).joinToString(" · ")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(32.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.LocationCity,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle.isNotBlank()) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * "All boards ▾" — which families the map shows. The menu lists every family
 * vertically with its marker and count, so it doubles as the map's legend.
 * Empty selection means all; ticking a family narrows to it, more ticks add.
 */
@Composable
private fun BrandChip(state: MapState, onSelectAll: () -> Unit, onToggle: (BoardBrand) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val selected = state.filters.brands
    val counts = remember(state.unfilteredVenues) {
        BoardBrand.entries
            .associateWith { brand -> state.unfilteredVenues.count { brand in it.brands } }
            .filterValues { it > 0 }
    }
    val ordered = selected.sortedBy { it.ordinal }
    val label = when {
        selected.isEmpty() -> stringResource(R.string.map_brand_all)
        selected.size == 1 -> ordered.single().displayName
        else -> stringResource(R.string.map_brand_chip_more, ordered.first().displayName, selected.size - 1)
    }
    val number = remember { NumberFormat.getIntegerInstance() }
    Box {
        ElevatedFilterChip(
            selected = selected.isNotEmpty(),
            onClick = { open = true },
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { BrandStack(if (selected.isEmpty()) counts.keys.take(3) else ordered.take(3)) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
            colors = FilterChipDefaults.elevatedFilterChipColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.testTag("map_brand_chip"),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.testTag("map_brand_menu")) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.map_brand_all)) },
                onClick = { onSelectAll(); open = false },
                trailingIcon = {
                    if (selected.isEmpty()) Icon(Icons.Filled.Check, contentDescription = null)
                },
            )
            HorizontalDivider()
            counts.forEach { (brand, count) ->
                DropdownMenuItem(
                    text = { Text(brand.displayName) },
                    leadingIcon = { VenueMarkerDot(listOf(brand), size = 24.dp) },
                    // The menu stays open, so several families can be ticked in one go.
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                number.format(count),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(12.dp))
                            Checkbox(checked = brand in selected, onCheckedChange = null)
                        }
                    },
                    onClick = { onToggle(brand) },
                    modifier = Modifier.testTag("map_brand_${brand.wireValue}"),
                )
            }
        }
    }
}

/** Up to three overlapping brand dots — a tiny legend on the chip. */
@Composable
private fun BrandStack(brands: List<BoardBrand>) {
    Box(Modifier.width((10 + 8 * brands.size.coerceAtLeast(1)).dp).height(14.dp)) {
        brands.forEachIndexed { i, brand ->
            Box(
                Modifier
                    .offset(x = (8 * i).dp)
                    .size(14.dp)
                    .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape)
                    .padding(1.5.dp)
                    .background(androidx.compose.ui.graphics.Color(brand.markerStyle().color), CircleShape)
            )
        }
    }
}

@Composable
private fun FiltersChip(activeCount: Int, onClick: () -> Unit) {
    ElevatedFilterChip(
        selected = activeCount > 0,
        onClick = onClick,
        label = {
            Text(
                if (activeCount > 0) stringResource(R.string.map_filters_chip_active, activeCount)
                else stringResource(R.string.map_filters_chip)
            )
        },
        leadingIcon = { Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(18.dp)) },
        colors = FilterChipDefaults.elevatedFilterChipColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.testTag("map_filters_chip"),
    )
}
