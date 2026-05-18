package com.cruxcoach.data.repository

/**
 * Read-only access to the Kilter Board location dataset populated by the
 * daily Blossom sync (manifest chunk type="locations"). See FEAT-006.
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
