package com.cruxcoach.app.gym

import com.cruxcoach.app.logbook.StateWatch
import com.cruxcoach.app.logbook.watchState
import com.cruxcoach.app.map.distanceMeters
import com.cruxcoach.app.map.normalizeMapSearchText
import com.cruxcoach.data.repository.Adjustability
import com.cruxcoach.data.repository.BoardLocation
import com.cruxcoach.data.repository.BoardLocationRepository
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant
import com.cruxcoach.domain.board.QuantumBoardModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A selectable board derived from a gym: a Kilter physical wall, a MoonBoard
 * variant, a Quantum model or an Aurora-family board/variant. Port of Android
 * `GymWallOption`. MoonBoard carries [MOONBOARD_NO_SIZE]; a single-layout
 * Aurora board carries layout 0 ("the catalogue derives the layout").
 */
data class GymWallOption(
    val layoutId: Int,
    val productSizeId: Int,
    val label: String,
    val boardBrand: BoardBrand,
    /** Installed angle of a FIXED Kilter wall; null for adjustable walls and other brands. */
    val fixedAngle: Int? = null,
)

/** MoonBoard variants are distinct boards, not sizes of one board. */
const val MOONBOARD_NO_SIZE = 0

data class GymBoardPickerState(
    /** False only while neither synced rows nor the bundled snapshot hold any gym. */
    val enabled: Boolean = false,
    val query: String = "",
    val results: List<BoardLocation> = emptyList(),
    val selectedGym: BoardLocation? = null,
    val wallOptions: List<GymWallOption> = emptyList(),
    val searching: Boolean = false,
    /** True while [results] are the gyms near the user, nearest first. */
    val nearby: Boolean = false,
    /** Distance from the user per result row id (nearby mode only). */
    val distanceById: Map<String, Double> = emptyMap(),
    /** The selectable board families at each result's gym, by result row id. */
    val brandsById: Map<String, List<BoardBrand>> = emptyMap(),
)

/** Gyms further away than this are not offered as "near you". */
const val NEARBY_RADIUS_METERS = 100_000.0
private const val NEARBY_LIMIT = 10
private const val SEARCH_LIMIT = 60

/**
 * Port of Android `GymBoardPickerViewModel` ("find your gym"): an offline
 * search over the synced `kilter_board_location` rows, with the bundled map
 * snapshot standing in while nothing is synced, one entry per physical gym,
 * and "Gyms near me". The host applies the final pick.
 */
