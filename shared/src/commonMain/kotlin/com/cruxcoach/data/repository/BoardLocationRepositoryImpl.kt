package com.cruxcoach.data.repository

import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.db.board.Kilter_board_location

class BoardLocationRepositoryImpl(
    private val database: BoardDatabase
) : BoardLocationRepository {

    private val q = database.kilterBoardLocationQueries

    override fun count(): Long = q.countLocations().executeAsOne()

    override fun getAll(): List<BoardLocation> =
        q.getAllLocations().executeAsList().map { it.toDomain() }

    override fun getById(storerocketId: Long): BoardLocation? =
        q.getLocationById(storerocketId).executeAsOneOrNull()?.toDomain()

    override fun getMatchingBoard(layoutId: Int, productSizeId: Int): List<BoardLocation> =
        q.getLocationsMatchingBoard(layoutId.toLong(), productSizeId.toLong())
            .executeAsList().map { it.toDomain() }

    override fun getPublicMatchingBoard(layoutId: Int, productSizeId: Int): List<BoardLocation> =
        q.getPublicLocationsMatchingBoard(layoutId.toLong(), productSizeId.toLong())
            .executeAsList().map { it.toDomain() }

    override fun getPublicOnly(): List<BoardLocation> =
        q.getPublicLocations().executeAsList().map { it.toDomain() }

    private fun Kilter_board_location.toDomain() = BoardLocation(
        id = storerocket_id,
        name = name,
        lat = lat,
        lng = lng,
        address = address,
        city = city,
        countryCode = country_code,
        phone = phone,
        email = email,
        url = url,
        instagram = instagram,
        layoutName = layout_name,
        layoutId = layout_id?.toInt(),
        sizeLabel = size_label,
        productSizeId = product_size_id?.toInt(),
        accessType = AccessType.fromString(access_type),
        adjustability = Adjustability.fromString(adjustability),
        fixedAngle = fixed_angle?.toInt(),
        frameMaker = frame_maker,
    )
}
