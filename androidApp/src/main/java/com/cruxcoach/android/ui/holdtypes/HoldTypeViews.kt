package com.cruxcoach.android.ui.holdtypes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.board.BoardImageCache
import com.cruxcoach.android.ui.board.KilterBoardVisualization
import com.cruxcoach.android.ui.board.MoonBoardAssetState
import com.cruxcoach.android.ui.board.MoonBoardVisualization
import com.cruxcoach.android.ui.board.boardImageCandidatePaths
import com.cruxcoach.android.ui.board.rememberMoonBoardAsset
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.data.repository.BoardImage
import com.cruxcoach.data.repository.BoardPlacement
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.BoardZone
import com.cruxcoach.domain.board.HoldRole
import com.cruxcoach.domain.board.MoonBoardVariant
import com.cruxcoach.domain.holdtypes.CANT_TELL
import com.cruxcoach.domain.holdtypes.FOOT_ONLY_NO
import com.cruxcoach.domain.holdtypes.FOOT_ONLY_YES
import com.cruxcoach.domain.holdtypes.HoldDirection
import com.cruxcoach.domain.holdtypes.HoldType
import com.cruxcoach.domain.holdtypes.HoldTypeEntry
import com.cruxcoach.domain.holdtypes.ReviewField
import com.cruxcoach.domain.holdtypes.moonBoardGridName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.roundToInt

/** Inches of board around the hold in the close-up, and the ring around it. */
private const val CLOSE_UP_INCHES = 10f
private const val RING_INCHES = 2.6f
/** Shiny Wall: centimetres of wall around the hold (two and a half grid cells), and the ring around it. */
private const val SHINY_CLOSE_UP_UNITS = 50f
private const val SHINY_RING_UNITS = 11f
/** MoonBoard close-up: grid cells of board around the hold. */
private const val MOON_CLOSE_UP_CELLS = 3f
private val MarkerColor = Color(0xFFFFC107)
private const val KILTER_HOMEWALL_LAYOUT = 8
/** MoonBoard `frames` role code for a start hold, as the catalogue writes it. */
private const val MOON_ROLE_START = 42

/** The board around one hold, as the hold-type screens draw it. */
sealed interface HoldBoardView {
    /** Aurora boards (Kilter, Tension, …): size, its hold-set images and the placements of its layout. */
    data class Aurora(
        val brand: BoardBrand,
        val boardSize: BoardSize,
        val boardImages: List<BoardImage>,
        val placements: Map<Int, BoardPlacement>,
    ) : HoldBoardView

    /** A MoonBoard generation; its image and hold map come with the app. */
    data class Moon(val layoutId: Long) : HoldBoardView
}

/** Loads and caches the board views the hold-type screens need, per board, layout and size. */
class HoldBoardViews @Inject constructor(private val boardRepository: BoardRepository) {
    private val cache = mutableMapOf<Triple<String, Int, Int>, HoldBoardView?>()

    suspend fun viewFor(board: String, entry: HoldTypeEntry): HoldBoardView? {
        val key = Triple(board, entry.layoutId, entry.viewSizeId)
        if (cache.containsKey(key)) return cache[key]
        val brand = BoardBrand.fromWireOrNull(board)
        val view = when {
            brand == null -> null
            brand == BoardBrand.MOONBOARD -> HoldBoardView.Moon(entry.layoutId.toLong())
            else -> withContext(Dispatchers.IO) {
                val size = boardRepository.getProductSize(entry.viewSizeId, board) ?: return@withContext null
                HoldBoardView.Aurora(
                    brand = brand,
                    boardSize = size,
                    boardImages = boardRepository.getBoardImages(entry.viewSizeId, entry.layoutId, board),
                    placements = boardRepository.getPlacementsForLayout(entry.viewSizeId, entry.layoutId, board)
                        .associateBy { it.placementId.toInt() },
                )
            }
        }
        cache[key] = view
        return view
    }
}

/** How a hold is named on screen: generation and grid position on MoonBoard, the placement number elsewhere. */
internal fun holdName(board: String, entry: HoldTypeEntry): String =
    if (BoardBrand.fromWireOrNull(board) == BoardBrand.MOONBOARD) {
        val generation = MoonBoardVariant.fromLayoutId(entry.layoutId.toLong())?.displayName ?: "#${entry.layoutId}"
        "$generation · ${moonBoardGridName(entry.placement)}"
    } else {
        "#${entry.placement}"
    }

