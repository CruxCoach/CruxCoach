package com.cruxcoach.android.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.data.repository.BoardLocation
import com.cruxcoach.data.repository.BoardLocationRepository
import com.cruxcoach.data.repository.BoardWall
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import com.cruxcoach.android.util.safeLaunch

private const val TAG = "GymBoardPickerVM"

/** A selectable board derived from a gym (FEAT-007/031): a Kilter physical
 *  wall, a MoonBoard variant, or an Aurora-family board/variant. [boardBrand]
 *  is the gym's own brand and tells the apply path which selection action to
 *  route through. MoonBoard has no product size, so [productSizeId] is the
 *  [MOONBOARD_NO_SIZE] sentinel there; a single-layout Aurora board likewise
 *  carries layout 0 / size 0 and lets the synced chunk derive the default. */
data class GymWallOption(
    val layoutId: Int,
    val productSizeId: Int,
    val label: String,
    val boardBrand: BoardBrand,
    /** Fixed installed angle for a Kilter wall whose `is_adjustable` is false —
     *  used to seed the browse angle on apply. Null for adjustable walls and
     *  every non-Kilter board (their angle is per-climb / the user's choice). */
    val fixedAngle: Int? = null,
    /** Marks the most-likely option in an unresolved multi-option list (e.g. the
     *  modal MoonBoard variant) so the UI can flag it as the recommended pick. */
    val isRecommended: Boolean = false,
)

/** Placeholder product size for MoonBoard options — MoonBoard variants are
 *  distinct boards, not sizes of one board, so there is nothing to carry. */
const val MOONBOARD_NO_SIZE = 0

/** Most-likely-first ordering for the unresolved MoonBoard variant list, keyed
 *  by layout_id: current full-size boards first, then Mini and legacy 2010.
 *  Drives the gym picker's variant order + recommended flag (FEAT-007). */
private val MOONBOARD_VARIANT_RANK: Map<Long, Int> =
    mapOf(2L to 0, 5L to 1, 4L to 2, 3L to 3, 7L to 4, 6L to 5, 1L to 6)

data class GymBoardPickerState(
    /** False while no location data is on the device — drives the sheet's
     *  "no gym data yet" empty state. Snapshot at VM init, self-healed on
     *  every search while false (the VM outlives the sheet, so a chunk that
     *  lands mid-session must re-enable the picker). */
    val enabled: Boolean = false,
    val query: String = "",
    val results: List<BoardLocation> = emptyList(),
    val selectedGym: BoardLocation? = null,
    val wallOptions: List<GymWallOption> = emptyList(),
    val searching: Boolean = false,
)

/**
 * FEAT-007 Path B — "find your gym". Local, offline search over the
 * synced `kilter_board_location`; tapping a gym resolves its physical
 * walls (`kilter_board_wall`) into selectable board configs, ordered by
 * how common each is across all gyms. Shared by Settings + Onboarding;
 * the host applies the final pick via its own ViewModel.
 */
