package com.cruxcoach.data.repository

import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.db.board.Kilter_board_location
import com.cruxcoach.db.board.Kilter_board_wall
import com.cruxcoach.domain.board.BoardBrand

class BoardLocationRepositoryImpl(
    private val database: BoardDatabase
) : BoardLocationRepository {

    private val q = database.kilterBoardLocationQueries
    private val w = database.kilterBoardWallQueries

    override fun count(): Long = q.countLocations().executeAsOne()

    override fun getAll(): List<BoardLocation> =
        q.getAllLocations().executeAsList().map { it.toDomain() }

    override fun getById(gymUuid: String): BoardLocation? =
        q.getLocationById(gymUuid).executeAsOneOrNull()?.toDomain()

    override fun getMatchingBoard(layoutId: Int, productSizeId: Int): List<BoardLocation> =
        q.getLocationsMatchingBoard(layoutId.toLong(), productSizeId.toLong())
            .executeAsList().map { it.toDomain() }

    override fun getPublicMatchingBoard(layoutId: Int, productSizeId: Int): List<BoardLocation> =
        q.getPublicLocationsMatchingBoard(layoutId.toLong(), productSizeId.toLong())
            .executeAsList().map { it.toDomain() }

    override fun getPublicOnly(): List<BoardLocation> =
        q.getPublicLocations().executeAsList().map { it.toDomain() }

    override fun countWalls(): Long = w.countWalls().executeAsOne()

    override fun getAllWalls(): List<BoardWall> =
        w.getAllWalls().executeAsList().map { it.toDomain() }

    override fun getWallsForGym(gymUuid: String): List<BoardWall> =
        w.getWallsForGym(gymUuid).executeAsList().map { it.toDomain() }

    override fun getWallsMatchingBoard(layoutId: Int, productSizeId: Int): List<BoardWall> =
        w.getWallsMatchingBoard(layoutId.toLong(), productSizeId.toLong())
            .executeAsList().map { it.toDomain() }

    override fun searchLocations(query: String, limit: Int): List<BoardLocation> {
        // Escape LIKE metacharacters so '%' / '_' typed by the user match
        // literally (the query uses ESCAPE '\'). Escape the backslash FIRST so
        // we don't double-escape the escapes we add below.
        val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return q.searchLocations(escaped, limit.toLong()).executeAsList().map { it.toDomain() }
    }

    override fun productSizeFrequency(): Map<Int, Long> =
        w.wallCountByProductSize().executeAsList()
            .mapNotNull { row -> row.product_size_id?.let { it.toInt() to row.cnt } }
            .toMap()

    private fun Kilter_board_location.toDomain(): BoardLocation {
        val mappedAccessType = AccessType.fromString(access_type)
        val exposeContact = mappedAccessType == AccessType.PUBLIC
        return BoardLocation(
            id = gym_uuid,
            name = name,
            lat = lat,
            lng = lng,
            address = address.takeIf { exposeContact },
            city = city,
            countryCode = country_code,
            phone = phone.takeIf { exposeContact },
            email = email.takeIf { exposeContact },
            url = url.takeIf { exposeContact },
            instagram = instagram.takeIf { exposeContact },
            layoutName = layout_name,
            layoutId = layout_id?.toInt(),
            sizeLabel = size_label,
            productSizeId = product_size_id?.toInt(),
            accessType = mappedAccessType,
            adjustability = Adjustability.fromString(adjustability),
            fixedAngle = fixed_angle?.toInt(),
            frameMaker = frame_maker,
            boardBrand = BoardBrand.fromWire(board_brand),
            wellpass = wellpass?.let { it == 1L },
        )
    }

    private fun Kilter_board_wall.toDomain() = BoardWall(
        wallUuid = wall_uuid,
        gymUuid = gym_uuid,
        name = name,
        productName = product_name,
        layoutId = layout_id?.toInt(),
        productLayoutUuid = product_layout_uuid,
        productSizeId = product_size_id?.toInt(),
        sizeLabel = size_label,
        isAdjustable = is_adjustable?.let { it == 1L },
        minAngle = min_angle?.toInt(),
        maxAngle = max_angle?.toInt(),
        angleIncrements = angle_increments?.toInt(),
        fixedAngle = fixed_angle?.toInt(),
        accumulatedHoldSetValue = accumulated_hold_set_value?.toInt(),
        serialNumber = serial_number,
        isListed = is_listed?.let { it == 1L },
    )
}