/** A close-up of the hold next to the whole board with the hold marked. */
@Composable
internal fun HoldViews(entry: HoldTypeEntry?, board: HoldBoardView?) {
    entry ?: return
    when (board) {
        null -> Text(stringResource(R.string.hold_review_no_board))
        is HoldBoardView.Aurora -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            HoldCloseUp(entry, board, Modifier.weight(0.6f))
            val placement = board.placements[entry.placement]
            val role = if (board.brand == BoardBrand.KILTER && entry.layoutId == KILTER_HOMEWALL_LAYOUT) {
                HoldRole.ROUTE_START
            } else {
                HoldRole.START
            }
            Box(Modifier.weight(0.4f)) {
                KilterBoardVisualization(
                    holds = listOf(BoardHold(entry.placement, role)),
                    placements = board.placements,
                    boardSize = board.boardSize,
                    boardImages = board.boardImages,
                    zone = placement?.let {
                        val r = if (board.brand == BoardBrand.SHINYWALL) 15 else 6
                        BoardZone(it.x - r, it.x + r, it.y - r, it.y + r)
                    },
                )
            }
        }
        is HoldBoardView.Moon -> {
            val asset = rememberMoonBoardAsset(board.layoutId)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                MoonHoldCloseUp(entry.placement, asset, Modifier.weight(0.6f))
                Box(Modifier.weight(0.4f)) {
                    MoonBoardVisualization(
                        frames = "p${entry.placement}r$MOON_ROLE_START",
                        assetState = asset,
                        variant = MoonBoardVariant.fromLayoutId(board.layoutId),
                    )
                }
            }
        }
    }
}

/** The board image around one hold, cut from the same asset the board view draws, with a ring on the hold. */
@Composable
private fun HoldCloseUp(entry: HoldTypeEntry, board: HoldBoardView.Aurora, modifier: Modifier) {
    val context = LocalContext.current
    val layoutId = board.boardImages.firstOrNull()?.layoutId ?: entry.layoutId.toLong()
    val paths = remember(board.brand, board.boardSize.id, layoutId) {
        boardImageCandidatePaths(board.brand, board.boardSize.id, layoutId)
    }
    var image by remember(paths) { mutableStateOf<ImageBitmap?>(BoardImageCache.get(paths.first())) }
    LaunchedEffect(paths) { image = BoardImageCache.getOrDecode(paths, context.assets) }
    val placement = board.placements[entry.placement]
    val size = board.boardSize
    Canvas(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black),
    ) {
        val img = image ?: return@Canvas
        val p = placement ?: return@Canvas
        val boardWidth = (size.edgeRight - size.edgeLeft).toFloat()
        val boardHeight = (size.edgeTop - size.edgeBottom).toFloat()
        val px = (p.x - size.edgeLeft) / boardWidth * img.width
        val py = img.height - (p.y - size.edgeBottom) / boardHeight * img.height
        // Shiny Wall units are centimetres and a hold fills a 20-unit cell: a wider window and a bigger ring.
        val shiny = board.brand == BoardBrand.SHINYWALL
        val windowUnits = if (shiny) SHINY_CLOSE_UP_UNITS else CLOSE_UP_INCHES
        val ringUnits = if (shiny) SHINY_RING_UNITS else RING_INCHES
        val window = (windowUnits / boardWidth * img.width).roundToInt()
        drawCloseUp(listOf(img), px, py, window, ringRadiusPx = ringUnits / boardWidth * img.width)
    }
}

/** The MoonBoard image (board plate and hold layers) around one hold, with a ring on it. */
@Composable
private fun MoonHoldCloseUp(holdId: Int, asset: MoonBoardAssetState, modifier: Modifier) {
    Canvas(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black),
    ) {
        val ready = asset as? MoonBoardAssetState.Ready ?: return@Canvas
        val img = ready.asset.image
        val xy = ready.asset.holdXy[holdId] ?: return@Canvas
        // The grid pitch from two neighbouring holds of the map; a MoonBoard row has 11 holds.
        val a = ready.asset.holdXy[1]
        val b = ready.asset.holdXy[2]
        val pitch = if (a != null && b != null) (b.x - a.x) * img.width else img.width / 12f
        val window = (pitch * MOON_CLOSE_UP_CELLS).roundToInt()
        drawCloseUp(listOf(img) + ready.asset.overlays, xy.x * img.width, xy.y * img.height, window, ringRadiusPx = pitch * 0.45f)
    }
}

