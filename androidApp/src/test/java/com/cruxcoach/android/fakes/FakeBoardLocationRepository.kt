package com.cruxcoach.android.fakes

import com.cruxcoach.data.repository.BoardLocation
import com.cruxcoach.data.repository.BoardLocationRepository
import com.cruxcoach.data.repository.BoardWall

/**
 * In-memory fake of [BoardLocationRepository] for ViewModel unit tests
 * covering FEAT-015 (board-locations map) and FEAT-007 (gym → board picker).
 *
 * Mirrors the real impl's query semantics where it matters for tests:
 * - `searchLocations` performs case-insensitive substring on `name`/`city`/`address`
 *   and respects the `limit`. It does NOT implement SQL `LIKE` wildcard
 *   escaping — callers that exercise that edge case should use a real
 *   JDBC-backed test instead.
 * - `getMatchingBoard` / `getPublicMatchingBoard` use the same NULL-product-size
 *   wildcard semantics as the production query.
 * - `productSizeFrequency` aggregates by `productSizeId` over `walls`.
 */
class FakeBoardLocationRepository : BoardLocationRepository {

    val locations = mutableListOf<BoardLocation>()
    val walls = mutableListOf<BoardWall>()

    override fun count(): Long = locations.size.toLong()

    override fun getAll(): List<BoardLocation> = locations.toList()

    override fun getById(gymUuid: String): BoardLocation? =
        locations.firstOrNull { it.id == gymUuid }

    override fun getMatchingBoard(layoutId: Int, productSizeId: Int): List<BoardLocation> =
        locations.filter { it.layoutId == layoutId && (it.productSizeId == null || it.productSizeId == productSizeId) }

    override fun getPublicMatchingBoard(layoutId: Int, productSizeId: Int): List<BoardLocation> =
        getMatchingBoard(layoutId, productSizeId)
            .filter { it.accessType == com.cruxcoach.data.repository.AccessType.PUBLIC }

    override fun getPublicOnly(): List<BoardLocation> =
        locations.filter { it.accessType == com.cruxcoach.data.repository.AccessType.PUBLIC }

    override fun countWalls(): Long = walls.size.toLong()

    override fun getAllWalls(): List<BoardWall> = walls.toList()

    override fun getWallsForGym(gymUuid: String): List<BoardWall> =
        walls.filter { it.gymUuid == gymUuid }

    override fun getWallsMatchingBoard(layoutId: Int, productSizeId: Int): List<BoardWall> =
        walls.filter { it.layoutId == layoutId && (it.productSizeId == null || it.productSizeId == productSizeId) }

    override fun searchLocations(query: String, limit: Int): List<BoardLocation> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return locations
            .filter {
                it.name.lowercase().contains(q) ||
                    (it.city?.lowercase()?.contains(q) == true) ||
                    (it.address?.lowercase()?.contains(q) == true)
            }
            .take(limit)
    }

    override fun productSizeFrequency(): Map<Int, Long> =
        walls.mapNotNull { it.productSizeId }
            .groupingBy { it }
            .eachCount()
            .mapValues { it.value.toLong() }
}
