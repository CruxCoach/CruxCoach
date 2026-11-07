package com.cruxcoach.android.boardscan

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.android.ui.board.assetBaseName
import com.cruxcoach.android.ui.board.parseMoonBoardLayout
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.domain.board.HoldRole
import com.cruxcoach.domain.board.MoonBoardVariant
import dagger.hilt.android.qualifiers.ApplicationContext
import com.cruxcoach.android.data.CatalogueRevisionSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.lang.ref.SoftReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

/** The board a scan looks at: the app's active board selection. */
data class ScanBoard(val brand: String, val layoutId: Int, val sizeId: Int, val angle: Int) {
    val moonBoardVariant: MoonBoardVariant?
        get() = if (brand == BRAND_MOONBOARD) MoonBoardVariant.fromLayoutId(layoutId.toLong()) else null

    /** The boards the recogniser was measured on: Kilter Original (every size), MoonBoard 2016 and 2024. */
    val isSupported: Boolean
        get() = when (brand) {
            BRAND_KILTER -> layoutId == BoardConstants.KILTER_ORIGINAL_LAYOUT
            BRAND_MOONBOARD -> moonBoardVariant in MARKED_MOONBOARDS
            else -> false
        }

    /**
     * What the catalogue depends on: a Kilter problem's holds are the same at every angle, a MoonBoard catalogue
     * has one problem per wall angle.
     */
    val catalogueKey: ScanBoard get() = if (moonBoardVariant != null) this else copy(angle = 0)

    companion object {
        const val BRAND_KILTER = "kilter"
        const val BRAND_MOONBOARD = "moonboard"

        /** MoonBoards whose coloured holds are known ([marksAsset]): the grid reader needs them. */
        private val MARKED_MOONBOARDS = setOf(MoonBoardVariant.MOONBOARD_2016, MoonBoardVariant.MOONBOARD_2024)

        fun marksAsset(variant: MoonBoardVariant) = "board_scan/${variant.assetBaseName()}_marks.txt"
    }
}

/** One board's problems in the recogniser's form, plus what a scan needs to read that board. */
class ScanCatalogue(
    val board: ScanBoard,
    val index: ClimbIndex,
    /** Climb uuid per index row, most climbed first. */
    val uuids: Array<String>,
    val palettes: List<Palette>,
    val staticMarks: List<StaticMark>,
    /** Kilter problems can be lit mirrored; MoonBoard problems cannot. */
    val searchMirrored: Boolean,
) {
    val size: Int get() = index.size

    /** Index row of a climb, or -1 when it is not among this board's problems. */
    fun rowOf(uuid: String): Int = uuids.indexOfFirst { it.equals(uuid, ignoreCase = true) }
}

/** Hole positions of one board in board units (y up), as the recogniser needs them. */
internal class ScanGeometry(
    /** Placement id (Kilter) or hold id (MoonBoard) per hole. */
    val holeIds: IntArray,
    val holeX: FloatArray,
    val holeY: FloatArray,
    val spacing: Double,
    /** xmin, xmax, ymin, ymax. */
    val extent: DoubleArray,
    val metresPerUnit: Double,
) {
    /** Hole index per placement id (-1 = not on this board). */
    val holeOf: IntArray = IntArray((holeIds.maxOrNull() ?: 0) + 1) { -1 }.also { a ->
        holeIds.forEachIndexed { i, id -> a[id] = i }
    }

    val minHoleDist: Double
        get() {
            var d = Double.MAX_VALUE
            for (i in holeX.indices) for (j in i + 1 until holeX.size) {
                d = min(d, hypot((holeX[i] - holeX[j]).toDouble(), (holeY[i] - holeY[j]).toDouble()))
            }
            return d
        }
}

/** One catalogue problem as hole indices and recogniser roles ([Roles]). */
internal class ScanClimb(val uuid: String, val holes: ShortArray, val roles: ByteArray, val ascents: Int)

/**
 * Reads a "p{placement}r{role}…" frame into hole indices and recogniser roles. Null when a hold is not on this
 * board, its role is unknown, or the problem has fewer than 3 or more than [MAX_HOLDS] holds: such a problem cannot
 * be the one lit on this board, or is too big to tell apart. Also null for the Kilter API's "h{hole}p{role}" form:
 * its numbers are hole ids, which overlap placement ids, so read as placements they would light other holds.
 */
internal fun parseScanClimb(uuid: String, frames: String, holeOf: IntArray, ascents: Int): ScanClimb? {
    val holes = ShortArray(MAX_HOLDS)
    val roles = ByteArray(MAX_HOLDS)
    var n = 0
    fun add(placement: Int, role: Int): Boolean {
        val h = if (placement in holeOf.indices) holeOf[placement] else -1
        val r = HoldRole.roleClass(role) - HoldRole.START
        if (h < 0 || r !in 0..3 || n >= MAX_HOLDS) return false
        holes[n] = h.toShort(); roles[n] = r.toByte(); n++
        return true
    }
    if (!frames.startsWith('p')) return null
    // hand-rolled: the regex parser is too slow for a whole catalogue on an older phone
    var i = 0
    val len = frames.length
    while (i < len) {
        if (frames[i] != 'p') return null
        i++
        var pl = 0
        while (i < len && frames[i].isDigit()) { pl = pl * 10 + (frames[i] - '0'); i++ }
        if (i >= len || frames[i] != 'r') return null
        i++
        var ro = 0
        while (i < len && frames[i].isDigit()) { ro = ro * 10 + (frames[i] - '0'); i++ }
        if (!add(pl, ro)) return null
    }
    if (n < 3) return null
    return ScanClimb(uuid, holes.copyOf(n), roles.copyOf(n), ascents)
}