/** Draws the same square window of every layer, scaled to the canvas, and a ring around (px, py). */
private fun DrawScope.drawCloseUp(layers: List<ImageBitmap>, px: Float, py: Float, windowPx: Int, ringRadiusPx: Float) {
    val img = layers.first()
    val window = windowPx.coerceIn(1, minOf(img.width, img.height))
    val left = (px - window / 2f).roundToInt().coerceIn(0, img.width - window)
    val top = (py - window / 2f).roundToInt().coerceIn(0, img.height - window)
    layers.forEach { layer ->
        drawImage(
            image = layer,
            srcOffset = IntOffset(left, top),
            srcSize = IntSize(window, window),
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        )
    }
    val scale = size.width / window
    drawCircle(
        color = MarkerColor,
        radius = ringRadiusPx * scale,
        center = Offset((px - left) * scale, (py - top) * scale),
        style = Stroke(width = 2.dp.toPx()),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TypeChooser(selected: String?, enabled: Boolean, withCantTell: Boolean = true, onSelect: (String) -> Unit) {
    Text(stringResource(R.string.hold_review_q_type), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HoldType.entries.forEach { type ->
            FilterChip(
                selected = selected == type.wire,
                enabled = enabled,
                onClick = { onSelect(type.wire) },
                label = { Text(typeName(type)) },
            )
        }
        if (withCantTell) {
            FilterChip(
                selected = selected == CANT_TELL,
                enabled = enabled,
                onClick = { onSelect(CANT_TELL) },
                label = { Text(stringResource(R.string.hold_review_cant_tell)) },
            )
        }
    }
}

/** Eight arrows around an "all round" circle, laid out like the directions they stand for. */
@Composable
internal fun DirectionChooser(selected: String?, enabled: Boolean, withCantTell: Boolean = true, onSelect: (String) -> Unit) {
    Text(stringResource(R.string.hold_review_q_direction), style = MaterialTheme.typography.labelLarge)
    val grid = listOf(
        listOf(HoldDirection.UP_LEFT, HoldDirection.UP, HoldDirection.UP_RIGHT),
        listOf(HoldDirection.LEFT, HoldDirection.ALL_ROUND, HoldDirection.RIGHT),
        listOf(HoldDirection.DOWN_LEFT, HoldDirection.DOWN, HoldDirection.DOWN_RIGHT),
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            grid.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { direction ->
                        DirectionCell(direction, selected == direction.wire, enabled) { onSelect(direction.wire) }
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val current = HoldDirection.fromWire(selected)
            if (current == HoldDirection.ALL_ROUND) Text(stringResource(R.string.hold_direction_all_round))
            if (withCantTell) {
                FilterChip(
                    selected = selected == CANT_TELL,
                    enabled = enabled,
                    onClick = { onSelect(CANT_TELL) },
                    label = { Text(stringResource(R.string.hold_review_cant_tell)) },
                )
            }
        }
    }
}

@Composable
private fun DirectionCell(direction: HoldDirection, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(shape)
            .background(if (selected) OrangeAccent else MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            arrow(direction),
            fontSize = 20.sp,
            color = if (selected) CruxCoachDesign.colors.onBrandAccent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FootOnlyChooser(selected: String?, enabled: Boolean, withCantTell: Boolean = true, onSelect: (String) -> Unit) {
    Text(stringResource(R.string.hold_review_q_foot_only), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        buildList {
            add(FOOT_ONLY_YES to R.string.hold_review_yes)
            add(FOOT_ONLY_NO to R.string.hold_review_no)
            if (withCantTell) add(CANT_TELL to R.string.hold_review_cant_tell)
        }.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                enabled = enabled,
                onClick = { onSelect(value) },
                label = { Text(stringResource(label)) },
            )
        }
    }
}

@Composable
internal fun NavRow(onPrevious: () -> Unit, onNext: () -> Unit, nextEnabled: Boolean, previousEnabled: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = onPrevious, enabled = previousEnabled) { Text("‹ " + stringResource(R.string.action_back)) }
        Button(onClick = onNext, enabled = nextEnabled) { Text(stringResource(R.string.action_next) + " ›") }
    }
}

internal fun arrow(direction: HoldDirection): String = when (direction) {
    HoldDirection.UP -> "↑"
    HoldDirection.UP_RIGHT -> "↗"
    HoldDirection.RIGHT -> "→"
    HoldDirection.DOWN_RIGHT -> "↘"
    HoldDirection.DOWN -> "↓"
    HoldDirection.DOWN_LEFT -> "↙"
    HoldDirection.LEFT -> "←"
    HoldDirection.UP_LEFT -> "↖"
    HoldDirection.ALL_ROUND -> "◯"
}

@Composable
internal fun typeName(type: HoldType): String = stringResource(
    when (type) {
        HoldType.JUG -> R.string.hold_type_jug
        HoldType.EDGE -> R.string.hold_type_edge
        HoldType.SLOPER -> R.string.hold_type_sloper
        HoldType.PINCH -> R.string.hold_type_pinch
        HoldType.POCKET -> R.string.hold_type_pocket
        HoldType.VOLUME -> R.string.hold_type_volume
        HoldType.FOOT_CHIP -> R.string.hold_type_foot_chip
    },
)

@Composable
internal fun fieldName(field: ReviewField): String = stringResource(
    when (field) {
        ReviewField.TYPE -> R.string.hold_review_q_type
        ReviewField.DIRECTION -> R.string.hold_review_field_direction
        ReviewField.FOOT_ONLY -> R.string.hold_review_q_foot_only
    },
)

@Composable
internal fun valueName(field: ReviewField, value: String?): String = when {
    value == null -> "–"
    value == CANT_TELL -> stringResource(R.string.hold_review_cant_tell)
    field == ReviewField.TYPE -> HoldType.fromWire(value)?.let { typeName(it) } ?: value
    field == ReviewField.DIRECTION -> HoldDirection.fromWire(value)?.let {
        if (it == HoldDirection.ALL_ROUND) stringResource(R.string.hold_direction_all_round) else arrow(it)
    } ?: value
    value == FOOT_ONLY_YES -> stringResource(R.string.hold_review_yes)
    value == FOOT_ONLY_NO -> stringResource(R.string.hold_review_no)
    else -> value
}
