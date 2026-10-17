package com.cruxcoach.android.ui.map

import android.app.Application
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.BottomSheetScaffoldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.cruxcoach.android.data.DarkModeSetting
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import com.cruxcoach.domain.board.BoardBrand
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Design review for the board map without a device. Skipped unless
 * `CRUXCOACH_MAP_REVIEW_DIR` is set, so CI pays nothing.
 *
 *  - [markerIcons] writes every marker and cluster image the bundled data
 *    needs, exactly as the app draws them, to `<dir>/icons/`.
 *  - The screen tests draw the real Compose layout over `<dir>/backdrop-<name>.png`
 *    (the same tiles, layers and icons rendered by MapLibre GL JS from a
 *    browser harness) and write `<dir>/screen-<name>.png`. The map itself is
 *    native GL and cannot render under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de-w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalMaterial3Api::class)
class MapUiReviewTest {
    @get:Rule val compose = createComposeRule()

    private val dir: String? = System.getenv("CRUXCOACH_MAP_REVIEW_DIR")

    @Before
    fun onlyOnRequest() = Assume.assumeTrue(dir != null)

    private val snapshot by lazy {
        val assets = listOf("src/main/assets/board_map", "androidApp/src/main/assets/board_map")
            .map(::File).first { it.isDirectory }
        PagesBoardMapParser.parse(File(assets, "boards.geojson").readText(), File(assets, "cities.json").readText())
    }
    private val venues by lazy { groupIntoVenues(snapshot.locations, snapshot.venueInfo) }
    private val state by lazy {
        MapState(
            unfilteredLocations = snapshot.locations,
            unfilteredVenues = venues,
            filteredLocations = snapshot.locations,
            filteredVenues = venues,
            places = snapshot.places,
            venueInfo = snapshot.venueInfo,
            boardInfo = snapshot.boardInfo,
            isLoading = false,
        )
    }

    @Test
    fun markerIcons() {
        val out = File(dir!!, "icons").apply { mkdirs() }
        val density = 2f
        venues.map { it.markerBrands }.toSet().forEach { brands ->
            File(out, MapMarkerIcons.iconId(brands) + ".png").outputStream().use {
                MapMarkerIcons.render(brands, density).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        val fills = listOf(MapMarkerLayer.Theme.Light.clusterColor, MapMarkerLayer.Theme.Dark.clusterColor) +
            BoardBrand.entries.map { it.markerStyle().color }
        fills.forEach { fill ->
            listOf("s" to 30f, "m" to 36f, "l" to 44f, "xl" to 52f).forEach { (bucket, diameter) ->
                File(out, "cluster-$bucket-${Integer.toHexString(fill)}.png").outputStream().use {
                    MapMarkerLayer.renderCluster(fill, diameter, density).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
    }

    /** Places around the backdrop's centre, as the app would list them. */
    private fun inView(lat: Double, lng: Double, dLat: Double, dLng: Double) =
        venuesInView(venues, MapViewport(lat - dLat, lng - dLng, lat + dLat, lng + dLng, lat, lng))

    @Test
    fun listMunich() = shoot("list-munich", inView(48.14, 11.57, 0.12, 0.2), null)

    @Test
    fun listMunichDark() = shoot("list-munich-dark", inView(48.14, 11.57, 0.12, 0.2), null, dark = true)

    @Test
    fun brandMenu() = shoot("brand-menu", inView(48.14, 11.57, 0.12, 0.2), null) {
        compose.onNodeWithTag("map_brand_chip").performClick()
    }

    @Test
    fun venueSelected() {
        val venue = venues.filter { it.city?.contains("Munich") == true || it.city?.contains("München") == true }
            .maxBy { it.boards.size * 10 + (if (it.info.hours != null) 5 else 0) }
        File(dir!!, "selected-venue.txt").writeText("${venue.name}\n${venue.lat},${venue.lng}\n${venue.markerBrands}")
        shoot("venue", inView(venue.lat, venue.lng, 0.03, 0.05), venue)
    }

    @Test
    fun venueExpanded() {
        val venue = venues.filter { it.city?.contains("Munich") == true || it.city?.contains("München") == true }
            .maxBy { it.boards.size * 10 + (if (it.info.hours != null) 5 else 0) }
        shoot("venue-expanded", inView(venue.lat, venue.lng, 0.03, 0.05), venue, expanded = true)
    }

    private fun shoot(
        name: String,
        inView: VenuesInView,
        selected: MapVenue?,
        dark: Boolean = false,
        expanded: Boolean = false,
        afterSetup: () -> Unit = {},
    ) {
        val backdrop = File(dir!!, "backdrop-$name.png").takeIf { it.isFile }
            ?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
        var view: android.view.View? = null
        compose.setContent {
            view = LocalView.current
            CruxCoachTheme(if (dark) DarkModeSetting.DARK else DarkModeSetting.LIGHT) {
                val scaffoldState = rememberBottomSheetScaffoldState(
                    rememberStandardBottomSheetState(
                        initialValue = if (expanded) SheetValue.Expanded else SheetValue.PartiallyExpanded,
                        skipHiddenState = true,
                    )
                )
                Screen(inView, selected, scaffoldState) {
                    if (backdrop != null) {
                        Image(backdrop, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Box(Modifier.fillMaxSize().background(Color(0xFFE8E4DC)))
                    }
                }
            }
        }
        compose.waitForIdle()
        afterSetup()
        compose.waitForIdle()
        compose.runOnIdle {
            val root = requireNotNull(view)
            val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            root.draw(canvas)
            // Menus and dialogs live in windows of their own: draw them on top, in order.
            windows().filter { it !== root.rootView && it.width > 0 }.forEach { w ->
                val at = IntArray(2).also { w.getLocationOnScreen(it) }
                canvas.save(); canvas.translate(at[0].toFloat(), at[1].toFloat()); w.draw(canvas); canvas.restore()
            }
            File(dir, "screen-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Composable
    private fun Screen(
        inView: VenuesInView,
        selected: MapVenue?,
        scaffoldState: BottomSheetScaffoldState,
        map: @Composable () -> Unit,
    ) {
        MapScreenLayout(
            state = state,
            inView = inView,
            selectedVenue = selected,
            search = MapSearchUi(),
            scaffoldState = scaffoldState,
            snackbarHostState = SnackbarHostState(),
            onBack = {},
            onQueryChange = {},
            onSearchResult = {},
            onOpenStats = {},
            onOpenFilters = {},
            onSelectAllBrands = {},
            onToggleBrand = {},
            onSelectVenue = {},
            onCloseVenue = {},
            onBrowseClimbs = { _, _, _ -> },
            onResetFilters = {},
            onOverlayHeight = { File(dir!!, "overlay-px.txt").writeText(it.toString()) },
            onPeekHeight = { px, id -> File(dir!!, "peek-px-${id ?: "list"}.txt".replace(',', '_')).writeText(px.toString()) },
            map = map,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun windows(): List<android.view.View> = runCatching {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        (global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as List<android.view.View>).toList()
    }.getOrDefault(emptyList())
}