class GymBoardPickerPresenter(
    private val repository: BoardLocationRepository,
    /** The bundled map snapshot's rows; read once, on first use. */
    private val loadBundled: () -> List<BoardLocation>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state = MutableStateFlow(GymBoardPickerState())
    val state: StateFlow<GymBoardPickerState> = _state.asStateFlow()

    private var frequency: Map<Int, Long> = emptyMap()

    // All rows per physical gym: the same gym appears once per brand (and once
    // per Kilter wall in the snapshot), so the list shows ONE entry and a pick
    // gathers the boards of every row.
    private var rowsByGym: Map<String, List<BoardLocation>> = emptyMap()
    private var bundledRows: List<BoardLocation>? = null

    init {
        scope.launch {
            try {
                // Gated on locations, not walls: MoonBoard-only data has no walls.
                val enabled = withContext(ioDispatcher) { repository.count() > 0L } || bundled().isNotEmpty()
                frequency = withContext(ioDispatcher) { repository.productSizeFrequency() }
                _state.update { it.copy(enabled = enabled) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("GymBoardPicker: init read failed, picker disabled: ${e::class.simpleName}")
                _state.update { it.copy(enabled = false) }
            }
        }
    }

    fun watch(onState: (GymBoardPickerState) -> Unit): StateWatch = scope.watchState(state, onState)

    fun close() = scope.cancel()

    private suspend fun bundled(): List<BoardLocation> = bundledRows ?: withContext(ioDispatcher) {
        try {
            loadBundled()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("GymBoardPicker: bundled map snapshot failed: ${e::class.simpleName}")
            emptyList()
        }
    }.also { bundledRows = it }

    private suspend fun allRows(): List<BoardLocation> {
        val synced = withContext(ioDispatcher) { repository.getAll() }
        return synced.ifEmpty { bundled() }
    }

    fun onQueryChange(q: String) {
        _state.update {
            it.copy(query = q, selectedGym = null, wallOptions = emptyList(), nearby = false, distanceById = emptyMap())
        }
        val trimmed = q.trim()
        if (trimmed.length < 2) {
            _state.update { it.copy(results = emptyList(), searching = false) }
            return
        }
        scope.launch {
            _state.update { it.copy(searching = true) }
            try {
                val synced = withContext(ioDispatcher) { repository.searchLocations(trimmed, SEARCH_LIMIT) }
                val res = synced.ifEmpty {
                    if (withContext(ioDispatcher) { repository.count() } > 0L) emptyList()
                    else searchBundled(trimmed, SEARCH_LIMIT)
                }
                // A locations chunk that lands mid-session must re-enable the picker.
                if (!_state.value.enabled) {
                    val hasData = res.isNotEmpty() || withContext(ioDispatcher) { repository.count() > 0L }
                    if (hasData) {
                        frequency = withContext(ioDispatcher) { repository.productSizeFrequency() }
                        _state.update { it.copy(enabled = true) }
                    }
                }
                // Drop the result if the user kept typing.
                if (_state.value.query.trim() == trimmed) {
                    val grouped = res.groupBy { pickerGymKey(it) }
                    rowsByGym = grouped
                    _state.update {
                        it.copy(results = grouped.values.map { rows -> rows.first() }, searching = false, brandsById = brandsOf(grouped))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("GymBoardPicker: search failed: ${e::class.simpleName}")
                if (_state.value.query.trim() == trimmed) {
                    _state.update { it.copy(results = emptyList(), searching = false) }
                }
            }
        }
    }

    /** "Gyms near me". The position is used for this ranking only and not kept. */
    fun showNearby(lat: Double, lng: Double) {
        _state.update {
            it.copy(
                query = "", selectedGym = null, wallOptions = emptyList(),
                searching = true, nearby = true, results = emptyList(), distanceById = emptyMap(),
            )
        }
        scope.launch {
            try {
                val rows = allRows()
                val ranked = withContext(ioDispatcher) { nearbyGyms(rows, lat, lng) }
                if (!_state.value.nearby) return@launch // the user started typing meanwhile
                val groups = ranked.associate { it.key to it.rows }
                rowsByGym = groups
                _state.update {
                    it.copy(
                        results = ranked.map { gym -> gym.rows.first() },
                        distanceById = ranked.associate { gym -> gym.rows.first().id to gym.meters },
                        brandsById = brandsOf(groups),
                        searching = false,
                        enabled = it.enabled || rows.isNotEmpty(),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("GymBoardPicker: nearby failed: ${e::class.simpleName}")
                _state.update { it.copy(results = emptyList(), searching = false) }
            }
        }
    }

    private fun brandsOf(groups: Map<String, List<BoardLocation>>): Map<String, List<BoardBrand>> =
        groups.values.associate { group ->
            group.first().id to group.map { it.boardBrand }.filter { it.isInteractive }.distinct().sortedBy { it.ordinal }
        }

    private suspend fun searchBundled(query: String, limit: Int): List<BoardLocation> {
        val needle = normalizeMapSearchText(query)
        val rows = bundled()
        return withContext(ioDispatcher) {
            rows.filter { row ->
                normalizeMapSearchText(row.name).contains(needle) ||
                    row.city?.let { normalizeMapSearchText(it).contains(needle) } == true ||
                    row.alternateSearchTerms.any { normalizeMapSearchText(it).contains(needle) }
            }.take(limit * 3) // several rows per gym; grouped afterwards
        }
    }

    /** The board map's "Choose this board": opens one known place without a search. */
    fun openGym(rows: List<BoardLocation>) {
        val first = rows.firstOrNull() ?: return
        rowsByGym = mapOf(pickerGymKey(first) to rows)
        _state.update { it.copy(query = "", nearby = false, distanceById = emptyMap(), results = emptyList(), searching = false) }
        selectGym(first)
    }

    fun selectGym(gym: BoardLocation) {
        // Info-layer brands are map-only: no walls, not selectable, and they
        // must not count towards the mixed-gym "Kilter " label prefix.
        val rows = (rowsByGym[pickerGymKey(gym)] ?: listOf(gym)).filter { it.boardBrand.isInteractive }
        val multiBrand = rows.map { it.boardBrand }.distinct().size > 1
        scope.launch {
            try {
                val opts = mutableListOf<GymWallOption>()
                for (row in rows) {
                    val brand = row.boardBrand
                    when {
                        brand == BoardBrand.MOONBOARD -> opts += moonBoardOptions(row)
                        // The model is not in the location data: every model is an equal choice.
                        brand == BoardBrand.QUANTUM -> opts += QuantumBoardModel.entries.map { model ->
                            GymWallOption(
                                layoutId = model.layoutId.toInt(),
                                productSizeId = model.productSizeId.toInt(),
                                label = "${BoardBrand.QUANTUM.displayName} ${model.displayName}",
                                boardBrand = BoardBrand.QUANTUM,
                            )
                        }
                        brand.usesAuroraProtocol && brand != BoardBrand.KILTER -> opts += auroraOptions(brand)
                        // A bundled-snapshot row already is one Kilter wall (layout + size).
                        brand == BoardBrand.KILTER && row.productSizeId != null && row.layoutId != null &&
                            withContext(ioDispatcher) { repository.getWallsForGym(row.id) }.isEmpty() -> {
                            val sizeId = row.productSizeId!!
                            val size = GymBoardCatalog.kilterSizeLabel(sizeId.toLong(), row.sizeLabel.orEmpty())
                            opts += GymWallOption(
                                layoutId = row.layoutId!!,
                                productSizeId = sizeId,
                                label = if (multiBrand) "Kilter $size" else size,
                                boardBrand = BoardBrand.KILTER,
                                fixedAngle = if (row.adjustability == Adjustability.FIXED) row.fixedAngle else null,
                            )
                        }
                        else -> {
                            val walls = withContext(ioDispatcher) { repository.getWallsForGym(row.id) }
                            opts += walls
                                .filter { it.layoutId != null && it.productSizeId != null }
                                .map { w ->
                                    val size = GymBoardCatalog.kilterSizeLabel(
                                        w.productSizeId!!.toLong(),
                                        w.sizeLabel ?: w.productName ?: "",
                                    )
                                    GymWallOption(
                                        layoutId = w.layoutId!!,
                                        productSizeId = w.productSizeId!!,
                                        label = if (multiBrand) "Kilter $size" else size,
                                        boardBrand = BoardBrand.KILTER,
                                        // Only a FIXED wall's angle is a real board property.
                                        fixedAngle = if (w.isAdjustable == false) w.fixedAngle else null,
                                    )
                                }
                                .sortedByDescending { frequency[it.productSizeId] ?: 0L }
                        }
                    }
                }
                val deduped = opts.distinctBy { Triple(it.boardBrand, it.layoutId, it.productSizeId) }
                _state.update { it.copy(selectedGym = gym, wallOptions = deduped) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("GymBoardPicker: selectGym failed: ${e::class.simpleName}")
                _state.update { it.copy(selectedGym = gym, wallOptions = emptyList()) }
            }
        }
    }

    /** One option per variant (with its default size named), or per bundled size of a single-layout board. */
    private fun auroraOptions(brand: BoardBrand): List<GymWallOption> {
        val variants = GymBoardCatalog.auroraVariants(brand)
        if (variants.isEmpty()) {
            val sizes = GymBoardCatalog.auroraBundledSizes(brand)
            if (sizes.isEmpty()) {
                return listOf(GymWallOption(layoutId = 0, productSizeId = 0, label = brand.displayName, boardBrand = brand))
            }
            return sizes.map { s ->
                GymWallOption(
                    layoutId = 0,
                    productSizeId = s.id,
                    label = "${brand.displayName} ${GymBoardCatalog.auroraSizeLabel(brand, s)}",
                    boardBrand = brand,
                )
            }
        }
        return variants.map { variant ->
            // Name the default size that WILL be applied, so the pick is never a silent assumption.
            val sizeName = GymBoardCatalog.auroraBundledSizes(brand)
                .firstOrNull { it.id == variant.defaultSizeId }
                ?.let { GymBoardCatalog.auroraSizeLabel(brand, it) }
            GymWallOption(
                layoutId = variant.layoutId,
                productSizeId = variant.defaultSizeId,
                label = if (sizeName != null) "${variant.displayName} ($sizeName)" else variant.displayName,
                boardBrand = brand,
            )
        }
    }

    /** A resolved MoonBoard variant, or every variant as an equal choice when unresolved. */
    private fun moonBoardOptions(gym: BoardLocation): List<GymWallOption> {
        val resolved = gym.layoutId?.toLong()?.let { MoonBoardVariant.fromLayoutId(it) }
        if (resolved != null) {
            return listOf(
                GymWallOption(
                    layoutId = resolved.layoutId.toInt(),
                    productSizeId = MOONBOARD_NO_SIZE,
                    label = resolved.displayName,
                    boardBrand = BoardBrand.MOONBOARD,
                ),
            )
        }
        return MoonBoardVariant.entries.map { variant ->
            GymWallOption(
                layoutId = variant.layoutId.toInt(),
                productSizeId = MOONBOARD_NO_SIZE,
                label = variant.displayName,
                boardBrand = BoardBrand.MOONBOARD,
            )
        }
    }

    fun clearGymSelection() {
        _state.update { it.copy(selectedGym = null, wallOptions = emptyList()) }
    }
}

/** One gym near the user: its picker key, all its rows, and the distance in metres. */
data class NearbyGym(val key: String, val rows: List<BoardLocation>, val meters: Double)

/**
 * Gyms within [NEARBY_RADIUS_METERS] that offer a board CruxCoach can drive,
 * nearest first, at most [limit]. Rows of one gym are grouped by [pickerGymKey].
 */
fun nearbyGyms(
    rows: List<BoardLocation>,
    lat: Double,
    lng: Double,
    limit: Int = NEARBY_LIMIT,
): List<NearbyGym> = rows
    .asSequence()
    .filter { kotlin.math.abs(it.lat - lat) < 1.5 } // cheap pre-filter: ~165 km of latitude
    .map { it to distanceMeters(lat, lng, it.lat, it.lng) }
    .filter { (_, meters) -> meters <= NEARBY_RADIUS_METERS }
    .groupBy { (row, _) -> pickerGymKey(row) }
    .mapNotNull { (key, pairs) ->
        val group = pairs.map { it.first }
        if (group.none { it.boardBrand.isInteractive }) null
        else NearbyGym(key, group, pairs.minOf { it.second })
    }
    .sortedBy { it.meters }
    .take(limit)
    .toList()

/**
 * Gym identity: name + ~1 km coordinates, so per-brand rows of one gym merge
 * while distinct chain locations stay separate. Deliberately coarser than the
 * map's geographic venue key.
 */
fun pickerGymKey(loc: BoardLocation): String =
    loc.name.trim().lowercase() + "|" + (loc.lat * 100).toInt() + "|" + (loc.lng * 100).toInt()