/** Packs the problems, most climbed first, into the recogniser's index. */
internal fun buildScanIndex(geo: ScanGeometry, climbs: List<ScanClimb>): Pair<ClimbIndex, Array<String>> {
    val sorted = climbs.sortedByDescending { it.ascents }
    val offsets = IntArray(sorted.size + 1)
    for ((i, c) in sorted.withIndex()) offsets[i + 1] = offsets[i] + c.holes.size
    val holes = ShortArray(offsets.last())
    val roles = ByteArray(offsets.last())
    for ((i, c) in sorted.withIndex()) {
        c.holes.copyInto(holes, offsets[i])
        c.roles.copyInto(roles, offsets[i])
    }
    val index = ClimbIndex(
        geo.holeX, geo.holeY, offsets, holes, roles, IntArray(sorted.size) { sorted[it].ascents },
        geo.spacing, geo.minHoleDist, geo.extent, geo.metresPerUnit,
    )
    return index to Array(sorted.size) { sorted[it].uuid }
}

/**
 * MoonBoard holes on the regular 11-column grid of the bundled layout: the free (unoccupied) cells mark the LED
 * positions, so a straight line through their per-column and per-row medians gives every hole. Units are pixels of
 * the bundled board image ([imageW] x [imageH]) with y up, as the coloured-hold marks use; holes are 20 cm apart.
 */
internal fun moonBoardGeometry(holds: List<MoonBoardHold>, rows: Int, imageW: Int, imageH: Int): ScanGeometry {
    val cols = MoonBoardVariant.GRID_COLUMNS
    val colX = HashMap<Int, MutableList<Double>>()
    val rowY = HashMap<Int, MutableList<Double>>()
    for (h in holds) {
        if (h.occupied) continue
        colX.getOrPut((h.holdId - 1) % cols) { ArrayList() }.add(h.x * imageW.toDouble())
        rowY.getOrPut((h.holdId - 1) / cols) { ArrayList() }.add(h.y * imageH.toDouble())
    }
    val (ax, bx) = lineFit(colX)
    val (ay, by) = lineFit(rowY)
    val n = cols * rows
    val xs = FloatArray(n)
    val ys = FloatArray(n)
    for (id in 1..n) {
        val c = (id - 1) % cols
        val r = (id - 1) / cols
        xs[id - 1] = (ax * c + bx).toFloat()
        ys[id - 1] = (imageH - (ay * r + by)).toFloat()
    }
    return ScanGeometry(
        IntArray(n) { it + 1 }, xs, ys, min(abs(ax), abs(ay)),
        doubleArrayOf(0.0, imageW.toDouble(), 0.0, imageH.toDouble()), MOONBOARD_HOLE_SPACING_M / abs(ax),
    )
}

/** A MoonBoard layout cell: normalised image position, and whether a hold covers its LED. */
internal class MoonBoardHold(val holdId: Int, val x: Float, val y: Float, val occupied: Boolean)

/** Least-squares line through the medians of each group: value = a * key + b. */
private fun lineFit(groups: Map<Int, List<Double>>): Pair<Double, Double> {
    val ks = groups.keys.sorted()
    val vs = ks.map { k -> groups.getValue(k).sorted().let { it[it.size / 2] } }
    val n = ks.size.toDouble()
    val mk = ks.sum() / n
    val mv = vs.sum() / n
    var sxy = 0.0
    var sxx = 0.0
    for (i in ks.indices) {
        sxy += (ks[i] - mk) * (vs[i] - mv)
        sxx += (ks[i] - mk) * (ks[i] - mk)
    }
    val a = sxy / sxx
    return a to (mv - a * mk)
}

internal fun parseStaticMarks(text: String): List<StaticMark> = text.lineSequence()
    .map { it.trim() }.filter { it.isNotEmpty() }
    .map { l -> l.split(' ').let { StaticMark(it[0].toDouble(), it[1].toDouble(), it[2].toDouble()) } }
    .toList()

/** Kilter Original: holes on a 4-inch grid with a diagonal offset; board units are inches. */
private val KILTER_SPACING = 4.0 * sqrt(2.0)
private const val KILTER_METRES_PER_UNIT = 0.0254
private const val MOONBOARD_HOLE_SPACING_M = 0.20
internal const val MAX_HOLDS = 32

