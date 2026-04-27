package com.cruxcoach.domain.board

/**
 * Supported Aurora climbing boards.
 * URL format matches BoardLib: https://{hostBase}.com (no api. subdomain, no /v1 prefix).
 * Image URL uses api. subdomain: https://api.{hostBase}.com/img/
 */
enum class AuroraBoard(
    val displayName: String,
    val hostBase: String,
    val apiUrl: String,
    val imageUrl: String,
    val appPackage: String,
    val productId: Long
) {
    KILTER("Kilter", "kilterboardapp", "https://kilterboardapp.com", "https://api.kilterboardapp.com", "com.auroraclimbing.kilterboard", 1L);
}
