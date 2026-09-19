package com.cruxcoach.app.render

import com.cruxcoach.domain.board.MoonBoardVariant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Port of Android's MoonBoardAsset.kt (coordinate-map JSON) and the geometry of
// MoonBoardVisualization.kt. Real MoonBoard holds sit a few percent off any regular
// lattice, so positions come from the measured per-hold map, never interpolation.

@Serializable
data class MoonBoardLayoutJson(
    val variant: String,
    val image: String,
    val overlays: List<String> = emptyList(),
    val imageAspect: Float,
    val holds: List<MoonBoardHoldJson>,
)

@Serializable
data class MoonBoardHoldJson(
    /** 1-based, `(row-1)*11 + col + 1`. */
    val holdId: Int,
    val x: Float,
    val y: Float,
    val occupied: Boolean,
)

private val moonBoardJson = Json { ignoreUnknownKeys = true }

/** Throws on malformed JSON; use [parseMoonBoardLayoutOrNull] from Swift-facing code. */
fun parseMoonBoardLayout(jsonText: String): MoonBoardLayoutJson = moonBoardJson.decodeFromString(jsonText)

fun parseMoonBoardLayoutOrNull(jsonText: String): MoonBoardLayoutJson? =
    try { parseMoonBoardLayout(jsonText) } catch (e: Exception) { null }

fun MoonBoardVariant.assetBaseName(): String = when (this) {
    MoonBoardVariant.MOONBOARD_2016 -> "moonboard_2016"
    MoonBoardVariant.MASTERS_2017 -> "moonboard_2017"
    MoonBoardVariant.MASTERS_2019 -> "moonboard_2019"
    MoonBoardVariant.MINI_2020 -> "mini_moonboard_2020"
    MoonBoardVariant.MOONBOARD_2024 -> "moonboard_2024"
    MoonBoardVariant.MINI_2025 -> "mini_moonboard_2025"
    MoonBoardVariant.MOONBOARD_2010 -> "moonboard_2010"
}

/** Relative path of the variant's coordinate map; image paths are `board_images/<layout.image>`. */
fun MoonBoardVariant.layoutJsonAssetPath(): String = "board_images/${assetBaseName()}.json"

fun moonBoardImageAssetPath(filename: String): String = "board_images/$filename"

object MoonBoardRender {
    const val ROLE_START = 42
    const val ROLE_HAND = 43
    const val ROLE_FINISH = 44

    const val START_ARGB: Long = 0xFF2FB84A
    const val HAND_ARGB: Long = 0xFF2F6BE0
    const val FINISH_ARGB: Long = 0xFFE23B36
    const val PANEL_ARGB: Long = 0xFFEAE1CC
    const val GRID_DOT_ARGB: Long = 0x40202020

    /** Card aspect (width / height) of the procedural no-photo grid. */
    const val FALLBACK_ASPECT_RATIO = 0.65f

    /** Ring radius on a real board image as a fraction of the image WIDTH. */
    const val IMAGE_HOLD_RADIUS_FRACTION = 0.028f
    const val TAP_THRESHOLD_FACTOR = 1.6f

    // Marker = translucent fill + black / white / role strokes, as fractions of the radius.
    const val MARKER_FILL_ALPHA = 0.22f
    const val MARKER_BLACK_STROKE = 0.72f
    const val MARKER_WHITE_STROKE = 0.52f
    const val MARKER_ROLE_STROKE = 0.32f

    const val GRID_HOLD_RADIUS_FACTOR = 0.34f
    const val GRID_DOT_RADIUS_FACTOR = 0.13f

    /** Null = role is not drawn. */
    fun roleArgb(roleCode: Int): Long? = when (roleCode) {
        ROLE_START -> START_ARGB
        ROLE_HAND -> HAND_ARGB
        ROLE_FINISH -> FINISH_ARGB
        else -> null
    }

    fun holdId(row: Int, col: Int): Int = (row - 1) * MoonBoardVariant.GRID_COLUMNS + col + 1
}

/** Measured board: normalized (0..1) hold centres over the image. */
class MoonBoardMappedGeometry(layout: MoonBoardLayoutJson) {
    val imageAspect: Float = layout.imageAspect
    val holdXy: Map<Int, BoardPoint> = layout.holds.associate { it.holdId to BoardPoint(it.x, it.y) }

    fun point(holdId: Int, canvasWidth: Float, canvasHeight: Float): BoardPoint? =
        holdXy[holdId]?.let { BoardPoint(it.x * canvasWidth, it.y * canvasHeight) }

    fun holdRadius(canvasWidth: Float): Float = canvasWidth * MoonBoardRender.IMAGE_HOLD_RADIUS_FRACTION