@HiltViewModel
class GymBoardPickerViewModel @Inject constructor(
    private val repository: BoardLocationRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(GymBoardPickerState())
    val state: StateFlow<GymBoardPickerState> = _state.asStateFlow()

    private var frequency: Map<Int, Long> = emptyMap()
    // All location rows per physical gym (key = name + coarse coords). The same
    // gym can appear once per board brand — a Kilter row plus a foreign
    // info-layer row per Aurora board (Tension, Grasshopper, …) — so the search
    // list shows ONE entry per gym and selecting it aggregates the board options
    // across every brand present at that gym (FEAT-031).
    private var rowsByGym: Map<String, List<BoardLocation>> = emptyMap()

    init {
        viewModelScope.safeLaunch(TAG) {
            try {
                // Any synced location enables the picker. Walls give Kilter
                // gyms their rich per-board resolution, but a MoonBoard gym
                // (no walls) resolves via its variant instead — so gate on
                // locations, not walls, or MoonBoard-only data would hide
                // the whole "find my gym" path.
                val enabled = withContext(Dispatchers.IO) { repository.count() > 0L }
                frequency = withContext(Dispatchers.IO) { repository.productSizeFrequency() }
                _state.update { it.copy(enabled = enabled) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "init read failed — gym picker disabled this session", e)
                _state.update { it.copy(enabled = false) }
            }
        }
    }

    fun onQueryChange(q: String) {
        _state.update { it.copy(query = q, selectedGym = null, wallOptions = emptyList()) }
        val trimmed = q.trim()
        if (trimmed.length < 2) {
            _state.update { it.copy(results = emptyList(), searching = false) }
            return
        }
        viewModelScope.safeLaunch(TAG) {
            _state.update { it.copy(searching = true) }
            try {
                val res = withContext(Dispatchers.IO) { repository.searchLocations(trimmed, 60) }
                // Self-heal the one-shot `enabled` snapshot from init: this VM
                // outlives the sheet, so a locations chunk that lands mid-session
                // must re-enable the picker — re-check on every search while
                // disabled, or real results would stay masked by the "no data
                // yet" text for the rest of the session. Also (re)load the
                // size-frequency ordering the init read skipped on an empty DB.
                if (!_state.value.enabled) {
                    val hasData = res.isNotEmpty() ||
                        withContext(Dispatchers.IO) { repository.count() > 0L }
                    if (hasData) {
                        frequency = withContext(Dispatchers.IO) { repository.productSizeFrequency() }
                        _state.update { it.copy(enabled = true) }
                    }
                }
                // Drop the query result if the user kept typing.
                if (_state.value.query.trim() == trimmed) {
                    // Collapse per-brand rows of the same physical gym into one
                    // search entry (keep all rows for the selection step).
                    val grouped = res.groupBy { gymKey(it) }
                    rowsByGym = grouped
                    val deduped = grouped.values.map { it.first() }
                    _state.update { it.copy(results = deduped, searching = false) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "searchLocations failed", e)
                if (_state.value.query.trim() == trimmed) {
                    _state.update { it.copy(results = emptyList(), searching = false) }
                }
            }
        }
    }

    fun selectGym(gym: BoardLocation) {
        // Gather options across EVERY board brand present at this physical gym
        // (the merged search entry can stand for a Kilter row + foreign rows for
        // Tension/Grasshopper/etc.). So a gym with Tension + Grasshopper shows
        // once and offers both boards' configs (FEAT-031).
        val rows = (rowsByGym[gymKey(gym)] ?: listOf(gym))
            // Info-layer brands (aurora, 12climb) are map-only: no walls, not
            // selectable. Drop them up front so they neither fall into the
            // Kilter else-branch nor inflate multiBrand (which drives the
            // disambiguating "Kilter " size prefix at a mixed gym).
            .filter { it.boardBrand.isInteractive }
        val multiBrand = rows.map { it.boardBrand }.distinct().size > 1
        viewModelScope.safeLaunch(TAG) {
            try {
                val opts = mutableListOf<GymWallOption>()
                for (row in rows) {
                    val brand = row.boardBrand
                    when {
                        // MoonBoard gyms carry no walls — resolve to variants.
                        brand == BoardBrand.MOONBOARD -> opts += moonBoardOptions(row)
                        // Foreign Aurora gyms are info-only — resolve from the
                        // static variant catalog (multi-layout boards offer one
                        // option per variant; single-layout boards offer one,
                        // layout 0 / size 0 so the chunk derives the default).
                        brand.usesAuroraProtocol && brand != BoardBrand.KILTER ->
                            opts += auroraOptions(brand)
                        // Kilter gyms resolve their physical walls.
                        else -> {
                            val walls = withContext(Dispatchers.IO) { repository.getWallsForGym(row.id) }
                            opts += walls
                                .filter { it.layoutId != null && it.productSizeId != null }
                                .map { w ->
                                    val size = BoardConstants.sizeLabel(
                                        w.productSizeId!!.toLong(),
                                        w.sizeLabel ?: w.productName ?: "",
                                    )
                                    GymWallOption(
                                        layoutId = w.layoutId!!,
                                        productSizeId = w.productSizeId!!,
                                        // Prefix the brand only at mixed-brand gyms,
                                        // so a bare "12x12" isn't ambiguous there.
                                        label = if (multiBrand) "Kilter $size" else size,
                                        boardBrand = BoardBrand.KILTER,
                                        // Seed the browse angle only for a FIXED wall
                                        // (is_adjustable=false): there the reported
                                        // angle is a real board property. Adjustable
                                        // walls leave the angle to the user.
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
                Log.w(TAG, "selectGym(${gym.id}) failed", e)
                _state.update { it.copy(selectedGym = gym, wallOptions = emptyList()) }
            }
        }
    }

    /** Key that collapses per-brand rows of the same physical gym: name +
     *  coarse coordinates (~1 km) so foreign info-layer rows from different
     *  board brands merge while distinct chain locations stay separate.
     *
     *  Deliberately coarser than the map's geographic `venueKey`
     *  ([com.cruxcoach.android.ui.map.venueKey], ~11 m): the picker groups by
     *  gym IDENTITY (name across brands → one list entry), the map clusters by
     *  precise geography (one pin). The name component is why two different
     *  gyms at one address don't merge here even though they'd share a map pin. */
    private fun gymKey(loc: BoardLocation): String =
        loc.name.trim().lowercase() + "|" + (loc.lat * 100).toInt() + "|" + (loc.lng * 100).toInt()

    /** Board options for a foreign Aurora gym (FEAT-031). A multi-layout board
     *  (Tension, Decoy) yields one option per catalog variant; a single-layout
     *  board (Grasshopper / So iLL / Touchstone) has no catalog entry, so produce
     *  exactly one option labelled by the brand — layout 0 / size 0, letting the
     *  apply path's [BoardConstants.auroraVariant] lookup return null and the
     *  synced chunk supply the default. */
    private fun auroraOptions(brand: BoardBrand): List<GymWallOption> {
        val variants = BoardConstants.auroraVariants(brand)
        if (variants.isEmpty()) {
            // Single-layout board: offer one option per bundled size (Grasshopper
            // Master/Ninja/GrandMaster, So iLL 8x12/12x12) instead of a single
            // sizeless default, so a gym pick is a real size choice. layout 0 =
            // "the synced chunk derives the layout"; the size is explicit.
            val sizes = BoardConstants.auroraBundledSizes(brand)
            if (sizes.isEmpty()) {
                return listOf(
                    GymWallOption(layoutId = 0, productSizeId = 0,
                                  label = brand.displayName, boardBrand = brand),
                )
            }
            return sizes.map { s ->
                GymWallOption(
                    layoutId = 0,
                    productSizeId = s.id.toInt(),
                    label = "${brand.displayName} ${BoardConstants.auroraSizeLabel(brand, s)}",
                    boardBrand = brand,
                )
            }
        }
        return variants.map { variant ->
            // Surface the default size that WILL be applied (Decoy 12×12, TB2
            // "12 high x 12 wide") so the gym pick is never a SILENT size
            // assumption. The user can still change it via the manual picker
            // (the persistent "pick directly" link). Falls back to the bare
            // name when the default isn't in the bundle.
            val sizeName = BoardConstants.auroraBundledSizes(brand)
                .firstOrNull { it.id.toInt() == variant.defaultSizeId }
                ?.let { BoardConstants.auroraSizeLabel(brand, it) }
            GymWallOption(
                layoutId = variant.layoutId,
                productSizeId = variant.defaultSizeId,
                label = if (sizeName != null) "${variant.displayName} ($sizeName)" else variant.displayName,
                boardBrand = brand,
            )
        }
    }

    /** Variant options for a MoonBoard gym. A gym whose variant the cron
     *  could resolve from its description offers exactly that board; an
     *  unresolved one offers every supported variant so the climber picks
     *  the board actually in front of them. */
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
        // Unresolved: offer every variant, most-likely first, with the modal
        // 2016 set flagged recommended — a cold 1-of-7 becomes confirm/correct.
        return MoonBoardVariant.entries
            .sortedBy { MOONBOARD_VARIANT_RANK[it.layoutId] ?: Int.MAX_VALUE }
            .mapIndexed { index, variant ->
                GymWallOption(
                    layoutId = variant.layoutId.toInt(),
                    productSizeId = MOONBOARD_NO_SIZE,
                    label = variant.displayName,
                    boardBrand = BoardBrand.MOONBOARD,
                    isRecommended = index == 0,
                )
            }
    }

    fun clearGymSelection() {
        _state.update { it.copy(selectedGym = null, wallOptions = emptyList()) }
    }
}