/**
 * Builds the recogniser's catalogue for the active board from the phone's catalogue, and keeps the last one while
 * memory allows: a whole Kilter catalogue takes several seconds to read on an older phone. A catalogue sync makes it
 * stale.
 */
@Singleton
class ScanCatalogueLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: BoardDatabase,
    private val repository: BoardRepository,
    private val revisions: CatalogueRevisionSource,
) {
    private val mutex = Mutex()
    private var cached: SoftReference<ScanCatalogue>? = null
    private var cachedRevision = -1

    /** The catalogue for [board]; null when the board is not supported or its geometry is missing. */
    suspend fun load(board: ScanBoard): ScanCatalogue? = mutex.withLock {
        val key = board.catalogueKey
        val revision = revisions.catalogueRevision.first()
        cached?.get()?.takeIf { it.board == key && cachedRevision == revision }?.let { return it }
        // let the old catalogue go before the new one is built: both at once can be ~100 MB on Kilter
        cached = null
        // a build the caller stopped waiting for is kept for the next caller
        withContext(NonCancellable + Dispatchers.IO) {
            val built = runCatching { build(key) }.onFailure { Log.e(TAG, "catalogue for $key failed", it) }.getOrNull()
            cached = built?.let { SoftReference(it) }
            cachedRevision = revision
            built
        }
    }

    /** Problems of [board] whose name contains [query], most climbed first: uuid and name. */
    fun search(board: ScanBoard, query: String, limit: Int): List<Pair<String, String>> {
        val pattern = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        return database.boardQueries.searchClimbsForBoardScan(
            boardBrand = board.brand,
            layoutId = board.layoutId.toLong(),
            pattern = pattern,
            angle = if (board.moonBoardVariant != null) board.angle.toLong() else null,
            limit = limit.toLong(),
        ) { uuid, name, _ -> uuid to name }.executeAsList()
    }

    private fun build(board: ScanBoard): ScanCatalogue? {
        if (!board.isSupported) return null
        val t0 = System.nanoTime()
        val variant = board.moonBoardVariant
        val geo = if (variant != null) moonBoardGeometry(variant) else kilterGeometry(board)
        geo ?: return null
        val climbs = database.boardQueries.getClimbsForBoardScan(
            boardBrand = board.brand,
            layoutId = board.layoutId.toLong(),
            angle = if (variant != null) board.angle.toLong() else null,
        ) { uuid, frames, popularity ->
            parseScanClimb(uuid, frames, geo.holeOf, popularity.toInt()) ?: SKIPPED
        }.executeAsList().filter { it !== SKIPPED }
        val (index, uuids) = buildScanIndex(geo, climbs)
        val marks = variant?.let { v ->
            context.assets.open(ScanBoard.marksAsset(v)).bufferedReader().use { parseStaticMarks(it.readText()) }
        } ?: emptyList()
        Log.i(TAG, "catalogue $board: ${index.size} problems in ${(System.nanoTime() - t0) / 1_000_000} ms")
        return ScanCatalogue(
            board, index, uuids,
            palettes = if (variant != null) listOf(Palette.MOONBOARD) else listOf(Palette.KILTER, Palette.CRUXCOACH),
            staticMarks = marks,
            searchMirrored = variant == null,
        )
    }

    private fun kilterGeometry(board: ScanBoard): ScanGeometry? {
        val size = repository.getProductSize(board.sizeId, board.brand) ?: return null
        val lit = repository.getLedGrid(board.sizeId, board.brand).mapTo(HashSet()) { it.placementId }
        val placements = repository.getPlacementsForLayout(board.sizeId, board.layoutId, board.brand)
            .filter {
                it.placementId in lit && it.x > size.edgeLeft && it.x < size.edgeRight &&
                    it.y > size.edgeBottom && it.y < size.edgeTop
            }
            .sortedBy { it.placementId }
        if (placements.size < 10) return null
        return ScanGeometry(
            IntArray(placements.size) { placements[it].placementId.toInt() },
            FloatArray(placements.size) { placements[it].x.toFloat() },
            FloatArray(placements.size) { placements[it].y.toFloat() },
            KILTER_SPACING,
            doubleArrayOf(size.edgeLeft.toDouble(), size.edgeRight.toDouble(), size.edgeBottom.toDouble(), size.edgeTop.toDouble()),
            KILTER_METRES_PER_UNIT,
        )
    }

    private fun moonBoardGeometry(variant: MoonBoardVariant): ScanGeometry {
        val base = variant.assetBaseName()
        val layout = context.assets.open("board_images/$base.json").bufferedReader().use { parseMoonBoardLayout(it.readText()) }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.assets.open("board_images/${layout.image}").use { BitmapFactory.decodeStream(it, null, bounds) }
        return moonBoardGeometry(
            layout.holds.map { MoonBoardHold(it.holdId, it.x, it.y, it.occupied) },
            variant.gridRows, bounds.outWidth, bounds.outHeight,
        )
    }

    private companion object {
        const val TAG = "BoardScan"
        val SKIPPED = ScanClimb("", ShortArray(0), ByteArray(0), 0)
    }
}
