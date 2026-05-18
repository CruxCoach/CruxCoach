package com.cruxcoach.domain.board

/**
 * Climbing-board products CruxCoach can talk to. Today: Kilter only.
 * Adding a new board (Tension, Decoy, Spire) is a matter of appending an
 * entry — the BLE GATT shape is shared across the Aurora-Climbing
 * ecosystem, so the encoder/scanner/connection classes need no changes.
 */
enum class SupportedBoard(
    val productId: Long,
    /** Legacy APK package on APKPure — used by [com.cruxcoach.android.data.ApkDownloader] for offline DB extraction. */
    val appPackage: String,
) {
    KILTER(productId = 1L, appPackage = "com.auroraclimbing.kilterboard"),
}
