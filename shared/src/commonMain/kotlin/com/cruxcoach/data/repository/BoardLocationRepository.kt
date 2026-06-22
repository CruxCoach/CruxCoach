package com.cruxcoach.data.repository

import com.cruxcoach.domain.board.BoardBrand

/**
 * Read-only access to the board-installation location dataset populated by
 * the daily Blossom sync (manifest chunk type="locations"). See FEAT-006.
 * Spans Kilter + MoonBoard gyms from 0.2.0 (discriminated by board_brand).
 *
 * Functions are synchronous and meant to be called from a background
 * dispatcher — matches the existing BoardRepository style.
 */
interface BoardLocationRepository {
    fun count(): Long
    fun getAll(): List<BoardLocation>
    fun getById(gymUuid: String): BoardLocation?

    /**
     * Locations whose `(layout_id, product_size_id)` matches the user's
     * configured board. Locations with NULL `product_size_id` (cron
     * couldn't resolve the size string) are included so a layout-only
     * match still surfaces — the UI flags those as "layout match,
     * size unknown" rather than hiding them.
     */
    fun getMatchingBoard(layoutId: Int, productSizeId: Int): List<BoardLocation>

    fun getPublicMatchingBoard(layoutId: Int, productSizeId: Int): List<BoardLocation>

    fun getPublicOnly(): List<BoardLocation>

    // ── Per-wall detail (FEAT-007 gym→board picker) ──────────────────
    // Additive: kilter_board_wall ships in the same "locations" chunk
    // from 0.1.6+ cron builds. Empty on pre-13.sqm chunks (guarded
    // importer), so callers must tolerate an empty list.
    fun countWalls(): Long
    fun getAllWalls(): List<BoardWall>
    fun getWallsForGym(gymUuid: String): List<BoardWall>
    fun getWallsMatchingBoard(layoutId: Int, productSizeId: Int): List<BoardWall>

    /** FEAT-007 gym search: case-insensitive name substring, bounded. */
    fun searchLocations(query: String, limit: Int = 60): List<BoardLocation>

    /** product_size_id → wall count across all gyms (picker frequency sort). */
    fun productSizeFrequency(): Map<Int, Long>
}

data class BoardLocation(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val address: String?,
    val city: String?,
    val countryCode: String,
    val phone: String?,
    val email: String?,
    val url: String?,
    val instagram: String?,
    val layoutName: String?,
    val layoutId: Int?,
    val sizeLabel: String?,
    val productSizeId: Int?,
    val accessType: AccessType,
    val adjustability: Adjustability,
    val fixedAngle: Int?,
    val frameMaker: String?,
    /** Board family this installation belongs to (kilter_board_location.
     *  board_brand). Drives the map's brand filter + decides how the gym
     *  picker resolves a tapped gym (Kilter walls vs MoonBoard variant). */
    val boardBrand: BoardBrand = BoardBrand.KILTER,
    /** egym Wellpass acceptance — true = accepts, false = explicitly not,
     *  null = unknown / not curated (the common case). */
    val wellpass: Boolean? = null,
)

data class BoardWall(
    val wallUuid: String,
    val gymUuid: String,
    val name: String?,
    val productName: String?,
    val layoutId: Int?,
    val productLayoutUuid: String?,
    val productSizeId: Int?,
    val sizeLabel: String?,
    val isAdjustable: Boolean?,
    val minAngle: Int?,
    val maxAngle: Int?,
    val angleIncrements: Int?,
    val fixedAngle: Int?,
    val accumulatedHoldSetValue: Int?,
    val serialNumber: String?,
    val isListed: Boolean?,
)

enum class AccessType {
    PUBLIC, PRIVATE, MEMBERS, UNKNOWN;

    companion object {
        fun fromString(raw: String?): AccessType = when (raw?.uppercase()) {
            "PUBLIC" -> PUBLIC
            "PRIVATE" -> PRIVATE
            "MEMBERS" -> MEMBERS
            else -> UNKNOWN
        }
    }
}

enum class Adjustability {
    FIXED, ADJUSTABLE, LIMITED, FULL, UNKNOWN;

    companion object {
        fun fromString(raw: String?): Adjustability = when (raw?.uppercase()) {
            "FIXED" -> FIXED
            "ADJUSTABLE" -> ADJUSTABLE
            "LIMITED" -> LIMITED
            "FULL" -> FULL
            else -> UNKNOWN
        }
    }
}
