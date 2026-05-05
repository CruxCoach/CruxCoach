package com.cruxcoach.android.ui.map

/**
 * OpenFreeMap vector-tile style URLs. Provider-agnostic: swap to Stadia /
 * MapTiler / self-hosted by changing only these constants. No API key.
 */
object MapStyleProvider {
    /** General-purpose light style. */
    const val LIGHT = "https://tiles.openfreemap.org/styles/liberty"

    /** Greyscale minimal style for dark mode. */
    const val DARK = "https://tiles.openfreemap.org/styles/positron"

    fun forDarkMode(isDark: Boolean): String = if (isDark) DARK else LIGHT
}
