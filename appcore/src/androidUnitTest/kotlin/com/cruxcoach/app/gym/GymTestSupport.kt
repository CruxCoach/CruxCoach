package com.cruxcoach.app.gym

import com.cruxcoach.data.repository.AccessType
import com.cruxcoach.data.repository.Adjustability
import com.cruxcoach.data.repository.BoardLocation
import com.cruxcoach.data.repository.BoardLocationRepository
import com.cruxcoach.data.repository.BoardWall
import com.cruxcoach.domain.board.BoardBrand

/** Port of Android's `MapFixtures.mapBoard`: one board row at a coordinate. */
internal fun mapBoard(
    id: String,
    lat: Double,
    lng: Double,
    name: String = "Gym $id",
    brand: BoardBrand = BoardBrand.KILTER,
    layoutId: Int? = if (brand == BoardBrand.KILTER) 1 else null,
    layoutName: String? = null,
    city: String? = "Munich",
    country: String = "DE",
    url: String? = null,
    phone: String? = null,
    address: String? = null,
    sizeLabel: String? = null,
    productSizeId: Int? = null,
    access: AccessType = AccessType.UNKNOWN,
    adjustability: Adjustability = Adjustability.UNKNOWN,
    fixedAngle: Int? = null,
    hasLed: Boolean? = null,
    wellpass: Boolean? = null,
) = BoardLocation(
    id = id, name = name, lat = lat, lng = lng,
    address = address, city = city, countryCode = country,
    phone = phone, email = null, url = url, instagram = null,
    layoutName = layoutName, layoutId = layoutId,
    sizeLabel = sizeLabel, productSizeId = productSizeId,
    accessType = access, adjustability = adjustability,
    fixedAngle = fixedAngle, frameMaker = null,
    boardBrand = brand, wellpass = wellpass, hasLed = hasLed,
)

/**
 * Port of Android's `FakeBoardLocationRepository`: in-memory rows with the
 * real implementation's query semantics where the picker depends on them
 * (case-insensitive name/city/address substring, NULL-size wildcard,
 * per-size wall frequency).
 */
internal class FakeBoardLocationRepository : BoardLocationRepository {
    val locations = mutableListOf<BoardLocation>()
    val walls = mutableListOf<BoardWall>()

    override fun count(): Long = locations.size.toLong()
    override fun getAll(): List<BoardLocation> = locations.toList()
    override fun getById(gymUuid: String): BoardLocation? = locations.firstOrNull { it.id == gymUuid }

    override fun getMatchingBoard(layoutId: Int, productSizeId: Int): List<BoardLocation> =
        locations.filter { it.layoutId == layoutId && (it.productSizeId == null || it.productSizeId == productSizeId) }

    override fun getPublicMatchingBoard(layoutId: Int, productSizeId: Int): List<BoardLocation> =
        getMatchingBoard(layoutId, productSizeId).filter { it.accessType == AccessType.PUBLIC }

    override fun getPublicOnly(): List<BoardLocation> = locations.filter { it.accessType == AccessType.PUBLIC }
    override fun countWalls(): Long = walls.size.toLong()
    override fun getAllWalls(): List<BoardWall> = walls.toList()
    override fun getWallsForGym(gymUuid: String): List<BoardWall> = walls.filter { it.gymUuid == gymUuid }

    override fun getWallsMatchingBoard(layoutId: Int, productSizeId: Int): List<BoardWall> =
        walls.filter { it.layoutId == layoutId && (it.productSizeId == null || it.productSizeId == productSizeId) }

    override fun searchLocations(query: String, limit: Int): List<BoardLocation> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return locations.filter {
            it.name.lowercase().contains(q) ||
                (it.city?.lowercase()?.contains(q) == true) ||
                (it.address?.lowercase()?.contains(q) == true)
        }.take(limit)
    }

    override fun productSizeFrequency(): Map<Int, Long> =
        walls.mapNotNull { it.productSizeId }.groupingBy { it }.eachCount().mapValues { it.value.toLong() }
}
