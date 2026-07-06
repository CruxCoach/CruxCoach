package com.cruxcoach.domain.board

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Layout-level metadata of a CruxCoach Board hold layout (grid pitch, main
 * grid dimensions, symmetry axis for mirroring). All fields are defaulted so
 * a trimmed-down `meta` object still parses; unknown keys are ignored.
 */
@Serializable
data class CruxBoardMeta(
    val name: String = "",
    @SerialName("grid_mm") val gridMm: Int = 0,
    @SerialName("main_cols") val mainCols: Int = 0,
    @SerialName("main_rows") val mainRows: Int = 0,
    /** X of the vertical mirror axis; positions mirror across this line. */
    @SerialName("symmetry_axis_x_mm") val symmetryAxisXMm: Double = 0.0,
    /** Vertical offset from hold bolt to its LED hole (negative = below). */
    @SerialName("led_offset_y_mm") val ledOffsetYMm: Int = 0,
    @SerialName("total_positions") val totalPositions: Int = 0,
)

/**
 * One hold position on the board: a bolt-on location with its grid slot,
 * physical coordinates, default hold, intended role and the LED wired to it.
 *
 * [mirrorId] points at the position's left/right mirror twin across the
 * board's symmetry axis; center-column positions are self-symmetric
 * (`mirrorId == id`).
 */
@Serializable
data class CruxBoardPosition(
    /** Stable position id, e.g. "M0000" (main tier) or "A0100" (aux tier). */
    val id: String,
    /** "main" (20 cm hand-hold grid) or "aux" (offset foot/aux grid). */
    val tier: String,
    val col: Int,
    val row: Int,
    @SerialName("x_mm") val xMm: Int,
    @SerialName("y_mm") val yMm: Int,
    /** Default hold shape at this position, e.g. "JUG", "EDGE_DEEP". */
    @SerialName("hold_type") val holdType: String,
    /** Intended use: "hand" or "foot". */
    val role: String,
    /** Id of the mirrored twin position; equals [id] on the center column. */
    @SerialName("mirror_id") val mirrorId: String,
    /** Index of this position's LED on the board's LED strip. */
    @SerialName("led_index") val ledIndex: Int,
    @SerialName("led_hole_x_mm") val ledHoleXMm: Int,
    @SerialName("led_hole_y_mm") val ledHoleYMm: Int,
)

/**
 * A full CruxCoach Board layout as shipped in `layout.json`: metadata plus
 * every hold position. Pure data — no I/O; obtain the JSON string elsewhere
 * and feed it to [parse].
 *
 * Lookup maps are derived lazily once and cached; they are not serialized.
 */
@Serializable
data class CruxBoardLayout(
    val meta: CruxBoardMeta = CruxBoardMeta(),
    val positions: List<CruxBoardPosition> = emptyList(),
) {
    /** Position lookup by stable id ("M0000", "A0100", …). */
    val positionsById: Map<String, CruxBoardPosition> by lazy {
        positions.associateBy { it.id }
    }

    /** Position lookup by LED strip index. */
    val byLedIndex: Map<Int, CruxBoardPosition> by lazy {
        positions.associateBy { it.ledIndex }
    }

    /**
     * id → mirrorId for every position. An involution over a well-formed
     * layout: following it twice returns the original id, and center-column
     * positions map to themselves.
     */
    val mirrorMap: Map<String, String> by lazy {
        positions.associate { it.id to it.mirrorId }
    }

    /** Mirror twin of [id], or null if the id is not part of this layout. */
    fun mirrorOf(id: String): String? = mirrorMap[id]

    /** Bolt coordinate (xMm, yMm) of [id], or null for an unknown id. */
    fun coordinatesOf(id: String): Pair<Int, Int>? =
        positionsById[id]?.let { it.xMm to it.yMm }

    /** Role ("hand"/"foot") of [id], or null for an unknown id. */
    fun roleOf(id: String): String? = positionsById[id]?.role

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Parses a layout JSON document (the `layout.json` format). Unknown
         * keys are ignored so newer layout files with extra metadata still
         * load. Throws [kotlinx.serialization.SerializationException] on
         * malformed input.
         */
        fun parse(json: String): CruxBoardLayout =
            Companion.json.decodeFromString(serializer(), json)
    }
}