    /** Nearest measured hold within 1.6 x the marker radius (in normalized units), or null. */
    fun holdIdAt(tapX: Float, tapY: Float, canvasWidth: Float, canvasHeight: Float): Int? {
        if (canvasWidth <= 0f || canvasHeight <= 0f) return null
        val nx = tapX / canvasWidth
        val ny = tapY / canvasHeight
        if (nx !in 0f..1f || ny !in 0f..1f) return null
        val threshold = MoonBoardRender.IMAGE_HOLD_RADIUS_FRACTION * MoonBoardRender.TAP_THRESHOLD_FACTOR
        fun d2(p: BoardPoint) = (p.x - nx) * (p.x - nx) + (p.y - ny) * (p.y - ny)
        return holdXy.minByOrNull { d2(it.value) }?.takeIf { sqrt(d2(it.value)) <= threshold }?.key
    }
}

/** Procedural 11 x [gridRows] lattice used when a variant has no decodable image. */
class MoonBoardGridGeometry(val gridRows: Int) {
    val aspectRatio: Float get() = MoonBoardRender.FALLBACK_ASPECT_RATIO

    private class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width get() = right - left
        val height get() = bottom - top
    }

    private fun gridRect(w: Float, h: Float) = Rect(w * 0.09f, h * 0.055f, w * 0.94f, h * 0.955f)

    private fun centre(grid: Rect, column: Int, rowIndex: Int) = BoardPoint(
        grid.left + column * grid.width / (MoonBoardVariant.GRID_COLUMNS - 1),
        grid.top + (gridRows - 1 - rowIndex) * grid.height / (gridRows - 1),
    )

    private fun cellSpacing(grid: Rect): Float =
        min(grid.width / (MoonBoardVariant.GRID_COLUMNS - 1), grid.height / (gridRows - 1))

    fun point(holdId: Int, canvasWidth: Float, canvasHeight: Float): BoardPoint? {
        if (holdId !in 1..MoonBoardVariant.GRID_COLUMNS * gridRows) return null
        val column = (holdId - 1) % MoonBoardVariant.GRID_COLUMNS
        val rowIndex = (holdId - 1) / MoonBoardVariant.GRID_COLUMNS
        return centre(gridRect(canvasWidth, canvasHeight), column, rowIndex)
    }

    fun holdRadius(canvasWidth: Float, canvasHeight: Float): Float =
        cellSpacing(gridRect(canvasWidth, canvasHeight)) * MoonBoardRender.GRID_HOLD_RADIUS_FACTOR

    fun dotRadius(canvasWidth: Float, canvasHeight: Float): Float =
        cellSpacing(gridRect(canvasWidth, canvasHeight)) * MoonBoardRender.GRID_DOT_RADIUS_FACTOR

    /** Taps further than half a cell from a lattice point are rejected. */
    fun holdIdAt(tapX: Float, tapY: Float, canvasWidth: Float, canvasHeight: Float): Int? {
        if (canvasWidth <= 0f || canvasHeight <= 0f) return null
        if (tapX / canvasWidth !in 0f..1f || tapY / canvasHeight !in 0f..1f) return null
        val grid = gridRect(canvasWidth, canvasHeight)
        val colStep = grid.width / (MoonBoardVariant.GRID_COLUMNS - 1)
        val rowStep = grid.height / (gridRows - 1)
        val col = ((tapX - grid.left) / colStep).roundToInt()
        val rowIndex = (gridRows - 1) - ((tapY - grid.top) / rowStep).roundToInt()
        if (col !in 0 until MoonBoardVariant.GRID_COLUMNS || rowIndex !in 0 until gridRows) return null
        val c = centre(grid, col, rowIndex)
        if (abs(tapX - c.x) > colStep / 2 || abs(tapY - c.y) > rowStep / 2) return null
        return rowIndex * MoonBoardVariant.GRID_COLUMNS + col + 1
    }
}

/** Candidate paths for a board PREVIEW image (picker), most specific first (port of BoardPreview.kt). */
fun boardPreviewCandidatePaths(
    brand: com.cruxcoach.domain.board.BoardBrand,
    sizeId: Long,
    layoutId: Long?,
): List<String> =
    if (brand == com.cruxcoach.domain.board.BoardBrand.MOONBOARD) {
        when (val variant = layoutId?.let { MoonBoardVariant.fromLayoutId(it) }) {
            MoonBoardVariant.MOONBOARD_2010 -> listOf("board_images/moonboard_2010_base.png")
            MoonBoardVariant.MINI_2025 -> listOf("board_images/mini_moonboard_2025_base.png")
            null -> emptyList()
            else -> listOf("board_images/${variant.assetBaseName()}.webp")
        }
    } else {
        boardImageCandidatePaths(brand, sizeId, layoutId)
    }
