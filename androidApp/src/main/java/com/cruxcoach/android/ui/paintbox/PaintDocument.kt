package com.cruxcoach.android.ui.paintbox

import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant
import com.cruxcoach.domain.board.MoonBoardHoldSets
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class PaintBoard(
    val brand: String,
    val layoutId: Int,
    val sizeId: Int,
    val holdSets: List<Long> = emptyList(),
    val label: String = "",
)

/** Boards PaintBox can light: the Aurora family, MoonBoard and Quantum. */
val BoardBrand.supportsPaintBox: Boolean
    get() = usesAuroraProtocol || this == BoardBrand.MOONBOARD || this == BoardBrand.QUANTUM

/** Same physical board configuration; the label is only display text. */
fun PaintBoard.sameBoardAs(other: PaintBoard): Boolean {
    if (brand != other.brand || layoutId != other.layoutId) return false
    if (brand != "moonboard") return sizeId == other.sizeId && holdSets == other.holdSets
    // A MoonBoard has no product size (the saved one belongs to another board),
    // and no hold sets means all of the variant's.
    return mountedMoonHoldSets() == other.mountedMoonHoldSets()
}

private fun PaintBoard.mountedMoonHoldSets(): Set<Long> {
    val variant = MoonBoardVariant.fromLayoutId(layoutId.toLong()) ?: return holdSets.toSet()
    return holdSets.ifEmpty { MoonBoardHoldSets.setIdsFor(variant) }.toSet()
}

fun PaintBoard.moonHoldIds(): Set<Int> {
    val variant = MoonBoardVariant.fromLayoutId(layoutId.toLong()) ?: return emptySet()
    val mounted = holdSets.ifEmpty { MoonBoardHoldSets.setIdsFor(variant) }
    return mounted.flatMap { MoonBoardHoldSets.holdIdsFor(variant, it) }.toSet()
}

@Serializable
data class PaintDocument(
    val version: Int = 1,
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val board: PaintBoard,
    val colors: Map<Int, Int> = emptyMap(),
    val updatedAt: Long = 0,
) {
    fun validate() {
        require(version == 1)
        UUID.fromString(id)
        require(BoardBrand.entries.any { it.wireValue == board.brand && it.supportsPaintBox })
        require(board.layoutId > 0 && board.sizeId >= 0)
        require(colors.keys.all { it > 0 })
        require(colors.values.all { it in PaintPalette.forBoard(BoardBrand.fromWire(board.brand)) })
        if (board.brand == "moonboard") {
            val variant = requireNotNull(MoonBoardVariant.fromLayoutId(board.layoutId.toLong()))
            require(board.holdSets.all { it in MoonBoardHoldSets.setIdsFor(variant) })
            require(colors.keys.all { it in board.moonHoldIds() })
        }
    }
}

object PaintPalette {
    // Stored in Aurora RGB332 notation, also used by the shared settings picker.
    val moon = listOf(0x1c, 0x03, 0xe0)
    val quantum = listOf(0x1c, 0x1f, 0xe3, 0xfc)
    fun forBoard(brand: BoardBrand): List<Int> = when (brand) {
        BoardBrand.MOONBOARD -> moon
        BoardBrand.QUANTUM -> quantum
        else -> (1..255).toList()
    }
    fun rgb(byte: Int): Int = (((byte shr 5) * 255 / 7) shl 16) or
        ((((byte shr 2) and 7) * 255 / 7) shl 8) or ((byte and 3) * 255 / 3)
    fun moonFrames(colors: Map<Int, Int>): String = colors.toSortedMap().entries.joinToString("") {
        val role = when (it.value) { 0x1c -> 42; 0x03 -> 43; 0xe0 -> 44; else -> error("Unsupported MoonBoard color") }
        "p${it.key}r$role"
    }
}

/** A drag is one history entry, regardless of how many pointer samples it contains. */
class PaintHistory {
    private val undo = ArrayDeque<Map<Int, Int>>()
    private val redo = ArrayDeque<Map<Int, Int>>()
    private var beforeStroke: Map<Int, Int>? = null
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()
    fun begin(colors: Map<Int, Int>) { if (beforeStroke == null) beforeStroke = colors }
    fun end(colors: Map<Int, Int>) {
        beforeStroke?.takeIf { it != colors }?.let {
            undo.addLast(it)
            if (undo.size > 50) undo.removeFirst()
            redo.clear()
        }
        beforeStroke = null
    }
    fun undo(colors: Map<Int, Int>): Map<Int, Int> {
        end(colors)
        if (undo.isEmpty()) return colors
        redo.addLast(colors)
        return undo.removeLast()
    }
    fun redo(colors: Map<Int, Int>): Map<Int, Int> {
        if (redo.isEmpty()) return colors
        undo.addLast(colors)
        return redo.removeLast()
    }
    fun clear() { undo.clear(); redo.clear(); beforeStroke = null }
}
