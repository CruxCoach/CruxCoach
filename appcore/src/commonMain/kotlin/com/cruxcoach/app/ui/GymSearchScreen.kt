package com.cruxcoach.app.ui

import com.cruxcoach.app.gym.GymBoardCatalog
import com.cruxcoach.app.gym.GymBoardPickerPresenter
import com.cruxcoach.app.gym.GymBoardPickerState
import com.cruxcoach.app.gym.GymWallOption
import com.cruxcoach.app.setup.BoardOption
import com.cruxcoach.data.repository.BoardLocation
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant
import kotlin.math.roundToInt

/** One gym in the search or "near me" list. */
class GymResultUi(
    val id: String,
    val name: String,
    /** Board families at this gym ("Kilter, MoonBoard") — product names, not translated. */
    val boardsText: String,
    /** City and ISO country, comma-separated; the "??" placeholder country is dropped. */
    val placeText: String,
    val hasDistance: Boolean,
    val distance: GymDistanceUi,
)

/**
 * A distance as people read it at a glance (Android `distanceNumber`): whole
 * 10 m below a kilometre, one decimal below 10 km, whole km beyond. Swift
 * formats [value] with [fractionDigits] in the user's locale and picks the
 * `distance_m` / `distance_km` string by [unitCode] (m | km).
 */
class GymDistanceUi(val value: Double, val fractionDigits: Int, val unitCode: String)

/** One board the picked gym offers, ready to hand to the board picker's `onPick`. */
class GymBoardOptionUi(
    val index: Int,
    val label: String,
    val brandWire: String,
    /** Installed angle of a fixed Kilter wall, 0 when adjustable or unknown. */
    val fixedAngle: Int,
    val board: BoardOption,
)

class GymSearchScreenState(
    val enabled: Boolean,
    val query: String,
    val searching: Boolean,
    /** True while [results] are the gyms near the user, nearest first. */
    val nearby: Boolean,
    val results: List<GymResultUi>,
    val hasSelection: Boolean,
    /** Empty when nothing is selected. */
    val selected: List<GymResultUi>,
    val options: List<GymBoardOptionUi>,
    /** Heading above the options: one | categories | variant | "" (no options). */
    val optionsPromptCode: String,
    /** Changes whenever a gym's options are resolved; Swift auto-applies a single option once per key. */
    val selectionKey: String,
)

/**
 * "Find your gym" (Android `GymBoardSearchSheet`): offline search, "Gyms near
 * me" and the board choice at the picked gym. Swift supplies the position (it
 * is never stored) and applies the chosen [GymBoardOptionUi.board] through the
 * same path as the manual board picker.
 */
class GymSearchScreenModel(private val presenter: GymBoardPickerPresenter) {

    val currentState: GymSearchScreenState get() = map(presenter.state.value)

    fun watch(onState: (GymSearchScreenState) -> Unit): Subscription {
        val watch = presenter.watch { onState(map(it)) }
        return Subscription { watch.cancel() }
    }

    fun close() = presenter.close()

    fun setQuery(text: String) = presenter.onQueryChange(text)

    /** A position from the "Gyms near me" tap. Out-of-range values are ignored. */
    fun showNearby(latitude: Double, longitude: Double) {
        if (latitude.isNaN() || longitude.isNaN() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return
        presenter.showNearby(latitude, longitude)
    }

    /** Unknown ids are ignored. */
    fun selectResult(id: String) {
        presenter.state.value.results.firstOrNull { it.id == id }?.let { presenter.selectGym(it) }
    }

    fun clearSelection() = presenter.clearGymSelection()

    /** The rows of one place handed over by the board map ("Choose this board"). */
    internal fun openGym(rows: List<BoardLocation>) = presenter.openGym(rows)

    private fun map(s: GymBoardPickerState): GymSearchScreenState {
        val options = s.wallOptions.mapIndexed { i, opt -> option(i, opt) }
        val brandCount = s.wallOptions.map { it.boardBrand }.distinct().size
        val gym = s.selectedGym
        return GymSearchScreenState(
            enabled = s.enabled,
            query = s.query,
            searching = s.searching,
            nearby = s.nearby,
            results = s.results.map { result(it, s) },
            hasSelection = gym != null,
            selected = listOfNotNull(gym?.let { result(it, s) }),
            options = options,
            optionsPromptCode = when {
                options.isEmpty() -> ""
                options.size == 1 -> "one"
                brandCount > 1 -> "categories"
                else -> "variant"
            },
            selectionKey = gym?.let { "${it.id}|${options.size}" } ?: "",
        )
    }

    private fun result(g: BoardLocation, s: GymBoardPickerState): GymResultUi {
        val brands = s.brandsById[g.id].orEmpty()
        val meters = s.distanceById[g.id]
        return GymResultUi(
            id = g.id,
            name = g.name,
            boardsText = when {
                brands.isNotEmpty() -> brands.joinToString(", ") { it.displayName }
                g.boardBrand == BoardBrand.MOONBOARD -> "MoonBoard"
                else -> ""
            },
            placeText = listOfNotNull(
                g.city?.takeIf { it.isNotBlank() },
                g.countryCode.takeIf { it.isNotBlank() && it != "??" },
            ).joinToString(", "),
            hasDistance = meters != null,
            distance = distance(meters ?: 0.0),
        )
    }

    private fun option(index: Int, opt: GymWallOption): GymBoardOptionUi = GymBoardOptionUi(
        index = index,
        label = opt.label,
        brandWire = opt.boardBrand.wireValue,
        fixedAngle = opt.fixedAngle ?: 0,
        board = boardOption(opt),
    )

    companion object {
        /** Android `distanceNumber`'s rounding, without the locale formatting. */
        fun distance(meters: Double): GymDistanceUi = when {
            meters < 995 -> GymDistanceUi(((meters / 10).roundToInt() * 10).toDouble(), 0, "m")
            meters < 9_950 -> GymDistanceUi((meters / 100).roundToInt() / 10.0, 1, "km")
            else -> GymDistanceUi((meters / 1000).roundToInt().toDouble(), 0, "km")
        }

        /**
         * The gym option as the manual picker's [BoardOption], so both hosts
         * (board browser, onboarding) apply it unchanged. Android's selector
         * derives a single-layout Aurora board's layout (0) and default size
         * from the catalogue; iOS stores the board directly, so those come from
         * the same static defaults here.
         */
        internal fun boardOption(opt: GymWallOption): BoardOption {
            val brand = opt.boardBrand
            val title = UiCodes.brandTitle(brand)
            return when (brand) {
                BoardBrand.MOONBOARD -> BoardOption(
                    brand.wireValue, title, opt.layoutId, 0,
                    MoonBoardVariant.fromLayoutId(opt.layoutId.toLong())?.displayName ?: opt.label, "",
                )
                BoardBrand.KILTER -> BoardOption(
                    brand.wireValue, title, opt.layoutId, opt.productSizeId,
                    if (opt.layoutId == KILTER_HOMEWALL_LAYOUT) "Kilter Board Homewall" else "Kilter Board Original",
                    opt.label,
                )
                else -> {
                    val single = GymBoardCatalog.singleLayoutDefault(brand)
                    val layoutId = if (opt.layoutId == 0 && single != null) single.first else opt.layoutId
                    val sizeId = if (opt.productSizeId == 0 && single != null) single.second else opt.productSizeId
                    val variantName = GymBoardCatalog.auroraVariant(brand, layoutId)?.displayName ?: title
                    BoardOption(brand.wireValue, title, layoutId, sizeId, variantName, opt.label)
                }
            }
        }

        private const val KILTER_HOMEWALL_LAYOUT = 8
    }
}
