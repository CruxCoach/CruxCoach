package com.cruxcoach.android.data

import com.cruxcoach.domain.board.BoardBrand

/**
 * How common each board, Kilter size and MoonBoard version is in gyms, from the gym list
 * the app syncs every day. The board picker shows it next to each option so that a rare
 * pick gets a second look: a wrong size makes the board light the wrong holds, and the
 * box does not report its size.
 *
 * Shares count boards: a Kilter gym with two walls counts twice, every other brand has one
 * entry per gym. An option the gym list does not mention has none of its boards; a group of
 * fewer than [MIN_BOARDS] boards gets no shares at all.
 */
class BoardGymShares(
    /** Kilter product_size_id → walls of that size. */
    private val kilterSizeWalls: Map<Int, Long> = emptyMap(),
    /** Kilter product_size_id → product (Original or Homewall). */
    private val kilterSizeProducts: Map<Int, Int> = emptyMap(),
    /** Boards in gyms per brand other than Kilter, map-only brands included. */
    private val otherBrandBoards: Map<String, Long> = emptyMap(),
    /** MoonBoard layout_id → gyms with that version; gyms that do not name it are left out. */
    private val moonBoardLayouts: Map<Int, Long> = emptyMap(),
) {
    private val kilterProductWalls: Map<Int, Long> = kilterSizeWalls.entries
        .mapNotNull { (sizeId, walls) -> kilterSizeProducts[sizeId]?.let { it to walls } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, walls) -> walls.sum() }

    /** Share of [sizeId] among the walls of its Kilter product. */
    fun kilterSize(sizeId: Int): Double? {
        val product = kilterSizeProducts[sizeId] ?: return null
        return share(kilterSizeWalls[sizeId] ?: 0L, kilterProductWalls[product] ?: 0L)
    }

    /** Share of a MoonBoard version among the MoonBoards whose version is known. */
    fun moonBoard(layoutId: Long): Double? =
        share(moonBoardLayouts[layoutId.toInt()] ?: 0L, moonBoardLayouts.values.sum())

    /**
     * Share of a board among all boards in gyms. Kilter is split by [kilterProductId]
     * (Original or Homewall). Null without wall data: without Kilter the rest would look
     * far more common than it is.
     */
    fun board(brand: BoardBrand, kilterProductId: Int? = null): Double? {
        if (kilterProductWalls.isEmpty() || otherBrandBoards.isEmpty()) return null
        val count = if (brand == BoardBrand.KILTER) {
            kilterProductWalls[kilterProductId] ?: 0L
        } else {
            otherBrandBoards[brand.wireValue] ?: return null
        }
        return share(count, kilterProductWalls.values.sum() + otherBrandBoards.values.sum())
    }

    /** Kilter walls per size, for "common first" ordering of the size lists. */
    val kilterSizeFrequency: Map<Int, Long> get() = kilterSizeWalls

    companion object {
        /** Below this share an option counts as rare and the picker asks for a second look. */
        const val RARE_SHARE = 0.02

        /** Fewer boards than this say too little for a share. */
        const val MIN_BOARDS = 20L

        fun isRare(share: Double): Boolean = share < RARE_SHARE

        private fun share(count: Long, total: Long): Double? =
            if (total < MIN_BOARDS) null else count.toDouble() / total

        /**
         * Builds the shares from the synced gym list. [locations] is board_brand wire value
         * and layout_id → gyms; without walls on the device the size shares fall back to
         * [BoardConstants.DEFAULT_SIZE_FREQUENCY], the board shares stay empty.
         */
        fun from(sizeWalls: Map<Int, Long>, locations: Map<Pair<String, Int?>, Long>): BoardGymShares {
            val products = BoardConstants.KILTER_KNOWN_SIZES.associate { it.id.toInt() to it.productId.toInt() }
            val others = locations.entries
                .filter { (key, _) -> key.first != BoardBrand.KILTER.wireValue }
                .groupBy({ it.key.first }, { it.value })
                .mapValues { (_, counts) -> counts.sum() }
            val moon = locations.entries
                .mapNotNull { (key, count) ->
                    key.second?.takeIf { key.first == BoardBrand.MOONBOARD.wireValue }?.let { it to count }
                }
                .toMap()
            return BoardGymShares(
                kilterSizeWalls = sizeWalls.ifEmpty { BoardConstants.DEFAULT_SIZE_FREQUENCY },
                kilterSizeProducts = products,
                otherBrandBoards = if (sizeWalls.isEmpty()) emptyMap() else others,
                moonBoardLayouts = moon,
            )
        }
    }
}
